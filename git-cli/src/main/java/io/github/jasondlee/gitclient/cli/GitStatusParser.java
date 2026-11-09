package io.github.jasondlee.gitclient.cli;
import io.github.jasondlee.gitclient.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Minimal porcelain v2 -z parser. Rename/copy records consume the extra path token. */
public final class GitStatusParser {
    public RepositoryStatus parse(byte[] bytes) {
        String[] records = new String(bytes, StandardCharsets.UTF_8).split("\u0000", -1);
        String branch = "(unknown)";
        List<ChangedFile> files = new ArrayList<>();
        for (int i = 0; i < records.length; i++) {
            String record = records[i];
            if (record.startsWith("# branch.head ")) branch = record.substring("# branch.head ".length());
            else if (record.startsWith("? ")) files.add(new ChangedFile(record.substring(2), "?", "?"));
            else if (record.startsWith("! ")) { /* ignored files are not shown */ }
            else if (record.startsWith("1 ") || record.startsWith("2 ") || record.startsWith("u ")) {
                int fields = record.charAt(0) == 'u' ? 11 : record.charAt(0) == '2' ? 10 : 9;
                String[] parts = record.split(" ", fields);
                if (parts.length == fields) {
                    String xy = parts[1];
                    files.add(new ChangedFile(parts[fields - 1], xy.substring(0, 1), xy.substring(1, 2)));
                }
                if (record.startsWith("2 ")) i++;
            }
        }
        return new RepositoryStatus(branch, files);
    }
}
