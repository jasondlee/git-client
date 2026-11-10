# Git Client

An open-source, cross-platform JavaFX desktop Git client that uses the installed Git command-line executable.

## Requirements

- JDK 27 and Maven for development
- Git available on `PATH` for repository operations
- Windows, macOS, or Linux with graphical desktop support

The packaged desktop bundles include a custom Java runtime, so end users do not need to install a separate JDK.

## Build and run

```bash
mvn --batch-mode verify
mvn --batch-mode install
mvn -pl git-app javafx:run
```

The application can open multiple repositories in tabs. Repository paths may be passed as command-line arguments, and previously open tabs are restored when no paths are specified.

## Package a desktop application

Build on the operating system for which you want to package the application:

```bash
mvn --batch-mode install
mvn --batch-mode -pl git-app javafx:jlink
jpackage --type app-image --name GitClient --runtime-image git-app/target/image --module gitclient.app/io.github.jasondlee.gitclient.app.GitClientApp --dest dist
```

This produces `dist/GitClient` on Windows/Linux and `dist/GitClient.app` on macOS. Use the native launcher inside the resulting application bundle. The archive produced by CI preserves the whole bundle; do not distribute the launcher alone.

GitHub Actions provides two separate workflows:

- [Build and test](.github/workflows/build.yml) runs Maven verification on Ubuntu, Windows, and macOS.
- [Package desktop application](.github/workflows/package.yml) builds and uploads platform-specific application bundles on version tags or manual dispatch.

Packaging has not yet been verified on every runner. These application bundles are not signed or notarized, and are not yet MSI, DMG, or DEB installers. Do not treat them as production releases until packaging and launch smoke tests pass on each OS.

## Workspace preferences and accessibility

Open **Preferences** in the toolbar to configure the number of recent commits
loaded (25–1000) and whether diffs wrap long lines. These settings persist
between application launches. Confirmation prompts for history-rewriting and
destructive actions remain enabled for safety.

Open **Shortcuts** in the toolbar for the complete keyboard reference.
Use the platform shortcut key (Command on macOS, Control elsewhere):
**O** opens a repository, **R** refreshes, **Enter** commits,
**F** focuses history search, **G** opens graph search,
**1/2/3** select the diff views, and **/** displays shortcut help.
The workspace provides accessible descriptions for its primary controls.
A progress indicator appears during repository refreshes and Git operations;
failed Git actions display a selectable error-details dialog.

## Commit history and graph

The main workspace has **Recent commits** and **Branch graph** tabs. Recent
commits can be filtered by commit SHA, subject, or author. The branch graph
displays all refs, including merge and branch connections, with native JavaFX
drawing; it retains connector rows during search so topology stays visible.
Use **Previous match** and **Next match** or Enter in the search field to
navigate matching commits. Double-click a graph commit or press Enter on its
row to inspect it. The toolbar's **Commit Graph** command opens a larger
graph view.

Right-click a commit in either history view to inspect it, copy its full SHA,
compare it to HEAD, cherry-pick or revert it, or create a branch or tag
pointing at that commit.
History operations that change repository state require confirmation. The
graph currently loads the most recent 500 commits across all refs.

## Interactive rebase

Choose a **base branch** from the branch selector and select **Interactive Rebase**
from the Branches menu. The editor lists commits from oldest to newest and
supports:

- **Pick**: replay the commit.
- **Reword**: replace its subject and optional message body; use **Edit Message**
  to revise the replacement before starting.
- **Edit**: pause after applying the commit so you can amend it, then continue.
- **Squash**: combine the commit with the preceding retained commit.
- **Fixup**: combine changes while discarding this commit's message.
- **Drop**: omit the commit.

Drag commits or use **Move Up / Move Down** to reorder them. Squash and fixup
must follow a retained commit. The application validates the complete plan,
requires a clean working tree, and currently supports linear local history.
Interactive rebase **rewrites commit IDs**; coordinate with collaborators
before force-pushing a branch that has already been shared.

If Git pauses for a conflict or an Edit action, **Rebase Recovery** opens
automatically. Select a conflicted file and resolve and stage it, then use
**Refresh** to update progress and enable **Continue**. For an Edit stop, amend
the commit before continuing. **Skip Commit** discards the current commit;
**Abort Rebase** restores the pre-rebase branch. Rebase Recovery is also
available from the Operations menu. Git and Java must be available on PATH
for the rebase editor subprocess.

## Project modules

- `git-model`: repository, file, and commit records
- `git-cli`: native Git execution and porcelain status parsing
- `git-services`: repository operations and integration tests
- `git-ui`: JavaFX repository explorer
- `git-app`: desktop application launcher

See [architecture](docs/ARCHITECTURE.md).
