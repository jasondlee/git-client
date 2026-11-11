# OpenPGP Signing Design

**Status:** Approved by user on 2026-11-10.

## Intent

Honor Git's configured tag and commit signing behavior. When an OpenPGP signature is requested, ask for the passphrase once and reuse it for the lifetime of the running GUI. Keep the passphrase out of persistent application settings and clear signing credentials when the GUI exits.

## Approaches

- **App-managed OpenPGP passphrase (selected):** A JavaFX prompt supplies the passphrase to the OpenPGP agent cache. Git continues to select whether to sign, annotate, and which key/program to use from the user's configuration. The app retains the passphrase only in memory and clears agent cache entries it seeded on exit.
- **Configured pinentry/agent:** Git and GnuPG handle their normal prompt and cache. This supports user-configured signing formats without app-specific signing code, but the prompt and cache lifetime are outside the GUI.

## Design

- Keep `tag.gpgSign`, `tag.forceSignAnnotated`, `commit.gpgSign`, `gpg.format`, `gpg.program`, and `user.signingKey` effective; do not add command-line overrides for them.
- Prompt on the first OpenPGP signing operation. Keep the passphrase in a run-scoped in-memory holder shared by repository tabs. Do not store it in preferences, logs, command arguments, or environment variables.
- Seed GnuPG's agent cache for the selected key and let Git invoke the configured signing program normally. Track seeded key grips and clear those cache entries when the application stops.
- If the GnuPG agent refuses passphrase presetting, show an actionable error. The app will not edit `gpg-agent.conf` or silently disable signing.
- Preserve Git's tag configuration. When it makes a tag operation annotated, collect an annotation in the UI so Git does not open an external editor. The existing explicit Annotated Tag flow continues to use the supplied message.
- Keep SSH and X.509 signing delegated to their configured programs/agents; this change adds app-owned passphrase handling for OpenPGP only.

## Verification

Use fake GPG and agent commands to verify configuration passthrough, one prompt across multiple signing operations, cache cleanup, and failures without preset permission. Add integration coverage showing configured signing and annotation options reach Git unchanged.

## Constraints and calls

- GnuPG must permit passphrase presetting (`allow-preset-passphrase`). The app will report the missing capability rather than changing the user's GnuPG configuration.
- This design is intentionally limited to OpenPGP; other signing formats retain their configured credential flow.
- The design file remains uncommitted, following the repository instruction not to create commits.
