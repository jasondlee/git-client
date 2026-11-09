package io.github.jasondlee.gitclient.service;
import io.github.jasondlee.gitclient.cli.*;
import io.github.jasondlee.gitclient.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
public final class RepositoryService {
    private final GitCommandExecutor git = new GitCommandExecutor();
    private final GitStatusParser statusParser = new GitStatusParser();
    public Path findRepository(Path directory) {
        String path = new String(git.run(directory, "rev-parse", "--show-toplevel"), StandardCharsets.UTF_8).strip();
        return Path.of(path).toAbsolutePath().normalize();
    }
    public RepositoryStatus status(Path directory) {
        return statusParser.parse(git.run(directory, "status", "--porcelain=v2", "--branch", "-z"));
    }
    /** Stage the entire current contents of the selected paths. */
    public void stage(Path directory, List<String> paths) {
        updateIndex(directory, paths, false);
    }
    /** Reset selected paths in the index to HEAD, leaving working files untouched. */
    public void unstage(Path directory, List<String> paths) {
        updateIndex(directory, paths, true);
    }
    private void updateIndex(Path directory, List<String> paths, boolean unstage) {
        if (paths.isEmpty()) return;
        Path root = findRepository(directory);
        List<String> command = new ArrayList<>();
        if (unstage) {
            command.addAll(List.of("reset", "-q", "--"));
        } else {
            command.addAll(List.of("add", "-A", "--"));
        }
        for (String path : paths) {
            Path candidate = root.resolve(path).normalize();
            if (path.isBlank() || Path.of(path).isAbsolute() || !candidate.startsWith(root)) {
                throw new IllegalArgumentException("Invalid repository-relative path: " + path);
            }
            command.add(path);
        }
        git.run(root, command.toArray(String[]::new));
    }
    /** Commit staged changes only, preserving unstaged working-tree edits. */
    public String commit(Path directory, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Commit message must not be blank");
        }
        Path root = findRepository(directory);
        boolean staged = status(root).files().stream()
                .anyMatch(file -> !file.indexStatus().equals(".")
                        && !file.indexStatus().equals("?"));
        if (!staged) throw new IllegalStateException("No staged changes to commit");
        git.run(root, "commit", "-m", message);
        return new String(git.run(root, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
    }
    public List<CommitInfo> history(Path directory, int count) {
        if (count <= 0) return List.of();
        String status = new String(git.run(directory, "status", "--porcelain=v2", "--branch", "-z"), StandardCharsets.UTF_8);
        if (status.startsWith("# branch.oid (initial)\u0000") || status.contains("\u0000# branch.oid (initial)\u0000")) return List.of();
        byte[] data = git.run(directory, "log", "-n", Integer.toString(count), "--format=%H%x1f%s%x1f%an%x1f%cI%x1e");
        String output = new String(data, StandardCharsets.UTF_8);
        List<CommitInfo> commits = new ArrayList<>();
        for (String record : output.split("\u001e")) {
            String[] fields = record.stripLeading().split("\u001f", -1);
            if (fields.length == 4 && !fields[0].isBlank()) {
                commits.add(new CommitInfo(fields[0], fields[1], fields[2], fields[3]));
            }
        }
        return commits;
    }
}
