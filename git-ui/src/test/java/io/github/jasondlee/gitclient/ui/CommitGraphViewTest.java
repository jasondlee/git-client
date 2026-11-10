package io.github.jasondlee.gitclient.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommitGraphViewTest {
    @Test void parsesCommitWithFullHashAndDecorations() {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        var line = CommitGraphView.GraphLine.parse("* " + sha + " Implement feature (HEAD -> master)");
        assertEquals("* ", line.lanes());
        assertEquals(sha, line.hash());
        assertTrue(line.description().contains("Implement feature"));
        assertTrue(line.description().contains("master"));
    }

    @Test void preservesMergeAndConnectorLines() {
        var merge = CommitGraphView.GraphLine.parse("|\\  0123456789abcdef0123456789abcdef01234567 Merge topic");
        assertEquals("|\\  ", merge.lanes());
        assertNotNull(merge.hash());
        var connector = CommitGraphView.GraphLine.parse("|/ ");
        assertEquals("|/ ", connector.lanes());
        assertNull(connector.hash());
        assertEquals("", connector.description());
    }

    @Test void ignoresNonCommitRowsAndShortHexFragments() {
        assertNull(CommitGraphView.GraphLine.parse("* 123abcd short hash").hash());
        assertNull(CommitGraphView.GraphLine.parse("| ").hash());
        assertNull(CommitGraphView.GraphLine.parse("").hash());
    }

    @Test void supportsSha256CommitIds() {
        String sha = "a".repeat(64);
        assertEquals(sha, CommitGraphView.GraphLine.parse("* " + sha + " sha256").hash());
    }

    @Test void doesNotMistakeHashWithinLongerHexSequenceForCommit() {
        assertNull(CommitGraphView.GraphLine.parse("* " + "a".repeat(65)).hash());
    }
}
