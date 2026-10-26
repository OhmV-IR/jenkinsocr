package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import java.io.IOException;
import java.util.List;

/**
 * The Evernote operations needed to store notes, independent of the Thrift SDK types.
 */
interface EvernoteNoteStore {

    /**
     * @param stack the stack the notebook belongs to, or {@code null}
     */
    record Notebook(String guid, String name, String stack, boolean defaultNotebook) {}

    record NoteSummary(String guid, String title) {}

    List<Notebook> listNotebooks() throws IOException, InterruptedException;

    /**
     * @param stack the stack to put the notebook in, or {@code null}
     */
    Notebook createNotebook(String name, String stack) throws IOException, InterruptedException;

    /**
     * Searches a notebook for notes whose title contains {@code title}.
     */
    List<NoteSummary> findNotesByTitle(String notebookGuid, String title) throws IOException, InterruptedException;

    String getNoteContent(String noteGuid) throws IOException, InterruptedException;

    /**
     * @return the new note's GUID
     */
    String createNote(String notebookGuid, String title, String enml) throws IOException, InterruptedException;

    void updateNote(String noteGuid, String title, String enml) throws IOException, InterruptedException;

    /**
     * The web link of a note.
     */
    String noteUrl(String noteGuid);
}
