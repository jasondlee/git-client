package io.github.jasondlee.gitclient.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class RepositoryServiceTest {
    @TempDir Path directory;
    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
    @Test void exportsAndAppliesCommitPatch() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("base.txt"), "base\n");
        git("add", "base.txt");
        git("commit", "-m", "base");
        Files.writeString(directory.resolve("added.txt"), "patch content\n");
        git("add", "added.txt");
        git("commit", "-m", "add file");
        var service = new RepositoryService();
        String id = service.history(directory, 1).getFirst().id();
        Path patchFile = directory.resolve("export.patch");
        Files.writeString(patchFile, service.createPatch(directory, id));
        assertTrue(Files.readString(patchFile).contains("patch content"));
        git("reset", "--hard", "HEAD~1");
        service.applyPatch(directory, patchFile);
        assertEquals("patch content\n", Files.readString(directory.resolve("added.txt")));
        assertFalse(service.gitConfiguration(directory).isEmpty());
    }
    @Test void searchesCommitsAndFileHistory() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "Initial unique search phrase");
        var service = new RepositoryService();
        assertEquals(1, service.searchCommits(directory, "unique search phrase", 10).size());
        assertEquals(1, service.fileHistory(directory, "tracked.txt", 10).size());
        assertTrue(service.searchCommits(directory, "no matching commit", 10).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.fileHistory(directory, "../outside", 10));
    }
    @Test void comparesLocalBranches() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        service.createBranch(directory, "feature");
        service.switchBranch(directory, "feature");
        Files.writeString(directory.resolve("tracked.txt"), "changed\n");
        git("add", "tracked.txt");
        git("commit", "-m", "change");
        String diff = service.compare(directory, "master", "feature");
        assertTrue(diff.contains("+changed"));
        assertThrows(IllegalArgumentException.class,
                () -> service.compare(directory, "--bad", "feature"));
    }
    @Test void discardsOnlyWorkingChangesAndAddsIgnorePatterns() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        Files.writeString(directory.resolve("tracked.txt"), "edited\n");
        var service = new RepositoryService();
        service.discardChanges(directory, java.util.List.of("tracked.txt"));
        assertEquals("initial\n", Files.readString(directory.resolve("tracked.txt")));
        service.ignorePattern(directory, "*.tmp");
        assertTrue(Files.readString(directory.resolve(".gitignore")).contains("*.tmp"));
        assertThrows(IllegalArgumentException.class,
                () -> service.discardChanges(directory, java.util.List.of("../outside")));
    }
    @Test void renamesBranchesAndCreatesAnnotatedTags() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        service.createBranch(directory, "old-name");
        service.renameBranch(directory, "old-name", "new-name");
        assertTrue(service.branches(directory).contains("new-name"));
        assertFalse(service.branches(directory).contains("old-name"));
        service.createAnnotatedTag(directory, "v1.0", "Release version one");
        assertTrue(service.tags(directory).contains("v1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createAnnotatedTag(directory, "v2.0", " "));
    }
    @Test void managesRemoteNamesAndUrls() throws Exception {
        git("init", "-b", "master");
        var service = new RepositoryService();
        service.addRemote(directory, "origin", directory.toString());
        assertEquals(java.util.List.of("origin"), service.remotes(directory));
        assertEquals(directory.toString(), service.remoteUrl(directory, "origin"));
        service.renameRemote(directory, "origin", "upstream");
        assertEquals(java.util.List.of("upstream"), service.remotes(directory));
        assertThrows(IllegalArgumentException.class,
                () -> service.renameRemote(directory, "missing", "other"));
        service.removeRemote(directory, "upstream");
        assertTrue(service.remotes(directory).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.addRemote(directory, "-invalid", directory.toString()));
    }
    @Test void initializesAndClonesLocalRepository() throws Exception {
        var service = new RepositoryService();
        Path source = Files.createDirectory(directory.resolve("source"));
        assertEquals(source.toRealPath(), service.initialize(source).toRealPath());
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("original.txt"), "content\n");
        git("add", "original.txt");
        git("commit", "-m", "original");
        Path clone = service.cloneRepository(directory, directory.toString(), "copy");
        assertTrue(Files.exists(clone.resolve("original.txt")));
        assertEquals(1, service.history(clone, 10).size());
        assertThrows(IllegalArgumentException.class,
                () -> service.cloneRepository(directory, directory.toString(), "../escape"));
    }
    @Test void detectsMergeConflictsAndAbortsSafely() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("conflict.txt"), "base\n");
        git("add", "conflict.txt");
        git("commit", "-m", "base");
        var service = new RepositoryService();
        service.createBranch(directory, "feature");
        Files.writeString(directory.resolve("conflict.txt"), "master change\n");
        git("add", "conflict.txt");
        git("commit", "-m", "master change");
        service.switchBranch(directory, "feature");
        Files.writeString(directory.resolve("conflict.txt"), "feature change\n");
        git("add", "conflict.txt");
        git("commit", "-m", "feature change");
        assertThrows(RuntimeException.class, () -> service.merge(directory, "master"));
        assertEquals("MERGE", service.operationState(directory));
        assertEquals(java.util.List.of("conflict.txt"), service.conflictedFiles(directory));
        assertThrows(IllegalStateException.class, () -> service.continueMerge(directory));
        service.abortMerge(directory);
        assertEquals("NONE", service.operationState(directory));
        assertTrue(service.conflictedFiles(directory).isEmpty());
        assertEquals("feature change\n", Files.readString(directory.resolve("conflict.txt")));
    }
    @Test void managesLocalConfigurationAndReset() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "first\n");
        git("add", "tracked.txt");
        git("commit", "-m", "first");
        var service = new RepositoryService();
        String first = service.history(directory, 1).getFirst().id();
        service.setConfiguration(directory, "user.name", "Another Author");
        assertEquals("Another Author", service.configuration(directory, "user.name"));
        Files.writeString(directory.resolve("tracked.txt"), "second\n");
        git("add", "tracked.txt");
        git("commit", "-m", "second");
        service.reset(directory, first, "hard");
        assertEquals("first\n", Files.readString(directory.resolve("tracked.txt")));
        assertEquals(first, service.history(directory, 1).getFirst().id());
        assertThrows(IllegalArgumentException.class,
                () -> service.reset(directory, first, "invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> service.setConfiguration(directory, "--bad", "x"));
    }
    @Test void exposesReflogBlameAndWorktrees() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        assertTrue(service.reflog(directory, 10).stream().anyMatch(s -> s.contains("initial")));
        assertTrue(service.blame(directory, "tracked.txt").contains("initial"));
        assertFalse(service.worktrees(directory).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.blame(directory, "../outside.txt"));
    }
    @Test void cherryPicksAndRevertsCommit() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("base.txt"), "base\n");
        git("add", "base.txt");
        git("commit", "-m", "base");
        var service = new RepositoryService();
        service.createBranch(directory, "feature");
        service.switchBranch(directory, "feature");
        Files.writeString(directory.resolve("feature.txt"), "feature\n");
        git("add", "feature.txt");
        git("commit", "-m", "feature");
        String id = service.history(directory, 1).getFirst().id();
        service.switchBranch(directory, "master");
        service.cherryPick(directory, id);
        assertTrue(Files.exists(directory.resolve("feature.txt")));
        service.revert(directory, service.history(directory, 1).getFirst().id());
        assertFalse(Files.exists(directory.resolve("feature.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> service.cherryPick(directory, "--bad"));
    }
    @Test void displaysCommitPatch() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("detail.txt"), "commit details\n");
        git("add", "detail.txt");
        git("commit", "-m", "show details");
        var service = new RepositoryService();
        String id = service.history(directory, 1).getFirst().id();
        String details = service.showCommit(directory, id);
        assertTrue(details.contains("show details"));
        assertTrue(details.contains("+commit details"));
        assertThrows(IllegalArgumentException.class, () -> service.showCommit(directory, "--all"));
    }
    @Test void mergesAndDeletesFeatureBranch() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("base.txt"), "base\n");
        git("add", "base.txt");
        git("commit", "-m", "base");
        var service = new RepositoryService();
        service.createBranch(directory, "feature");
        service.switchBranch(directory, "feature");
        Files.writeString(directory.resolve("feature.txt"), "feature\n");
        git("add", "feature.txt");
        git("commit", "-m", "feature");
        service.switchBranch(directory, "master");
        service.merge(directory, "feature");
        assertTrue(Files.exists(directory.resolve("feature.txt")));
        service.deleteBranch(directory, "feature");
        assertFalse(service.branches(directory).contains("feature"));
    }
    @Test void stashesAndRestoresWorkingChanges() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        Files.writeString(directory.resolve("tracked.txt"), "modified\n");
        Files.writeString(directory.resolve("new.txt"), "untracked\n");
        var service = new RepositoryService();
        service.stash(directory, "work in progress");
        assertTrue(service.status(directory).files().isEmpty());
        assertEquals(1, service.stashes(directory).size());
        service.applyStash(directory, "stash@{0}");
        assertEquals("modified\n", Files.readString(directory.resolve("tracked.txt")));
        assertTrue(Files.exists(directory.resolve("new.txt")));
        service.dropStash(directory, "stash@{0}");
        assertTrue(service.stashes(directory).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> service.dropStash(directory, "--bad"));
    }
    @Test void listsAndFetchesLocalRemotes() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        git("remote", "add", "origin", directory.toString());
        var service = new RepositoryService();
        assertEquals(java.util.List.of("origin"), service.remotes(directory));
        service.fetch(directory, "origin");
        assertThrows(IllegalArgumentException.class, () -> service.fetch(directory, "missing"));
    }
    @Test void createsAndDeletesLocalTags() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        service.createTag(directory, "v1.0");
        assertTrue(service.tags(directory).contains("v1.0"));
        service.deleteTag(directory, "v1.0");
        assertFalse(service.tags(directory).contains("v1.0"));
        assertThrows(IllegalArgumentException.class, () -> service.createTag(directory, "-bad"));
    }
    @Test void createsAndSwitchesLocalBranches() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        service.createBranch(directory, "feature/test");
        assertTrue(service.branches(directory).containsAll(java.util.List.of("master", "feature/test")));
        service.switchBranch(directory, "feature/test");
        assertEquals("feature/test", service.status(directory).branch());
        assertThrows(IllegalArgumentException.class, () -> service.createBranch(directory, "-bad"));
    }
    @Test void previewsStagedAndUnstagedDiffsIndependently() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("example.txt"), "original\n");
        git("add", "example.txt");
        git("commit", "-m", "initial");
        Files.writeString(directory.resolve("example.txt"), "staged version\n");
        var service = new RepositoryService();
        service.stage(directory, java.util.List.of("example.txt"));
        Files.writeString(directory.resolve("example.txt"), "working version\n");
        String staged = service.diff(directory, "example.txt", true);
        String unstaged = service.diff(directory, "example.txt", false);
        assertTrue(staged.contains("+staged version"), staged);
        assertFalse(staged.contains("+working version"), staged);
        assertTrue(unstaged.contains("+working version"), unstaged);
        assertTrue(unstaged.contains("-staged version"), unstaged);
        assertThrows(IllegalArgumentException.class,
                () -> service.diff(directory, "../outside.txt", false));
        Files.writeString(directory.resolve("new file.txt"), "untracked\n");
        assertTrue(service.diff(directory, "new file.txt", false).contains("Untracked file"));
    }
    @Test void stagesAndUnstagesSelectedPaths() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        Files.writeString(directory.resolve("tracked.txt"), "modified\n");
        Files.writeString(directory.resolve("another.txt"), "untracked\n");
        var service = new RepositoryService();
        service.stage(directory, java.util.List.of("tracked.txt"));
        var staged = service.status(directory);
        assertEquals("M", staged.files().stream().filter(f -> f.path().equals("tracked.txt"))
                .findFirst().orElseThrow().indexStatus());
        assertEquals("?", staged.files().stream().filter(f -> f.path().equals("another.txt"))
                .findFirst().orElseThrow().indexStatus());
        service.unstage(directory, java.util.List.of("tracked.txt"));
        var unstaged = service.status(directory);
        assertEquals(".", unstaged.files().stream().filter(f -> f.path().equals("tracked.txt"))
                .findFirst().orElseThrow().indexStatus());
        assertEquals("M", unstaged.files().stream().filter(f -> f.path().equals("tracked.txt"))
                .findFirst().orElseThrow().worktreeStatus());
    }
    @Test void commitsOnlyStagedChanges() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("staged.txt"), "staged\n");
        Files.writeString(directory.resolve("unstaged.txt"), "unstaged\n");
        var service = new RepositoryService();
        assertThrows(IllegalArgumentException.class, () -> service.commit(directory, "  "));
        assertThrows(IllegalStateException.class, () -> service.commit(directory, "nothing staged"));
        service.stage(directory, java.util.List.of("staged.txt"));
        String commitId = service.commit(directory, "first commit");
        assertEquals(40, commitId.length());
        assertEquals("first commit", service.history(directory, 1).getFirst().subject());
        var changes = service.status(directory).files();
        assertEquals(1, changes.size());
        assertEquals("unstaged.txt", changes.getFirst().path());
        assertThrows(IllegalStateException.class, () -> service.commit(directory, "again"));
    }
    @Test void emptyRepositoryHasNoHistory() throws Exception {
        git("init", "-b", "master");
        var service = new RepositoryService();
        assertEquals("master", service.status(directory).branch());
        assertTrue(service.history(directory, 100).isEmpty());
    }
    @Test void readsRealRepositoryStatusAndHistory() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial commit");
        Files.writeString(directory.resolve("tracked.txt"), "changed\n");
        Files.writeString(directory.resolve("new file.txt"), "new\n");
        var service = new RepositoryService();
        assertEquals(directory.toRealPath(), service.findRepository(directory).toRealPath());
        var status = service.status(directory);
        assertEquals("master", status.branch());
        assertEquals(2, status.files().size());
        assertTrue(status.files().stream().anyMatch(f -> f.path().equals("tracked.txt")));
        assertTrue(status.files().stream().anyMatch(f -> f.path().equals("new file.txt")));
        var history = service.history(directory, 10);
        assertEquals(1, history.size());
        assertEquals("initial commit", history.getFirst().subject());
        assertEquals("Test Author", history.getFirst().author());
    }
}
