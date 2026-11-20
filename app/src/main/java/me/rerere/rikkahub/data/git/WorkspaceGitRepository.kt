package me.rerere.rikkahub.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.github.GitHubAccountManager
import me.rerere.rikkahub.data.github.GitHubRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
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

    suspend fun configuredIdentity(workspaceId: String): GitCommitIdentity? =
        withContext(Dispatchers.IO) {
            git.configuredIdentity(directory(workspaceId))
        }

    suspend fun configureIdentity(workspaceId: String, identity: GitCommitIdentity) =
        withContext(Dispatchers.IO) {
            git.configureIdentity(directory(workspaceId), identity)
        }

    /** Local repository config wins, then the rootfs global config, then GitHub metadata. */
    suspend fun detectIdentity(workspaceId: String): GitCommitIdentity = withContext(Dispatchers.IO) {
        val local = git.configuredIdentity(directory(workspaceId))
        val global = globalGitIdentity(workspaceId)
        val account = accountManager.currentAccount()
        GitCommitIdentity(
            name = local?.name
                ?: global?.name?.takeIf { it.isNotBlank() }
                ?: account.committerName,
            email = local?.email
                ?: global?.email?.takeIf { it.isNotBlank() }
                ?: account.committerEmail,
        )
    }

    suspend fun gitVersion(workspaceId: String): String? {
        val workspace = workspaceRepository.getById(workspaceId) ?: error("Workspace not found")
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return null
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "if command -v git >/dev/null 2>&1; then git --version; fi",
        )
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("git version") }
    }

    /** Install only git inside the sandbox; no host Android packages are changed. */
    suspend fun ensureGitInstalled(workspaceId: String): String {
        val workspace = workspaceRepository.getById(workspaceId) ?: error("Workspace not found")
        require(workspace.capabilities().rootfs) { "Agent Git setup requires a local rootfs workspace" }
        require(workspace.shellStatus == WorkspaceShellStatus.READY.name) {
            "Install the workspace Rootfs before installing Git"
        }
        gitVersion(workspaceId)?.let { return it }
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = """
                set -e
                if command -v apt-get >/dev/null 2>&1; then
                  export DEBIAN_FRONTEND=noninteractive
                  apt-get update
                  apt-get install -y --no-install-recommends git ca-certificates
                elif command -v apk >/dev/null 2>&1; then
                  apk add --no-cache git ca-certificates
                else
                  printf '%s\n' 'No supported package manager found. Install git manually with apt-get or apk.' >&2
                  exit 1
                fi
                git --version
            """.trimIndent(),
            timeoutMillis = GIT_INSTALL_TIMEOUT_MS,
        )
        check(!result.timedOut) { "Git installation timed out. Check the network and try again." }
        check(result.exitCode == 0) {
            val details = result.stderr.ifBlank { result.stdout }.trim().takeLast(MAX_ERROR_OUTPUT)
            "Could not install Git${details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}"
        }
        return gitVersion(workspaceId) ?: "Git installed"
    }

    private suspend fun globalGitIdentity(workspaceId: String): GitCommitIdentity? {
        val workspace = workspaceRepository.getById(workspaceId) ?: return null
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return null
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = """
                if ! command -v git >/dev/null 2>&1; then exit 0; fi
                printf 'name='
                git config --global --get user.name 2>/dev/null || printf '\n'
                printf 'email='
                git config --global --get user.email 2>/dev/null || printf '\n'
            """.trimIndent(),
        )
        if (result.exitCode != 0) return null
        val values = result.stdout.lineSequence()
            .filter { it.startsWith("name=") || it.startsWith("email=") }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=').trim() }
        val name = values["name"].orEmpty()
        val email = values["email"].orEmpty()
        return if (name.isNotEmpty() || email.isNotEmpty()) GitCommitIdentity(name, email) else null
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
        const val GIT_INSTALL_TIMEOUT_MS = 10 * 60 * 1_000L
        const val MAX_ERROR_OUTPUT = 4_000
        const val GITHUB_HTTPS_PREFIX = "https://github.com/"
        // GitHub accepts any non-blank username for token auth; this value is not an account name.
        const val GITHUB_TOKEN_USERNAME = "x-access-token"
    }
}
