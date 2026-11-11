package com.steeplesoft.kiboko.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies passphrase reuse and cleanup without requiring a local GnuPG key or agent. */
class OpenPgpSigningSessionTest {
    /** Ensures repeated signing in multiple repositories asks once and clears the agent cache. */
    @Test void reusesOnePassphraseAcrossRepositoriesAndClearsItOnClose() {
        FakeCommandRunner commands = new FakeCommandRunner();
        AtomicInteger prompts = new AtomicInteger();
        Supplier<char[]> prompt = () -> {
            prompts.incrementAndGet();
            return new char[] {'s', 'e', 'c', 'r', 'e', 't'};
        };
        OpenPgpSigningSession session = new OpenPgpSigningSession(prompt, commands);

        session.prepare(Path.of("first-repository"));
        session.prepare(Path.of("second-repository"));

        assertEquals(1, prompts.get());
        assertEquals(1, commands.count("--preset"));
        assertTrue(commands.calls.stream().noneMatch(call -> call.command.contains("secret")));

        session.close();
        session.close();

        assertEquals(1, commands.count("--forget"));
        assertArrayEquals(new char[] {0, 0, 0, 0, 0, 0}, commands.passphraseInput);
    }

    /** Ensures cancelling the passphrase prompt stops before the agent is changed. */
    @Test void cancellationDoesNotPresetPassphrase() {
        FakeCommandRunner commands = new FakeCommandRunner();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> null, commands);

        assertThrows(CancellationException.class, () -> session.prepare(Path.of("repository")));

        assertEquals(0, commands.count("--preset"));
    }

    /** Ensures an agent refusal fails closed and erases the supplied passphrase. */
    @Test void presetFailureDoesNotRetainPassphrase() {
        FakeCommandRunner commands = new FakeCommandRunner();
        commands.presetFailure = true;
        OpenPgpSigningSession session = new OpenPgpSigningSession(
                () -> new char[] {'s', 'e', 'c', 'r', 'e', 't'}, commands);

        GitException failure = assertThrows(GitException.class,
                () -> session.prepare(Path.of("repository")));

        assertTrue(failure.getMessage().contains("passphrase"));
        assertArrayEquals(new char[] {0, 0, 0, 0, 0, 0}, commands.passphraseInput);
        assertEquals(0, commands.count("--forget"));
    }

    /** Ensures SSH and X.509 formats remain delegated to their configured signing agents. */
    @Test void nonOpenPgpFormatDoesNotPromptOrUsePresetHelper() {
        FakeCommandRunner commands = new FakeCommandRunner();
        commands.signingFormat = "ssh";
        AtomicInteger prompts = new AtomicInteger();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> {
            prompts.incrementAndGet();
            return new char[] {'s'};
        }, commands);

        session.prepare(Path.of("repository"));

        assertEquals(0, prompts.get());
        assertEquals(0, commands.count("--preset"));
    }

    /** Ensures explicit Git signing flags trigger preflight when configuration is disabled. */
    @Test void explicitSigningFlagsPrepareOpenPgp() {
        FakeCommandRunner commands = new FakeCommandRunner();
        AtomicInteger prompts = new AtomicInteger();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> {
            prompts.incrementAndGet();
            return new char[] {'s'};
        }, commands);

        session.prepareIfRequested(Path.of("repository"), "commit", "-S", "-m", "message");
        session.prepareIfRequested(Path.of("repository"), "tag", "--sign", "release");

        assertEquals(1, prompts.get());
        assertEquals(1, commands.count("--preset"));
        session.close();
        assertEquals(1, commands.count("--forget"));
    }

    /** Ensures an explicit OpenPGP prompt cancellation prevents agent setup. */
    @Test void explicitSigningCancellationStopsBeforeAgentSetup() {
        FakeCommandRunner commands = new FakeCommandRunner();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> null, commands);

        assertThrows(CancellationException.class, () -> session.prepareIfRequested(
                Path.of("repository"), "tag", "-s", "release"));

        assertEquals(0, commands.count("--preset"));
    }

    /** Supplies deterministic Git and GnuPG command results for the session tests. */
    private static final class FakeCommandRunner implements OpenPgpSigningSession.CommandRunner {
        private final List<Call> calls = new ArrayList<>();
        private String signingFormat = "openpgp";
        private boolean presetFailure;
        private char[] passphraseInput;

        /** Records each invocation and emulates the selected key and agent helper. */
        @Override public OpenPgpSigningSession.CommandResult run(
                Path directory, List<String> command, char[] standardInput) {
            List<String> arguments = List.copyOf(command);
            calls.add(new Call(arguments, standardInput));
            if (arguments.getFirst().equals("git") && arguments.contains("config")) {
                if (arguments.contains("gpg.format")) return result(signingFormat);
                if (arguments.contains("gpg.openpgp.program")) return result("fake-gpg");
                if (arguments.contains("gpg.program")) return missing();
                if (arguments.contains("user.signingKey")) return result("signer@example.invalid");
                return missing();
            }
            if (arguments.getFirst().equals("gpgconf")) return result("/fake/libexec");
            if (arguments.getFirst().equals("fake-gpg")) {
                return result(signingKeyRecord() + "\n"
                        + keyGripRecord("ABCDEF1234567890ABCDEF1234567890ABCDEF12") + "\n");
            }
            if (arguments.contains("--preset")) {
                passphraseInput = standardInput;
                return presetFailure
                        ? new OpenPgpSigningSession.CommandResult(1, "", "preset refused")
                        : result("");
            }
            return result("");
        }

        /** Counts invocations carrying a selected option. */
        private long count(String option) {
            return calls.stream().filter(call -> call.command.contains(option)).count();
        }

        /** Returns one colon-formatted GnuPG secret-key record with signing capability. */
        private static String signingKeyRecord() {
            String[] fields = new String[13];
            fields[0] = "sec";
            fields[1] = "u";
            fields[2] = "4096";
            fields[3] = "1";
            fields[4] = "KEYID";
            fields[11] = "sc";
            return String.join(":", fields);
        }

        /** Returns one colon-formatted GnuPG key-grip record. */
        private static String keyGripRecord(String keyGrip) {
            String[] fields = new String[11];
            fields[0] = "grp";
            fields[9] = keyGrip;
            return String.join(":", fields);
        }

        /** Creates a successful result with UTF-8 output. */
        private static OpenPgpSigningSession.CommandResult result(String output) {
            return new OpenPgpSigningSession.CommandResult(0, output, "");
        }

        /** Creates the expected missing-config result. */
        private static OpenPgpSigningSession.CommandResult missing() {
            return new OpenPgpSigningSession.CommandResult(1, "", "");
        }
    }

    /** Stores a command and its input reference so tests can check secret erasure. */
    private record Call(List<String> command, char[] standardInput) { }
}
