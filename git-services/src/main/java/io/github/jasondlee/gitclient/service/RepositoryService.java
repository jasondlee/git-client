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
    /** Preview untracked files and directories that Git clean would remove. */
    public List<String> cleanPreview(Path directory, boolean includeIgnored) {
        Path root = findRepository(directory);
        String[] args = includeIgnored
                ? new String[]{"clean", "-n", "-d", "-x"}
                : new String[]{"clean", "-n", "-d"};
        String output = new String(git.run(root, args), StandardCharsets.UTF_8);
        return output.lines().filter(line -> line.startsWith("Would remove "))
                .map(line -> line.substring("Would remove ".length())).toList();
    }
    /** Remove untracked files only after the caller has reviewed a clean preview. */
    public void cleanUntracked(Path directory, boolean includeIgnored) {
        Path root = findRepository(directory);
        if (includeIgnored) {
            git.run(root, "clean", "-f", "-d", "-x");
        } else {
            git.run(root, "clean", "-f", "-d");
        }
    }
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
    /** Returns individual unified-diff hunks including the file header needed by git apply. */
    public List<String> diffHunks(Path directory, String path, boolean staged) {
        String patch = diff(directory, path, staged);
        int first = patch.indexOf("@@ ");
        if (first < 0) return List.of();
        String header = patch.substring(0, first);
        List<String> hunks = new ArrayList<>();
        int start = first;
        while (start >= 0) {
            int next = patch.indexOf("\n@@ ", start + 3);
            int end = next < 0 ? patch.length() : next + 1;
            hunks.add(header + patch.substring(start, end));
            start = next < 0 ? -1 : next + 1;
        }
        return hunks;
    }
    /**
     * Build a patch containing only selected changed lines from one unified diff hunk.
     * Change-line indexes are zero-based and count '+' and '-' lines, excluding headers.
     */
    public String selectDiffLines(String patch, Set<Integer> selectedLines) {
        if (patch == null || selectedLines == null || selectedLines.isEmpty()) {
            throw new IllegalArgumentException("Select at least one changed line");
        }
        int marker = patch.indexOf("\n@@ ");
        if (!patch.startsWith("diff --git ") || marker < 0) {
            throw new IllegalArgumentException("Expected a unified diff hunk");
        }
        int headerEnd = patch.indexOf('\n', marker + 1);
        if (headerEnd < 0) throw new IllegalArgumentException("Missing hunk body");
        String hunkHeader = patch.substring(marker + 1, headerEnd);
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")
                .matcher(hunkHeader);
        if (!match.matches()) throw new IllegalArgumentException("Invalid hunk header");
        int oldStart = Integer.parseInt(match.group(1));
        int newStart = Integer.parseInt(match.group(3));
        int oldCount = 0;
        int newCount = 0;
        int changeIndex = 0;
        int chosen = 0;
        StringBuilder body = new StringBuilder();
        for (String line : patch.substring(headerEnd + 1).split("\n", -1)) {
            if (line.isEmpty()) continue;
            char prefix = line.charAt(0);
            if (prefix == '\\') continue;
            if (prefix != ' ' && prefix != '+' && prefix != '-') {
                throw new IllegalArgumentException("Unexpected diff line");
            }
            if (prefix == ' ') {
                oldCount++;
                newCount++;
                body.append(line).append('\n');
            } else {
                boolean include = selectedLines.contains(changeIndex++);
                if (include) {
                    chosen++;
                    if (prefix != '+') oldCount++;
                    if (prefix != '-') newCount++;
                    body.append(line).append('\n');
                } else if (prefix == '-') {
                    oldCount++;
                    newCount++;
                    body.append(' ').append(line.substring(1)).append('\n');
                }
            }
        }
        if (chosen != selectedLines.size()) {
            throw new IllegalArgumentException("Selected line index out of range");
        }
        return patch.substring(0, marker + 1)
                + "@@ -" + oldStart + "," + oldCount
                + " +" + newStart + "," + newCount + " @@" + match.group(5)
                + "\n" + body;
    }
    /** Apply a selected diff hunk to the index, or reverse it out of the index. */
    public void applyIndexHunk(Path directory, String patch, boolean reverse) {
        if (patch == null || !patch.startsWith("diff --git ")
                || !patch.contains("\n@@ ") || patch.length() > 2_000_000) {
            throw new IllegalArgumentException("Invalid unified diff hunk");
        }
        Path root = findRepository(directory);
        Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempFile("git-client-hunk-", ".patch");
            java.nio.file.Files.writeString(temporary, patch, StandardCharsets.UTF_8);
            if (reverse) {
                git.run(root, "apply", "--cached", "--reverse", "--unidiff-zero",
                        temporary.toString());
            } else {
                git.run(root, "apply", "--cached", "--unidiff-zero", temporary.toString());
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to prepare diff hunk", ex);
        } finally {
            if (temporary != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                    temporary.toFile().deleteOnExit();
                }
            }
        }
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
        String output = new String(git.run(root, "log", "--follow", "-n", Integer.toString(Math.min(count, 10000)),
                "--format=%h %ad %s", "--date=short", "--", path), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    /** Show the changes made to a file by one commit, including its parent diff. */
    public String fileCommitPatch(Path directory, String commitId, String path) {
        validateCommitId(commitId);
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        // Walk the same rename-aware history shown in the UI. A commit preceding
        // a rename must be displayed using the file's name at that commit.
        String history = new String(git.run(root, "log", "--follow", "--name-status",
                "--format=commit:%H", "--", path), StandardCharsets.UTF_8);
        String historicalPath = path;
        String commitPath = null;
        boolean selected = false;
        for (String line : history.lines().toList()) {
            if (line.startsWith("commit:")) {
                if (selected) break;
                selected = line.substring("commit:".length()).startsWith(commitId);
                if (selected) commitPath = historicalPath;
            } else if (line.startsWith("R") && line.contains("\t")) {
                String[] fields = line.split("\t", 3);
                if (fields.length == 3 && fields[2].equals(historicalPath)) {
                    historicalPath = fields[1];
                }
            }
        }
        if (commitPath == null) {
            throw new IllegalArgumentException("Commit is not in this file's history");
        }
        return new String(git.run(root, "show", "--format=fuller", "--find-renames",
                "--no-ext-diff", "--no-color", commitId, "--", commitPath),
                StandardCharsets.UTF_8);
    }
    /** Search tracked filenames without traversing ignored or untracked directories. */
    public List<String> searchTrackedPaths(Path directory, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        byte[] data = git.run(findRepository(directory), "ls-files", "-z");
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(new String(data, StandardCharsets.UTF_8).split("\\u0000"))
                .filter(path -> !path.isEmpty())
                .filter(path -> path.toLowerCase(java.util.Locale.ROOT).contains(needle))
                .limit(Math.min(limit, 1000)).toList();
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
    public String conflictVersion(Path directory, String path, int stage) {
        if (stage != 1 && stage != 2 && stage != 3) {
            throw new IllegalArgumentException("Conflict stage must be 1, 2 or 3");
        }
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        return new String(git.run(root, "show", ":" + stage + ":" + path),
                StandardCharsets.UTF_8);
    }
    /** Read a conflict stage when present; absent stages are common in add/delete conflicts. */
    public String conflictVersionOrEmpty(Path directory, String path, int stage) {
        if (stage < 1 || stage > 3) {
            throw new IllegalArgumentException("Conflict stage must be 1, 2 or 3");
        }
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        if (!conflictedFiles(root).contains(path)) {
            throw new IllegalArgumentException("File is not currently conflicted: " + path);
        }
        String stages = new String(git.run(root, "ls-files", "--unmerged", "--", path),
                StandardCharsets.UTF_8);
        boolean present = stages.lines().anyMatch(line -> {
            int tab = line.indexOf('\t');
            return tab >= 0 && line.substring(0, tab).endsWith(" " + stage);
        });
        return present ? conflictVersion(root, path, stage) : "";
    }
    /** True when an available unmerged version contains a NUL byte (binary content). */
    public boolean binaryConflict(Path directory, String path) {
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        if (!conflictedFiles(root).contains(path)) {
            throw new IllegalArgumentException("File is not currently conflicted: " + path);
        }
        String stages = new String(git.run(root, "ls-files", "--unmerged", "--", path),
                StandardCharsets.UTF_8);
        for (int stage = 1; stage <= 3; stage++) {
            final int candidate = stage;
            boolean present = stages.lines().anyMatch(line -> {
                int tab = line.indexOf('\t');
                return tab >= 0 && line.substring(0, tab).endsWith(" " + candidate);
            });
            if (present) {
                byte[] contents = git.run(root, "show", ":" + stage + ":" + path);
                if (looksBinary(contents)) return true;
            }
        }
        return false;
    }

    private static boolean looksBinary(byte[] contents) {
        for (byte value : contents) {
            int unsigned = value & 0xff;
            if (unsigned == 0 || unsigned < 9 || (unsigned > 13 && unsigned < 32)
                    || unsigned == 127) return true;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(contents));
            return false;
        } catch (java.nio.charset.CharacterCodingException ex) {
            return true;
        }
    }

    /** Accept an unmodified binary conflict side without decoding or rewriting its bytes. */
    public void resolveConflictUsingStage(Path directory, String path, int stage) {
        if (stage != 2 && stage != 3) {
            throw new IllegalArgumentException("Choose ours (2) or theirs (3)");
        }
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        if (!conflictedFiles(root).contains(path)) {
            throw new IllegalArgumentException("File is not currently conflicted: " + path);
        }
        String stages = new String(git.run(root, "ls-files", "--unmerged", "--", path),
                StandardCharsets.UTF_8);
        boolean present = stages.lines().anyMatch(line -> {
            int tab = line.indexOf('\t');
            return tab >= 0 && line.substring(0, tab).endsWith(" " + stage);
        });
        if (!present) {
            throw new IllegalArgumentException("Selected conflict side does not exist");
        }
        git.run(root, "checkout", stage == 2 ? "--ours" : "--theirs", "--", path);
        git.run(root, "add", "--", path);
    }
    public void resolveConflict(Path directory, String path, String contents) {
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        if (contents == null) throw new IllegalArgumentException("Missing resolution");
        Path target = root.resolve(path).normalize();
        Path ancestor = target;
        while (ancestor != null && ancestor.startsWith(root)) {
            if (java.nio.file.Files.isSymbolicLink(ancestor)) {
                throw new IllegalArgumentException("Cannot resolve a conflict through a symbolic link");
            }
            ancestor = ancestor.getParent();
        }
        if (!conflictedFiles(root).contains(path)) {
            throw new IllegalArgumentException("File is not currently conflicted: " + path);
        }
        try {
            java.nio.file.Files.writeString(target, contents, StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to write conflict resolution", ex);
        }
        stage(root, List.of(path));
    }
    /** Resolve a delete/modify conflict by accepting the deletion and staging it. */
    public void resolveConflictByDeleting(Path directory, String path) {
        Path root = findRepository(directory);
        validateRepositoryPath(root, path);
        Path target = root.resolve(path).normalize();
        for (Path ancestor = target; ancestor != null && ancestor.startsWith(root);
                ancestor = ancestor.getParent()) {
            if (java.nio.file.Files.isSymbolicLink(ancestor)) {
                throw new IllegalArgumentException("Cannot resolve a conflict through a symbolic link");
            }
        }
        if (!conflictedFiles(root).contains(path)) {
            throw new IllegalArgumentException("File is not currently conflicted: " + path);
        }
        try {
            java.nio.file.Files.deleteIfExists(target);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to delete conflicted file", ex);
        }
        git.run(root, "add", "-u", "--", path);
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
    public void createBranchAt(Path directory, String name, String commitId) {
        validateBranchName(name);
        validateCommitId(commitId);
        git.run(findRepository(directory), "branch", name, commitId);
    }

    public void createTagAt(Path directory, String name, String commitId) {
        if (name == null || name.isBlank() || name.startsWith("-")
                || name.contains(" ") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid tag name");
        }
        validateCommitId(commitId);
        git.run(findRepository(directory), "tag", name, commitId);
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
    public List<String> lfsTrackedFiles(Path directory) {
        String output = new String(git.run(findRepository(directory),
                "lfs", "ls-files"), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public void trackLfsPattern(Path directory, String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.startsWith("-")
                || pattern.contains("\n") || pattern.contains("\r")) {
            throw new IllegalArgumentException("Invalid LFS tracking pattern");
        }
        git.run(findRepository(directory), "lfs", "track", "--", pattern);
    }
    public void untrackLfsPattern(Path directory, String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.startsWith("-")
                || pattern.contains("\\n") || pattern.contains("\\r")) {
            throw new IllegalArgumentException("Invalid LFS tracking pattern");
        }
        git.run(findRepository(directory), "lfs", "untrack", "--", pattern);
    }
    public List<String> installedHooks(Path directory) {
        Path root = findRepository(directory);
        String hooksPath = new String(git.run(root, "rev-parse", "--git-path", "hooks"),
                StandardCharsets.UTF_8).strip();
        Path hooks = Path.of(hooksPath);
        if (!hooks.isAbsolute()) hooks = root.resolve(hooks);
        if (!java.nio.file.Files.isDirectory(hooks)) return List.of();
        try (var files = java.nio.file.Files.list(hooks)) {
            return files.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".sample"))
                    .map(path -> path.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to list Git hooks", ex);
        }
    }
    public List<String> submodules(Path directory) {
        String output = new String(git.run(findRepository(directory), "submodule",
                "status"), StandardCharsets.UTF_8);
        return output.lines().toList();
    }
    public void updateSubmodules(Path directory) {
        git.run(findRepository(directory), "submodule", "update", "--init", "--recursive");
    }
    public void syncSubmodules(Path directory) {
        git.run(findRepository(directory), "submodule", "sync", "--recursive");
    }
    public void pruneWorktrees(Path directory) {
        git.run(findRepository(directory), "worktree", "prune");
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
    /** Generate a rebase todo list for commits unique to the current branch. */
    public String interactiveRebasePlan(Path directory, String baseBranch) {
        validateBranchName(baseBranch);
        if (!branches(directory).contains(baseBranch)) {
            throw new IllegalArgumentException("Unknown local branch: " + baseBranch);
        }
        Path root = findRepository(directory);
        return new String(git.run(root, "log", "--reverse", "--format=pick %H %s",
                baseBranch + "..HEAD"), StandardCharsets.UTF_8);
    }

    /**
     * Apply a validated interactive rebase plan. Every original commit must occur
     * exactly once, with an action of pick, reword, edit, squash, fixup, or drop.
     * The editor is a temporary Java source-file program, avoiding shell-specific
     * sed scripts and external scripting dependencies.
     */
    public void interactiveRebase(Path directory, String baseBranch, String plan) {
        interactiveRebase(directory, baseBranch, plan, Map.of());
    }

    public void interactiveRebase(Path directory, String baseBranch, String plan,
            Map<String, String> replacementMessages) {
        validateBranchName(baseBranch);
        Path root = findRepository(directory);
        if (rebaseInProgress(root)) {
            throw new IllegalStateException("Finish or abort the existing rebase before starting another");
        }
        if (!branches(root).contains(baseBranch)) {
            throw new IllegalArgumentException("Unknown local branch: " + baseBranch);
        }
        if (!new String(git.run(root, "status", "--porcelain"),
                StandardCharsets.UTF_8).isBlank()) {
            throw new IllegalStateException("Commit or stash working tree changes before rebasing");
        }
        if (!new String(git.run(root, "rev-list", "--merges", baseBranch + "..HEAD"),
                StandardCharsets.UTF_8).isBlank()) {
            throw new IllegalArgumentException(
                    "Interactive rebase currently supports linear commit history only");
        }
        String original = interactiveRebasePlan(root, baseBranch);
        String todo = validateInteractiveRebasePlan(original, plan);
        Map<String, String> messages = Objects.requireNonNull(replacementMessages,
                "replacementMessages");
        List<Map.Entry<String, String>> subjects = new ArrayList<>();
        for (String line : todo.lines().filter(line -> !line.isBlank()).toList()) {
            String[] tokens = line.split(" ", 2);
            if (!tokens[0].equals("reword")) continue;
            String replacement = messages.get(tokens[1]);
            if (replacement == null || replacement.isBlank()
                    || replacement.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("A nonempty replacement message is required for " + tokens[1]);
            }
            String originalMessage = new String(git.run(root, "log", "-1", "--format=%B",
                    tokens[1]), StandardCharsets.UTF_8);
            String subject = originalMessage.lines().findFirst().orElse("");
            if (subject.isBlank()) {
                throw new IllegalArgumentException("Cannot reword a commit with an empty subject");
            }
            subjects.add(Map.entry(subject, replacement));
        }
        if (!messages.keySet().equals(
                todo.lines().filter(line -> line.startsWith("reword "))
                        .map(line -> line.split(" ", 2)[1]).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("Replacement messages must match reword commits");
        }
        Path gitDirectory = gitDirectory(root);
        Path editor = gitDirectory.resolve("git-client-rebase-editor.java");
        Path instructions = gitDirectory.resolve("git-client-rebase-plan.todo");
        Path messageMap = gitDirectory.resolve("git-client-rebase-messages.map");
        try {
            java.nio.file.Files.writeString(editor, """
                    import java.nio.charset.StandardCharsets;
                    import java.nio.file.*;
                    import java.util.Base64;
                    class GitClientSequenceEditor {
                        public static void main(String[] args) throws Exception {
                            if (args[0].equals("sequence")) {
                                Files.copy(Path.of(args[1]), Path.of(args[2]),
                                        StandardCopyOption.REPLACE_EXISTING);
                                return;
                            }
                            Path target = Path.of(args[2]);
                            String first = Files.readString(target, StandardCharsets.UTF_8)
                                    .lines().findFirst().orElse("");
                            for (String line : Files.readAllLines(Path.of(args[1]))) {
                                String[] pair = line.split("\\t", 2);
                                String original = new String(Base64.getDecoder().decode(pair[0]),
                                        StandardCharsets.UTF_8);
                                if (first.equals(original)) {
                                    String replacement = new String(Base64.getDecoder().decode(pair[1]),
                                            StandardCharsets.UTF_8);
                                    Files.writeString(target, replacement.stripTrailing() + "\\n",
                                            StandardCharsets.UTF_8);
                                    java.util.List<String> remaining =
                                            new java.util.ArrayList<>(Files.readAllLines(Path.of(args[1])));
                                    remaining.remove(line);
                                    Files.write(Path.of(args[1]), remaining, StandardCharsets.UTF_8);
                                    return;
                                }
                            }
                        }
                    }
                    """);
            java.nio.file.Files.writeString(instructions, todo);
            StringBuilder mapping = new StringBuilder();
            for (var entry : subjects) {
                mapping.append(Base64.getEncoder().encodeToString(
                        entry.getKey().getBytes(StandardCharsets.UTF_8)))
                        .append('\t').append(Base64.getEncoder().encodeToString(
                                entry.getValue().getBytes(StandardCharsets.UTF_8)))
                        .append('\n');
            }
            java.nio.file.Files.writeString(messageMap, mapping.toString());
            String javaEditor = "java \"" + editor.toString().replace('\\', '/')
                    + "\"";
            String sequenceCommand = javaEditor + " sequence \""
                    + instructions.toString().replace('\\', '/') + "\"";
            String messageCommand = javaEditor + " message \""
                    + messageMap.toString().replace('\\', '/') + "\"";
            git.run(root, "-c", "sequence.editor=" + sequenceCommand,
                    "-c", "core.editor=" + messageCommand, "rebase", "-i", baseBranch);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to prepare rebase editor", ex);
        } finally {
            if (!rebaseInProgress(root)) cleanupInteractiveRebaseFiles(root);
        }
    }

    /** Validate a proposed plan without changing the repository. */
    public String validateInteractiveRebasePlan(Path directory, String baseBranch, String plan) {
        return validateInteractiveRebasePlan(interactiveRebasePlan(directory, baseBranch), plan);
    }

    private static String validateInteractiveRebasePlan(String original, String plan) {
        java.util.Set<String> expected = new java.util.HashSet<>();
        for (String line : original.lines().filter(line -> !line.isBlank()).toList()) {
            expected.add(line.split(" ", 3)[1]);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        StringBuilder todo = new StringBuilder();
        boolean hasPriorCommit = false;
        for (String line : Objects.requireNonNull(plan, "plan").lines().toList()) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            String[] parts = line.strip().split("\\s+", 3);
            if (parts.length < 2 || !List.of("pick", "reword", "edit", "squash", "fixup", "drop").contains(parts[0]) || !expected.contains(parts[1])
                    || !seen.add(parts[1])) {
                throw new IllegalArgumentException("Invalid rebase action or commit: " + line);
            }
            if ((parts[0].equals("squash") || parts[0].equals("fixup"))
                    && !hasPriorCommit) {
                throw new IllegalArgumentException("Squash and fixup require a preceding commit");
            }
            if (!parts[0].equals("drop")) hasPriorCommit = true;
            todo.append(parts[0]).append(' ').append(parts[1]).append('\n');
        }
        if (!seen.equals(expected)) {
            throw new IllegalArgumentException("Plan must contain every original commit exactly once");
        }
        if (expected.isEmpty()) throw new IllegalArgumentException("No commits to rebase");
        return todo.toString();
    }

    public void rebaseAutosquash(Path directory, String baseBranch) {
        validateBranchName(baseBranch);
        if (!branches(directory).contains(baseBranch)) {
            throw new IllegalArgumentException("Unknown local branch: " + baseBranch);
        }
        git.run(findRepository(directory), "-c", "sequence.editor=true",
                "rebase", "--interactive", "--autosquash", baseBranch);
    }
    public void rebase(Path directory, String branchName) {
        validateBranchName(branchName);
        if (!branches(directory).contains(branchName)) {
            throw new IllegalArgumentException("Unknown local branch: " + branchName);
        }
        git.run(findRepository(directory), "rebase", branchName);
    }
    public void continueRebase(Path directory) {
        resumeRebase(directory, "--continue");
    }

    private void resumeRebase(Path directory, String operation) {
        Path root = findRepository(directory);
        Path editor = gitDirectory(root).resolve("git-client-rebase-editor.java");
        Path messages = gitDirectory(root).resolve("git-client-rebase-messages.map");
        String command = java.nio.file.Files.isRegularFile(editor)
                && java.nio.file.Files.isRegularFile(messages)
                ? "java \"" + editor.toString().replace('\\', '/')
                        + "\" message \"" + messages.toString().replace('\\', '/') + "\""
                : "true";
        try {
            git.run(root, "-c", "core.editor=" + command, "rebase", operation);
        } finally {
            if (!rebaseInProgress(root)) cleanupInteractiveRebaseFiles(root);
        }
    }
    private Path gitDirectory(Path root) {
        return Path.of(new String(git.run(root, "rev-parse",
                "--absolute-git-dir"), StandardCharsets.UTF_8).strip());
    }

    private void cleanupInteractiveRebaseFiles(Path root) {
        Path directory = gitDirectory(root);
        for (String name : List.of("git-client-rebase-editor.java",
                "git-client-rebase-plan.todo", "git-client-rebase-messages.map")) {
            try {
                java.nio.file.Files.deleteIfExists(directory.resolve(name));
            } catch (java.io.IOException ignored) {
                directory.resolve(name).toFile().deleteOnExit();
            }
        }
    }

    public boolean rebaseInProgress(Path directory) {
        Path root = findRepository(directory);
        Path gitDirectory = gitDirectory(root);
        return java.nio.file.Files.isDirectory(gitDirectory.resolve("rebase-merge"))
                || java.nio.file.Files.isDirectory(gitDirectory.resolve("rebase-apply"));
    }

    /** Describe an in-progress rebase without modifying its state. */
    public String rebaseProgress(Path directory) {
        Path root = findRepository(directory);
        Path gitDirectory = Path.of(new String(git.run(root, "rev-parse",
                "--absolute-git-dir"), StandardCharsets.UTF_8).strip());
        Path merge = gitDirectory.resolve("rebase-merge");
        Path apply = gitDirectory.resolve("rebase-apply");
        Path state = java.nio.file.Files.isDirectory(merge) ? merge : apply;
        if (!java.nio.file.Files.isDirectory(state)) return "No rebase in progress.";
        try {
            String current = java.nio.file.Files.exists(state.resolve("msgnum"))
                    ? java.nio.file.Files.readString(state.resolve("msgnum")).strip()
                    : "?";
            String total = java.nio.file.Files.exists(state.resolve("end"))
                    ? java.nio.file.Files.readString(state.resolve("end")).strip()
                    : "?";
            String branch = java.nio.file.Files.exists(state.resolve("head-name"))
                    ? java.nio.file.Files.readString(state.resolve("head-name")).strip()
                    : "current branch";
            List<String> conflicts = conflictedFiles(root);
            return "Rebasing " + branch + " — step " + current + " of " + total
                    + "\n" + (conflicts.isEmpty()
                    ? "No unresolved conflicts."
                    : "Unresolved conflicts:\n" + String.join("\n", conflicts))
                    + "\nResolve and stage conflicts, then Continue Operation. "
                    + "Alternatively Skip Rebase Commit or Abort Operation.";
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to inspect rebase progress", ex);
        }
    }

    public void skipRebase(Path directory) {
        resumeRebase(directory, "--skip");
    }
    public void abortRebase(Path directory) {
        Path root = findRepository(directory);
        git.run(root, "rebase", "--abort");
        cleanupInteractiveRebaseFiles(root);
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
    public String commitGraph(Path directory, int count) {
        if (count <= 0 || history(directory, 1).isEmpty()) return "";
        return new String(git.run(findRepository(directory), "log", "--all",
                "--graph", "--decorate", "--format=%H %s %d", "--color=never",
                "-n", Integer.toString(Math.min(count, 1000))),
                StandardCharsets.UTF_8);
    }
    public List<CommitInfo> history(Path directory, int count) {
        return historyPage(directory, 0, count);
    }

    /** Load a bounded window of commit history without materializing the full log. */
    public List<CommitInfo> historyPage(Path directory, int offset, int count) {
        if (offset < 0) throw new IllegalArgumentException("History offset must be nonnegative");
        if (count <= 0) return List.of();
        if (count > 1000) throw new IllegalArgumentException("History page size exceeds 1000");
        String status = new String(git.run(directory, "status", "--porcelain=v2", "--branch", "-z"), StandardCharsets.UTF_8);
        if (status.startsWith("# branch.oid (initial)\u0000") || status.contains("\u0000# branch.oid (initial)\u0000")) return List.of();
        byte[] data = git.run(directory, "log", "--skip=" + offset, "-n", Integer.toString(count), "--format=%H%x1f%s%x1f%an%x1f%cI%x1e");
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
