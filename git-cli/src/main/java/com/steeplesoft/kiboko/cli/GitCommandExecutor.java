package com.steeplesoft.kiboko.cli;
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
    private final OpenPgpSigningSession signingSession;

    /** Creates an executor without app-managed OpenPGP prompting. */
    public GitCommandExecutor() {
        this(null);
    }

    /** Creates an executor that prepares OpenPGP signing when Git configuration requests it. */
    public GitCommandExecutor(OpenPgpSigningSession signingSession) {
        this.signingSession = signingSession;
    }

    /** Runs Git after preparing OpenPGP for operations that are configured to sign. */
    public byte[] run(Path directory, String... args) {
        if (signingSession != null) signingSession.prepareIfRequested(directory, args);
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

    /** Reads a Git boolean setting and returns false when the setting is unset. */
    public boolean configBoolean(Path directory, String key) {
        try {
            return new String(run(directory, "config", "--bool", "--get", key),
                    StandardCharsets.UTF_8).strip().equalsIgnoreCase("true");
        } catch (GitException ex) {
            String detail = ex.getMessage();
            if (detail != null && detail.startsWith("Git exited with 1:")) return false;
            throw ex;
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
