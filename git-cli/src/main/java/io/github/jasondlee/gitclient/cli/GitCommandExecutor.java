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
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(15);
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
            try {
                Future<byte[]> stdout = io.submit(() -> process.getInputStream().readAllBytes());
                Future<byte[]> stderr = io.submit(() -> process.getErrorStream().readAllBytes());
                String operation = args.length == 0 ? "" : args[0];
                Duration timeout = switch (operation) {
                    case "clone", "fetch", "pull", "push", "lfs", "submodule" -> NETWORK_TIMEOUT;
                    case "rebase" -> REBASE_TIMEOUT;
                    default -> TIMEOUT;
                };
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminateProcessTree(process);
                    throw new GitException("Git command timed out");
                }
                byte[] out = stdout.get();
                byte[] err = stderr.get();
                if (process.exitValue() != 0) {
                    throw new GitException("Git exited with " + process.exitValue() + ": " + new String(err, StandardCharsets.UTF_8).strip());
                }
                return out;
            } catch (InterruptedException e) {
                terminateProcessTree(process);
                Thread.currentThread().interrupt();
                throw new GitException("Git operation interrupted", e);
            } catch (ExecutionException e) {
                terminateProcessTree(process);
                throw new GitException("Unable to read Git output", e);
            }
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
