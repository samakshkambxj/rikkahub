package me.rerere.rikkahub.data.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Local repository operations backed by JGit. This class deliberately has no Android or workspace
 * dependencies, so repository behavior can be tested on the JVM.
 */
class JGitService {
    fun isRepository(directory: File): Boolean = File(directory, Constants.DOT_GIT).exists()

    fun cloneRepository(
        directory: File,
        cloneUrl: String,
        credentials: CredentialsProvider?,
    ) {
        require(cloneUrl.startsWith("https://")) { "Only HTTPS clone URLs are supported" }
        require(!directory.exists() || directory.listFiles()?.isEmpty() == true) {
            "Clone destination must be an empty directory"
        }
        directory.mkdirs()
        Git.cloneRepository()
            .setURI(cloneUrl)
            .setDirectory(directory)
            .apply { credentials?.let(::setCredentialsProvider) }
            .call()
            .close()
    }

    fun remoteUrl(directory: File): String? = withGit(directory) { git ->
        git.repository.config.getString("remote", "origin", "url")
    }

    fun status(directory: File): WorkspaceGitStatus = withGit(directory) { git ->
        val repository = git.repository
        val branch = repository.branch ?: Constants.HEAD
        val tracking = runCatching { BranchTrackingStatus.of(repository, branch) }.getOrNull()
        git.status().call().toWorkspaceStatus(
            branch = branch,
            ahead = tracking?.aheadCount ?: 0,
            behind = tracking?.behindCount ?: 0,
        )
    }

    fun diff(directory: File): WorkspaceGitDiff = withGit(directory) { git ->
        WorkspaceGitDiff(
            staged = stagedDiff(git),
            unstaged = ByteArrayOutputStream().use { output ->
                git.diff()
                    .setCached(false)
                    .setOutputStream(output)
                    .call()
                output.toString(Charsets.UTF_8.name())
            },
        )
    }

    /** Stages all additions, edits and deletions, then creates one non-empty commit. */
    fun commitAll(
        directory: File,
        message: String,
        identity: GitCommitIdentity,
    ): String {
        require(message.isNotBlank()) { "Commit message is required" }
        require(identity.name.isNotBlank()) { "Author name is required" }
        require(identity.email.isNotBlank()) { "Author email is required" }
        return withGit(directory) { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            if (git.status().call().isClean) error("There are no changes to commit")
            git.commit()
                .setMessage(message.trim())
                .setAuthor(identity.name.trim(), identity.email.trim())
                .setCommitter(identity.name.trim(), identity.email.trim())
                .setAllowEmpty(false)
                .call()
                .abbreviate(SHORT_COMMIT_LENGTH)
                .name()
        }
    }

    fun pull(directory: File, credentials: CredentialsProvider?) = withGit(directory) { git ->
        val result = git.pull()
            .setRemote("origin")
            .apply { credentials?.let(::setCredentialsProvider) }
            .call()
        check(result.isSuccessful) {
            val mergeStatus = result.mergeResult?.mergeStatus?.name?.replace('_', ' ')
            "Pull failed${mergeStatus?.let { ": $it" }.orEmpty()}. Resolve conflicts in the editor."
        }
        GitOperationResult(
            message = if (result.mergeResult == null) "Already up to date" else "Pulled latest changes",
        )
    }

    fun push(directory: File, credentials: CredentialsProvider?) = withGit(directory) { git ->
        val results = git.push()
            .setRemote("origin")
            .apply { credentials?.let(::setCredentialsProvider) }
            .call()
        val rejected = results.flatMap { result ->
            result.remoteUpdates.filterNot { update ->
                update.status == RemoteRefUpdate.Status.OK ||
                    update.status == RemoteRefUpdate.Status.UP_TO_DATE
            }
        }
        check(rejected.isEmpty()) {
            rejected.joinToString(prefix = "Push rejected: ", separator = "; ") { update ->
                listOfNotNull(
                    update.remoteName,
                    update.status.name.replace('_', ' '),
                    update.message,
                ).joinToString(": ")
            }
        }
        GitOperationResult(message = "Pushed commits to origin")
    }

    private fun stagedDiff(git: Git): String {
        val hasHead = git.repository.resolve(Constants.HEAD) != null
        if (hasHead) {
            return ByteArrayOutputStream().use { output ->
                git.diff().setCached(true).setOutputStream(output).call()
                output.toString(Charsets.UTF_8.name())
            }
        }

        // DiffCommand normally compares the index against HEAD. An unborn branch has no HEAD yet,
        // so compare against JGit's empty tree to make the first commit reviewable as well.
        return ByteArrayOutputStream().use { output ->
            git.repository.newObjectReader().use { reader ->
                val oldTree = CanonicalTreeParser().apply { reset(reader, Constants.EMPTY_TREE_ID) }
                val newTree = DirCacheIterator(git.repository.readDirCache())
                git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .setOutputStream(output)
                    .call()
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun Status.toWorkspaceStatus(
        branch: String,
        ahead: Int,
        behind: Int,
    ): WorkspaceGitStatus {
        val changes = buildList {
            added.forEach { add(GitFileChange(it, GitChangeKind.ADDED, staged = true)) }
            changed.forEach { add(GitFileChange(it, GitChangeKind.MODIFIED, staged = true)) }
            removed.forEach { add(GitFileChange(it, GitChangeKind.DELETED, staged = true)) }
            modified.forEach { add(GitFileChange(it, GitChangeKind.MODIFIED, staged = false)) }
            missing.forEach { add(GitFileChange(it, GitChangeKind.DELETED, staged = false)) }
            untracked.forEach { add(GitFileChange(it, GitChangeKind.UNTRACKED, staged = false)) }
            conflicting.forEach { add(GitFileChange(it, GitChangeKind.CONFLICTED, staged = false)) }
        }.sortedWith(compareBy(GitFileChange::path, GitFileChange::staged, GitFileChange::kind))
        return WorkspaceGitStatus(
            branch = branch,
            ahead = ahead,
            behind = behind,
            changes = changes,
        )
    }

    private fun <T> withGit(directory: File, action: (Git) -> T): T {
        require(isRepository(directory)) { "This workspace is not a Git repository" }
        return Git.open(directory).use(action)
    }

    private companion object {
        const val SHORT_COMMIT_LENGTH = 7
    }
}
