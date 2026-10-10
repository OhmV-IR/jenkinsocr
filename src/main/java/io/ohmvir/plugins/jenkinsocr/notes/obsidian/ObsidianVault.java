package io.ohmvir.plugins.jenkinsocr.notes.obsidian;

import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.MasterToSlaveFileCallable;

/**
 * File operations on an Obsidian vault, which is a plain folder of Markdown files. The operations are
 * {@link MasterToSlaveFileCallable}s so that they run on whichever Jenkins node holds the vault.
 */
final class ObsidianVault {
    /** Characters Obsidian (or Windows, macOS, Linux) does not allow in file names, or that break wiki links. */
    private static final Pattern FORBIDDEN_CHARACTERS = Pattern.compile("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]");

    private static final Pattern WINDOWS_RESERVED_NAMES =
            Pattern.compile("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");
    private static final int MAX_NAME_BYTES = 200;
    private static final int MAX_FOLDER_DEPTH = 10;
    private static final String NOTE_EXTENSION = ".md";

    private ObsidianVault() {}

    /**
     * Turns a folder or note title into a safe file name: characters that are not allowed are replaced by spaces,
     * hidden-file dots and trailing dots are removed, and the name is shortened to a length every file system
     * accepts. The result can never be {@code .}, {@code ..} or contain a path separator.
     */
    static String fileName(String title) {
        String name = FORBIDDEN_CHARACTERS.matcher(title).replaceAll(" ");
        name = name.replaceAll("\\s+", " ").strip();
        name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (WINDOWS_RESERVED_NAMES.matcher(name).matches()) {
            name = name + "_";
        }
        name = truncateToBytes(name, MAX_NAME_BYTES).strip();
        return name.isEmpty() ? "Untitled" : name;
    }

    private static String truncateToBytes(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            result.append(character);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }

    /**
     * The last element of a path, or an empty string for a file system root.
     */
    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static boolean isHidden(Path path) {
        return name(path).startsWith(".");
    }

    private static Path requireVault(File vault) throws IOException {
        Path root = vault.toPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("The Obsidian vault folder '" + vault + "' does not exist or is not a folder");
        }
        return root;
    }

    /**
     * Finds the entry of {@code folder} whose name equals {@code name} ignoring case, so that notes and folders
     * are reused rather than duplicated on case-sensitive file systems.
     */
    private static Optional<Path> findIgnoringCase(Path folder, String name, boolean directory) throws IOException {
        Path exact = folder.resolve(name);
        if (directory ? Files.isDirectory(exact) : Files.isRegularFile(exact)) {
            return Optional.of(exact);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                boolean matchesType = directory ? Files.isDirectory(entry) : Files.isRegularFile(entry);
                if (matchesType && name(entry).equalsIgnoreCase(name)) {
                    return Optional.of(entry);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Lists the vault's folders as {@code /}-separated paths, skipping hidden folders such as {@code .obsidian}.
     */
    static final class ListFolders extends MasterToSlaveFileCallable<List<String>> {
        private static final long serialVersionUID = 1L;

        @Override
        public List<String> invoke(File vault, VirtualChannel channel) throws IOException {
            Path root = requireVault(vault);
            List<String> paths = new ArrayList<>();
            collect(root, "", 0, paths);
            return paths;
        }

        private static void collect(Path folder, String prefix, int depth, List<String> paths) throws IOException {
            if (depth >= MAX_FOLDER_DEPTH) {
                return;
            }
            List<Path> children;
            try (Stream<Path> entries = Files.list(folder)) {
                children = entries.filter(Files::isDirectory)
                        .filter(entry -> !isHidden(entry))
                        .sorted((a, b) -> name(a).compareToIgnoreCase(name(b)))
                        .toList();
            }
            for (Path child : children) {
                String path = prefix + name(child);
                paths.add(path);
                collect(child, path + "/", depth + 1, paths);
            }
        }
    }

    /**
     * The outcome of {@link WriteNote}.
     *
     * @param path     the absolute path of the note file
     * @param appended {@code true} if the content was appended to an existing note
     */
    record WriteResult(String path, boolean appended) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Creates the note's folders and file, or appends to the note with the same name.
     */
    static final class WriteNote extends MasterToSlaveFileCallable<WriteResult> {
        private static final long serialVersionUID = 1L;

        private final List<String> folders;
        private final String title;
        private final String markdown;

        /**
         * @param folders  the folder names of the note's path; sanitised with {@link #fileName}
         * @param title    the note's title; sanitised with {@link #fileName}
         * @param markdown the note's content
         */
        WriteNote(List<String> folders, String title, String markdown) {
            this.folders = new ArrayList<>(folders);
            this.title = title;
            this.markdown = markdown;
        }

        @Override
        public WriteResult invoke(File vault, VirtualChannel channel) throws IOException {
            Path root = requireVault(vault).toAbsolutePath().normalize();
            Path folder = root;
            for (String name : folders) {
                String safeName = fileName(name);
                Optional<Path> existing = findIgnoringCase(folder, safeName, true);
                folder = existing.isPresent() ? existing.get() : Files.createDirectories(folder.resolve(safeName));
            }

            String safeTitle = fileName(title);
            Optional<Path> existing = findIgnoringCase(folder, safeTitle + NOTE_EXTENSION, false);
            Path note = existing.orElse(folder.resolve(safeTitle + NOTE_EXTENSION));
            if (!note.toAbsolutePath().normalize().startsWith(root)) {
                throw new IOException("Refusing to write the note outside of the vault: " + note);
            }
            if (existing.isPresent()) {
                String current = Files.readString(note, StandardCharsets.UTF_8);
                String separator = current.isEmpty() ? "" : current.endsWith("\n") ? "\n---\n\n" : "\n\n---\n\n";
                Files.writeString(note, separator + markdown + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } else {
                StringBuilder content = new StringBuilder();
                if (!safeTitle.equals(title.strip())) {
                    // Keep the original title searchable and linkable when it could not be used as the file name.
                    content.append("---\naliases:\n  - ")
                            .append(yamlString(title.strip()))
                            .append("\n---\n\n");
                }
                content.append(markdown).append('\n');
                Files.writeString(note, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            return new WriteResult(note.toString(), existing.isPresent());
        }

        private static String yamlString(String value) {
            StringBuilder quoted = new StringBuilder("\"");
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '"' -> quoted.append("\\\"");
                    case '\\' -> quoted.append("\\\\");
                    default -> {
                        if (Character.isISOControl(c)) {
                            quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            quoted.append(c);
                        }
                    }
                }
            }
            return quoted.append('"').toString();
        }
    }
}
