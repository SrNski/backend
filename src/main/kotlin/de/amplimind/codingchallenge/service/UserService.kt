package de.amplimind.codingchallenge.service

import de.amplimind.codingchallenge.constants.AppConstants
import de.amplimind.codingchallenge.constants.MessageConstants
import de.amplimind.codingchallenge.dto.DeletedUserInfoDTO
import de.amplimind.codingchallenge.dto.IsAdminDTO
import de.amplimind.codingchallenge.dto.UserInfoDTO
import de.amplimind.codingchallenge.dto.UserStatus
import de.amplimind.codingchallenge.dto.request.ChangePasswordRequestDTO
import de.amplimind.codingchallenge.dto.request.ChangeUserRoleRequestDTO
import de.amplimind.codingchallenge.dto.request.InviteRequestDTO
import de.amplimind.codingchallenge.dto.request.RegisterRequestDTO
import de.amplimind.codingchallenge.exceptions.InvalidTokenException
import de.amplimind.codingchallenge.exceptions.ResourceNotFoundException
import de.amplimind.codingchallenge.exceptions.TokenAlreadyUsedException
import de.amplimind.codingchallenge.exceptions.UserAlreadyExistsException
import de.amplimind.codingchallenge.exceptions.UserAlreadyRegisteredException
import de.amplimind.codingchallenge.exceptions.UserSelfDeleteException
import de.amplimind.codingchallenge.extensions.EnumExtensions.matchesAny
import de.amplimind.codingchallenge.model.Submission
import de.amplimind.codingchallenge.model.SubmissionStates
import de.amplimind.codingchallenge.model.User
import de.amplimind.codingchallenge.model.UserRole
import de.amplimind.codingchallenge.repository.ProjectRepository
import de.amplimind.codingchallenge.repository.SubmissionRepository
import de.amplimind.codingchallenge.repository.UserRepository
import de.amplimind.codingchallenge.storage.ResetPasswordTokenStorage
import de.amplimind.codingchallenge.utils.JWTUtils
import de.amplimind.codingchallenge.utils.JWTUtils.INVITE_LINK_EXPIRATION_DAYS
import de.amplimind.codingchallenge.utils.UserUtils
import de.amplimind.codingchallenge.utils.ValidationUtils
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random
import kotlin.streams.asSequence

/**
 * Service for managing users.
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val submissionRepository: SubmissionRepository,
    private val projectRepository: ProjectRepository,
    private val passwordEncoder: PasswordEncoder,
    private val emailService: EmailService,
    private val authenticationProvider: AuthenticationProvider,
    private val resetPasswordTokenStorage: ResetPasswordTokenStorage,
    private val inviteTokenExpirationService: InviteTokenExpirationService,
) {
    companion object {
        private const val RESET_PASSWORD_SUBJECT = "Password Reset Requested"
        private const val RESET_PASSWORD_TEXT =
            "You have requested to reset your password for your Amplimind Coding Challenge account." +
                " Please follow the link below to set up a new password:"
        private const val RESET_LINK_PREFIX = "http://localhost:5173/reset-password/"
    }

    // TODO fix server variable.
    @Value("\${app.frontend.url}")
    val SERVER_URL: String = "null"

    private val checkResetPasswordLock = Any()

    /**
     * Fetches all user infos [UserInfoDTO]
     */
    fun fetchAllUserInfos(): List<UserInfoDTO> {
        return this.userRepository.findAll().map {
            UserInfoDTO(
                email = it.email,
                isAdmin = it.role.matchesAny(UserRole.ADMIN),
                status = extractUserStatus(it),
            )
        }
    }

    // TODO maybe remove later, might not be needed
    @Throws(ResourceNotFoundException::class)
    fun fetchUserInfosForEmail(email: String): UserInfoDTO {
        return this.userRepository.findByEmail(email)?.let {
            return UserInfoDTO(
                email = it.email,
                isAdmin = it.role.matchesAny(UserRole.ADMIN),
                status = extractUserStatus(it),
            )
        } ?: throw ResourceNotFoundException("User with email $email was not found")
    }

    /**
     * Deletes a user by its email
     * @param email the email of the user to delete
     * @return the [UserInfoDTO] of the deleted user
     */
    fun deleteUserByEmail(email: String): DeletedUserInfoDTO {
        // Check if the user is trying to delete himself
        val auth = SecurityContextHolder.getContext().authentication
        val authenticatedUserEmail = auth?.name
        if (authenticatedUserEmail == email) {
            throw UserSelfDeleteException("User with email $email cannot delete himself")
        }

        // Delete the submissions of the user
        val submissions = this.submissionRepository.findByUserEmail(email)
        if (submissions != null) {
            this.submissionRepository.delete(submissions)
        }

        // Find the user & delete the user
        val user =
            this.userRepository.findByEmail(email)
                ?: throw ResourceNotFoundException("User with email $email was not found")
        this.userRepository.delete(user)
        this.inviteTokenExpirationService.deleteEntryForUser(email)
        return DeletedUserInfoDTO(
            email = user.email,
            isAdmin = user.role.matchesAny(UserRole.ADMIN),
        )
    }

    /**
     * override random password with password set by user
     * @param registerRequest
     */
    @Transactional
    fun handleRegister(
        registerRequest: RegisterRequestDTO,
        session: HttpSession,
    ) {
        val email: String = JWTUtils.getClaimItem(registerRequest.token, JWTUtils.MAIL_KEY) as String
        val isAdmin: Boolean = JWTUtils.getClaimItem(registerRequest.token, JWTUtils.ADMIN_KEY) as Boolean

        val user =
            userRepository.findByEmail(email)
                ?: throw ResourceNotFoundException("User with email $email was not found")

        if (user.role.matchesAny(UserRole.ADMIN, UserRole.USER)) {
            throw InvalidTokenException("Token was already used")
        }

        setPassword(email, registerRequest.password, isAdmin)

        // Remove the token from the repository
        this.inviteTokenExpirationService.deleteEntryForUser(email)

        val authentication: Authentication =
            authenticationProvider.authenticate(
                UsernamePasswordAuthenticationToken(
                    email,
                    registerRequest.password,
                ),
            )

        SecurityContextHolder.getContext().authentication = authentication
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext())
    }

    /**
     * handle the Invite of a new applicant
     * @param inviteRequest The email of the applicant which should be created and where the email should be sent to and a boolean if user is admin or not
     */
    @Transactional
    fun handleInvite(inviteRequest: InviteRequestDTO): UserInfoDTO {
        val user = createUser(inviteRequest)

        sendInviteText(inviteRequest)

        // User should be unregistered at this point since he has not registered yet
        return UserInfoDTO(
            email = user.email,
            isAdmin = inviteRequest.isAdmin,
            status = UserStatus.UNREGISTERED,
        )
    }

    /**
     * Create a new User
     * @param email The email of the user which should be created
     */
    @Transactional
    fun createUser(inviteRequest: InviteRequestDTO): User {
        val foundUser: User? =
            this.userRepository.findByEmail(inviteRequest.email)

        if (foundUser != null) {
            // User should not exists at this point
            throw UserAlreadyExistsException("User with email ${inviteRequest.email} already exists")
        }

        val newUser =
            User(
                email = inviteRequest.email,
                password = passwordEncoder.encode(createPassword(20)),
                role = UserRole.INIT,
            )

        this.userRepository.save(newUser)

        if (!inviteRequest.isAdmin) {
            generateSubmission(newUser)
        }

        return newUser
    }

    fun createPassword(length: Long): String {
        // create Random initial Password
        val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')

        return ThreadLocalRandom.current()
            .ints(length, 0, charPool.size)
            .asSequence()
            .map(charPool::get)
            .joinToString("")
    }

    fun generateSubmission(user: User) {
        // create new Submission
        // TODO maybe write specific method for this
        val activeProjectIds = projectRepository.findByActive().filter { it.id != null }.filter { it.active }.map { it.id as Long }
        val newSubmission =
            Submission(
                userEmail = user.email,
                expirationDate = Timestamp(0),
                projectID = activeProjectIds[Random.nextInt(activeProjectIds.size)],
                turnInDate = Timestamp(0),
                status = SubmissionStates.INIT,
            )
        this.submissionRepository.save(newSubmission)
    }

    /**
     * Update password for User
     * @param email The email of the user which will be updated
     * @param password The new password that will be set
     */
    fun setPassword(
        email: String,
        password: String,
        isAdmin: Boolean,
    ) {
        val userObject =
            this.userRepository.findByEmail(email)
                ?: throw ResourceNotFoundException("User with email $email was not found")

        val userRole = if (isAdmin) UserRole.ADMIN else UserRole.USER

        ValidationUtils.validatePassword(password)

        val updatedUser =
            userObject.let {
                User(
                    email = it.email,
                    role = userRole,
                    password = passwordEncoder.encode(password),
                )
            }
        this.userRepository.save(updatedUser)
    }

    /**
     * Extracts the [UserStatus] for a provided [User]
     * @param user the user to extract the status from
     * @return the [UserStatus]
     */
    private fun extractUserStatus(user: User) = if (isUserRegistered(user)) UserStatus.REGISTERED else UserStatus.UNREGISTERED

    /**
     * Checks if the user is registered (not init anymore)
     * @param user the user to check
     * @return true if the user is registered
     */
    private fun isUserRegistered(user: User): Boolean {
        return user.role.matchesAny(UserRole.USER, UserRole.ADMIN)
    }

    /**
     * Send a reset password link to the user with the provided email
     * @param email The email to which the reset password link should be sent
     */
    fun requestPasswordChange(email: String) {
        ValidationUtils.validateEmail(email)
        val token =
            JWTUtils.createToken(
                mapOf(JWTUtils.MAIL_KEY to email),
                Date.from(
                    Instant.now().plus(
                        JWTUtils.RESET_PASSWORD_EXPIRATION_MIN,
                        ChronoUnit.MINUTES,
                    ),
                ),
            )
        this.emailService.sendEmail(email, RESET_PASSWORD_SUBJECT, RESET_PASSWORD_TEXT + RESET_LINK_PREFIX + token)
    }

    /**
     * Change the password of the user with the provided token
     * @param changePasswordRequestDTO The request to change the password
     * @throws ResourceNotFoundException if the user with the email from the token was not found
     * @throws InvalidTokenException if the token is invalid
     */
    fun changePassword(changePasswordRequestDTO: ChangePasswordRequestDTO) {
        synchronized(checkResetPasswordLock) {
            this.resetPasswordTokenStorage.isTokenUsed(changePasswordRequestDTO.token)
                .takeIf { it }
                ?.let { throw TokenAlreadyUsedException("Token has already be used") }

            val email = JWTUtils.getClaimItem(changePasswordRequestDTO.token, JWTUtils.MAIL_KEY) as String

            ValidationUtils.validateEmail(email)

            val user = userRepository.findByEmail(email) ?: throw ResourceNotFoundException("User with email $email was not found")

            val updatedUser =
                user.let {
                    User(
                        email = it.email,
                        role = it.role,
                        password = passwordEncoder.encode(changePasswordRequestDTO.newPassword),
                    )
                }
            userRepository.save(updatedUser)
            this.resetPasswordTokenStorage.addToken(changePasswordRequestDTO.token)
        }
    }

    /**
     * checks if the current user is an admin
     * @return if the current user is an admin
     */
    fun fetchLoggedInUserAdminStatus(): IsAdminDTO {
        return IsAdminDTO(fetchUserInfosForEmail(UserUtils.fetchLoggedInUser().username).isAdmin)
    }

    /**
     * handles the entire behaviour of the repeat send invite
     * @param inviteRequest the repeat invite request
     */
    @Transactional
    fun handleResendInvite(inviteRequest: InviteRequestDTO): UserInfoDTO {
        val user: User =
            userRepository.findByEmail(inviteRequest.email)
                ?: throw ResourceNotFoundException("User with email ${inviteRequest.email} was not found")

        if (extractUserStatus(user) != UserStatus.UNREGISTERED) {
            throw UserAlreadyRegisteredException("User with email ${inviteRequest.email} is already registered")
        }

        sendInviteText(inviteRequest)

        return UserInfoDTO(
            inviteRequest.email,
            inviteRequest.isAdmin,
            extractUserStatus(user),
        )
    }

    private fun fetchInviteSubject(isAdmin: Boolean): String {
        return if (isAdmin) MessageConstants.ADMIN_SUBJECT else MessageConstants.USER_SUBJECT
    }

    private fun sendInviteText(inviteRequest: InviteRequestDTO) {
        val claims = mapOf(JWTUtils.MAIL_KEY to inviteRequest.email, JWTUtils.ADMIN_KEY to inviteRequest.isAdmin)

        val expiration = Date.from(Instant.now().plus(INVITE_LINK_EXPIRATION_DAYS, ChronoUnit.DAYS))
        val token =
            JWTUtils.createToken(claims, expiration)

        val subject = fetchInviteSubject(inviteRequest.isAdmin)
        val text = fetchInviteText(token, inviteRequest.isAdmin)

        this.inviteTokenExpirationService.updateExpirationToken(inviteRequest.email, expiration.time)

        this.emailService.sendEmail(inviteRequest.email, subject, text)
    }

    private fun fetchInviteText(
        token: String,
        isAdmin: Boolean,
    ): String {
        if (isAdmin) {
            return "<p>Hallo,<br>" +
                "<br>" +
                "Mit dem unten stehenden Link können sie sich als Admin auf der coding challange Plattform von Amplimind registrieren.<br>" +
                "<br>" +
                "<a href=\"$SERVER_URL/invite/$token\">Jetzt registrieren</a><br>" +
                "<br>" +
                "<b>Der Link läuft nach $INVITE_LINK_EXPIRATION_DAYS Tagen ab.</b>" +
                MessageConstants.EMAIL_SIGNATURE +
                "</p>"
        }

        return "<p>Sehr geehrter Bewerber,<br>" +
            "<br>" +
            "wir laden Sie hiermit zu ihrer Coding Challange ein.<br>" +
            "Mit dem unten stehenden Link können Sie sich auf unserer Plattform registrieren.<br>" +
            "<br>" +
            "<a href=\"$SERVER_URL/invite/$token\">Für Coding Challange registrieren</a><br>" +
            "<br>" +
            "<b>Der Link läuft nach $INVITE_LINK_EXPIRATION_DAYS Tagen ab.</b> Nachdem Sie sich registriert haben,<br> können Sie ihre Aufgabe einsehen. Ab dann haben Sie <b>${AppConstants.SUBMISSION_EXPIRATION_DAYS} Tage</b> Zeit ihre Lösung hochzuladen.<br>" +
            MessageConstants.EMAIL_SIGNATURE +
            "</p>"
    }
}
