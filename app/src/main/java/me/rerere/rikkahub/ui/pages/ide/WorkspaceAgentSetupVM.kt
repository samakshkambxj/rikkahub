package me.rerere.rikkahub.ui.pages.ide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.git.GitCommitIdentity
import me.rerere.rikkahub.data.git.WorkspaceGitRepository
import me.rerere.rikkahub.data.github.GitHubAccountManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

class WorkspaceAgentSetupVM(
    private val workspaceId: String,
    private val workspaceRepository: WorkspaceRepository,
    private val gitRepository: WorkspaceGitRepository,
    private val accountManager: GitHubAccountManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceAgentSetupUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        loadIdentity()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val workspace = workspaceRepository.getById(workspaceId) ?: error("Workspace not found")
                val rootfsReady = workspace.shellStatus == WorkspaceShellStatus.READY.name
                val gitVersion = if (rootfsReady) gitRepository.gitVersion(workspaceId) else null
                _state.update {
                    it.copy(
                        loading = false,
                        workspaceName = workspace.name,
                        rootfsReady = rootfsReady,
                        gitVersion = gitVersion,
                        isRepository = gitRepository.isRepository(workspaceId),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, error = error.message ?: "Could not inspect workspace")
                }
            }
        }
    }

    private fun loadIdentity() {
        viewModelScope.launch {
            _state.update { it.copy(identityLoading = true) }
            try {
                val local = runCatching { gitRepository.configuredIdentity(workspaceId) }.getOrNull()
                if (local != null) {
                    _state.update {
                        it.copy(
                            authorName = local.name,
                            authorEmail = local.email,
                            identityLoading = false,
                        )
                    }
                    return@launch
                }

                // Existing local/global values are preferred. Refresh GitHub only when the repository
                // has no identity, and never let a network failure block manual setup.
                runCatching { accountManager.refreshAccountMetadata() }
                val identity = gitRepository.detectIdentity(workspaceId)
                _state.update {
                    it.copy(
                        authorName = identity.name,
                        authorEmail = identity.email,
                        identityLoading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(identityLoading = false) }
            }
        }
    }

    fun updateAuthorName(value: String) = _state.update {
        it.copy(authorName = value, ready = false, notice = null)
    }

    fun updateAuthorEmail(value: String) = _state.update {
        it.copy(authorEmail = value, ready = false, notice = null)
    }

    fun installAndConfigure() {
        val snapshot = _state.value
        if (snapshot.installing || snapshot.identityLoading) return
        if (!snapshot.rootfsReady) {
            _state.update { it.copy(error = "Install the workspace Rootfs first") }
            return
        }
        if (snapshot.authorName.isBlank() || snapshot.authorEmail.isBlank()) {
            _state.update { it.copy(error = "Both author name and email are required") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(installing = true, error = null, notice = null, ready = false)
            }
            try {
                val version = gitRepository.ensureGitInstalled(workspaceId)
                gitRepository.configureIdentity(
                    workspaceId = workspaceId,
                    identity = GitCommitIdentity(snapshot.authorName, snapshot.authorEmail),
                )
                _state.update {
                    it.copy(
                        installing = false,
                        gitVersion = version,
                        ready = true,
                        notice = "Git and repository identity are ready for the agent",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        installing = false,
                        error = error.message ?: "Agent environment setup failed",
                    )
                }
            }
        }
    }
}

data class WorkspaceAgentSetupUiState(
    val loading: Boolean = true,
    val identityLoading: Boolean = true,
    val installing: Boolean = false,
    val rootfsReady: Boolean = false,
    val isRepository: Boolean = true,
    val workspaceName: String = "",
    val gitVersion: String? = null,
    val authorName: String = "",
    val authorEmail: String = "",
    val ready: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)
