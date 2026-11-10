package io.github.jasondlee.gitclient.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    @Test void rejectsMissingGitWorkingDirectory() {
        var git = new GitCommandExecutor();
        assertThrows(GitException.class,
                () -> git.run(directory.resolve("missing"), "status"));
    }
}
