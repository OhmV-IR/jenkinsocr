package io.ohmvir.plugins.jenkinsocr.notes.evernote;

import hudson.AbortException;
import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import io.ohmvir.plugins.jenkinsocr.notes.Note;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProvider;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProviderDescriptor;
import io.ohmvir.plugins.jenkinsocr.notes.NoteUploadResult;
import java.io.IOException;
import java.util.List;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Stores notes in Evernote. Folders map onto stacks and notebooks (see {@link EvernoteNotes}) and notes are written
 * as ENML. Evernote cannot render TeX formulas, so they are kept as TeX source and users are warned to prefer plain
 * text output.
 */
public class EvernoteNoteProvider extends NoteProvider {
    private final String credentialsId;
    private EvernoteService service = EvernoteService.PRODUCTION;

    @DataBoundConstructor
    public EvernoteNoteProvider(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    /**
     * The ID of the "Secret text" credential holding the Evernote developer token or OAuth access token.
     */
    public String getCredentialsId() {
        return credentialsId;
    }

    public EvernoteService getService() {
        return service;
    }

    @DataBoundSetter
    public void setService(EvernoteService service) {
        this.service = service == null ? EvernoteService.PRODUCTION : service;
    }

    @Override
    public List<String> listFolderPaths(@NonNull TaskListener listener) throws IOException, InterruptedException {
        return EvernoteNotes.folderPaths(connect().listNotebooks());
    }

    @Override
    protected NoteUploadResult doUpload(@NonNull Note note, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        String body = EnmlRenderer.render(parse(note, listener));
        return EvernoteNotes.store(
                connect(),
                note.pathSegments(),
                note.title(),
                body,
                message -> listener.getLogger().println("[Note OCR] " + message));
    }

    private EvernoteNoteStore connect() throws IOException, InterruptedException {
        String token = SecretsUtils.getSecretText(credentialsId, null);
        if (token == null || token.isBlank()) {
            throw new AbortException("The Evernote token could not be loaded: no non-empty \"Secret text\" credential"
                    + " with ID '" + credentialsId + "' was found");
        }
        return ThriftEvernoteNoteStore.connect(ProxyConfiguration.newHttpClient(), service, token.strip());
    }

    @Extension
    @Symbol("evernote")
    public static final class DescriptorImpl extends NoteProviderDescriptor {
        @Override
        public @NonNull String getDisplayName() {
            return "Evernote";
        }

        @Override
        public boolean isFormulaRenderingSupported() {
            return false;
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            return fillSecretTextCredentialsItems(credentialsId);
        }

        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            return checkSecretTextCredentials(value, "Evernote token");
        }
    }
}
