package me.rerere.rikkahub.data.git

enum class GitChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    CONFLICTED,
    UNTRACKED,
}

data class GitFileChange(
    val path: String,
    val kind: GitChangeKind,
    val staged: Boolean,
)

data class WorkspaceGitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val changes: List<GitFileChange>,
) {
    val isClean: Boolean
        get() = changes.isEmpty()
}

data class WorkspaceGitDiff(
    val staged: String,
    val unstaged: String,
) {
    val isEmpty: Boolean
        get() = staged.isBlank() && unstaged.isBlank()
}

data class GitCommitIdentity(
    val name: String,
    val email: String,
)

data class GitOperationResult(
    val message: String,
)
