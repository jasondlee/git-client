package com.steeplesoft.kiboko.cli;

import com.steeplesoft.kiboko.model.ChangedFile;
import com.steeplesoft.kiboko.model.RepositoryStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Git status --porcelain=v2 --branch -z.
 * The -z format uses NUL delimiters, including an additional original-path
 * record following each rename/copy entry. Paths are never shell-quoted.
 */
public final class GitStatusParser {
    public RepositoryStatus parse(byte[] bytes) {
        String[] records = new String(bytes, StandardCharsets.UTF_8).split("\u0000", -1);
        String branch = "(unknown)";
        List<ChangedFile> files = new ArrayList<>();

        for (int i = 0; i < records.length; i++) {
            String record = records[i];
            if (record.isEmpty()) continue;

            if (record.startsWith("# branch.head ")) {
                branch = record.substring("# branch.head ".length());
            } else if (record.startsWith("? ")) {
                files.add(new ChangedFile(record.substring(2), "?", "?"));
            } else if (record.startsWith("! ") || record.startsWith("# ")) {
                // Ignored paths and other branch headers are not file changes.
            } else if (record.startsWith("1 ") || record.startsWith("2 ") || record.startsWith("u ")) {
                char type = record.charAt(0);
                int fieldCount = type == '1' ? 9 : type == '2' ? 10 : 11;
                String[] fields = record.split(" ", fieldCount);
                if (fields.length != fieldCount || fields[1].length() != 2) {
                    throw new GitException("Malformed porcelain v2 status entry: " + type);
                }
                files.add(new ChangedFile(fields[fieldCount - 1],
                        fields[1].substring(0, 1), fields[1].substring(1, 2)));

                if (type == '2') {
                    if (++i >= records.length || records[i].isEmpty()) {
                        throw new GitException("Missing original path for rename/copy status entry");
                    }
                }
            } else {
                throw new GitException("Unknown porcelain v2 status entry");
            }
        }
        return new RepositoryStatus(branch, files);
    }
}
