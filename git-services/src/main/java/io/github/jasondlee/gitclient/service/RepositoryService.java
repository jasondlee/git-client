package io.github.jasondlee.gitclient.service;
import io.github.jasondlee.gitclient.cli.*;
import io.github.jasondlee.gitclient.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
public final class RepositoryService {
    private final GitCommandExecutor git = new GitCommandExecutor();
    private final GitStatusParser statusParser = new GitStatusParser();
    public Path initialize(Path directory) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Choose an existing directory");
        }
        git.run(directory, "init");
        return findRepository(directory);
    }
    public Path cloneRepository(Path parentDirectory, String url, String folderName) {
        if (parentDirectory == null || !java.nio.file.Files.isDirectory(parentDirectory)
                || url == null || url.isBlank() || url.startsWith("-")
                || folderName == null || folderName.isBlank()
                || folderName.equals(".") || folderName.equals("..")
                || folderName.contains("/") || folderName.contains("\\\\")
                || !Path.of(folderName).getFileName().toString().equals(folderName)
                || java.nio.file.Files.exists(parentDirectory.resolve(folderName))) {
            throw new IllegalArgumentException("Invalid clone destination or repository URL");
        }
        git.run(parentDirectory, "clone", "--", url, folderName);
        return findRepository(parentDirectory.resolve(folderName));
    }
    public Path findRepository(Path directory) {
        String path = new String(git.run(directory, "rev-parse", "--show-toplevel"), StandardCharsets.UTF_8).strip();
        return Path.of(path).toAbsolutePath().normalize();
    }
    public RepositoryStatus status(Path directory) {
        return statusParser.parse(git.run(directory, "status", "--porcelain=v2", "--branch", "-z"));
    }
    /** Stage the entire current contents of the selected paths. */
    public void discardChanges(Path directory, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        Path root = findRepository(directory);
        List<String> command = new ArrayList<>(List.of("restore", "--worktree", "--"));
        for (String path : paths) {
            validateRepositoryPath(root, path);
            command.add(path);
        }
        git.run(root, command.toArray(String[]::new));
    }
    public void ignorePattern(Path directory, String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.contains("\n")
                || pattern.contains("\r")) {
            throw new IllegalArgumentException("Invalid ignore pattern");
        }
        Path root = findRepository(directory);
        Path ignoreFile = root.resolve(".gitignore");
        try {
            java.nio.file.Files.writeString(ignoreFile, pattern + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to update .gitignore", ex);
        }
    }
    private void validateRepositoryPath(Path root, String path) {
        if (path == null || path.isBlank() || Path.of(path).isAbsolute()
                || !root.resolve(path).normalize().startsWith(root)) {
            throw new IllegalArgumentException("Invalid repository-relative path: " + path);
        }
    }
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
    /** Returns a unified diff for one repository-relative path. */
    public String diff(Path directory, String path, boolean staged) {
        Path root = findRepository(directory);
        if (path == null || path.isBlank() || Path.of(path).isAbsolute()
                || !root.resolve(path).normalize().startsWith(root)) {
            throw new IllegalArgumentException("Invalid repository-relative path: " + path);
        }
        List<String> args = new ArrayList<>(List.of("diff", "--no-ext-diff",
                "--no-color", "--find-renames"));
        if (staged) args.add("--cached");
        args.add("--");
        args.add(path);
        String result = new String(git.run(root, args.toArray(String[]::new)),
                StandardCharsets.UTF_8);
        if (!staged && result.isEmpty() && status(root).files().stream()
                .anyMatch(file -> file.path().equals(path) && file.indexStatus().equals("?"))) {
            return "Untracked file: stage this file to preview its diff.";
        }
        return result;
    }
    public void pull(Path directory) {
        git.run(findRepository(directory), "pull", "--ff-only");
    }
    public void push(Path directory) {
        git.run(findRepository(directory), "push");
    }
    public List<String> stashes(Path directory) {
        String output = new String(git.run(findRepository(directory), "stash", "list",
                "--format=%gd%x09%s"), StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.isBlank()).toList();
    }
    public void stash(Path directory, String message) {
        List<String> args = new ArrayList<>(List.of("stash", "push", "--include-untracked"));
        if (message != null && !message.isBlank()) {
            args.add("-m");
            args.add(message);
        }
        git.run(findRepository(directory), args.toArray(String[]::new));
    }
    public void applyStash(Path directory, String reference) {
        validateStashReference(reference);
        git.run(findRepository(directory), "stash", "apply", reference);
    }
    public void dropStash(Path directory, String reference) {
        validateStashReference(reference);
        git.run(findRepository(directory), "stash", "drop", reference);
    }
    private void validateStashReference(String reference) {
        if (reference == null || !reference.matches("stash@[{][0-9]+[}]")) {
            throw new IllegalArgumentException("Invalid stash reference");
        }
    }
    public void addRemote(Path directory, String name, String url) {
        validateRemoteName(name);
        if (url == null || url.isBlank() || url.startsWith("-")) {
            throw new IllegalArgumentException("Invalid remote URL");
        }
        git.run(findRepository(directory), "remote", "add", name, url);
    }
    public void renameRemote(Path directory, String oldName, String newName) {
        validateRemoteName(oldName);
        validateRemoteName(newName);
        if (!remotes(directory).contains(oldName)) {
            throw new IllegalArgumentException("Unknown remote: " + oldName);
        }
        git.run(findRepository(directory), "remote", "rename", oldName, newName);
    }
    public void removeRemote(Path directory, String name) {
        validateRemoteName(name);
        if (!remotes(directory).contains(name)) {
            throw new IllegalArgumentException("Unknown remote: " + name);
        }
        git.run(findRepository(directory), "remote", "remove", name);
    }
    public String remoteUrl(Path directory, String name) {
        validateRemoteName(name);
        if (!remotes(directory).contains(name)) {
            throw new IllegalArgumentException("Unknown remote: " + name);
        }
        return new String(git.run(findRepository(directory), "remote", "get-url", name),
                StandardCharsets.UTF_8).strip();
    }
    private void validateRemoteName(String name) {
        if (name == null || name.isBlank() || name.startsWith("-")
                || name.contains("\\n") || name.contains("\\r")) {
            throw new IllegalArgumentException("Invalid remote name");
        }
    }
    public List<String> remotes(Path directory) {
        String output = new String(git.run(findRepository(directory), "remote"),
                StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.isBlank()).toList();
    }
    public void fetch(Path directory, String remote) {
        if (remote == null || remote.isBlank() || remote.startsWith("-")
                || !remotes(directory).contains(remote)) {
            throw new IllegalArgumentException("Unknown remote");
        }
        git.run(findRepository(directory), "fetch", "--prune", "--", remote);
    }
    public List<String> tags(Path directory) {
        String output = new String(git.run(findRepository(directory), "tag", "--list"),
                StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.isBlank()).toList();
    }
    public void startBisect(Path directory, String badCommit, String goodCommit) {
        validateCommitId(badCommit);
        validateCommitId(goodCommit);
        git.run(findRepository(directory), "bisect", "start", badCommit, goodCommit);
    }
    public void markBisect(Path directory, boolean good) {
        git.run(findRepository(directory), "bisect", good ? "good" : "bad");
    }
    public void resetBisect(Path directory) {
        git.run(findRepository(directory), "bisect", "reset");
    }
    public boolean isBisecting(Path directory) {
        String gitDir = new String(git.run(findRepository(directory),
                "rev-parse", "--git-path", "BISECT_LOG"), StandardCharsets.UTF_8).strip();
        Path path = Path.of(gitDir);
        if (!path.isAbsolute()) path = findRepository(directory).resolve(path);
        return java.nio.file.Files.isRegularFile(path);
    }
    public String createPatch(Path directory, String commitId) {
        validateCommitId(commitId);
        return new String(git.run(findRepository(directory), "format-patch", "-1",
                "--stdout", commitId), StandardCharsets.UTF_8);
    }
    public void applyPatch(Path directory, Path patchFile) {
        if (patchFile == null || !java.nio.file.Files.isRegularFile(patchFile)) {
            throw new IllegalArgumentException("Patch file does not exist");
        }
        git.run(findRepository(directory), "apply", "--", patchFile.toAbsolutePath().toString());
    }
    public List<String> gitConfiguration(Path directory) {
        String output = new String(git.run(findRepository(directory), "config", "--local",
                "--list"), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public List<String> fileHistory(Path directory, String path, int count) {
        if (count <= 0) return List.of();
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        String output = new String(git.run(root, "log", "-n", Integer.toString(count),
                "--format=%h %ad %s", "--date=short", "--", path), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public List<String> searchCommits(Path directory, String query, int count) {
        if (query == null || query.isBlank() || count <= 0) return List.of();
        String output = new String(git.run(findRepository(directory), "log", "--all",
                "-n", Integer.toString(count), "--fixed-strings", "--grep=" + query,
                "--format=%h %ad %s", "--date=short"), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public String compare(Path directory, String left, String right) {
        validateCommitish(left);
        validateCommitish(right);
        return new String(git.run(findRepository(directory), "diff", "--no-ext-diff",
                "--no-color", left, right), StandardCharsets.UTF_8);
    }
    private void validateCommitish(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_./-]+")
                || value.startsWith("-") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid Git reference");
        }
    }
    public List<String> remoteBranches(Path directory) {
        String output = new String(git.run(findRepository(directory), "branch",
                "--remotes", "--format=%(refname:short)"), StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.isBlank()
                && !line.contains(" -> ")).toList();
    }
    public void createTrackingBranch(Path directory, String localName, String remoteBranch) {
        validateBranchName(localName);
        validateBranchName(remoteBranch);
        if (!remoteBranches(directory).contains(remoteBranch)) {
            throw new IllegalArgumentException("Unknown remote-tracking branch: " + remoteBranch);
        }
        git.run(findRepository(directory), "branch", "--track", localName, remoteBranch);
    }
    public void renameBranch(Path directory, String oldName, String newName) {
        validateBranchName(oldName);
        validateBranchName(newName);
        if (!branches(directory).contains(oldName)) {
            throw new IllegalArgumentException("Unknown local branch: " + oldName);
        }
        git.run(findRepository(directory), "branch", "-m", oldName, newName);
    }
    public void createAnnotatedTag(Path directory, String name, String message) {
        if (name == null || name.isBlank() || name.startsWith("-")
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Tag name and message are required");
        }
        git.run(findRepository(directory), "tag", "-a", name, "-m", message);
    }
    public void createTag(Path directory, String name) {
        if (name == null || name.isBlank() || name.startsWith("-")) {
            throw new IllegalArgumentException("Invalid tag name");
        }
        git.run(findRepository(directory), "tag", name);
    }
    public void deleteTag(Path directory, String name) {
        if (name == null || name.isBlank() || name.startsWith("-")) {
            throw new IllegalArgumentException("Invalid tag name");
        }
        git.run(findRepository(directory), "tag", "-d", name);
    }
    /** Git's sequencer and merge state, including linked-worktree gitdirs. */
    public String operationState(Path directory) {
        Path root = findRepository(directory);
        String gitDir = new String(git.run(root, "rev-parse", "--absolute-git-dir"),
                StandardCharsets.UTF_8).strip();
        Path metadata = Path.of(gitDir);
        if (java.nio.file.Files.exists(metadata.resolve("rebase-merge"))
                || java.nio.file.Files.exists(metadata.resolve("rebase-apply"))) return "REBASE";
        if (java.nio.file.Files.exists(metadata.resolve("MERGE_HEAD"))) return "MERGE";
        if (java.nio.file.Files.exists(metadata.resolve("CHERRY_PICK_HEAD"))) return "CHERRY_PICK";
        if (java.nio.file.Files.exists(metadata.resolve("REVERT_HEAD"))) return "REVERT";
        return "NONE";
    }
    public List<String> conflictedFiles(Path directory) {
        String output = new String(git.run(findRepository(directory),
                "diff", "--name-only", "--diff-filter=U", "-z"), StandardCharsets.UTF_8);
        if (output.isEmpty()) return List.of();
        return Arrays.stream(output.split("\u0000")).filter(path -> !path.isEmpty()).toList();
    }
    public void continueMerge(Path directory) {
        if (!conflictedFiles(directory).isEmpty()) {
            throw new IllegalStateException("Resolve and stage all conflicts before continuing");
        }
        git.run(findRepository(directory), "merge", "--continue");
    }
    public void continueRevert(Path directory) {
        git.run(findRepository(directory), "revert", "--continue");
    }
    public void merge(Path directory, String branchName) {
        validateBranchName(branchName);
        if (!branches(directory).contains(branchName)) {
            throw new IllegalArgumentException("Unknown local branch: " + branchName);
        }
        git.run(findRepository(directory), "merge", "--no-edit", branchName);
    }
    public void abortMerge(Path directory) {
        git.run(findRepository(directory), "merge", "--abort");
    }
    public void deleteBranch(Path directory, String branchName) {
        validateBranchName(branchName);
        git.run(findRepository(directory), "branch", "-d", branchName);
    }
    public List<String> branches(Path directory) {
        String output = new String(git.run(findRepository(directory), "branch", "--list",
                "--format=%(refname:short)"), StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.isBlank()).toList();
    }
    public void createBranch(Path directory, String name) {
        validateBranchName(name);
        git.run(findRepository(directory), "branch", name);
    }
    public void switchBranch(Path directory, String name) {
        validateBranchName(name);
        git.run(findRepository(directory), "switch", name);
    }
    private void validateBranchName(String name) {
        if (name == null || name.isBlank() || name.startsWith("-")) {
            throw new IllegalArgumentException("Invalid branch name");
        }
    }
    /** Commit staged changes only, preserving unstaged working-tree edits. */
    public String amendCommit(Path directory, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Commit message must not be blank");
        }
        Path root = findRepository(directory);
        if (history(root, 1).isEmpty()) {
            throw new IllegalStateException("Cannot amend without an existing commit");
        }
        git.run(root, "commit", "--amend", "-m", message);
        return new String(git.run(root, "rev-parse", "HEAD"),
                StandardCharsets.UTF_8).strip();
    }
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
    public void reset(Path directory, String commitId, String mode) {
        validateCommitId(commitId);
        if (!List.of("soft", "mixed", "hard").contains(mode)) {
            throw new IllegalArgumentException("Invalid reset mode");
        }
        git.run(findRepository(directory), "reset", "--" + mode, commitId);
    }
    public String configuration(Path directory, String key) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9-]*[.][A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException("Invalid configuration key");
        }
        return new String(git.run(findRepository(directory), "config", "--local",
                "--get", key), StandardCharsets.UTF_8).strip();
    }
    public void setConfiguration(Path directory, String key, String value) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9-]*[.][A-Za-z0-9.-]+")
                || value == null) {
            throw new IllegalArgumentException("Invalid configuration");
        }
        git.run(findRepository(directory), "config", "--local", key, value);
    }
    public List<String> submodules(Path directory) {
        String output = new String(git.run(findRepository(directory), "submodule",
                "status"), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public void updateSubmodules(Path directory) {
        git.run(findRepository(directory), "submodule", "update", "--init", "--recursive");
    }
    public List<String> worktrees(Path directory) {
        String output = new String(git.run(findRepository(directory),
                "worktree", "list", "--porcelain"), StandardCharsets.UTF_8);
        return output.lines().filter(line -> line.startsWith("worktree "))
                .map(line -> line.substring("worktree ".length())).toList();
    }
    public void addWorktree(Path directory, Path destination, String branchName) {
        validateBranchName(branchName);
        if (destination == null || !destination.isAbsolute()) {
            throw new IllegalArgumentException("Worktree destination must be absolute");
        }
        if (!branches(directory).contains(branchName)) {
            throw new IllegalArgumentException("Unknown local branch: " + branchName);
        }
        git.run(findRepository(directory), "worktree", "add", destination.toString(), branchName);
    }
    public void removeWorktree(Path directory, Path destination) {
        if (destination == null || !destination.isAbsolute()) {
            throw new IllegalArgumentException("Worktree destination must be absolute");
        }
        Path target = canonicalWorktreePath(destination);
        if (!worktrees(directory).stream().map(Path::of)
                .map(this::canonicalWorktreePath).anyMatch(target::equals)) {
            throw new IllegalArgumentException("Not a registered worktree: " + target);
        }
        if (target.equals(canonicalWorktreePath(findRepository(directory)))) {
            throw new IllegalArgumentException("Cannot remove the current worktree");
        }
        git.run(findRepository(directory), "worktree", "remove", target.toString());
    }
    private Path canonicalWorktreePath(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Worktree path is inaccessible: " + path, ex);
        }
    }
    public List<String> reflog(Path directory, int count) {
        if (count <= 0) return List.of();
        String output = new String(git.run(findRepository(directory), "reflog",
                "-n", Integer.toString(count), "--format=%h %gs"), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public String blame(Path directory, String path) {
        Path root = findRepository(directory);
        if (path == null || path.isBlank() || Path.of(path).isAbsolute()
                || !root.resolve(path).normalize().startsWith(root)) {
            throw new IllegalArgumentException("Invalid path");
        }
        return new String(git.run(root, "blame", "--", path), StandardCharsets.UTF_8);
    }
    public void cherryPick(Path directory, String commitId) {
        validateCommitId(commitId);
        git.run(findRepository(directory), "cherry-pick", commitId);
    }
    public void revert(Path directory, String commitId) {
        validateCommitId(commitId);
        git.run(findRepository(directory), "revert", "--no-edit", commitId);
    }
    public void continueCherryPick(Path directory) {
        git.run(findRepository(directory), "cherry-pick", "--continue");
    }
    public void abortCherryPick(Path directory) {
        git.run(findRepository(directory), "cherry-pick", "--abort");
    }
    public void abortRevert(Path directory) {
        git.run(findRepository(directory), "revert", "--abort");
    }
    public String createFixupCommit(Path directory, String targetCommitId) {
        validateCommitId(targetCommitId);
        Path root = findRepository(directory);
        git.run(root, "commit", "--fixup=" + targetCommitId);
        return new String(git.run(root, "rev-parse", "HEAD"),
                StandardCharsets.UTF_8).strip();
    }
    public void rebaseAutosquash(Path directory, String baseBranch) {
        validateBranchName(baseBranch);
        if (!branches(directory).contains(baseBranch)) {
            throw new IllegalArgumentException("Unknown local branch: " + baseBranch);
        }
        git.run(findRepository(directory), "rebase", "--autosquash", baseBranch);
    }
    public void rebase(Path directory, String branchName) {
        validateBranchName(branchName);
        if (!branches(directory).contains(branchName)) {
            throw new IllegalArgumentException("Unknown local branch: " + branchName);
        }
        git.run(findRepository(directory), "rebase", branchName);
    }
    public void continueRebase(Path directory) {
        git.run(findRepository(directory), "rebase", "--continue");
    }
    public void skipRebase(Path directory) {
        git.run(findRepository(directory), "rebase", "--skip");
    }
    public void abortRebase(Path directory) {
        git.run(findRepository(directory), "rebase", "--abort");
    }
    private void validateCommitId(String commitId) {
        if (commitId == null || !commitId.matches("[0-9a-fA-F]{7,64}")) {
            throw new IllegalArgumentException("Invalid commit ID");
        }
    }
    public String showCommit(Path directory, String commitId) {
        if (commitId == null || !commitId.matches("[0-9a-fA-F]{7,64}")) {
            throw new IllegalArgumentException("Invalid commit ID");
        }
        return new String(git.run(findRepository(directory), "show", "--no-ext-diff",
                "--no-color", "--format=fuller", "--stat", "--patch", commitId),
                StandardCharsets.UTF_8);
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
