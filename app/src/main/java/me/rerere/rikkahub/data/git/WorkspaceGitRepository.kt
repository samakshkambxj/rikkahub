package me.rerere.rikkahub.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.github.GitHubAccountManager
import me.rerere.rikkahub.data.github.GitHubRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/** Connects the pure JGit engine to app workspaces and the current GitHub account. */
class WorkspaceGitRepository(
    private val workspaceRepository: WorkspaceRepository,
    private val accountManager: GitHubAccountManager,
    private val git: JGitService = JGitService(),
) {
    suspend fun isRepository(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        git.isRepository(directory(workspaceId))
    }

    suspend fun remoteUrl(workspaceId: String): String? = withContext(Dispatchers.IO) {
        git.remoteUrl(directory(workspaceId))
    }

    suspend fun status(workspaceId: String): WorkspaceGitStatus = withContext(Dispatchers.IO) {
        git.status(directory(workspaceId))
    }

    suspend fun diff(workspaceId: String): WorkspaceGitDiff = withContext(Dispatchers.IO) {
        git.diff(directory(workspaceId))
    }

    suspend fun cloneRepository(repository: GitHubRepository): WorkspaceEntity {
        require(repository.cloneUrl.startsWith(GITHUB_HTTPS_PREFIX)) {
            "Only github.com HTTPS repositories can be cloned"
        }
        val workspace = workspaceRepository.create(repository.name)
        try {
            withContext(Dispatchers.IO) {
                git.cloneRepository(
                    directory = workspaceRepository.localFilesDirectory(workspace.id),
                    cloneUrl = repository.cloneUrl,
                    credentials = credentialsProvider(repository.cloneUrl),
                )
            }
            return workspace
        } catch (error: Throwable) {
            // A failed clone must not leave a workspace that looks like a usable project.
            runCatching { workspaceRepository.delete(workspace.id) }
            throw error
        }
    }

    suspend fun defaultIdentity(): GitCommitIdentity {
        val account = accountManager.currentAccount()
        require(account.isSignedIn) { "Sign in to GitHub before committing" }
        return GitCommitIdentity(
            name = account.committerName,
            email = account.committerEmail,
        )
    }

    suspend fun commitAll(
        workspaceId: String,
        message: String,
        identity: GitCommitIdentity,
    ): String = withContext(Dispatchers.IO) {
        git.commitAll(directory(workspaceId), message, identity)
    }

    suspend fun pull(workspaceId: String): GitOperationResult = withContext(Dispatchers.IO) {
        val directory = directory(workspaceId)
        git.pull(directory, credentialsProvider(git.remoteUrl(directory)))
    }

    suspend fun push(workspaceId: String): GitOperationResult = withContext(Dispatchers.IO) {
        val directory = directory(workspaceId)
        git.push(directory, credentialsProvider(git.remoteUrl(directory)))
    }

    private suspend fun directory(workspaceId: String): File =
        workspaceRepository.localFilesDirectory(workspaceId)

    private suspend fun credentialsProvider(remoteUrl: String?): CredentialsProvider? {
        // Never forward a GitHub token to another host, an SSH remote, or a crafted lookalike URL.
        if (remoteUrl?.startsWith(GITHUB_HTTPS_PREFIX) != true) return null
        return accountManager.accessToken()?.let { token ->
            UsernamePasswordCredentialsProvider(GITHUB_TOKEN_USERNAME, token)
        }
    }

    private companion object {
        const val GITHUB_HTTPS_PREFIX = "https://github.com/"
        // GitHub accepts any non-blank username for token auth; this value is not an account name.
        const val GITHUB_TOKEN_USERNAME = "x-access-token"
    }
}
