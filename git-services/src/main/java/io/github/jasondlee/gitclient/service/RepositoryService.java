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
        byte[] data = git.run(directory, "log", "-n", Integer.toString(count), "--format=%H%x00%s%x00%an%x00%cI%x00");
        String[] fields = new String(data, StandardCharsets.UTF_8).split("\u0000");
        List<CommitInfo> commits = new ArrayList<>();
        for (int i = 0; i + 3 < fields.length; i += 4) {
            String id = fields[i].strip();
            if (!id.isEmpty()) commits.add(new CommitInfo(id, fields[i+1], fields[i+2], fields[i+3]));
        }
        return commits;
    }
}
