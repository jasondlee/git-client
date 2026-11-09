# Git Client — Architecture and Requirements

**Version:** 1.0 (initial architecture baseline)  
**Date:** 2026-11-08  
**Status:** Proposed design; implementation not started

## 1. Vision and objectives

Build a full-featured, responsive, cross-platform Git desktop client with a modernized SourceTree-inspired workflow. Git remains the source of truth: repositories require no proprietary metadata or changes to standard Git structures.

Principles:

1. Complete Git workflows through a graphical interface, including advanced operations.
2. Native Git compatibility, including existing configuration, hooks, credential helpers, and extensions.
3. Nonblocking JavaFX UI, predictable cancellation, and useful progress feedback.
4. Safety for destructive actions and visibility into recovery mechanisms.
5. Equivalent core functionality on Windows, macOS, and Linux.
6. Efficient operation on large repositories and histories.
7. Maintainable, testable Java with minimal unnecessary dependencies.

## 2. Finalized decisions

| Concern | Decision |
| --- | --- |
| Language | Java |
| JDK policy | Latest generally available JDK; baseline JDK 27, subject to verification during implementation |
| GUI | JavaFX; latest generally available compatible release |
| Build | Maven multi-module reactor |
| Git engine | Native Git CLI invoked via `ProcessBuilder` |
| Platforms | Windows, macOS, Linux |
| UI direction | Modernized SourceTree layout and workflows; original branding/assets |
| Distribution | Initially private; intended open-source project |
| Architecture | Modular monolith with explicit dependency boundaries |
| Concurrency | Virtual threads for blocking work; JavaFX application thread for UI changes |
| Packaging | `jlink` and `jpackage` with platform-specific builds |
| Testing | JUnit, real-repository integration tests, and JavaFX UI tests |

Working recommendations (not yet finalized): Apache-2.0 license, system Git prerequisite, programmatic JavaFX UI, constructor injection, JSON settings with atomic writes, SLF4J logging, and GitHub Actions CI.

## 3. Functional scope — all 48 selected capabilities

### FR-01: Repository and workspace management (8)
- Open existing repositories.
- Initialize repositories.
- Clone repositories.
- Multiple repositories and tabs.
- Repository bookmarks and groups.
- Recent repositories.
- Bare repositories.
- Repository settings.

**Acceptance:** Users can switch between multiple open repositories, restore workspace state after restart, and inspect bare repositories without assuming a working tree exists.

### FR-02: Working tree and commits (8)
- Working tree status.
- Stage and unstage files.
- Stage individual hunks and lines.
- Discard selected changes.
- Create and amend commits.
- Commit templates and signing.
- Ignore file management.
- Untracked and binary file handling.

**Acceptance:** Selected-line staging affects only intended changes; index and working-tree states remain distinguishable. Destructive actions clearly describe their scope.

### FR-03: History and branches (8)
- Interactive commit graph.
- Local and remote branches.
- Create, rename and delete branches.
- Checkout and detached HEAD.
- Tags and annotated tags.
- Commit search and filtering.
- File history and blame.
- Compare branches and commits.

**Acceptance:** Users can inspect, filter, and navigate non-linear histories and perform branch operations from relevant views.

### FR-04: Git operations and recovery (8)
- Merge and conflict resolution.
- Interactive rebase.
- Cherry-pick and revert.
- Reset (soft, mixed, hard).
- Stash management.
- Fetch, pull and push.
- Reflog and recovery.
- Bisect.

**Acceptance:** Users can see in-progress operations and continue, skip, or abort where Git supports it; recoverable actions expose useful recovery information.

### FR-05: Advanced Git (8)
- Worktrees.
- Submodules.
- Git LFS.
- Git-flow.
- Patch creation and application.
- Sparse checkout.
- Git hooks integration.
- Git configuration editor.

**Acceptance:** Each workflow is operable through the GUI while retaining native Git semantics and configuration.

### FR-06: Integration and experience (8)
- SSH and HTTPS authentication.
- Credential helper integration.
- GitHub/GitLab/Bitbucket integration.
- External diff and merge tools.
- Keyboard shortcuts.
- Dark and light themes.
- Background refresh.
- Cross-platform native packaging.

**Acceptance:** Core actions are keyboard-accessible; existing authentication helpers work; native installers run without a separately installed JDK.

## 4. Maven module structure

```text
git-client/
├── pom.xml                         # Parent reactor and version management
├── README.md
├── docs/
│   └── ARCHITECTURE.md
├── .github/workflows/
├── git-model/                       # Domain records and shared contracts
├── git-cli/                         # Native Git invocation and output parsers
├── git-services/                    # Application workflows and state coordination
├── git-ui/                          # JavaFX views, view models, controls, CSS
├── git-app/                         # Launcher, wiring, preferences, lifecycle
└── git-distribution/                # Native runtime and installer builds
```

The five Java modules use JPMS (`module-info.java`) with narrow exports. `git-distribution` is a Maven packaging module, not necessarily a JPMS module.

Dependency direction:

```text
git-app ────────────────> git-ui ─────────────> git-services
   │                                             │
   └─────────────────────────────────────────────┘
                                                 │
                                                 v
                                             git-cli
                                                 │
                                                 v
                                             git-model
```

`git-services` and `git-ui` may also depend directly on `git-model`. No JavaFX types may appear in `git-model`, `git-cli`, or `git-services`. Application composition occurs in `git-app`. Prefer explicit constructor injection over a dependency-injection framework.

## 5. Domain model

Use immutable Java records for snapshots and value objects, including:

- Repository: `RepositoryInfo`, `RepositoryState`, `WorktreeInfo`, `RemoteInfo`.
- Status: `WorkingTreeStatus`, `FileStatus`, `ConflictStatus`.
- History: `CommitInfo`, `CommitId`, `CommitGraphPage`, `PersonIdentity`.
- Refs: `BranchInfo`, `TagInfo`, `RefInfo`.
- Diffs: `FileDiff`, `DiffHunk`, `DiffLine`.
- Operations: `OperationState`, `OperationResult`.

Model repository identity separately from worktree identity. Linked worktrees share some Git resources but have distinct working trees and indexes. Bare repositories have no working tree. Model unborn branches, detached HEAD, and ongoing merge/rebase/cherry-pick/revert/bisect states explicitly.

Example:

```java
public record CommitInfo(
        String id,
        String treeId,
        List<String> parentIds,
        PersonIdentity author,
        PersonIdentity committer,
        String subject,
        String message,
        Instant authoredAt,
        Instant committedAt) {
    public CommitInfo {
        parentIds = List.copyOf(parentIds);
    }
}
```

## 6. Native Git integration (`git-cli`)

Core components:

| Component | Responsibility |
| --- | --- |
| `GitExecutableLocator` | Find and validate installed Git executable |
| `GitVersion` / `GitCapabilities` | Version detection and feature capability checks |
| `GitCommand` | Immutable invocation definition: arguments, directory, environment, timeout |
| `GitCommandExecutor` | Execution contract |
| `GitProcessExecutor` | `ProcessBuilder` implementation |
| `GitCommandResult` | Exit code, output, duration, diagnostics |
| `GitCommandException` | Structured execution failures |
| `GitEnvironment` | Safe environment and execution settings |
| `StatusParser`, `LogParser`, `RefParser`, `DiffParser` | Typed structured-output parsing |

Execution rules:

1. Pass arguments directly to `ProcessBuilder`; never construct a shell command string.
2. Drain stdout and stderr concurrently; support streaming and bounded buffering.
3. Support cancellation, timeouts, and process-tree cleanup.
4. Respect command-specific exit-code semantics.
5. Preserve raw bytes where needed; use NUL-delimited output for path safety.
6. Respect user configuration except where deterministic parsing requires explicit flags.
7. Keep credentials and sensitive environment variables out of diagnostics.
8. Support stdin and authentication interactions without blocking the JavaFX thread.

Prefer `git status --porcelain=v2 -z --branch`, structured `git log`, and `git for-each-ref`. Diff and patch operations must distinguish working-tree, index, and committed states. Patch staging requires careful handling of stale diffs, context, and concurrent modifications.

## 7. Application services (`git-services`)

Initial service candidates:

- `RepositoryService`: discovery, validation, initialization, cloning, repository metadata.
- `WorkspaceService`: tabs, bookmarks, groups, recent repositories, persistence.
- `StatusService`: working-tree/index snapshots and refresh.
- `StagingService`: file, hunk, and line staging; unstaging; discard workflows.
- `CommitService`: create/amend/sign commits and templates.
- `HistoryService`: paged commit history, file history, blame, comparisons.
- `BranchService` / `TagService`: ref operations and checkout.
- `RemoteService`: fetch, pull, push, and remote configuration.
- `MergeService` / `RebaseService`: multi-step operation coordination.
- `StashService`, `WorktreeService`, `SubmoduleService`, `LfsService`, `GitFlowService`.
- `ConfigurationService`, `AuthenticationService`.
- `GitOperationCoordinator`: coordinate operations on shared repository resources.
- `RepositoryRefreshManager`: filesystem monitoring and debounced refresh.
- `RepositorySession`: per-tab state and resource lifetime.

Services expose user workflows, not just one method per Git command. Introduce classes as features are implemented rather than creating empty placeholders.

### Concurrency and refresh

- Use virtual threads for blocking Git operations.
- Publish immutable snapshots to JavaFX on the FX application thread.
- Cancel superseded read operations and reject stale results using request generations.
- Serialize conflicting index and ref mutations; account for linked worktrees and shared Git resources.
- Treat Git's own locks and external process changes as authoritative; application locks are advisory.
- Use filesystem watching with debounce and fallback refresh/polling when necessary.
- Keep progress, cancellation, errors, and operation logs visible.

## 8. JavaFX presentation (`git-ui`)

Use MVVM-inspired programmatic JavaFX. Views own rendering and user interaction; view models own observable presentation state and orchestrate asynchronous service calls.

### Main window

- Repository tab bar, toolbar, and global command/search affordances.
- Left navigation sidebar: workspace/status/history, branches, remotes, tags, stashes, worktrees, submodules.
- Main content area with history graph, file status, and diff-oriented layouts.
- Commit details or staging/commit panel.
- Operation/status bar and task details.
- Draggable split panes, context menus, keyboard shortcuts, persistent layouts, dark/light themes.

### Specialized controls

| Control | Responsibilities |
| --- | --- |
| `CommitGraphView` | Virtualized commit rows, lane layout, merge edges, ref labels, selection |
| `DiffView` | Unified/side-by-side diff, syntax highlighting, whitespace controls, line/hunk selection |
| `FileStatusView` | Staged/unstaged/untracked/conflicted groups and file actions |
| `ConflictResolverView` | Base/ours/theirs/result workflow, conflict navigation, manual resolution |
| `InteractiveRebaseView` | Pick/reword/edit/squash/fixup/drop, ordering, paused-state controls |
| `OperationProgressView` | Running tasks, progress, cancellation, failures, diagnostics |
| `RepositorySidebar` | Navigation and repository references |
| `WorkspaceView` | Main shell and configurable layout |

Commit graph rendering should avoid creating a JavaFX node per commit: use virtualization, incremental paging, bounded caching, and efficient drawing. Evaluate JavaFX rich-text capabilities before adopting a third-party editor component.

## 9. Packaging and runtime

- Build platform-specific application images with `jlink` and installers with `jpackage`.
- Windows: MSI or EXE; macOS: app bundle with DMG or PKG; Linux: DEB/RPM and optional application image.
- Native packaging jobs run on their respective operating systems.
- Bundle a suitable Java runtime, but initially rely on an installed native Git executable.
- Detect missing or unsupported Git and provide actionable setup guidance.
- Consider signing/notarization and platform integration as release-readiness requirements.

## 10. Security, reliability, and accessibility

- Respect system SSH agents and configured Git credential helpers; do not store plaintext credentials in application settings.
- Never log secrets or credential-bearing remote URLs without redaction.
- Confirm destructive operations and communicate exactly which data may be lost.
- Preserve recovery details (including reflog where applicable), while never promising that all destructive operations are reversible.
- Use atomic settings writes and avoid modifying repositories when merely opening them.
- Support keyboard navigation, accessible names, readable contrast, and OS conventions.
- Provide structured diagnostics and a way to inspect failed Git commands safely.

## 11. Testing and CI

- Unit tests for parsers, domain models, graph layout, and command construction.
- Integration tests against temporary real Git repositories, including workflows and failure cases.
- UI tests for tabs, staging, dialogs, keyboard behavior, and theme/layout changes.
- Cross-platform CI for Windows, macOS, and Linux.
- Performance tests for large histories, many files, and expensive status operations.

Required edge cases: bare repositories, unborn branches, detached HEAD, linked worktrees, shallow clones, unusual filenames, non-ASCII paths, binary files, conflicts, interrupted rebases, external mutations, and cancellation.

## 12. Delivery roadmap

| Phase | Deliverable |
| --- | --- |
| 1. Foundation | Maven reactor, JavaFX shell, Git executable discovery, command execution, repository opening |
| 2. Daily workflow | Status, staging, diff, commits, branches, history graph |
| 3. Collaboration | Remotes, authentication, fetch/pull/push, tags |
| 4. History editing | Merge, rebase, cherry-pick, revert, reset, conflict resolution |
| 5. Advanced Git | Worktrees, submodules, LFS, Git-flow, bisect, sparse checkout, patches, hooks |
| 6. Product completion | Hosting integrations, accessibility, performance, installers, documentation |

Each phase should produce an installable and tested build.

### Milestone 1: Repository Explorer

- [ ] Create Maven multi-module reactor.
- [ ] Configure latest GA JDK and JavaFX.
- [ ] Detect and validate native Git.
- [ ] Implement command execution and error handling.
- [ ] Implement repository discovery and validation.
- [ ] Parse porcelain v2 working-tree status.
- [ ] Parse recent commit history.
- [ ] Create JavaFX window and repository tabs.
- [ ] Display status, current branch, and recent commits.
- [ ] Add real-repository integration tests.
- [ ] Configure CI on all three platforms.

**Exit criterion:** Open a real repository, inspect its status and recent history, refresh after external changes, and close cleanly without orphaned processes.

## 13. Open-source and project governance

The repository starts private and may become public after a readiness review. Proposed future policies: Apache License 2.0, semantic versioning, tagged releases, contribution guide, security disclosure process, and GitHub Actions CI. Do not copy SourceTree proprietary branding, icons, or code. License selection remains provisional until dependency and asset licensing is reviewed.

## 14. Explicit non-goals for initial milestone

- No plugin framework, generalized event bus, or heavyweight dependency-injection framework.
- No embedded Git implementation or bundled Git executable.
- No complete advanced Git workflow in the first milestone.
- No automatic update service initially.

These are sequencing decisions, not removals from the full product scope.

## 15. Outstanding design questions

- Final public project name and Java package namespace.
- Final open-source license and third-party asset policy.
- Git minimum supported version and compatibility policy.
- Rich text and diff rendering strategy after prototyping.
- Hosting-provider integration scope (Git transport versus PR/issues APIs).
- Release signing and macOS notarization.
- Whether to bundle native Git on Windows in a later release.
