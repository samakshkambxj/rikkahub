package me.rerere.rikkahub.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubUser(
    val id: Long,
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val email: String? = null,
)

@Serializable
data class GitHubEmail(
    val email: String,
    val primary: Boolean = false,
    val verified: Boolean = false,
)

@Serializable
data class GitHubRepositoryOwner(
    val login: String,
)

@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: GitHubRepositoryOwner,
    @SerialName("private") val isPrivate: Boolean,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("clone_url") val cloneUrl: String,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val description: String? = null,
    val language: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val fork: Boolean = false,
    val archived: Boolean = false,
)

/** A page of repositories. [nextPage] is parsed from GitHub's RFC 5988 Link header. */
data class GitHubRepositoryPage(
    val repositories: List<GitHubRepository>,
    val nextPage: Int?,
)

/**
 * Persisted account metadata. The access token is encrypted with Android Keystore before it is
 * stored here; plaintext tokens must never be placed in Settings or included in backups.
 */
@Serializable
data class GitHubAccount(
    val userId: Long? = null,
    val login: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val email: String? = null,
    val encryptedAccessToken: String? = null,
    val oauthClientId: String = "",
) {
    val isSignedIn: Boolean
        get() = encryptedAccessToken != null && userId != null && login.isNotBlank()

    val committerName: String
        get() = displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: login

    /** Prefer the account email when available; otherwise use GitHub's ID-based noreply address. */
    val committerEmail: String
        get() = email?.trim()?.takeIf { it.isNotEmpty() }
            ?: userId?.let { "$it+$login@users.noreply.github.com" }.orEmpty()
}

sealed interface GitHubLoginStatus {
    data object Idle : GitHubLoginStatus

    data object Authorizing : GitHubLoginStatus

    data class DeviceCode(
        val userCode: String,
        val verificationUrl: String,
    ) : GitHubLoginStatus

    data class Failed(val message: String) : GitHubLoginStatus
}

class GitHubApiException(
    val statusCode: Int,
    message: String,
) : Exception("GitHub API request failed ($statusCode): $message")
