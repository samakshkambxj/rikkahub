package me.rerere.rikkahub.data.github

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.SecretCipher
import okhttp3.OkHttpClient

/** Owns GitHub sign-in and decrypts credentials only at the point an API/Git operation needs them. */
class GitHubAccountManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    client: OkHttpClient,
    private val apiClient: GitHubApiClient,
) {
    private val oauthClient = OAuthHttpClient(client)
    private val _loginStatus = MutableStateFlow<GitHubLoginStatus>(GitHubLoginStatus.Idle)
    val loginStatus = _loginStatus.asStateFlow()
    private var loginJob: Job? = null

    val account: Flow<GitHubAccount> = settingsStore.settingsFlow
        .map { it.gitHubAccount }
        .distinctUntilChanged()

    suspend fun currentAccount(): GitHubAccount = settingsStore.awaitLoaded().gitHubAccount

    fun startDeviceLogin(clientId: String, context: Context) {
        if (loginJob?.isActive == true) return
        val normalizedClientId = clientId.trim()
        require(normalizedClientId.isNotEmpty()) { "A GitHub OAuth App client ID is required" }
        val applicationContext = context.applicationContext
        _loginStatus.value = GitHubLoginStatus.Authorizing
        loginJob = appScope.launch {
            try {
                settingsStore.update { settings ->
                    settings.copy(
                        gitHubAccount = settings.gitHubAccount.copy(oauthClientId = normalizedClientId)
                    )
                }
                val authorization = oauthClient.requestDeviceAuthorization(
                    OAuthHttpClient.DeviceAuthorizationRequest(
                        deviceAuthorizationEndpoint = DEVICE_AUTHORIZATION_ENDPOINT,
                        clientId = normalizedClientId,
                        scope = DEVICE_FLOW_SCOPE,
                    )
                )
                val verificationUrl = authorization.verificationUriComplete
                    ?: authorization.verificationUri
                require(verificationUrl.startsWith("https://github.com/")) {
                    "GitHub returned an invalid verification URL"
                }
                _loginStatus.value = GitHubLoginStatus.DeviceCode(
                    userCode = authorization.userCode,
                    verificationUrl = verificationUrl,
                )
                CustomTabsOAuthAuthorizationLauncher.launch(applicationContext, verificationUrl)
                val token = oauthClient.awaitDeviceToken(
                    OAuthHttpClient.DeviceTokenRequest(
                        tokenEndpoint = ACCESS_TOKEN_ENDPOINT,
                        clientId = normalizedClientId,
                        deviceCode = authorization.deviceCode,
                    ),
                    authorization,
                )
                val user = apiClient.currentUser(token.accessToken)
                persistLogin(user, token.accessToken)
                _loginStatus.value = GitHubLoginStatus.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "GitHub device login failed", error)
                _loginStatus.value = GitHubLoginStatus.Failed(
                    error.message ?: "GitHub sign-in failed"
                )
            }
        }
    }

    fun cancelDeviceLogin() {
        loginJob?.cancel()
        loginJob = null
        _loginStatus.value = GitHubLoginStatus.Idle
    }

    /** Validates a PAT before saving it, so a typo does not replace a working account session. */
    suspend fun loginWithToken(token: String): GitHubUser {
        val normalizedToken = token.trim()
        require(normalizedToken.isNotEmpty()) { "Enter a GitHub personal access token" }
        val user = apiClient.currentUser(normalizedToken)
        persistLogin(user, normalizedToken)
        return user
    }

    suspend fun logout() {
        cancelDeviceLogin()
        settingsStore.update { settings ->
            val account = settings.gitHubAccount
            settings.copy(
                gitHubAccount = GitHubAccount(oauthClientId = account.oauthClientId)
            )
        }
    }

    suspend fun accessToken(): String? {
        val encrypted = currentAccount().encryptedAccessToken ?: return null
        return runCatching { SecretCipher.decrypt(encrypted) }.getOrElse {
            throw IllegalStateException(
                "GitHub credentials can no longer be decrypted. Please sign in again.",
                it,
            )
        }
    }

    private suspend fun persistLogin(user: GitHubUser, accessToken: String) {
        val encryptedToken = withContext(Dispatchers.IO) { SecretCipher.encrypt(accessToken) }
        settingsStore.update { settings ->
            settings.copy(
                gitHubAccount = settings.gitHubAccount.copy(
                    userId = user.id,
                    login = user.login,
                    displayName = user.name,
                    avatarUrl = user.avatarUrl,
                    encryptedAccessToken = encryptedToken,
                )
            )
        }
    }

    private companion object {
        const val TAG = "GitHubAccountManager"
        const val DEVICE_AUTHORIZATION_ENDPOINT = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
        // repo covers private/public repository Git operations; read:user identifies the account.
        const val DEVICE_FLOW_SCOPE = "repo read:user"
    }
}
