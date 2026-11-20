package me.rerere.rikkahub.ui.pages.ide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.git.GitFileChange
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceGitPage(
    id: String,
    vm: WorkspaceGitVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Source control") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = vm::refresh,
                        enabled = !state.loading && state.operation == null,
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Refresh Git status")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.operation != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                state.loading && state.status == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                !state.isRepository -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Not a Git repository", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        "Clone a GitHub repository into a new workspace, or initialize Git in this workspace's terminal.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { navController.navigate(Screen.GitHub) }) {
                        Text("Open GitHub repositories")
                    }
                }

                else -> WorkspaceGitContent(state = state, vm = vm)
            }
        }
    }
}

@Composable
private fun WorkspaceGitContent(
    state: WorkspaceGitUiState,
    vm: WorkspaceGitVM,
) {
    val status = state.status
    val operationRunning = state.operation != null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        status?.branch ?: "Unknown branch",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    status?.let {
                        Text(
                            "Ahead ${it.ahead} · Behind ${it.behind} · ${it.changes.size} changes",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.remoteUrl?.let { remote ->
                        Text(
                            redactRemoteCredentials(remote),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        state.operation?.let { item { Text("$it…", color = MaterialTheme.colorScheme.primary) } }
        state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        state.error?.let { item { IdeErrorText(it) } }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = vm::pull,
                    modifier = Modifier.weight(1f),
                    enabled = !operationRunning,
                ) {
                    Text("Pull")
                }
                OutlinedButton(
                    onClick = vm::push,
                    modifier = Modifier.weight(1f),
                    enabled = !operationRunning,
                ) {
                    Text("Push")
                }
            }
        }

        item {
            Text("Changes", style = MaterialTheme.typography.titleMediumEmphasized)
        }
        if (status?.changes.isNullOrEmpty()) {
            item {
                Text(
                    "Working tree clean",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(status?.changes.orEmpty()) { change -> GitChangeItem(change) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Commit all changes", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    "Stages additions, edits and deletions, then creates one commit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.commitMessage,
                    onValueChange = vm::updateCommitMessage,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Commit message") },
                    minLines = 2,
                    enabled = !operationRunning,
                )
                OutlinedTextField(
                    value = state.authorName,
                    onValueChange = vm::updateAuthorName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Author and committer name") },
                    singleLine = true,
                    enabled = !operationRunning,
                )
                OutlinedTextField(
                    value = state.authorEmail,
                    onValueChange = vm::updateAuthorEmail,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Author and committer email") },
                    singleLine = true,
                    enabled = !operationRunning,
                )
                Button(
                    onClick = vm::commitAll,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !operationRunning &&
                        state.commitMessage.isNotBlank() &&
                        state.authorName.isNotBlank() &&
                        state.authorEmail.isNotBlank() &&
                        status?.isClean == false,
                ) {
                    Text("Commit all")
                }
            }
        }

        state.diff?.staged?.takeIf { it.isNotBlank() }?.let { diff ->
            item { GitDiffSection(title = "Staged diff", diff = diff) }
        }
        state.diff?.unstaged?.takeIf { it.isNotBlank() }?.let { diff ->
            item { GitDiffSection(title = "Unstaged diff", diff = diff) }
        }
    }
}

@Composable
private fun GitChangeItem(change: GitFileChange) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                change.path,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    change.kind.name.lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (change.kind == me.rerere.rikkahub.data.git.GitChangeKind.CONFLICTED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    if (change.staged) "staged" else "unstaged",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GitDiffSection(title: String, diff: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            SelectionContainer {
                Text(
                    diff,
                    modifier = Modifier.padding(14.dp),
                    fontFamily = JetbrainsMono,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

private fun redactRemoteCredentials(remote: String): String =
    remote.replace(Regex("^(https?://)[^/@]+@"), "$1")
