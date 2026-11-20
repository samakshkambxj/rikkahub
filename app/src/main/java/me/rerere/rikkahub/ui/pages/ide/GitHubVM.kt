package me.rerere.rikkahub.ui.pages.ide

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.git.WorkspaceGitRepository
import me.rerere.rikkahub.data.github.GitHubAccount
import me.rerere.rikkahub.data.github.GitHubAccountManager
import me.rerere.rikkahub.data.github.GitHubApiClient
import me.rerere.rikkahub.data.github.GitHubRepository

class GitHubVM(
    private val accountManager: GitHubAccountManager,
    private val apiClient: GitHubApiClient,
    private val gitRepository: WorkspaceGitRepository,
) : ViewModel() {
    val account = accountManager.account.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        GitHubAccount(),
    )
    val loginStatus = accountManager.loginStatus

    private val _state = MutableStateFlow(GitHubUiState())
    val state = _state.asStateFlow()
    private val _openWorkspace = MutableStateFlow<String?>(null)
    val openWorkspace = _openWorkspace.asStateFlow()

    private var refreshJob: Job? = null
    private var observedSession: String? = null

    init {
        viewModelScope.launch {
            account.collect { current ->
                if (observedSession != current.encryptedAccessToken) {
                    observedSession = current.encryptedAccessToken
                    refreshJob?.cancel()
                    if (current.isSignedIn) {
                        refresh()
                    } else {
                        _state.value = GitHubUiState()
                    }
                }
            }
        }
    }

    fun startDeviceLogin(clientId: String, context: Context) {
        accountManager.startDeviceLogin(clientId, context)
    }

    fun cancelDeviceLogin() = accountManager.cancelDeviceLogin()

    fun loginWithToken(token: String) {
        if (_state.value.authenticating) return
        viewModelScope.launch {
            _state.update { it.copy(authenticating = true, error = null) }
            try {
                accountManager.loginWithToken(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = error.message ?: "GitHub sign-in failed")
                }
            } finally {
                _state.update { it.copy(authenticating = false) }
            }
        }
    }

    fun logout() {
        refreshJob?.cancel()
        viewModelScope.launch { accountManager.logout() }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notice = null) }
            try {
                val token = accountManager.accessToken() ?: error("Sign in to GitHub first")
                val page = apiClient.listRepositories(token)
                _state.update {
                    it.copy(
                        repositories = page.repositories,
                        nextPage = page.nextPage,
                        loading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Could not load repositories",
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val snapshot = _state.value
        val page = snapshot.nextPage ?: return
        if (snapshot.loading || snapshot.loadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            try {
                val token = accountManager.accessToken() ?: error("Sign in to GitHub first")
                val result = apiClient.listRepositories(token, page = page)
                _state.update { current ->
                    current.copy(
                        repositories = (current.repositories + result.repositories).distinctBy { it.id },
                        nextPage = result.nextPage,
                        loadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loadingMore = false,
                        error = error.message ?: "Could not load more repositories",
                    )
                }
            }
        }
    }

    fun clone(repository: GitHubRepository) {
        if (_state.value.cloningRepository != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    cloningRepository = repository.fullName,
                    error = null,
                    notice = null,
                )
            }
            try {
                val workspace = gitRepository.cloneRepository(repository)
                _openWorkspace.value = workspace.id
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = error.message ?: "Could not clone ${repository.fullName}")
                }
            } finally {
                _state.update { it.copy(cloningRepository = null) }
            }
        }
    }

    fun consumeOpenWorkspace() {
        _openWorkspace.value = null
    }
}

data class GitHubUiState(
    val repositories: List<GitHubRepository> = emptyList(),
    val nextPage: Int? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val authenticating: Boolean = false,
    val cloningRepository: String? = null,
    val error: String? = null,
    val notice: String? = null,
)
