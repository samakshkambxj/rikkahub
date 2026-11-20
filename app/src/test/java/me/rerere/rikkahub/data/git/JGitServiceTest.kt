package me.rerere.rikkahub.data.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JGitServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = JGitService()
    private val identity = GitCommitIdentity("Test Author", "author@example.test")

    @Test
    fun firstCommitCanBeReviewedAndCommitted() {
        val directory = repository()
        File(directory, "README.md").writeText("# Project\n")

        assertEquals(
            listOf(GitChangeKind.UNTRACKED),
            service.status(directory).changes.map { it.kind },
        )

        Git.open(directory).use { git -> git.add().addFilepattern(".").call() }
        val stagedDiff = service.diff(directory).staged
        assertTrue(stagedDiff.contains("new file mode"))
        assertTrue(stagedDiff.contains("README.md"))
        assertTrue(stagedDiff.contains("# Project"))

        val commitId = service.commitAll(directory, "Initial commit", identity)

        assertEquals(7, commitId.length)
        assertTrue(service.status(directory).isClean)
        Git.open(directory).use { git ->
            val commit = git.log().call().single()
            assertEquals("Initial commit", commit.fullMessage)
            assertEquals("Test Author", commit.authorIdent.name)
            assertEquals("author@example.test", commit.authorIdent.emailAddress)
        }
    }

    @Test
    fun statusAndDiffSeparateStagedAndUnstagedChanges() {
        val directory = repository()
        File(directory, "app.txt").writeText("first\n")
        service.commitAll(directory, "Initial commit", identity)
        File(directory, "app.txt").writeText("second\n")
        File(directory, "new.txt").writeText("new\n")
        Git.open(directory).use { git -> git.add().addFilepattern("new.txt").call() }

        val status = service.status(directory)
        assertTrue(status.changes.any { it.path == "app.txt" && !it.staged })
        assertTrue(status.changes.any { it.path == "new.txt" && it.staged })
        val diff = service.diff(directory)
        assertTrue(diff.unstaged.contains("app.txt"))
        assertFalse(diff.unstaged.contains("new.txt"))
        assertTrue(diff.staged.contains("new.txt"))
    }

    @Test
    fun commitAllStagesEditsAndDeletions() {
        val directory = repository()
        File(directory, "keep.txt").writeText("old\n")
        File(directory, "delete.txt").writeText("delete\n")
        service.commitAll(directory, "Initial commit", identity)
        File(directory, "keep.txt").writeText("new\n")
        assertTrue(File(directory, "delete.txt").delete())

        service.commitAll(directory, "Update project", identity)

        assertTrue(service.status(directory).isClean)
        assertEquals(2, Git.open(directory).use { git -> git.log().call().count() })
    }

    @Test
    fun emptyCommitIsRejected() {
        val directory = repository()
        assertThrows(IllegalStateException::class.java) {
            service.commitAll(directory, "Nothing", identity)
        }
    }

    @Test
    fun cloneRejectsUnsafeUrlAndNonEmptyDestination() {
        val emptyDirectory = temporaryFolder.newFolder("empty")
        assertThrows(IllegalArgumentException::class.java) {
            service.cloneRepository(emptyDirectory, "file:///tmp/repository", null)
        }
        val nonEmptyDirectory = temporaryFolder.newFolder("non-empty")
        File(nonEmptyDirectory, "file").writeText("content")
        assertThrows(IllegalArgumentException::class.java) {
            service.cloneRepository(nonEmptyDirectory, "https://github.com/octocat/project.git", null)
        }
    }

    private fun repository(): File = temporaryFolder.newFolder().also { directory ->
        Git.init().setDirectory(directory).call().close()
    }
}
