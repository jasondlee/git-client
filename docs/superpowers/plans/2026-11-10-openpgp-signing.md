# OpenPGP Signing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Honor Git's tag and commit signing configuration while prompting once for an OpenPGP passphrase per GUI run.

**Architecture:** Add a run-scoped signing session that seeds and clears GnuPG agent cache entries. Integrate it at the Git command boundary, preserve all signing config, and share one session across repository tabs. Collect tag annotations in the UI when configured signing makes Git require one.

**Tech Stack:** Java 27, Maven, JavaFX, Git, GnuPG agent tools.

**Spec:** `docs/superpowers/specs/2026-11-10-openpgp-signing-design.md`

## Global Constraints

- Preserve `tag.gpgSign`, `tag.forceSignAnnotated`, `commit.gpgSign`, `gpg.format`, `gpg.program`, and `user.signingKey`.
- Keep passphrases out of preferences, logs, command arguments, and environment variables.
- Cache passphrases only for the GUI run and clear agent entries seeded by the app at shutdown.
- Do not edit the user's GnuPG configuration; report when presetting is unavailable.
- SSH and X.509 signing remain with their configured agents/programs.
- Do not create commits.

## Review Focus

- Agent refuses presetting: report an actionable error and do not run Git without the requested signature (Task 1).
- User cancels the prompt: abort the Git operation and clear the temporary passphrase (Task 1).
- Multiple repository tabs sign during one run: prompt once and reuse the session (Task 1 and Task 3).
- Non-OpenPGP signing format: do not prompt or alter the configured signer (Task 2).
- Configured signing requires an annotated tag: collect its message in the GUI rather than launching an editor (Task 3).

---

### Task 1: OpenPGP signing session

**Files:**
- Create: `git-cli/src/main/java/com/steeplesoft/kiboko/cli/OpenPgpSigningSession.java`
- Create: `git-cli/src/test/java/com/steeplesoft/kiboko/cli/OpenPgpSigningSessionTest.java`

**Interface:** `OpenPgpSigningSession(Supplier<char[]> passphrasePrompt)`; `prepare(Path repository)` prompts once, presets the selected OpenPGP key's agent cache, and tracks the key grip; `close()` forgets tracked cache entries and clears the in-memory passphrase.

- [x] Write tests for one prompt across repeated `prepare`, cancellation, preset failure, and cache cleanup using fake GnuPG helper commands.
- [x] Run `mvn -Dtest=OpenPgpSigningSessionTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm expected failures.
- [x] Implement the session using the configured signing key/program and `gpg-preset-passphrase`; never place the passphrase in arguments, environment, or persistent storage.
- [x] Run `mvn -Dtest=OpenPgpSigningSessionTest -Dsurefire.failIfNoSpecifiedTests=false test` and confirm they pass.

### Task 2: Git command integration and config preservation

**Files:**
- Modify: `git-cli/src/main/java/com/steeplesoft/kiboko/cli/GitCommandExecutor.java`
- Modify: `git-cli/src/test/java/com/steeplesoft/kiboko/cli/GitCommandExecutorTest.java`
- Modify: `git-services/src/main/java/com/steeplesoft/kiboko/service/RepositoryService.java`
- Modify: `git-services/src/test/java/com/steeplesoft/kiboko/service/RepositoryServiceTest.java`

**Interface:** Add an injectable `GitCommandExecutor(OpenPgpSigningSession)` while retaining the no-argument constructor. The executor prepares the session only for operations where effective Git configuration requests OpenPGP signing. `RepositoryService` accepts that executor/session for application construction. Tag calls do not pass `--no-sign` or `--no-annotate`.

- [x] Add tests for configured OpenPGP signing, non-OpenPGP delegation, explicit signing flags, and unchanged tag/commit signing configuration.
- [x] Run focused CLI and service tests; 6 signing-session tests and 68 service tests pass.
- [x] Integrate signing preflight and preserve tag signing configuration without command-line overrides.
- [x] Run focused tests and confirm they pass.

### Task 3: GUI prompt and run lifetime

**Files:**
- Modify: `git-app/src/main/java/com/steeplesoft/kiboko/app/GitClientApp.java`
- Modify: `git-ui/src/main/java/com/steeplesoft/kiboko/ui/WorkspaceView.java`
- Modify: `git-services/src/main/java/com/steeplesoft/kiboko/service/RepositoryService.java`

- [x] Add coverage for configured tag signing, tag annotation requirements, prompt reuse, and non-OpenPGP delegation without launching JavaFX.
- [x] Share one signing session from `GitClientApp` to every workspace and clear it from `Application.stop()`.
- [x] Prompt with a JavaFX `PasswordField` on the first OpenPGP signing action; cancellation aborts the Git command.
- [x] When effective tag config requires signing, request an annotation before invoking Git; preserve the explicit Annotated Tag message flow.
- [x] Run `mvn --batch-mode --no-transfer-progress compile test-compile`, `mvn --batch-mode --no-transfer-progress test`, and `mvn --batch-mode --no-transfer-progress --fail-at-end verify`; all six modules build and all 91 tests pass.

## Execution Notes

Implementation preserves the repository's modular architecture and performs blocking Git and tag-configuration work on a virtual thread. No commit was created, consistent with the repository's explicit no-commit directive. The remote Windows/macOS CI failure has a local canonical-path test fix, but requires another CI run after integration. The Windows/macOS signing flow still needs hosted CI and graphical manual acceptance.
