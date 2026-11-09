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
