package me.rerere.rikkahub.ui.pages.ide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.git.GitCommitIdentity
import me.rerere.rikkahub.data.git.WorkspaceGitDiff
import me.rerere.rikkahub.data.git.WorkspaceGitRepository
import me.rerere.rikkahub.data.git.WorkspaceGitStatus

class WorkspaceGitVM(
    private val workspaceId: String,
    private val repository: WorkspaceGitRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceGitUiState())
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { repository.defaultIdentity() }.onSuccess { identity ->
                _state.update {
                    it.copy(authorName = identity.name, authorEmail = identity.email)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_state.value.operation != null) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notice = null) }
            try {
                loadRepositoryState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, error = error.message ?: "Could not read Git repository")
                }
            }
        }
    }

    fun updateCommitMessage(value: String) = _state.update { it.copy(commitMessage = value) }

    fun updateAuthorName(value: String) = _state.update { it.copy(authorName = value) }

    fun updateAuthorEmail(value: String) = _state.update { it.copy(authorEmail = value) }

    fun commitAll() {
        val snapshot = _state.value
        performOperation("Committing") {
            val commit = repository.commitAll(
                workspaceId = workspaceId,
                message = snapshot.commitMessage,
                identity = GitCommitIdentity(snapshot.authorName, snapshot.authorEmail),
            )
            _state.update { it.copy(commitMessage = "") }
            "Created commit $commit"
        }
    }

    fun pull() = performOperation("Pulling") {
        repository.pull(workspaceId).message
    }

    fun push() = performOperation("Pushing") {
        repository.push(workspaceId).message
    }

    private fun performOperation(name: String, block: suspend () -> String) {
        if (_state.value.operation != null) return
        viewModelScope.launch {
            _state.update { it.copy(operation = name, error = null, notice = null) }
            try {
                val message = block()
                loadRepositoryState()
                _state.update {
                    it.copy(operation = null, notice = message, loading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        operation = null,
                        loading = false,
                        error = error.message ?: "$name failed",
                    )
                }
            }
        }
    }

    private suspend fun loadRepositoryState() {
        if (!repository.isRepository(workspaceId)) {
            _state.update {
                WorkspaceGitUiState(
                    loading = false,
                    isRepository = false,
                    authorName = it.authorName,
                    authorEmail = it.authorEmail,
                )
            }
            return
        }
        val remote = repository.remoteUrl(workspaceId)
        val status = repository.status(workspaceId)
        val diff = repository.diff(workspaceId)
        _state.update {
            it.copy(
                loading = false,
                isRepository = true,
                remoteUrl = remote,
                status = status,
                diff = diff,
                error = null,
            )
        }
    }
}

data class WorkspaceGitUiState(
    val loading: Boolean = true,
    val isRepository: Boolean = true,
    val remoteUrl: String? = null,
    val status: WorkspaceGitStatus? = null,
    val diff: WorkspaceGitDiff? = null,
    val operation: String? = null,
    val commitMessage: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val error: String? = null,
    val notice: String? = null,
)
