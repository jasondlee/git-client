# Development status and Codex handoff

Updated: 2026-11-10. Repository: https://github.com/jasondlee/git-client

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

These are historical references, **not evidence of current CI status**. Inspect newer runs and check the latest commit, including the package rename.

## Outstanding verification and release work
1. Audit original 48 requirements in `docs/ARCHITECTURE.md` against actual code, UI actions, and tests. Classify each as complete, partial, absent, or deferred; link evidence.
2. Verify current CI and resolve any cross-platform build, test, or packaging failures after the latest package/coordinate rename.
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

## Next Codex session
1. Inspect current HEAD, branches, working tree, and latest CI runs.
2. Build and test; identify regressions and verify actual feature coverage.
3. Create an evidence-backed feature matrix against the 48 requirements, preserving explicit deferrals.
4. Fix the next agreed priority end-to-end, including tests and documentation.
5. Keep this file current and finish every progress report with a bulleted list of remaining tasks.
