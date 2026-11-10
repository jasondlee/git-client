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
    private static Path canonicalPath(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
    @Test void interactiveRebaseRejectsFirstFixupAndDirtyTree() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("base.txt"), "base\n");
        git("add", ".");
        git("commit", "-m", "base");
        git("branch", "base");
        Files.writeString(directory.resolve("change.txt"), "change\n");
        git("add", ".");
        git("commit", "-m", "change");
        var service = new RepositoryService();
        String plan = service.interactiveRebasePlan(directory, "base");
        assertThrows(IllegalArgumentException.class,
                () -> service.interactiveRebase(directory, "base",
                        plan.replaceFirst("^pick ", "fixup ")));
        Files.writeString(directory.resolve("untracked.txt"), "dirty\n");
        assertThrows(IllegalStateException.class,
                () -> service.interactiveRebase(directory, "base", plan));
    }

    @Test void interactiveRebaseSquashesCommits() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("base.txt"), "base\n");
        git("add", ".");
        git("commit", "-m", "base");
        git("branch", "base");
        Files.writeString(directory.resolve("first.txt"), "first\n");
        git("add", ".");
        git("commit", "-m", "first");
        Files.writeString(directory.resolve("second.txt"), "second\n");
        git("add", ".");
        git("commit", "-m", "second");
        var service = new RepositoryService();
        var lines = service.interactiveRebasePlan(directory, "base").lines().toList();
        service.interactiveRebase(directory, "base",
                lines.get(0) + "\n" + lines.get(1).replaceFirst("^pick ", "fixup ") + "\n");
        assertEquals(1, service.interactiveRebasePlan(directory, "base").lines().count());
        assertTrue(Files.exists(directory.resolve("first.txt")));
        assertTrue(Files.exists(directory.resolve("second.txt")));
    }

    @Test void interactiveRebaseCanReorderAndDropCommits() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("base.txt"), "base\n");
        git("add", ".");
        git("commit", "-m", "base");
        git("branch", "base");
        Files.writeString(directory.resolve("first.txt"), "first\n");
        git("add", ".");
        git("commit", "-m", "first");
        Files.writeString(directory.resolve("second.txt"), "second\n");
        git("add", ".");
        git("commit", "-m", "second");
        var service = new RepositoryService();
        String original = service.interactiveRebasePlan(directory, "base");
        var lines = original.lines().toList();
        assertEquals(2, lines.size());
        assertThrows(IllegalArgumentException.class,
                () -> service.interactiveRebase(directory, "base", lines.get(0)));
        String plan = lines.get(1) + "\n"
                + lines.get(0).replaceFirst("^pick ", "drop ") + "\n";
        service.interactiveRebase(directory, "base", plan);
        assertFalse(Files.exists(directory.resolve("first.txt")));
        assertTrue(Files.exists(directory.resolve("second.txt")));
        assertEquals(1, service.interactiveRebasePlan(directory, "base").lines().count());
    }

    @Test void selectsIndividualLinesWithinAHunk() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("lines.txt"), "one\ntwo\nthree\nfour\n");
        git("add", "lines.txt");
        git("commit", "-m", "initial");
        Files.writeString(directory.resolve("lines.txt"), "one\nTWO\nthree\nFOUR\n");
        var service = new RepositoryService();
        String patch = service.diffHunks(directory, "lines.txt", false).get(0);
        String selected = service.selectDiffLines(patch, java.util.Set.of(0, 1));
        service.applyIndexHunk(directory, selected, false);
        String staged = service.diff(directory, "lines.txt", true);
        assertTrue(staged.contains("+TWO"));
        assertFalse(staged.contains("+FOUR"));
        assertThrows(IllegalArgumentException.class,
                () -> service.selectDiffLines(patch, java.util.Set.of(100)));
    }
    @Test void previewsAndCleansOnlyUntrackedFiles() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "keep\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        Files.writeString(directory.resolve("untracked.txt"), "delete\n");
        Files.writeString(directory.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(directory.resolve("ignored.txt"), "ignore\n");
        var service = new RepositoryService();
        assertTrue(service.cleanPreview(directory, false).stream()
                .anyMatch(path -> path.contains("untracked.txt")));
        service.cleanUntracked(directory, false);
        assertFalse(Files.exists(directory.resolve("untracked.txt")));
        assertTrue(Files.exists(directory.resolve("ignored.txt")));
        assertTrue(Files.exists(directory.resolve("tracked.txt")));
    }
    @Test void resolvesThreeWayMergeConflict() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "base\n");
        git("add", "tracked.txt");
        git("commit", "-m", "base");
        var service = new RepositoryService();
        service.createBranch(directory, "feature");
        Files.writeString(directory.resolve("tracked.txt"), "ours\n");
        git("commit", "-am", "ours");
        service.switchBranch(directory, "feature");
        Files.writeString(directory.resolve("tracked.txt"), "theirs\n");
        git("commit", "-am", "theirs");
        service.switchBranch(directory, "master");
        assertThrows(RuntimeException.class, () -> service.merge(directory, "feature"));
        assertEquals("base\n", service.conflictVersion(directory, "tracked.txt", 1));
        assertEquals("ours\n", service.conflictVersion(directory, "tracked.txt", 2));
        assertEquals("theirs\n", service.conflictVersion(directory, "tracked.txt", 3));
        assertThrows(IllegalArgumentException.class,
                () -> service.conflictVersion(directory, "tracked.txt", 4));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveConflict(directory, "../outside.txt", "invalid"));
        service.resolveConflict(directory, "tracked.txt", "resolved\n");
        assertFalse(service.conflictedFiles(directory).contains("tracked.txt"));
        assertEquals("resolved\n", Files.readString(directory.resolve("tracked.txt")));
    }
    @Test void showsDecoratedCommitGraph() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        String graph = service.commitGraph(directory, 10);
        assertTrue(graph.contains("initial"));
        assertTrue(graph.contains("*"));
        assertEquals("", service.commitGraph(directory, 0));
    }
    @Test void stagesAndUnstagesIndividualHunks() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        StringBuilder initial = new StringBuilder();
        for (int i = 0; i < 30; i++) initial.append("line ").append(i).append('\n');
        Files.writeString(directory.resolve("tracked.txt"), initial.toString());
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        String modified = initial.toString().replace("line 2\n", "changed 2\n")
                .replace("line 25\n", "changed 25\n");
        Files.writeString(directory.resolve("tracked.txt"), modified);
        var service = new RepositoryService();
        var hunks = service.diffHunks(directory, "tracked.txt", false);
        assertEquals(2, hunks.size());
        service.applyIndexHunk(directory, hunks.getFirst(), false);
        assertEquals(1, service.diffHunks(directory, "tracked.txt", true).size());
        assertEquals(1, service.diffHunks(directory, "tracked.txt", false).size());
        service.applyIndexHunk(directory,
                service.diffHunks(directory, "tracked.txt", true).getFirst(), true);
        assertTrue(service.diffHunks(directory, "tracked.txt", true).isEmpty());
        assertEquals(2, service.diffHunks(directory, "tracked.txt", false).size());
    }
    @Test void createsFixupAndAutosquashesIntoTargetCommit() throws Exception {
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
        Files.writeString(directory.resolve("feature.txt"), "original\n");
        git("add", "feature.txt");
        git("commit", "-m", "feature");
        String target = service.history(directory, 1).getFirst().id();
        Files.writeString(directory.resolve("feature.txt"), "updated\n");
        git("add", "feature.txt");
        service.createFixupCommit(directory, target);
        assertEquals(3, service.history(directory, 10).size());
        service.rebaseAutosquash(directory, "master");
        assertEquals(2, service.history(directory, 10).size());
        assertEquals("feature", service.history(directory, 1).getFirst().subject());
        assertEquals("updated\n", Files.readString(directory.resolve("feature.txt")));
    }
    @Test void startsAndResetsBisect() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        var service = new RepositoryService();
        for (int i = 0; i < 4; i++) {
            Files.writeString(directory.resolve("tracked.txt"), "version " + i);
            git("add", "tracked.txt");
            git("commit", "-m", "version " + i);
        }
        String bad = service.history(directory, 1).getFirst().id();
        String good = service.history(directory, 4).getLast().id();
        service.startBisect(directory, bad, good);
        assertTrue(service.isBisecting(directory));
        service.markBisect(directory, true);
        service.resetBisect(directory);
        assertFalse(service.isBisecting(directory));
        assertEquals(bad, service.history(directory, 1).getFirst().id());
        assertThrows(IllegalArgumentException.class,
                () -> service.startBisect(directory, "--bad", good));
    }
    @Test void createsLocalBranchTrackingRemote() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        service.addRemote(directory, "origin", directory.toString());
        git("fetch", "origin");
        assertTrue(service.remoteBranches(directory).contains("origin/master"));
        service.createTrackingBranch(directory, "tracking", "origin/master");
        assertTrue(service.branches(directory).contains("tracking"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTrackingBranch(directory, "bad", "origin/missing"));
    }
    @Test void amendsLatestCommitWithoutCreatingAnother() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "old message");
        var service = new RepositoryService();
        String oldId = service.history(directory, 1).getFirst().id();
        Files.writeString(directory.resolve("extra.txt"), "extra\n");
        service.stage(directory, java.util.List.of("extra.txt"));
        String newId = service.amendCommit(directory, "new message");
        assertNotEquals(oldId, newId);
        assertEquals(1, service.history(directory, 10).size());
        assertEquals("new message", service.history(directory, 1).getFirst().subject());
        assertTrue(Files.exists(directory.resolve("extra.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> service.amendCommit(directory, " "));
    }
    @Test void addsAndRemovesLinkedWorktrees() throws Exception {
        git("init", "-b", "master");
        git("config", "user.name", "Test Author");
        git("config", "user.email", "test@example.invalid");
        git("config", "core.autocrlf", "false");
        Files.writeString(directory.resolve("tracked.txt"), "initial\n");
        git("add", "tracked.txt");
        git("commit", "-m", "initial");
        var service = new RepositoryService();
        service.createBranch(directory, "feature");
        Path linked = directory.resolveSibling(directory.getFileName() + "-linked");
        try {
            service.addWorktree(directory, linked, "feature");
            assertTrue(Files.exists(linked.resolve("tracked.txt")));
            assertTrue(service.worktrees(directory).stream()
                    .anyMatch(path -> canonicalPath(Path.of(path)).equals(canonicalPath(linked))));
            assertThrows(IllegalArgumentException.class,
                    () -> service.removeWorktree(directory, directory));
            service.removeWorktree(directory, linked);
            assertFalse(Files.exists(linked));
        } finally {
            if (Files.exists(linked)) {
                git("worktree", "remove", "--force", linked.toString());
            }
        }
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
