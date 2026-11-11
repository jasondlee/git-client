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
