package me.rerere.rikkahub.data.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Minimal native GitHub REST client used by the IDE account and repository picker. */
class GitHubApiClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
) {
    suspend fun currentUser(accessToken: String): GitHubUser {
        val url = baseUrl.newBuilder().addPathSegment("user").build()
        val response = get(url, accessToken)
        return json.decodeFromString(GitHubUser.serializer(), response.body)
    }

    suspend fun primaryEmail(accessToken: String): String? {
        val url = baseUrl.newBuilder().addPathSegments("user/emails").build()
        val response = get(url, accessToken)
        val emails = json.decodeFromString(EMAIL_LIST_SERIALIZER, response.body)
        return emails.firstOrNull { it.primary && it.verified }?.email
            ?: emails.firstOrNull { it.primary }?.email
            ?: emails.firstOrNull { it.verified }?.email
    }

    suspend fun listRepositories(
        accessToken: String,
        page: Int = 1,
        perPage: Int = DEFAULT_PAGE_SIZE,
    ): GitHubRepositoryPage {
        require(page >= 1) { "Page must be at least 1" }
        require(perPage in 1..100) { "perPage must be between 1 and 100" }
        val url = baseUrl.newBuilder()
            .addPathSegments("user/repos")
            .addQueryParameter("visibility", "all")
            .addQueryParameter("affiliation", "owner,collaborator,organization_member")
            .addQueryParameter("sort", "updated")
            .addQueryParameter("direction", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", perPage.toString())
            .build()
        val response = get(url, accessToken)
        return GitHubRepositoryPage(
            repositories = json.decodeFromString(REPOSITORY_LIST_SERIALIZER, response.body),
            nextPage = response.linkHeader.nextPage(),
        )
    }

    private suspend fun get(url: HttpUrl, accessToken: String): GitHubHttpResponse =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) { "GitHub access token is required" }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "RikkaHub-AI-IDE")
                .build()
            client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull()?.take(MAX_ERROR_MESSAGE_LENGTH)
                        ?: response.message.ifBlank { "HTTP ${response.code}" }
                    throw GitHubApiException(response.code, message)
                }
                GitHubHttpResponse(body = body, linkHeader = response.header("Link"))
            }
        }

    private fun String?.nextPage(): Int? {
        if (this == null) return null
        return split(',').firstOrNull { part ->
            part.contains("rel=\"next\"")
        }?.let { nextLink ->
            NEXT_URL_REGEX.find(nextLink)?.groupValues?.get(1)
                ?.toHttpUrl()
                ?.queryParameter("page")
                ?.toIntOrNull()
        }
    }

    private data class GitHubHttpResponse(
        val body: String,
        val linkHeader: String?,
    )

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_ERROR_MESSAGE_LENGTH = 300
        val NEXT_URL_REGEX = Regex("<([^>]+)>")
        val REPOSITORY_LIST_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(
            GitHubRepository.serializer()
        )
        val EMAIL_LIST_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(
            GitHubEmail.serializer()
        )
    }
}
