package de.amplimind.codingchallenge.submission

import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

interface GitHubApiClientI {
    @PUT("repos/amplimindcc/{repoName}/contents/{filePath}")
    suspend fun pushFileCall(
        @Path("repoName") repoName: String,
        @Path("filePath") filePath: String,
        @Body submissionFile: SubmissionFile,
    ): Response<PushFileResponse>

    @POST("orgs/{org}/repos")
    suspend fun createSubmissionRepository(
        @Path("org") org: String,
        @Body submissionRepository: SubmissionGitHubRepository,
    ): Response<CreateRepoResponse>

    @POST("repos/amplimindcc/{repoName}/actions/workflows/{workflowName}/dispatches")
    suspend fun triggerWorkflow(
        @Path("repoName") repoName: String,
        @Path("workflowName") workflowName: String,
        @Body workflowDispatch: WorkflowDispatch,
    ): Response<Void>

    @GET("repos/amplimindcc/{repoName}")
    fun getSubmissionRepository(
        @Path("repoName") repoName: String,
    ): Call<Result<String>>

    @DELETE("repos/amplimindcc/{repoName}")
    suspend fun deleteRepository(
        @Path("repoName") repoName: String
    ): Response<Void>
}

// Requests

@Serializable
data class SubmissionFile(
    val message: String,
    val content: String,
    val committer: Committer
)

@Serializable
data class Committer(
    val name: String,
    val email: String
)

@Serializable
data class SubmissionGitHubRepository(
    val name: String,
    val description: String,
)

@Serializable
data class WorkflowDispatch(
    val ref: String,
)

// Responses

@Serializable
data class PushFileResponse(
    val name: String,
)

@Serializable
data class CreateRepoResponse(
    val id: Int,
    val name: String
)