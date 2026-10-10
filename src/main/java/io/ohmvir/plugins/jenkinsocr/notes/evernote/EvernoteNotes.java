package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import io.ohmvir.plugins.jenkinsocr.notes.NoteUploadResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Maps note folders onto Evernote's two-level hierarchy and stores notes.
 *
 * <p>Evernote has notebooks, optionally grouped in stacks, rather than nested folders. A one-segment path such as
 * {@code "Biology"} is a notebook (or, if a stack with that name exists, a notebook of the same name in that
 * stack); a longer path such as {@code "Math/Linear Algebra"} is the notebook {@code "Linear Algebra"} in the
 * stack {@code "Math"}, further segments being kept in the notebook's name ({@code "Math/Algebra/Groups"} is the
 * notebook {@code "Algebra/Groups"}). Notebook names are unique in an Evernote account regardless of case, so an
 * existing notebook with the wanted name is reused wherever it is. An empty path is the default notebook.
 */
final class EvernoteNotes {
    static final int MAX_NOTEBOOK_NAME_LENGTH = 100;
    static final int MAX_TITLE_LENGTH = 255;

    /** Characters Evernote does not allow in names: control characters and line or paragraph separators. */
    private static final Pattern INVALID_NAME_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    private EvernoteNotes() {}

    /**
     * Makes a notebook, stack or note name valid for Evernote: no control characters, no surrounding whitespace,
     * and at most {@code maxLength} characters.
     */
    static String name(String value, int maxLength) {
        String name =
                INVALID_NAME_CHARACTERS.matcher(value).replaceAll(" ").strip().replaceAll("\\s+", " ");
        if (name.length() > maxLength) {
            int end = maxLength;
            if (Character.isHighSurrogate(name.charAt(end - 1))) {
                end--;
            }
            name = name.substring(0, end).strip();
        }
        return name.isEmpty() ? "Untitled" : name;
    }

    /**
     * The folder paths of the notebooks: each stack, followed by its notebooks, then the notebooks without a stack.
     */
    static List<String> folderPaths(List<EvernoteNoteStore.Notebook> notebooks) {
        List<EvernoteNoteStore.Notebook> sorted = new ArrayList<>(notebooks);
        sorted.sort(Comparator.comparing((EvernoteNoteStore.Notebook n) -> n.stack() == null)
                .thenComparing(n -> n.stack() == null ? "" : n.stack(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EvernoteNoteStore.Notebook::name, String.CASE_INSENSITIVE_ORDER));
        Set<String> paths = new LinkedHashSet<>();
        for (EvernoteNoteStore.Notebook notebook : sorted) {
            if (notebook.stack() == null) {
                paths.add(notebook.name());
            } else {
                paths.add(notebook.stack());
                paths.add(notebook.stack() + "/" + notebook.name());
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * Finds or creates the notebook for a folder path.
     */
    static EvernoteNoteStore.Notebook notebookFor(EvernoteNoteStore store, List<String> path, Consumer<String> log)
            throws IOException, InterruptedException {
        List<EvernoteNoteStore.Notebook> notebooks = store.listNotebooks();
        if (path.isEmpty()) {
            return notebooks.stream()
                    .filter(EvernoteNoteStore.Notebook::defaultNotebook)
                    .findFirst()
                    .orElseThrow(() -> new IOException("The Evernote account has no default notebook"));
        }
        String stack;
        String name;
        if (path.size() == 1) {
            name = name(path.get(0), MAX_NOTEBOOK_NAME_LENGTH);
            boolean stackExists = notebooks.stream().anyMatch(n -> name.equalsIgnoreCase(n.stack()));
            stack = stackExists ? name : null;
        } else {
            stack = name(path.get(0), MAX_NOTEBOOK_NAME_LENGTH);
            name = name(String.join("/", path.subList(1, path.size())), MAX_NOTEBOOK_NAME_LENGTH);
        }
        Optional<EvernoteNoteStore.Notebook> existing =
                notebooks.stream().filter(n -> n.name().equalsIgnoreCase(name)).findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        log.accept("Creating the Evernote notebook '" + name + "'"
                + (stack == null ? "" : " in the stack '" + stack + "'"));
        return store.createNotebook(name, stack);
    }

    /**
     * Stores a note in a notebook, appending to the note with the same title if there is one.
     *
     * @param body the note's content as an ENML fragment
     */
    static NoteUploadResult store(
            EvernoteNoteStore store, List<String> path, String title, String body, Consumer<String> log)
            throws IOException, InterruptedException {
        EvernoteNoteStore.Notebook notebook = notebookFor(store, path, log);
        String noteTitle = name(title, MAX_TITLE_LENGTH);
        Optional<EvernoteNoteStore.NoteSummary> existing = store.findNotesByTitle(notebook.guid(), noteTitle).stream()
                .filter(note -> note.title() != null && note.title().strip().equalsIgnoreCase(noteTitle))
                .findFirst();
        if (existing.isPresent()) {
            String guid = existing.get().guid();
            String content = EnmlRenderer.append(store.getNoteContent(guid), body);
            checkSize(content);
            log.accept(
                    "Appending to the existing Evernote note '" + existing.get().title() + "'");
            store.updateNote(guid, existing.get().title(), content);
            return new NoteUploadResult(store.noteUrl(guid), true);
        }
        String content = EnmlRenderer.document(body);
        checkSize(content);
        log.accept("Creating the Evernote note '" + noteTitle + "' in the notebook '" + notebook.name() + "'");
        String guid = store.createNote(notebook.guid(), noteTitle, content);
        return new NoteUploadResult(store.noteUrl(guid), false);
    }

    private static void checkSize(String content) throws IOException {
        int size = content.getBytes(StandardCharsets.UTF_8).length;
        if (size > EnmlRenderer.MAX_CONTENT_LENGTH) {
            throw new IOException("The note would be " + size + " bytes long, more than Evernote's limit of "
                    + EnmlRenderer.MAX_CONTENT_LENGTH + " bytes");
        }
    }
}
