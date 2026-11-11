package com.steeplesoft.kiboko.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Holds one OpenPGP passphrase for a GUI run and seeds the configured GnuPG agent cache. */
public final class OpenPgpSigningSession implements AutoCloseable {
    private final Supplier<char[]> passphrasePrompt;
    private final CommandRunner commandRunner;
    private final Map<String, String> seededKeyGrips = new LinkedHashMap<>();
    private char[] passphrase;
    private boolean closed;

    /** Creates a session using system Git and GnuPG tools. */
    public OpenPgpSigningSession(Supplier<char[]> passphrasePrompt) {
        this(passphrasePrompt, new SubprocessCommandRunner());
    }

    /** Creates a session with an injectable command runner for focused tests. */
    OpenPgpSigningSession(Supplier<char[]> passphrasePrompt, CommandRunner commandRunner) {
        this.passphrasePrompt = passphrasePrompt;
        this.commandRunner = commandRunner;
    }

    /** Prompts when needed and presets the signing key cache for an OpenPGP operation. */
    public synchronized void prepare(Path repository) {
        ensureOpen();
        String format = config(repository, "gpg.format");
        if (format != null && !format.equalsIgnoreCase("openpgp")) return;

        List<String> keyGrips = signingKeyGrips(repository);
        if (passphrase == null) {
            char[] entered = passphrasePrompt.get();
            if (entered == null) throw new CancellationException("OpenPGP signing cancelled");
            passphrase = entered.clone();
            Arrays.fill(entered, '\0');
        }

        for (String keyGrip : keyGrips) {
            if (seededKeyGrips.containsKey(keyGrip)) continue;
            try {
                seededKeyGrips.put(keyGrip, presetPassphrase(repository, keyGrip));
            } catch (RuntimeException ex) {
                clearPassphrase();
                throw ex;
            }
        }
    }

    /** Prepares OpenPGP only when the requested Git operation will sign an object. */
    synchronized void prepareIfRequested(Path repository, String... gitArguments) {
        ensureOpen();
        if (signingRequested(repository, gitArguments)) prepare(repository);
    }

    /** Checks explicit signing flags and effective commit or tag signing configuration. */
    private boolean signingRequested(Path repository, String[] args) {
        if (args.length == 0) return false;
        String operation = args[0];
        boolean commit = operation.equals("commit");
        boolean tag = operation.equals("tag");
        if (!commit && !tag) return false;

        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--")) break;
            if (argument.equals("-m") || argument.equals("--message")
                    || argument.equals("-F") || argument.equals("--file")) {
                index++;
                continue;
            }
            if ((commit && argument.equals("--no-gpg-sign")) || (tag && argument.equals("--no-sign"))) {
                return false;
            }
        }
        if (hasSigningFlag(args, commit)) return true;
        if (commit) return configEnabled(repository, "commit.gpgSign");
        if (configEnabled(repository, "tag.gpgSign")) return true;
        return hasAnnotationFlag(args) && configEnabled(repository, "tag.forceSignAnnotated");
    }

    /** Recognizes Git's signing flags, including flags with an attached key selector. */
    private static boolean hasSigningFlag(String[] args, boolean commit) {
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--")) return false;
            if (argument.equals("-m") || argument.equals("--message")
                    || argument.equals("-F") || argument.equals("--file")) {
                index++;
                continue;
            }
            if (argument.equals("-S") || argument.startsWith("-S") && argument.length() > 2
                    || argument.equals("--gpg-sign") || argument.startsWith("--gpg-sign=")) return true;
            if (!commit && (argument.equals("-s") || argument.equals("--sign")
                    || argument.equals("-u") || argument.startsWith("-u") && argument.length() > 2
                    || argument.equals("--local-user") || argument.startsWith("--local-user="))) return true;
        }
        return false;
    }

    /** Recognizes tag options that create an annotated tag. */
    private static boolean hasAnnotationFlag(String[] args) {
        for (String argument : args) {
            if (argument.equals("-a") || argument.equals("--annotate")) return true;
        }
        return false;
    }

    /** Reads a Git boolean setting and treats an unset value as disabled. */
    private boolean configEnabled(Path repository, String key) {
        CommandResult result = commandRunner.run(repository,
                List.of("git", "config", "--bool", "--get", key), null);
        if (result.exitCode() == 1) return false;
        if (result.exitCode() != 0) {
            throw new GitException("Unable to read Git signing configuration: " + key);
        }
        return result.stdout().strip().equalsIgnoreCase("true");
    }

    /** Forgets every cache entry seeded by this session and erases its in-memory passphrase. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        boolean failed = false;
        for (Map.Entry<String, String> entry : seededKeyGrips.entrySet()) {
            try {
                CommandResult result = commandRunner.run(null,
                        List.of(entry.getValue(), "--forget", entry.getKey()), null);
                failed |= result.exitCode() != 0;
            } catch (RuntimeException ex) {
                failed = true;
            }
        }
        seededKeyGrips.clear();
        clearPassphrase();
        if (failed) throw new GitException("Unable to clear an OpenPGP agent cache entry");
    }

    /** Resolves the configured OpenPGP program and its usable signing key grips. */
    private List<String> signingKeyGrips(Path repository) {
        String program = config(repository, "gpg.openpgp.program");
        if (program == null || program.isBlank()) program = config(repository, "gpg.program");
        if (program == null || program.isBlank()) program = "gpg";
        String selector = config(repository, "user.signingKey");
        if (selector == null || selector.isBlank()) selector = config(repository, "user.email");
        if (selector == null || selector.isBlank()) {
            throw new GitException("Set user.signingKey or user.email before OpenPGP signing");
        }

        CommandResult result = commandRunner.run(repository,
                List.of(program, "--with-colons", "--with-keygrip", "--list-secret-keys", selector), null);
        if (result.exitCode() != 0) {
            throw new GitException("Unable to find the configured OpenPGP signing key: "
                    + result.stderr().strip());
        }
        return parseSigningKeyGrips(result.stdout());
    }

    /** Selects key grips attached to secret keys with signing capability. */
    private List<String> parseSigningKeyGrips(String output) {
        Set<String> keyGrips = new LinkedHashSet<>();
        boolean signingKey = false;
        for (String line : output.lines().toList()) {
            String[] fields = line.split(":", -1);
            if (fields[0].equals("sec") || fields[0].equals("ssb")) {
                signingKey = fields.length > 11
                        && (fields[11].contains("s") || fields[11].contains("S"));
            } else if (fields[0].equals("grp") && signingKey && fields.length > 9
                    && fields[9].matches("(?i)[0-9a-f]{40}")) {
                keyGrips.add(fields[9]);
            }
        }
        if (keyGrips.isEmpty()) {
            throw new GitException("The configured OpenPGP key has no usable signing key");
        }
        return List.copyOf(keyGrips);
    }

    /** Seeds one key grip through GnuPG's helper, passing the secret only on stdin. */
    private String presetPassphrase(Path repository, String keyGrip) {
        CommandResult libexec = commandRunner.run(repository,
                List.of("gpgconf", "--list-dirs", "libexecdir"), null);
        String directory = libexec.stdout().strip();
        if (libexec.exitCode() != 0 || directory.isEmpty()) {
            throw new GitException("Unable to locate the GnuPG agent helper");
        }
        String helper = Path.of(directory, isWindows() ? "gpg-preset-passphrase.exe"
                : "gpg-preset-passphrase").toString();
        CommandResult result = commandRunner.run(repository,
                List.of(helper, "--preset", keyGrip), passphrase);
        if (result.exitCode() != 0) {
            throw new GitException("Unable to cache the OpenPGP passphrase; enable "
                    + "allow-preset-passphrase in gpg-agent.conf and restart GnuPG");
        }
        return helper;
    }

    /** Reads an effective Git setting, returning null when it is unset. */
    private String config(Path repository, String key) {
        CommandResult result = commandRunner.run(repository,
                List.of("git", "config", "--get", key), null);
        if (result.exitCode() == 1) return null;
        if (result.exitCode() != 0) {
            throw new GitException("Unable to read Git signing configuration: " + key);
        }
        return result.stdout().strip();
    }

    /** Rejects use after the app has ended this signing session. */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("OpenPGP signing session is closed");
    }

    /** Overwrites the cached passphrase before releasing it. */
    private void clearPassphrase() {
        if (passphrase != null) {
            Arrays.fill(passphrase, '\0');
            passphrase = null;
        }
    }

    /** Selects the helper executable suffix used by Windows GnuPG distributions. */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Executes GnuPG and Git commands while keeping passphrases off process arguments. */
    interface CommandRunner {
        /** Runs a command in a repository and writes optional passphrase input to stdin. */
        CommandResult run(Path directory, List<String> command, char[] standardInput);
    }

    /** Captures output needed to resolve keys and report tool failures. */
    /** Carries a helper command's exit status and captured text output. */
    record CommandResult(int exitCode, String stdout, String stderr) { }

    /** Runs helper processes without copying a passphrase into an argument or environment. */
    private static final class SubprocessCommandRunner implements CommandRunner {
        /** Starts the requested process and captures its output and exit status. */
        @Override public CommandResult run(Path directory, List<String> command, char[] standardInput) {
            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (directory != null) builder.directory(directory.toFile());
                process = builder.start();
            } catch (IOException ex) {
                return new CommandResult(-1, "", ex.getMessage());
            }
            try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<byte[]> stdout = io.submit(() -> process.getInputStream().readAllBytes());
                Future<byte[]> stderr = io.submit(() -> process.getErrorStream().readAllBytes());
                writeInput(process, standardInput);
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    return new CommandResult(-1, "", "GnuPG helper timed out");
                }
                return new CommandResult(process.exitValue(), decode(stdout.get()), decode(stderr.get()));
            } catch (InterruptedException ex) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new GitException("GnuPG helper interrupted", ex);
            } catch (ExecutionException | IOException ex) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new GitException("Unable to run GnuPG helper", ex);
            }
        }

        /** Writes and erases a temporary UTF-8 byte buffer for the helper's stdin. */
        private static void writeInput(Process process, char[] standardInput) throws IOException {
            try (OutputStream output = process.getOutputStream()) {
                if (standardInput == null) return;
                ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(standardInput));
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                try {
                    output.write(bytes);
                    output.write('\n');
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                    if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
                }
            }
        }

        /** Decodes captured Git or GnuPG text output. */
        private static String decode(byte[] output) {
            return new String(output, StandardCharsets.UTF_8);
        }
    }
}
