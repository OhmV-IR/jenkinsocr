package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMNotFoundException;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.notestore.NoteStore;
import com.evernote.edam.notestore.NotesMetadataList;
import com.evernote.edam.notestore.NotesMetadataResultSpec;
import com.evernote.edam.type.Note;
import com.evernote.edam.type.User;
import com.evernote.edam.userstore.UserStore;
import com.evernote.thrift.TException;
import com.evernote.thrift.protocol.TBinaryProtocol;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EvernoteNoteStore} backed by the Evernote (EDAM) Thrift API.
 *
 * <p>Calls that hit Evernote's rate limit are retried when the required wait is short; other errors are raised as
 * {@link IOException}s with Evernote's error code.
 */
final class ThriftEvernoteNoteStore implements EvernoteNoteStore {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_RATE_LIMIT_WAIT_SECONDS = 60;
    private static final int MAX_SEARCH_RESULTS = 50;

    private final NoteStore.Client noteStore;
    private final String token;
    private final EvernoteService service;
    private final User user;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private ThriftEvernoteNoteStore(
            NoteStore.Client noteStore, String token, EvernoteService service, User user, Sleeper sleeper) {
        this.noteStore = noteStore;
        this.token = token;
        this.service = service;
        this.user = user;
        this.sleeper = sleeper;
    }

    /**
     * Looks up the account's NoteStore through the UserStore and connects to it.
     */
    static ThriftEvernoteNoteStore connect(HttpClient httpClient, EvernoteService service, String token)
            throws IOException, InterruptedException {
        return connect(httpClient, service.userStoreUri(), service, token, Thread::sleep);
    }

    static ThriftEvernoteNoteStore connect(
            HttpClient httpClient, URI userStoreUri, EvernoteService service, String token, Sleeper sleeper)
            throws IOException, InterruptedException {
        UserStore.Client userStore =
                new UserStore.Client(new TBinaryProtocol(new HttpThriftTransport(httpClient, userStoreUri)));
        String noteStoreUrl = call(sleeper, "looking up the NoteStore", () -> userStore.getNoteStoreUrl(token));
        User user = call(sleeper, "looking up the user", () -> userStore.getUser(token));
        NoteStore.Client noteStore = new NoteStore.Client(
                new TBinaryProtocol(new HttpThriftTransport(httpClient, URI.create(noteStoreUrl))));
        return new ThriftEvernoteNoteStore(noteStore, token, service, user, sleeper);
    }

    @Override
    public List<Notebook> listNotebooks() throws IOException, InterruptedException {
        List<Notebook> notebooks = new ArrayList<>();
        for (com.evernote.edam.type.Notebook notebook :
                call(sleeper, "listing notebooks", () -> noteStore.listNotebooks(token))) {
            notebooks.add(toNotebook(notebook));
        }
        return notebooks;
    }

    @Override
    public Notebook createNotebook(String name, String stack) throws IOException, InterruptedException {
        com.evernote.edam.type.Notebook notebook = new com.evernote.edam.type.Notebook();
        notebook.setName(name);
        if (stack != null) {
            notebook.setStack(stack);
        }
        return toNotebook(
                call(sleeper, "creating the notebook '" + name + "'", () -> noteStore.createNotebook(token, notebook)));
    }

    @Override
    public List<NoteSummary> findNotesByTitle(String notebookGuid, String title)
            throws IOException, InterruptedException {
        NoteFilter filter = new NoteFilter();
        filter.setNotebookGuid(notebookGuid);
        // Quotes cannot be escaped in Evernote's search grammar; the caller compares titles exactly anyway.
        filter.setWords("intitle:\"" + title.replace("\"", " ").strip() + "\"");
        NotesMetadataResultSpec spec = new NotesMetadataResultSpec();
        spec.setIncludeTitle(true);
        NotesMetadataList result = call(
                sleeper,
                "searching for the note '" + title + "'",
                () -> noteStore.findNotesMetadata(token, filter, 0, MAX_SEARCH_RESULTS, spec));
        List<NoteSummary> notes = new ArrayList<>();
        if (result.getNotes() != null) {
            result.getNotes().forEach(note -> notes.add(new NoteSummary(note.getGuid(), note.getTitle())));
        }
        return notes;
    }

    @Override
    public String getNoteContent(String noteGuid) throws IOException, InterruptedException {
        return call(sleeper, "reading the note", () -> noteStore.getNoteContent(token, noteGuid));
    }

    @Override
    public String createNote(String notebookGuid, String title, String enml) throws IOException, InterruptedException {
        Note note = new Note();
        note.setTitle(title);
        note.setContent(enml);
        note.setNotebookGuid(notebookGuid);
        return call(sleeper, "creating the note '" + title + "'", () -> noteStore.createNote(token, note))
                .getGuid();
    }

    @Override
    public void updateNote(String noteGuid, String title, String enml) throws IOException, InterruptedException {
        Note note = new Note();
        note.setGuid(noteGuid);
        note.setTitle(title);
        note.setContent(enml);
        call(sleeper, "updating the note '" + title + "'", () -> noteStore.updateNote(token, note));
    }

    @Override
    public String noteUrl(String noteGuid) {
        return service.noteUrl(user.getShardId(), user.getId(), noteGuid);
    }

    private static Notebook toNotebook(com.evernote.edam.type.Notebook notebook) {
        return new Notebook(notebook.getGuid(), notebook.getName(), notebook.getStack(), notebook.isDefaultNotebook());
    }

    @FunctionalInterface
    private interface ThriftCall<T> {
        T run() throws EDAMUserException, EDAMSystemException, EDAMNotFoundException, TException;
    }

    private static <T> T call(Sleeper sleeper, String action, ThriftCall<T> call)
            throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.run();
            } catch (EDAMSystemException e) {
                boolean shortRateLimit = e.getErrorCode() == EDAMErrorCode.RATE_LIMIT_REACHED
                        && e.isSetRateLimitDuration()
                        && e.getRateLimitDuration() <= MAX_RATE_LIMIT_WAIT_SECONDS;
                if (shortRateLimit && attempt < MAX_ATTEMPTS) {
                    sleeper.sleep(Math.max(1, e.getRateLimitDuration()) * 1000L);
                    continue;
                }
                throw new IOException(describe(action, e), e);
            } catch (EDAMUserException e) {
                throw new IOException(describe(action, e), e);
            } catch (EDAMNotFoundException e) {
                throw new IOException(
                        "Evernote could not find " + e.getIdentifier() + " '" + e.getKey() + "' while " + action, e);
            } catch (TException e) {
                if (Thread.interrupted()) {
                    InterruptedException interrupted = new InterruptedException("Interrupted while " + action);
                    interrupted.initCause(e);
                    throw interrupted;
                }
                throw new IOException("Communication with Evernote failed while " + action + ": " + e.getMessage(), e);
            }
        }
    }

    private static String describe(String action, EDAMUserException e) {
        StringBuilder message = new StringBuilder("Evernote rejected ")
                .append(action)
                .append(": ")
                .append(e.getErrorCode());
        if (e.getParameter() != null) {
            message.append(" (").append(e.getParameter()).append(')');
        }
        if (e.getErrorCode() == EDAMErrorCode.AUTH_EXPIRED || e.getErrorCode() == EDAMErrorCode.INVALID_AUTH) {
            message.append(". Check that the configured Evernote token is valid and has not expired.");
        } else if (e.getErrorCode() == EDAMErrorCode.PERMISSION_DENIED) {
            message.append(". The Evernote token does not allow this operation.");
        }
        return message.toString();
    }

    private static String describe(String action, EDAMSystemException e) {
        StringBuilder message = new StringBuilder("Evernote failed while ")
                .append(action)
                .append(": ")
                .append(e.getErrorCode());
        if (e.getMessage() != null) {
            message.append(" (").append(e.getMessage()).append(')');
        }
        if (e.getErrorCode() == EDAMErrorCode.RATE_LIMIT_REACHED && e.isSetRateLimitDuration()) {
            message.append(". Evernote's rate limit allows retrying in ")
                    .append(e.getRateLimitDuration())
                    .append(" seconds.");
        }
        return message.toString();
    }
}
