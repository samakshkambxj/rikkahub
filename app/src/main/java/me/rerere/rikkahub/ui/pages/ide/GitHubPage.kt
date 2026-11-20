package me.rerere.rikkahub.ui.pages.ide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.github.GitHubAccount
import me.rerere.rikkahub.data.github.GitHubLoginStatus
import me.rerere.rikkahub.data.github.GitHubRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun GitHubPage(vm: GitHubVM = koinViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val loginStatus by vm.loginStatus.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val openWorkspace by vm.openWorkspace.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current

    LaunchedEffect(openWorkspace) {
        openWorkspace?.let { workspaceId ->
            navController.navigate(Screen.WorkspaceAgentSetup(workspaceId))
            vm.consumeOpenWorkspace()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = { BackButton() },
                actions = {
                    if (account.isSignedIn) {
                        IconButton(onClick = vm::refresh, enabled = !state.loading) {
                            Icon(HugeIcons.Refresh01, contentDescription = "Refresh repositories")
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (account.isSignedIn) {
            GitHubRepositoryContent(
                account = account,
                state = state,
                onLogout = vm::logout,
                onClone = vm::clone,
                onLoadMore = vm::loadNextPage,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            GitHubSignInContent(
                account = account,
                loginStatus = loginStatus,
                state = state,
                onDeviceLogin = { clientId -> vm.startDeviceLogin(clientId, context) },
                onCancelDeviceLogin = vm::cancelDeviceLogin,
                onTokenLogin = vm::loginWithToken,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun GitHubSignInContent(
    account: GitHubAccount,
    loginStatus: GitHubLoginStatus,
    state: GitHubUiState,
    onDeviceLogin: (String) -> Unit,
    onCancelDeviceLogin: () -> Unit,
    onTokenLogin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var clientId by rememberSaveable(account.oauthClientId) { mutableStateOf(account.oauthClientId) }
    // Never use rememberSaveable for a secret: saved state can be copied into an Android Bundle.
    var accessToken by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val deviceLoginBusy = loginStatus is GitHubLoginStatus.Authorizing ||
        loginStatus is GitHubLoginStatus.DeviceCode

    Column(
        modifier = modifier
            .fillMaxSize()
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
                    "Native GitHub integration",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Clone repositories into local AI workspaces, edit code, review diffs, commit and push without embedding credentials in Git remotes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sign in with GitHub", style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                "Enter a GitHub OAuth App client ID with Device Flow enabled. The client ID is not a secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OAuth App client ID") },
                singleLine = true,
                enabled = !deviceLoginBusy && !state.authenticating,
            )
            Button(
                onClick = { onDeviceLogin(clientId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = clientId.isNotBlank() && !deviceLoginBusy && !state.authenticating,
            ) {
                Text("Start device sign-in")
            }
        }

        when (val status = loginStatus) {
            GitHubLoginStatus.Authorizing -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                Text("Requesting a GitHub device code…")
            }

            is GitHubLoginStatus.DeviceCode -> Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Enter this code on GitHub:")
                    Text(
                        status.userCode,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                    TextButton(onClick = { uriHandler.openUri(status.verificationUrl) }) {
                        Text("Open GitHub verification page")
                    }
                    TextButton(onClick = onCancelDeviceLogin) {
                        Text("Cancel sign-in")
                    }
                }
            }

            is GitHubLoginStatus.Failed -> IdeErrorText(status.message)
            GitHubLoginStatus.Idle -> Unit
        }

        Text(
            "Or use a personal access token",
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        OutlinedTextField(
            value = accessToken,
            onValueChange = { accessToken = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Personal access token") },
            supportingText = {
                Text("Validated, then encrypted with Android Keystore. It is never saved in this form field's state.")
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !state.authenticating && !deviceLoginBusy,
        )
        Button(
            onClick = { onTokenLogin(accessToken) },
            modifier = Modifier.fillMaxWidth(),
            enabled = accessToken.isNotBlank() && !state.authenticating && !deviceLoginBusy,
        ) {
            if (state.authenticating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(if (state.authenticating) "Validating…" else "Validate and connect")
        }
        state.error?.let { IdeErrorText(it) }
    }
}

@Composable
private fun GitHubRepositoryContent(
    account: GitHubAccount,
    state: GitHubUiState,
    onLogout: () -> Unit,
    onClone: (GitHubRepository) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = account.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(CircleShape),
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    ) {
                        Text(
                            account.committerName,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        Text(
                            "@${account.login}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onLogout) { Text("Sign out") }
                }
            }
        }

        state.error?.let { message -> item { IdeErrorText(message) } }
        state.notice?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
        }

        if (state.loading && state.repositories.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.repositories.isEmpty()) {
            item { Text("No repositories were returned for this account.") }
        }

        items(state.repositories, key = { it.id }) { repository ->
            GitHubRepositoryItem(
                repository = repository,
                cloning = state.cloningRepository == repository.fullName,
                cloneEnabled = state.cloningRepository == null,
                onClone = { onClone(repository) },
            )
        }

        if (state.nextPage != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.loadingMore,
                ) {
                    Text(if (state.loadingMore) "Loading…" else "Load more repositories")
                }
            }
        }
    }
}

@Composable
private fun GitHubRepositoryItem(
    repository: GitHubRepository,
    cloning: Boolean,
    cloneEnabled: Boolean,
    onClone: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                repository.fullName,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildList {
                    add(if (repository.isPrivate) "Private" else "Public")
                    repository.language?.let(::add)
                    if (repository.fork) add("Fork")
                    if (repository.archived) add("Archived")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            repository.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onClone, enabled = cloneEnabled) {
                    if (cloning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (cloning) "Cloning…" else "Clone into workspace")
                }
            }
        }
    }
}

@Composable
internal fun IdeErrorText(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}
