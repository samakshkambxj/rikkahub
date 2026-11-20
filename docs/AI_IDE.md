# RikkaHub AI IDE

RikkaHub's AI IDE extends the existing assistant, sandboxed workspaces, terminal, and model-provider
support with project-centric development and native GitHub integration. It is not a WebView wrapper
around github.com.

## Milestone 1: GitHub + source-control vertical slice

The `feat/ai-ide-github` branch implements:

- GitHub Device Flow sign-in through the existing OAuth module.
- Personal access token sign-in with validation before the session is replaced.
- Access-token encryption through Android Keystore (`SecretCipher`). Plaintext tokens are not stored
  in Settings, saved Compose state, repository URLs, or `.git/config`.
- `Authorization` header redaction in the shared HTTP logger.
- Native repository browsing with GitHub REST pagination.
- HTTPS cloning into a new local RikkaHub workspace using JGit. No rootfs `git` package is required.
- Branch, ahead/behind, changed-file, staged-diff, and unstaged-diff views.
- Commit-all with an explicit author/committer identity, plus pull and push.
- GitHub noreply email as the signed-in account's default commit identity.
- JVM tests for the REST client and real temporary JGit repositories.

Current boundaries:

- Native JGit operations apply to local workspaces, not SSH workspaces.
- Device sign-in currently targets a **GitHub OAuth App** with Device Flow enabled. Expiring GitHub
  App user tokens and refresh-token rotation are a later milestone.
- The first slice has no branch creation, stash, conflict-resolution editor, or pull-request UI.
- The existing workspace text editor is reused. A multi-tab, syntax-aware editor is the next major
  milestone rather than something falsely claimed by this slice.

## GitHub setup

### OAuth Device Flow

1. In GitHub, create an **OAuth App** (not a GitHub App for this milestone).
2. Enable **Device Flow** in the OAuth App settings.
3. Open **Extensions → GitHub** in RikkaHub.
4. Enter the OAuth App's public client ID and start device sign-in.
5. Confirm the displayed code at GitHub's verification page.

The requested scope is `repo read:user`. Do not put a client secret in the Android app.

### Personal access token

Use a token with only the repository/account permissions needed by the IDE. For a classic PAT,
`repo` and `read:user` match the Device Flow scope. For a fine-grained PAT, grant access only to
selected repositories, with **Contents: Read and write** for push access (read-only is enough for
cloning and browsing).

The token is validated with `/user`, encrypted with a non-exportable Android Keystore AES key, and
only decrypted in memory for an API, clone, pull, or push operation. Losing the Keystore key (for
example after reinstalling) requires signing in again.

## Architecture

- `data/github`: Serializable API/account models, REST client, sign-in and encrypted persistence.
- `data/git/JGitService.kt`: Pure JVM local Git operations.
- `data/git/WorkspaceGitRepository.kt`: Workspace/account adapter and credential lifetime boundary.
- `ui/pages/ide/GitHubPage.kt`: Account and repository picker.
- `ui/pages/ide/WorkspaceGitPage.kt`: Source-control UI for an existing workspace.
- Existing workspace pages provide the file browser, editor, AI tools, and terminal.

Credentials are passed to JGit through `UsernamePasswordCredentialsProvider("x-access-token", token)`
per operation. They are never appended to the clone URL.

## Validation

```bash
./gradlew testGithubDebugUnitTest --tests '*GitHubApiClientTest' --tests '*JGitServiceTest'
./gradlew spotlessCheck
./gradlew assembleGithubDebug
```

The repository needs its normal Android build prerequisites, including an Android SDK/JDK and the
project's `app/google-services.json` configuration.

`.github/workflows/android-apk.yml` builds `assembleGithubRelease` for every branch and tag push,
uploads the APKs as Actions artifacts, and publishes a GitHub Release whenever a tag is pushed. It
uses the existing `KEY_BASE64`, `SIGNING_CONFIG`, and `GOOGLE_SERVICES_JSON` repository secrets.

## Remaining roadmap

### Milestone 2: Real project editor

- Multi-tab editor with unsaved-buffer indicators and session restoration that excludes secrets.
- Syntax highlighting, line numbers, bracket matching, code folding, indent guides, minimap on large
  screens, find/replace, and project-wide text search.
- Create/rename/move/delete files and directories with conflict-safe saves and external-change checks.
- Split editor/terminal/AI layout using Material 3 adaptive layouts.

### Milestone 3: Language intelligence

- Run language servers in the existing PRoot/SSH workspace where supported.
- LSP completion, hover, diagnostics, definitions/references, symbols, formatting, and rename.
- Per-language setup for TypeScript/JavaScript, Python, Kotlin/Java, Rust, Go, and C/C++.
- Resource limits and clear offline/degraded behavior.

### Milestone 4: IDE AI agent

- Project-root selection and repository-aware context indexing with `.gitignore` support.
- Planning, codebase search, patch edits, shell/test loops, and concise progress in an agent panel.
- Checkpoints and rollback for AI edits; diff review before applying or committing changes.
- Per-workspace command/network/write approval policies and no autonomous push by default.
- Reuse the existing provider abstraction, MCP, skills, memory, and workspace tools.

### Milestone 5: Complete source control and GitHub collaboration

- Branch creation/switching, fetch, merge, rebase, stash, history, and conflict editor.
- Selective staging and line/hunk commits.
- Pull requests, reviews, issues, Actions status, and CI logs through GitHub's native APIs.
- Expiring token refresh and optional GitHub Enterprise configuration.

### Milestone 6: Run, test, and debug

- Saved run configurations, background task output, problem matchers, and web previews.
- Test discovery/results and debugger integration where the selected language/runtime supports it.
- Android, JVM, Node, Python, and native build presets without hiding command output.

### Milestone 7: Productization

- IDE-first onboarding, optional project home screen, keyboard shortcuts, tablets/foldables, backups,
  accessibility, performance profiling, and user documentation.
- Decide separately whether to retain RikkaHub branding or introduce a new product name/application ID.
