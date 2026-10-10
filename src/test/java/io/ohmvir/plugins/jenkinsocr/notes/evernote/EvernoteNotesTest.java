package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmvir.plugins.jenkinsocr.notes.NoteUploadResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvernoteNotesTest {
    private static final String NUL = String.valueOf((char) 0);
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);

    /** An in-memory Evernote account. */
    private static final class FakeStore implements EvernoteNoteStore {
        final List<Notebook> notebooks = new ArrayList<>();
        final Map<String, NoteSummary> notes = new LinkedHashMap<>();
        final Map<String, String> notebookOfNote = new LinkedHashMap<>();
        final Map<String, String> contents = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();

        FakeStore(Notebook... notebooks) {
            this.notebooks.addAll(List.of(notebooks));
        }

        @Override
        public List<Notebook> listNotebooks() {
            return List.copyOf(notebooks);
        }

        @Override
        public Notebook createNotebook(String name, String stack) {
            Notebook notebook = new Notebook("nb" + notebooks.size(), name, stack, false);
            notebooks.add(notebook);
            return notebook;
        }

        @Override
        public List<NoteSummary> findNotesByTitle(String notebookGuid, String title) {
            return notes.values().stream()
                    .filter(note -> notebookOfNote.get(note.guid()).equals(notebookGuid))
                    .filter(note -> note.title().toLowerCase().contains(title.toLowerCase()))
                    .toList();
        }

        @Override
        public String getNoteContent(String noteGuid) {
            return contents.get(noteGuid);
        }

        @Override
        public String createNote(String notebookGuid, String title, String enml) {
            String guid = "note" + notes.size();
            notes.put(guid, new NoteSummary(guid, title));
            notebookOfNote.put(guid, notebookGuid);
            contents.put(guid, enml);
            return guid;
        }

        @Override
        public void updateNote(String noteGuid, String title, String enml) {
            log.add("update " + noteGuid + " " + title);
            contents.put(noteGuid, enml);
        }

        @Override
        public String noteUrl(String noteGuid) {
            return "https://evernote.test/" + noteGuid;
        }
    }

    private static final EvernoteNoteStore.Notebook DEFAULT = new EvernoteNoteStore.Notebook("d", "Inbox", null, true);
    private static final EvernoteNoteStore.Notebook ALGEBRA =
            new EvernoteNoteStore.Notebook("a", "Algebra", "Math", false);
    private static final EvernoteNoteStore.Notebook GEOMETRY =
            new EvernoteNoteStore.Notebook("g", "Geometry", "Math", false);

    @Test
    void foldersListStacksBeforeTheirNotebooks() {
        assertEquals(
                List.of("Math", "Math/Algebra", "Math/Geometry", "Inbox"),
                EvernoteNotes.folderPaths(List.of(DEFAULT, GEOMETRY, ALGEBRA)));
    }

    @Test
    void pathsMapToStacksAndNotebooks() throws Exception {
        FakeStore store = new FakeStore(DEFAULT, ALGEBRA);
        List<String> log = new ArrayList<>();
        assertEquals(DEFAULT, EvernoteNotes.notebookFor(store, List.of(), log::add));
        assertEquals(ALGEBRA, EvernoteNotes.notebookFor(store, List.of("Math", "algebra"), log::add));
        assertEquals(ALGEBRA, EvernoteNotes.notebookFor(store, List.of("ALGEBRA"), log::add), "names are unique");

        EvernoteNoteStore.Notebook calculus = EvernoteNotes.notebookFor(store, List.of("Math", "Calculus"), log::add);
        assertEquals("Calculus", calculus.name());
        assertEquals("Math", calculus.stack());

        EvernoteNoteStore.Notebook groups =
                EvernoteNotes.notebookFor(store, List.of("Math", "Algebra", "Groups"), log::add);
        assertEquals("Algebra/Groups", groups.name());
        assertEquals("Math", groups.stack());

        EvernoteNoteStore.Notebook biology = EvernoteNotes.notebookFor(store, List.of("Biology"), log::add);
        assertEquals("Biology", biology.name());
        assertEquals(null, biology.stack());

        // A one-segment path naming an existing stack gets a notebook of the same name inside that stack.
        EvernoteNoteStore.Notebook math = EvernoteNotes.notebookFor(store, List.of("Math"), log::add);
        assertEquals("Math", math.name());
        assertEquals("Math", math.stack());
        assertEquals(4, log.size());
    }

    @Test
    void createsNotesAndAppendsToExistingOnes() throws Exception {
        FakeStore store = new FakeStore(DEFAULT, ALGEBRA);
        List<String> log = new ArrayList<>();
        NoteUploadResult created =
                EvernoteNotes.store(store, List.of("Math", "Algebra"), "Rings", "<p>a</p>", log::add);
        assertFalse(created.appended());
        assertEquals("https://evernote.test/note0", created.location());
        assertEquals(EnmlRenderer.document("<p>a</p>"), store.contents.get("note0"));
        assertEquals("a", store.notebookOfNote.get("note0"));

        // "Rings and Fields" also matches the title search, but only the exact title is appended to.
        EvernoteNotes.store(store, List.of("Math", "Algebra"), "Rings and Fields", "<p>x</p>", log::add);
        NoteUploadResult appended =
                EvernoteNotes.store(store, List.of("Math", "Algebra"), "rings", "<p>b</p>", log::add);
        assertTrue(appended.appended());
        assertEquals("https://evernote.test/note0", appended.location());
        assertEquals(EnmlRenderer.document("<p>a</p><hr/><p>b</p>"), store.contents.get("note0"));
        assertEquals(List.of("update note0 Rings"), store.log);
    }

    @Test
    void namesAreMadeValidForEvernote() {
        assertEquals("a b", EvernoteNotes.name(" a\n\tb" + LINE_SEPARATOR, 100));
        assertEquals("Untitled", EvernoteNotes.name(NUL + " ", 100));
        assertEquals("x".repeat(100), EvernoteNotes.name("x".repeat(150), 100));
        assertEquals("x".repeat(99), EvernoteNotes.name("x".repeat(99) + "😀", 100), "no broken surrogate pair");
    }
}
