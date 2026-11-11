# Development status and Codex handoff

Updated: 2026-11-10. Repository: https://github.com/jasondlee/git-client

## Current verified state (2026-11-10)
- Branch `master` is at `95d1c31`, one commit ahead of `origin/master`; no other local branches or open GitHub issues/PRs were found. No Jira references or `proposals/` directory were found in the checkout.
- The historical run [42758137662](https://github.com/jasondlee/git-client/actions/runs/42758137662) passed its Windows, Ubuntu, and macOS build/test jobs.
- Runs [Build and test 42937032558](https://github.com/jasondlee/git-client/actions/runs/42937032558) and [Package 42937032612](https://github.com/jasondlee/git-client/actions/runs/42937032612) ran on commit `2d568e6`, before the worktree-path fix. They fail on Windows and macOS at `RepositoryServiceTest.createsWorktreeWithNewBranchFromHead`; Ubuntu succeeds, as does Linux packaging. The canonical-path fix is now included in `95d1c31`, but has not been verified by hosted CI.
- Current local verification passes: `mvn --batch-mode --no-transfer-progress compile test-compile`, `mvn --batch-mode --no-transfer-progress test`, and `mvn --batch-mode --no-transfer-progress --fail-at-end verify`. The test run reports 18 CLI, 68 service, and 5 UI tests (91 total), with no failures.
- Commit `95d1c31` adds the OpenPGP signing flow: one run-scoped passphrase prompt, GnuPG agent cache seeding/cleanup, preserved Git signing config, explicit-sign flag coverage, and UI annotation collection. Git tag signing configuration is checked from the existing background operation worker, not the JavaFX thread. It has not been verified by hosted CI.

## Authority and history
This document transfers project context from a long ChatGPT development conversation. **It is not an independently verified feature audit.** The current branch, source code, automated tests, and current GitHub Actions runs take precedence. The initial `docs/ARCHITECTURE.md` (2026-11-08) says implementation had not started; that statement is obsolete. It contains the original vision and 48-capability scope, not a current completion checklist.

Latest commit: `95d1c31` — configured OpenPGP signing. Previous commit `2d568e6` added the Codex project handoff and development status; `02636e3` renamed Java packages and Maven coordinates. The current uncommitted change aligns the parent Maven artifact with the Java package name.

## Technology and modules
- Java 27, JavaFX 27.0.1, Maven, JUnit Jupiter; native Git CLI on PATH.
- Current parent coordinates: `com.steeplesoft:kiboko:0.1.0-SNAPSHOT`; packages: `com.steeplesoft.kiboko`.
- `git-model`: domain records; `git-cli`: Git execution and parsing; `git-services`: operations; `git-ui`: JavaFX UI; `git-app`: launcher.
- GitHub Actions: `.github/workflows/build.yml` verifies on Ubuntu/Windows/macOS; `.github/workflows/package.yml` builds jlink/jpackage bundles and installers.

## Features described as implemented in current README (verify in code)
- Multiple repository tabs, command-line repository paths, restored workspace tabs.
- Status, commit workflows and repository navigation; consult code for exact action coverage.
- Recent commit history with paging, search, branch graph with ref/merge connectors and context actions.
- Commit message and tracked-file search, rename-aware file history, blame/diff searching, ref comparisons.
- Interactive rebase: pick/reword/edit/squash/fixup/drop, reorder, plan validation, clean-tree precondition, pause/recovery/continue/skip/abort. Documented limitation: linear local history.
- Conflict resolution: text three-way, deletion handling, binary-stage choice.
- Worktrees, submodule operations, Git LFS tracking and downloads (git-lfs separately required).
- Cancellable operations, persisted preferences, keyboard shortcuts, accessible descriptions, error detail dialogs.
- Cross-platform portable bundles and native installers: Windows MSI, macOS DMG, Linux DEB/RPM.

These are README-described capabilities, **not** a claim that all 48 initial requirements are complete or that every GUI workflow has passed acceptance testing.

## Prior conversation milestones
The user requested complete interactive rebase, commit-history visualization, native packaging, and subsequent implementation phases (including phases 7 and 8), with verification and remaining-work lists after each step. Some earlier tasks were explicitly deferred; the exact identity and status of those numbered tasks cannot be reconstructed reliably from the available conversation summary. **Do not invent or silently close deferred items.** Reconcile them against GitHub issues/PRs, code, and user confirmation if necessary.

Prior investigated GitHub Actions runs include:
- https://github.com/jasondlee/git-client/actions/runs/42731125222
- https://github.com/jasondlee/git-client/actions/runs/42735160430
- https://github.com/jasondlee/git-client/actions/runs/42754824586
- https://github.com/jasondlee/git-client/actions/runs/42757564143
- https://github.com/jasondlee/git-client/actions/runs/42758137662

These are historical references, **not evidence of current CI status**. The current latest workflow results and failures are recorded above.

## Outstanding verification and release work
1. Audit original 48 requirements in `docs/ARCHITECTURE.md` against actual code, UI actions, and tests. Classify each as complete, partial, absent, or deferred; link evidence.
2. Rerun Windows/macOS CI after the canonical-path fix is integrated. The signing implementation also needs cross-platform CI.
3. Run manual JavaFX acceptance on Windows, macOS, Linux: graph topology/filtering, keyboard focus/shortcuts, accessibility, dialogs, diff and conflict views, rebase recovery, and repository refresh.
4. Exercise native installers by installing and launching on supported OSes. Linux CI has an application-bundle launcher smoke test; this is not the same as installing an installer.
5. Decide and implement signing, macOS notarization, release publication, and distribution policies before public release. Current installers are unsigned/unnotarized.
6. Validate large-history performance, network cancellation, edge-case Git states, binary/rename handling, and safe destructive workflows with real repositories.
7. Reconcile any deliberately deferred tasks with the user; do not silently treat them as completed.

## Build and run
```bash
mvn --batch-mode --no-transfer-progress --fail-at-end verify
mvn --batch-mode install
mvn -pl git-app javafx:run
```
JDK 27, Maven, and Git are required. See `README.md` for jlink/jpackage commands.

## Next work
1. Run hosted Windows/macOS build and package workflows after the local worktree-path fix and OpenPGP signing changes are integrated; current remote results predate them.
2. Create an evidence-backed matrix for all 48 requirements, using complete/partial/absent/deferred with source and test evidence.
3. Run manual JavaFX acceptance and installer install/launch checks on supported desktop platforms.
4. Reconcile older phase-specific deferrals from the prior conversation; their identities are not available in this checkout.

## Feature audit (2026-11-10)

This is a source and documentation audit of the 48 capabilities in `docs/ARCHITECTURE.md`, not a manual GUI acceptance result. “Implemented” means a corresponding workflow is evidenced in the current source or README. “Partial” means related functionality exists but the full requirement or its acceptance conditions are not established. “Absent” means no corresponding workflow was found. The architecture is an initial proposal; its roadmap checkboxes are not implementation status.

### FR-01 — Repository and workspace

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Open existing repositories | Implemented | Repository open and validation workflows are present. |
| Initialize repositories | Implemented | Workspace action and service operation exist. |
| Clone repositories | Implemented | Workspace action and service operation exist. |
| Multiple repositories and tabs | Partial | Multiple repositories and workspace restoration are documented; full tabbed-workflow acceptance was not verified in this audit. |
| Repository bookmarks and groups | Absent | Recent repository navigation exists; no bookmark or grouping workflow found. |
| Recent repositories | Implemented | Recent repository navigation is present. |
| Bare repositories | Partial | Repository discovery exists; complete bare-repository inspection behavior is not established. |
| Repository settings | Absent | No repository settings editor found. |

### FR-02 — Working tree and commits

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Working tree status | Implemented | Status model, service, and workspace UI exist. |
| Stage and unstage files | Implemented | File staging operations are present. |
| Stage individual hunks and lines | Implemented | Hunk and selected-line patch operations exist. |
| Discard selected changes | Implemented | Discard workflow exists with confirmation for destructive actions. |
| Create and amend commits | Implemented | Commit and amend controls are present. |
| Commit templates and signing | Partial | OpenPGP signing was added; commit templates and other signing setups are not established. |
| Ignore file management | Partial | Ignore-pattern action exists; broader ignore-file management is not established. |
| Untracked and binary file handling | Partial | Untracked cleanup and binary-conflict handling exist; full file handling acceptance is not established. |

### FR-03 — History and branches

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Interactive commit graph | Implemented | Graph view and history navigation exist; the graph loads at most the latest 500 commits. |
| Local and remote branches | Implemented | Branch and remote operations are present. |
| Create, rename, and delete branches | Implemented | Branch operations are present. |
| Checkout and detached HEAD | Partial | Checkout support exists; detached-HEAD acceptance behavior was not established. |
| Tags and annotated tags | Implemented | Tag workflows exist; OpenPGP signing support is documented in the current status above. |
| Commit search and filtering | Implemented | History filtering and search are documented. |
| File history and blame | Implemented | Rename-aware file history and blame are documented. |
| Compare branches and commits | Implemented | Ref comparison workflow is documented. |

### FR-04 — Git operations and recovery

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Merge and conflict resolution | Partial | Text, deletion, and binary-stage resolution exist; a full merge workflow is not established. |
| Interactive rebase | Implemented | Plan editing and recovery actions are documented; linear local history is a stated limitation. |
| Cherry-pick and revert | Implemented | History context actions are documented. |
| Reset (soft, mixed, hard) | Absent | No reset workflow found. |
| Stash management | Implemented | List, create, apply, and drop operations are present. |
| Fetch, pull, and push | Implemented | Remote operations are present. |
| Reflog and recovery | Absent | No reflog or recovery workflow found. |
| Bisect | Absent | No bisect workflow found. |

### FR-05 — Advanced Git

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| Worktrees | Implemented | Create, remove, and prune workflows are documented. |
| Submodules | Implemented | Add, update, and URL synchronization workflows are documented. |
| Git LFS | Implemented | Tracking and object downloads are documented; `git-lfs` is a separate prerequisite. |
| Git-flow | Absent | No Git-flow workflow found. |
| Patch creation and application | Absent | No standalone patch workflow found. |
| Sparse checkout | Absent | No sparse-checkout workflow found. |
| Git hooks integration | Absent | No hooks management workflow found. |
| Git configuration editor | Absent | No configuration editor found. |

### FR-06 — Integration and experience

| Capability | Status | Evidence / limitation |
| --- | --- | --- |
| SSH and HTTPS authentication | Partial | Native Git preserves configured transport and authentication behavior; no dedicated authentication workflow found. |
| Credential helper integration | Partial | Operations delegate to native Git and its configured helpers; no separate integration UI is established. |
| GitHub/GitLab/Bitbucket integration | Absent | No hosting-provider API workflow found. |
| External diff and merge tools | Absent | No external-tool integration workflow found. |
| Keyboard shortcuts | Implemented | Shortcut reference and key actions are documented. |
| Dark and light themes | Absent | No theme-switching workflow found. |
| Background refresh | Partial | Manual refresh and progress behavior exist; filesystem monitoring and automatic-refresh acceptance are not established. |
| Cross-platform native packaging | Partial | Platform bundles and installers are defined; CI after recent commits and installer install/launch acceptance remain outstanding. |

### Audit findings and follow-up

- Strongest implementation evidence covers the core repository workflow, history navigation, interactive rebase, and selected advanced Git operations.
- No corresponding workflows were found for Git-flow, patch creation/application, sparse checkout, hooks management, configuration editing, reflog, bisect, reset, hosting-provider API integration, themes, or external diff/merge tools. Decide which remain in scope and record deferrals or priorities.
- At audit time, the checkout was clean and `master` was two commits ahead of `origin/master`; this supersedes the stale branch/worktree counts above. The two local commits add OpenPGP signing and align the Maven artifact name.
- No Jira references or `proposals/` directory were found in the checkout. GitHub search returned no open issues or pull requests for the repository.
- No build or GUI acceptance checks were run specifically for this audit. The verification recorded earlier in this document predates the latest Maven-coordinate commit; hosted CI results were not independently checked during the audit.
- Remaining work: refresh the stale verified-state and history text near the top of this handoff; verify Windows/macOS CI after the latest commits; run manual GUI and installer acceptance on supported platforms; reconcile any prior phase deferrals that are not recorded here.
