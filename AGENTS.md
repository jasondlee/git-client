# Codex working instructions — Git Client

Repository: https://github.com/jasondlee/git-client
Read `DEVELOPMENT_STATUS.md`, `README.md`, `docs/ARCHITECTURE.md`, and relevant code before making changes. The architecture document was written as an initial proposal and its "implementation not started" heading is stale; actual code and tests are authoritative.

## Stack and architecture
- Java 27, JavaFX 27, Maven multi-module JPMS application.
- Parent Maven coordinates currently `com.steeplesoft:kiboko:0.1.0-SNAPSHOT`; Java source packages recently moved to `com.steeplesoft.kiboko`. Check actual module POMs and declarations before changing them.
- Modules: `git-model` (records), `git-cli` (ProcessBuilder/native Git), `git-services` (workflows), `git-ui` (JavaFX), `git-app` (launcher).
- Git installed on PATH is the source of truth. Preserve Git configuration, credential helpers, hooks, and ordinary repositories; no proprietary repository metadata.
- Preserve module boundaries. No JavaFX dependencies in model, CLI, or services. Prefer simple, testable Java and avoid unnecessary frameworks or abstractions.
- Never shell-interpolate Git arguments. Handle paths, binary data, timeouts, cancellation, process cleanup, and secrets safely.
- Do blocking work off the JavaFX thread; update UI on the JavaFX thread. Protect repository mutations and avoid stale refresh results.

## Working method
1. Inspect the current branch, working tree, recent commits, open PRs, and current GitHub Actions results. Do not overwrite uncommitted user work.
2. Verify feature status against source, tests, and real Git repositories; do not treat previous chat claims or old design checklists as proof.
3. Plan the selected scope, implement it fully, and add focused unit/integration tests and necessary documentation.
4. Run `mvn --batch-mode --no-transfer-progress --fail-at-end verify` with JDK 27 and Git. Where applicable, run packaging and GUI smoke tests; disclose checks not run.
5. Fix failures within scope, review the diff, and commit coherent changes on the appropriate branch. Do not force-push or rewrite shared history without explicit approval.
6. After each major step, report what changed, tests/results, commit or PR links, and a **bulleted list of remaining work**.
7. When asked to finish a phase, continue through implementation, tests, and fixes without asking to proceed after every small step. Never claim unverified completion.

## Safety and release
- Confirm destructive actions; make merge/rebase/cherry-pick recovery visible. Preserve Git-native recovery mechanisms.
- Interactive rebase rewrites commits; never silently force-push.
- Cross-platform support: Windows, macOS, Linux. Packaging uses jlink/jpackage; platform installers are currently unsigned/unnotarized.
- Do not claim CI green from a historical run. Check current workflow status and logs.
- Update `DEVELOPMENT_STATUS.md` whenever features, known failures, or release readiness materially change.
