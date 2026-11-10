package com.steeplesoft.kiboko.cli;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class GitStatusParserTest {
    @Test void parsesBranchAndUntrackedFile() {
        var result = new GitStatusParser().parse(("# branch.oid abc\0# branch.head master\0? a file.txt\0").getBytes(StandardCharsets.UTF_8));
        assertEquals("master", result.branch());
        assertEquals(1, result.files().size());
        assertEquals("a file.txt", result.files().getFirst().path());
    }
    @Test void parsesTrackedFile() {
        String record = "1 .M N... 100644 100644 100644 abc def src/a.txt\0";
        var result = new GitStatusParser().parse(record.getBytes(StandardCharsets.UTF_8));
        assertEquals("src/a.txt", result.files().getFirst().path());
        assertEquals(".", result.files().getFirst().indexStatus());
        assertEquals("M", result.files().getFirst().worktreeStatus());
    }
    @Test void parsesRenameAndPreservesUnusualPaths() {
        String renamed = "2 R. N... 100644 100644 100644 abc def R100 new name\nwith tab\t.txt\0"
                + "old name.txt\0"
                + "? Unicode-雪.txt\0";
        var result = new GitStatusParser().parse(renamed.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, result.files().size());
        assertEquals("new name\nwith tab\t.txt", result.files().get(0).path());
        assertEquals("R", result.files().get(0).indexStatus());
        assertEquals("Unicode-雪.txt", result.files().get(1).path());
    }
    @Test void rejectsRenameMissingOriginalPath() {
        String renamed = "2 R. N... 100644 100644 100644 abc def R100 new.txt\0";
        assertThrows(GitException.class,
                () -> new GitStatusParser().parse(renamed.getBytes(StandardCharsets.UTF_8)));
    }

}
