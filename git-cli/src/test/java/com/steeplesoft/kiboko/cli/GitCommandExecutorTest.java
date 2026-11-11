package com.steeplesoft.kiboko.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GitCommandExecutorTest {
    @TempDir Path directory;

    @Test void runsGitAndCapturesOutput() throws Exception {
        var git = new GitCommandExecutor();
        git.run(directory, "init");
        String root = new String(git.run(directory, "rev-parse", "--show-toplevel"),
                StandardCharsets.UTF_8).strip();
        assertEquals(directory.toRealPath(), Path.of(root).toRealPath());
    }

    @Test void reportsGitFailuresWithoutHangingForCredentials() {
        var git = new GitCommandExecutor();
        git.run(directory, "init");
        GitException failure = assertThrows(GitException.class,
                () -> git.run(directory, "rev-parse", "--verify", "does-not-exist"));
        assertTrue(failure.getMessage().contains("Git exited with"), failure.getMessage());
    }

    @Test void interruptingGitCommandStopsTheProcess() throws Exception {
        var git = new GitCommandExecutor();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                // Git waits for stdin until EOF; interrupting must terminate that wait.
                git.run(directory, "hash-object", "--stdin");
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        Thread.sleep(150);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive(), "Git process did not stop after interruption");
        assertInstanceOf(GitException.class, failure.get());
    }

    @Test void rejectsMissingGitWorkingDirectory() {
        var git = new GitCommandExecutor();
        assertThrows(GitException.class,
                () -> git.run(directory.resolve("missing"), "status"));
    }

    /** Ensures configured commit signing prompts before the Git operation starts. */
    @Test void preparesOpenPgpForConfiguredCommitSigning() throws Exception {
        gitInit();
        FakeCommandRunner commands = new FakeCommandRunner("commit.gpgSign", "true", "openpgp");
        AtomicInteger prompts = new AtomicInteger();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> {
            prompts.incrementAndGet();
            return new char[] {'p'};
        }, commands);
        GitCommandExecutor git = new GitCommandExecutor(session);

        git.run(directory, "commit", "--allow-empty", "-m", "signed commit");

        assertEquals(1, prompts.get());
        assertEquals(1, commands.count("--preset"));
        session.close();
    }

    /** Ensures unrelated operations and non-OpenPGP formats do not prompt. */
    @Test void doesNotPromptForUnrelatedOrDelegatedSigningOperations() throws Exception {
        gitInit();
        FakeCommandRunner commands = new FakeCommandRunner("commit.gpgSign", "true", "openpgp");
        AtomicInteger prompts = new AtomicInteger();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> {
            prompts.incrementAndGet();
            return new char[] {'p'};
        }, commands);
        GitCommandExecutor git = new GitCommandExecutor(session);

        git.run(directory, "status");
        commands.signingFormat = "ssh";
        git.run(directory, "commit", "--allow-empty", "-m", "ssh signed commit");

        assertEquals(0, prompts.get());
        assertEquals(0, commands.count("--preset"));
        session.close();
    }

    /** Ensures a commit message that resembles a flag does not trigger signing. */
    @Test void doesNotTreatCommitMessageAsSigningOption() throws Exception {
        gitInit();
        FakeCommandRunner commands = new FakeCommandRunner("commit.gpgSign", "false", "openpgp");
        AtomicInteger prompts = new AtomicInteger();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> {
            prompts.incrementAndGet();
            return new char[] {'p'};
        }, commands);

        new GitCommandExecutor(session).run(directory, "commit", "--allow-empty", "-m", "-Ssecret");

        assertEquals(0, prompts.get());
        assertEquals(0, commands.count("--preset"));
        session.close();
    }

    /** Ensures tag.gpgSign triggers OpenPGP setup for a tag without a sign flag. */
    @Test void preparesOpenPgpForConfiguredTagSigning() throws Exception {
        gitInit();
        new GitCommandExecutor().run(directory, "commit", "--allow-empty", "-m", "initial");
        FakeCommandRunner commands = new FakeCommandRunner("tag.gpgSign", "true", "openpgp");
        AtomicInteger prompts = new AtomicInteger();
        OpenPgpSigningSession session = new OpenPgpSigningSession(() -> {
            prompts.incrementAndGet();
            return new char[] {'p'};
        }, commands);
        GitCommandExecutor executor = new GitCommandExecutor(session);

        executor.run(directory, "tag", "v1");

        assertEquals(1, prompts.get());
        assertEquals(1, commands.count("--preset"));
        assertTrue(new GitCommandExecutor().run(directory, "tag", "--list").length > 0);
        session.close();
    }

    /** Creates the temporary repository used by signing preflight tests. */
    private void gitInit() {
        GitCommandExecutor git = new GitCommandExecutor();
        git.run(directory, "init", "-b", "main");
        git.run(directory, "config", "user.name", "Test Author");
        git.run(directory, "config", "user.email", "test@example.invalid");
        git.run(directory, "config", "commit.gpgSign", "false");
        git.run(directory, "config", "tag.gpgSign", "false");
        git.run(directory, "config", "tag.forceSignAnnotated", "false");
    }

    /** Supplies a fake Git/GnuPG configuration and helper process for signing tests. */
    private static final class FakeCommandRunner implements OpenPgpSigningSession.CommandRunner {
        private final String signingOption;
        private final String signingValue;
        private final List<List<String>> calls = new ArrayList<>();
        private String signingFormat;

        /** Creates a fake runner with one enabled Git signing option. */
        private FakeCommandRunner(String signingOption, String signingValue, String signingFormat) {
            this.signingOption = signingOption;
            this.signingValue = signingValue;
            this.signingFormat = signingFormat;
        }

        /** Captures subprocess requests and returns a deterministic OpenPGP key. */
        @Override public OpenPgpSigningSession.CommandResult run(
                Path directory, List<String> command, char[] standardInput) {
            List<String> args = List.copyOf(command);
            calls.add(args);
            if (args.getFirst().equals("git") && args.contains("config")) {
                String key = args.getLast();
                if (key.equals("gpg.format")) return result(signingFormat);
                if (key.equals(signingOption)) return result(signingValue);
                if (key.equals("gpg.openpgp.program")) return result("fake-gpg");
                if (key.equals("user.signingKey")) return result("signer@example.invalid");
                return missing();
            }
            if (args.getFirst().equals("gpgconf")) return result("/fake/libexec");
            if (args.getFirst().equals("fake-gpg")) {
                String[] secretKey = new String[13];
                secretKey[0] = "sec";
                secretKey[11] = "sc";
                String[] keyGrip = new String[11];
                keyGrip[0] = "grp";
                keyGrip[9] = "ABCDEF1234567890ABCDEF1234567890ABCDEF12";
                return result(String.join(":", secretKey) + "\n"
                        + String.join(":", keyGrip) + "\n");
            }
            return result("");
        }

        /** Counts calls containing a selected command option. */
        private long count(String option) {
            return calls.stream().filter(args -> args.contains(option)).count();
        }

        /** Returns a successful command result with the supplied standard output. */
        private static OpenPgpSigningSession.CommandResult result(String output) {
            return new OpenPgpSigningSession.CommandResult(0, output, "");
        }

        /** Returns the result used for an unset Git configuration value. */
        private static OpenPgpSigningSession.CommandResult missing() {
            return new OpenPgpSigningSession.CommandResult(1, "", "");
        }
    }
}
