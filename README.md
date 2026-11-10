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

## Advanced Git and history navigation

The **History** menu supports commit-message search, tracked-filename search,
file history across renames, and comparisons between two branches, tags, or
commits. The recent-commits tab loads history in pages; use **Load More Commits**
to retrieve older entries without loading the entire repository history.
The file-history inspector also supports **Load More File Commits**, commit
selection, patch preview, and in-patch search. Diff, blame, and text-report
views provide case-insensitive Find Next navigation.

The **Advanced** menu includes linked worktree creation/removal, creation of a
new branch and linked worktree from HEAD, stale-worktree pruning, submodule
update and URL synchronization, and Git LFS pattern tracking/untracking.
Git LFS operations require the separate `git-lfs` executable to be installed.

Conflict resolution supports text-based three-way editing, accepting a
deletion, and choosing the original Git stage for binary conflicts. The
binary detector checks for NUL, control bytes, and invalid UTF-8; some
binary formats may still require an external tool. Changes are staged only
after confirmation. Git operations launched through the shared operation
controller can be interrupted with **Cancel Running Operation**. Cancellation
may leave partial work (especially during cloning or network operations);
inspect repository status afterward.

## Native packaging

The [packaging workflow](.github/workflows/package.yml) builds self-contained
Java runtime bundles and platform-specific installers using JDK 27's `jlink`
and `jpackage`. Artifacts are uploaded for each platform:

| Platform | Portable application | Native installer |
| --- | --- | --- |
| Windows | `GitClient-Windows.zip` | `.msi` |
| macOS | `GitClient-macOS.tar.gz` | `.dmg` |
| Linux | `GitClient-Linux.tar.gz` | `.deb` and `.rpm` |

Installers are built on their corresponding operating systems. To package
locally, install JDK 27, Maven, Git, and your OS packaging prerequisites
(WiX Toolset on Windows, `dpkg-deb`/`rpmbuild` on Linux):

```bash
mvn --batch-mode --no-transfer-progress install
mvn --batch-mode --no-transfer-progress -pl git-app javafx:jlink
jpackage --type app-image --name GitClient \
  --runtime-image git-app/target/image \
  --module gitclient.app/io.github.jasondlee.gitclient.app.GitClientApp \
  --dest dist
```

Then package the application image with `jpackage --type msi` on Windows,
`--type dmg` on macOS, or `--type deb`/`--type rpm` on Linux. For
example, on Linux:

```bash
jpackage --type deb --app-image dist/GitClient --name gitclient \
  --app-version 0.1.0 --linux-shortcut --dest installers
```

Git must still be installed separately and available on `PATH` at runtime.
These installers are **unsigned and unnotarized**; users may encounter OS
security prompts. CI verifies that installer files are created and can be
inspected, but it does not install and launch them in a graphical session.
Code signing, notarization, release publication, and installer launch smoke
tests remain separate release-readiness work.

## Verification and quality gates

Run `mvn --batch-mode --no-transfer-progress --fail-at-end verify` with
JDK 27 and Git installed. The GitHub Actions build matrix runs the same
verification on Ubuntu, Windows, and macOS and uploads Surefire reports
from each runner, including failed runs. The `git-cli`, `git-services`,
and `git-ui` modules include automated tests covering Git command
execution, repository workflows, graph parsing, and history operations.

Manual GUI acceptance remains necessary for JavaFX rendering, focus,
keyboard shortcuts, accessibility, and native window behavior. On each
supported desktop OS, check that repository refresh and history graph
load correctly, commit graph merges align, history filtering preserves
topology, shortcuts work, and errors provide readable details. Packaging
and installer verification are tracked separately.

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
