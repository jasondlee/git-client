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
    @Test void cherryPicksAndRevertsCommit() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
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
