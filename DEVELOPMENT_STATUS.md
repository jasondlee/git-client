# Development status and Codex handoff

Updated: 2026-11-10. Repository: https://github.com/jasondlee/git-client

## Current verified state (2026-11-10)
- Branch `master` is at `2d568e6` and tracks `origin/master`; there are no other local branches or open GitHub issues/PRs. No Jira references or `proposals/` directory were found in the checkout.
- The historical run [42758137662](https://github.com/jasondlee/git-client/actions/runs/42758137662) passed its Windows, Ubuntu, and macOS build/test jobs.
- Newer runs on the current commit, [Build and test 42937032558](https://github.com/jasondlee/git-client/actions/runs/42937032558) and [Package 42937032612](https://github.com/jasondlee/git-client/actions/runs/42937032612), fail on Windows and macOS. Both fail `RepositoryServiceTest.createsWorktreeWithNewBranchFromHead` at the worktree-path comparison; Ubuntu succeeds. Linux packaging succeeds. The working-tree test now compares canonical paths and passes locally, but that change is uncommitted and has not been verified on Windows/macOS CI.
- Current local verification passes: `mvn --batch-mode --no-transfer-progress compile test-compile`, `mvn --batch-mode --no-transfer-progress test`, and `mvn --batch-mode --no-transfer-progress --fail-at-end verify`. The test run reports 18 CLI, 68 service, and 5 UI tests (91 total), with no failures.
- Uncommitted work also completes the OpenPGP signing flow: one run-scoped passphrase prompt, GnuPG agent cache seeding/cleanup, preserved Git signing config, explicit-sign flag coverage, and UI annotation collection. Git tag signing configuration is checked from the existing background operation worker, not the JavaFX thread. This implementation has not been committed or run through GitHub Actions.

## Authority and history
This document transfers project context from a long ChatGPT development conversation. **It is not an independently verified feature audit.** The current branch, source code, automated tests, and current GitHub Actions runs take precedence. The initial `docs/ARCHITECTURE.md` (2026-11-08) says implementation had not started; that statement is obsolete. It contains the original vision and 48-capability scope, not a current completion checklist.

Latest observed commit at handoff: `02636e323208ec34ed4d04ccfb27dd73eb439994` — rename Java packages and Maven coordinates. Previous commit `51a892496bfec3b684236215ef92673717aa3be8` fixed build/test failures involving Git interruption, untracked status, rename-aware pagination, and diff-search naming. Recheck HEAD before acting.

## Technology and modules
- Java 27, JavaFX 27.0.1, Maven, JUnit Jupiter; native Git CLI on PATH.
- Current parent coordinates: `com.steeplesoft:kikobo:0.1.0-SNAPSHOT`; packages: `com.steeplesoft.kiboko`.
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
