package me.rerere.rikkahub.ui.pages.ide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceAgentSetupPage(
    id: String,
    vm: WorkspaceAgentSetupVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent setup") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.loading && !state.installing) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Check environment again")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Prepare ${state.workspaceName.ifBlank { "this repository" }}",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Install Git inside the workspace sandbox and configure the identity the agent will use for commits. No Android host packages are changed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (state.loading || state.installing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AgentSetupStatus(state = state)

            if (!state.rootfsReady && !state.loading) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Rootfs is required",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Install the Ubuntu rootfs from the workspace Basic tab, then return here and tap refresh.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.WorkspaceDetail(id)) }
                        ) {
                            Text("Open workspace settings")
                        }
                    }
                }
            }

            if (!state.isRepository) {
                IdeErrorText("Agent identity setup requires a cloned Git repository")
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Commit identity", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    "Auto-detected from repository config, workspace global config, or your GitHub profile. Review and edit if needed. It is saved only in this repository's .git/config.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.identityLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = state.authorName,
                    onValueChange = vm::updateAuthorName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !state.installing && !state.identityLoading,
                )
                OutlinedTextField(
                    value = state.authorEmail,
                    onValueChange = vm::updateAuthorEmail,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.installing && !state.identityLoading,
                )
            }

            state.error?.let { IdeErrorText(it) }
            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = vm::installAndConfigure,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.rootfsReady &&
                    state.isRepository &&
                    !state.loading &&
                    !state.identityLoading &&
                    !state.installing &&
                    state.authorName.isNotBlank() &&
                    state.authorEmail.isNotBlank(),
            ) {
                Text(
                    when {
                        state.installing -> "Preparing agent environment…"
                        state.gitVersion == null -> "Install Git & save identity"
                        else -> "Save identity & finish"
                    }
                )
            }

            if (state.ready) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Agent environment ready",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                        )
                        Text(
                            "Return to a chat, open the attachment/more panel, choose Workspace, and select ${state.workspaceName}. The agent can now use Git in /workspace.",
                        )
                        Button(onClick = { navController.navigate(Screen.WorkspaceDetail(id)) }) {
                            Text("Open project")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSetupStatus(state: WorkspaceAgentSetupUiState) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Environment", style = MaterialTheme.typography.titleSmallEmphasized)
            Text("Rootfs: ${if (state.rootfsReady) "Ready" else "Not installed"}")
            Text("Git: ${state.gitVersion ?: "Not installed"}")
            if (state.loading) {
                CircularProgressIndicator()
            }
        }
    }
}
