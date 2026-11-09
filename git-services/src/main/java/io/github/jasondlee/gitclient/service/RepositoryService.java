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
    public List<CommitInfo> history(Path directory, int count) {
        if (count <= 0) return List.of();
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
