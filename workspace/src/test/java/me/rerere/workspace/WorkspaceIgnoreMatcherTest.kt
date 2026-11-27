package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIgnoreMatcherTest {
    @Test
    fun defaultRulesIgnoreCommonBuildDirectories() {
        val matcher = WorkspaceIgnoreMatcher()

        assertTrue(matcher.isIgnored(".git", isDirectory = true))
        assertTrue(matcher.isIgnored("node_modules", isDirectory = true))
        assertTrue(matcher.isIgnored("app/build", isDirectory = true))
        // 目录规则只匹配目录本身, 目录被忽略后由遍历整棵跳过, 不逐一匹配里面的文件
        assertFalse(matcher.isIgnored(".git/HEAD", isDirectory = false))
        assertFalse(matcher.isIgnored("dist/bundle.js", isDirectory = false))
        // 目录规则不匹配同名文件
        assertFalse(matcher.isIgnored("build", isDirectory = false))
        assertFalse(matcher.isIgnored("src/main.kt", isDirectory = false))
    }

    @Test
    fun noneMatcherIgnoresNothing() {
        val matcher = WorkspaceIgnoreMatcher.none()

        assertFalse(matcher.isIgnored(".git", isDirectory = true))
        assertFalse(matcher.isIgnored("node_modules/x.js", isDirectory = false))
    }

    @Test
    fun gitignoreRulesApplyFromTheirDirectory() {
        val matcher = WorkspaceIgnoreMatcher()
            .withGitignore("", "*.log\n")
            .withGitignore("sub", "/secret.txt\ntemp/\nnotes.txt\n")

        assertTrue(matcher.isIgnored("debug.log", isDirectory = false))
        assertTrue(matcher.isIgnored("sub/deep/debug.log", isDirectory = false))
        // 锚定到 sub 目录的规则不影响其他目录
        assertFalse(matcher.isIgnored("other/secret.txt", isDirectory = false))
        assertTrue(matcher.isIgnored("sub/secret.txt", isDirectory = false))
        assertTrue(matcher.isIgnored("sub/temp", isDirectory = true))
        assertFalse(matcher.isIgnored("sub/temp", isDirectory = false))
        // 不带 / 的规则匹配任意层级的文件名
        assertTrue(matcher.isIgnored("sub/deep/notes.txt", isDirectory = false))
    }

    @Test
    fun negationUnignoresEarlierRule() {
        val matcher = WorkspaceIgnoreMatcher()
            .withGitignore("", "*.log\n!keep.log\n")

        assertTrue(matcher.isIgnored("debug.log", isDirectory = false))
        assertFalse(matcher.isIgnored("keep.log", isDirectory = false))
    }

    @Test
    fun doubleStarMatchesAcrossDirectories() {
        val matcher = WorkspaceIgnoreMatcher()
            .withGitignore("", "**/generated/\n")

        assertTrue(matcher.isIgnored("generated", isDirectory = true))
        assertTrue(matcher.isIgnored("a/generated", isDirectory = true))
        assertTrue(matcher.isIgnored("a/b/generated", isDirectory = true))
        // 目录规则不匹配目录里的文件 (遍历时整棵跳过即可)
        assertFalse(matcher.isIgnored("a/generated/x.kt", isDirectory = false))
        assertFalse(matcher.isIgnored("a/generate", isDirectory = true))
    }

    @Test
    fun commentsAndEscapedCharsAreHandled() {
        val matcher = WorkspaceIgnoreMatcher()
            .withGitignore("", "# comment\n\\#hash.txt\n")

        // \# 转义后表示字面文件名 #hash.txt 被忽略
        assertTrue(matcher.isIgnored("#hash.txt", isDirectory = false))
        assertFalse(matcher.isIgnored("other.txt", isDirectory = false))
    }
}
