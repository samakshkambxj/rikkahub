package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFileSystemSearchTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fileSystem = WorkspaceFileSystem()

    private fun createProject(): File {
        val root = tempFolder.newFolder("project")
        File(root, "src").mkdirs()
        File(root, "src/main.kt").writeText("fun main() {\n    println(\"needle here\")\n}\n")
        File(root, "src/util.kt").writeText("const NEEDLE = 1\n")
        File(root, "build").mkdirs()
        File(root, "build/output.txt").writeText("needle in build\n")
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/dep.js").writeText("needle in node_modules\n")
        File(root, "debug.log").writeText("needle in log\n")
        File(root, "keep.log").writeText("needle in keep log\n")
        File(root, ".gitignore").writeText("build/\nnode_modules/\n*.log\n!keep.log\n")
        return root
    }

    @Test
    fun grepRespectsGitignoreAndDefaultExcludes() {
        val root = createProject()

        val matches = fileSystem.grep(root, "needle")

        assertEquals(
            setOf(
                WorkspaceSearchMatch("src/main.kt", 2, """    println("needle here")"""),
                WorkspaceSearchMatch("src/util.kt", 1, "const NEEDLE = 1"),
                WorkspaceSearchMatch("keep.log", 1, "needle in keep log"),
            ),
            matches.toSet(),
        )
    }

    @Test
    fun grepWithoutGitignoreSearchesEverything() {
        val root = createProject()

        val paths = fileSystem.grep(root, "needle", respectGitignore = false)
            .map { it.path }
            .toSet()

        assertTrue(paths.containsAll(
            listOf("src/main.kt", "src/util.kt", "build/output.txt", "node_modules/dep.js", "debug.log", "keep.log"),
        ))
    }

    @Test
    fun nestedGitignoreExtendsParentRules() {
        val root = createProject()
        File(root, "src/.gitignore").writeText("util.kt\n")

        val paths = fileSystem.grep(root, "needle").map { it.path }

        assertEquals(setOf("src/main.kt", "keep.log"), paths.toSet())
    }

    @Test
    fun grepSupportsRegexAndCaseSensitivity() {
        val root = createProject()

        val regexMatches = fileSystem.grep(root, """println\("[^"]*"\)""", regex = true)
        assertEquals(listOf(WorkspaceSearchMatch("src/main.kt", 2, """    println("needle here")""")), regexMatches)

        val caseSensitive = fileSystem.grep(root, "needle", ignoreCase = false)
        assertEquals(
            setOf(
                WorkspaceSearchMatch("src/main.kt", 2, """    println("needle here")"""),
                WorkspaceSearchMatch("keep.log", 1, "needle in keep log"),
            ),
            caseSensitive.toSet(),
        )
    }

    @Test
    fun grepFiltersByIncludeGlob() {
        val root = createProject()

        // glob 的 * 不跨目录, 匹配子目录里的文件要用 **
        val matches = fileSystem.grep(root, "needle", includeGlob = "**/*.kt")

        assertEquals(
            setOf(
                WorkspaceSearchMatch("src/main.kt", 2, """    println("needle here")"""),
                WorkspaceSearchMatch("src/util.kt", 1, "const NEEDLE = 1"),
            ),
            matches.toSet(),
        )
    }

    @Test
    fun grepHonorsMaxResultsAndSubdirectoryScope() {
        val root = createProject()
        File(root, "src/extra").mkdirs()
        File(root, "src/extra/a.txt").writeText("needle a\n")
        File(root, "src/extra/b.txt").writeText("needle b\n")

        assertEquals(1, fileSystem.grep(root, "needle", maxResults = 1).size)
        assertEquals(
            listOf("extra/a.txt", "extra/b.txt", "main.kt", "util.kt"),
            fileSystem.grep(root, "needle", path = "src").map { it.path }.sorted(),
        )
    }

    @Test
    fun grepSkipsOversizedFilesAndRejectsBadInput() {
        val root = tempFolder.newFolder("big")
        val big = File(root, "big.txt")
        big.writeText("x".repeat(1024 * 1024 + 1) + "\nneedle\n")
        File(root, "small.txt").writeText("needle\n")

        assertEquals(
            listOf(WorkspaceSearchMatch("small.txt", 1, "needle")),
            fileSystem.grep(root, "needle"),
        )
        assertThrows(IllegalArgumentException::class.java) { fileSystem.grep(root, " ") }
        assertThrows(IllegalArgumentException::class.java) { fileSystem.grep(root, "needle", path = "../outside") }
        assertThrows(IllegalArgumentException::class.java) { fileSystem.grep(root, "needle", path = "missing") }
    }
}
