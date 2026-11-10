package io.github.jasondlee.gitclient.cli;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
public final class GitCommandExecutor {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final Duration REBASE_TIMEOUT = Duration.ofMinutes(5);
    public byte[] run(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git"); command.add("--no-pager"); command.addAll(List.of(args));
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
        } catch (IOException e) { throw new GitException("Unable to start Git: " + e.getMessage(), e); }
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = io.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = io.submit(() -> process.getErrorStream().readAllBytes());
            Duration timeout = List.of(args).contains("rebase") ? REBASE_TIMEOUT : TIMEOUT;
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException("Git command timed out");
            }
            byte[] out = stdout.get();
            byte[] err = stderr.get();
            if (process.exitValue() != 0) {
                throw new GitException("Git exited with " + process.exitValue() + ": " + new String(err, StandardCharsets.UTF_8).strip());
            }
            return out;
        } catch (InterruptedException e) {
            process.destroyForcibly(); Thread.currentThread().interrupt();
            throw new GitException("Git operation interrupted", e);
        } catch (ExecutionException e) {
            process.destroyForcibly(); throw new GitException("Unable to read Git output", e);
        }
    }
}
