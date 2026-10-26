package io.ohmvir.plugins.jenkinsocr.notes.notion;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Stores notes as Notion pages below a root page: each folder of the note's path is a sub-page, and the note
 * itself is a page whose content is made of native Notion blocks, with formulas as Notion equations.
 */
public class NotionNoteProvider extends NoteProvider {
    /** The default number of folder levels listed for the OCR model. */
    public static final int DEFAULT_MAX_FOLDER_DEPTH = 10;

    /** The ID of the "Secret text" credential holding the Notion integration token. */
    @Getter
    private final String credentialsId;

    /** The ID or URL of the page below which notes are stored. */
    @Getter
    private final String rootPageId;

    /**
     * How many folder levels below the root page are listed for the OCR model. Also guards against very deep
     * page hierarchies, since listing takes one request per page.
     */
    @Getter
    private int maxFolderDepth = DEFAULT_MAX_FOLDER_DEPTH;

    @DataBoundConstructor
    public NotionNoteProvider(String credentialsId, String rootPageId) {
        this.credentialsId = credentialsId;
        this.rootPageId = rootPageId;
    }

    @DataBoundSetter
    public void setMaxFolderDepth(int maxFolderDepth) {
        this.maxFolderDepth = maxFolderDepth > 0 ? maxFolderDepth : DEFAULT_MAX_FOLDER_DEPTH;
    }

    /**
     * XStream does not run field initializers, so a configuration without {@link #maxFolderDepth} loads it as 0.
     */
    protected Object readResolve() {
        if (maxFolderDepth <= 0) {
            maxFolderDepth = DEFAULT_MAX_FOLDER_DEPTH;
        }
        return this;
    }

    @Override
    public List<String> listFolderPaths(@NonNull TaskListener listener) throws IOException, InterruptedException {
        return client().listFolderPaths(rootPage(), maxFolderDepth);
    }

    @Override
    protected NoteUploadResult doUpload(@NonNull Note note, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        NotionClient client = client();
        String parentId = rootPage();
        for (String folder : note.pathSegments()) {
            NotionClient.ResolvedPage page = client.findOrCreateChildPage(parentId, folder);
            if (page.created()) {
                listener.getLogger().println("[Note OCR] Created the Notion folder page '" + folder + "'");
            }
            parentId = page.id();
        }

        NotionClient.ResolvedPage page = client.findOrCreateChildPage(parentId, note.title());
        boolean appended = !page.created();
        listener.getLogger()
                .println("[Note OCR] " + (appended ? "Appending to the existing" : "Created the") + " Notion page '"
                        + note.title() + "'");

        NoteDocument document = parse(note, listener);
        NotionBlockRenderer renderer = new NotionBlockRenderer();
        List<NotionBlock> blocks = new ArrayList<>();
        if (appended && !document.blocks().isEmpty()) {
            blocks.add(new NotionBlock("divider", JsonNodeFactory.instance.objectNode(), List.of()));
        }
        blocks.addAll(renderer.render(document));
        renderer.getWarnings().forEach(warning -> warn(listener, warning));
        client.appendBlocks(page.id(), blocks);
        return new NoteUploadResult(NotionIds.pageUrl(page.id()), appended);
    }

    private NotionClient client() throws AbortException {
        String token = SecretsUtils.getSecretText(credentialsId, null);
        if (token == null || token.isBlank()) {
            throw new AbortException("The Notion integration token could not be loaded: no non-empty \"Secret text\""
                    + " credential with ID '" + credentialsId + "' was found");
        }
        return new NotionClient(ProxyConfiguration.newHttpClient(), token);
    }

    private String rootPage() throws AbortException {
        try {
            return NotionIds.normalize(rootPageId);
        } catch (IllegalArgumentException e) {
            throw new AbortException("Invalid Notion root page: " + e.getMessage());
        }
    }

    @Extension
    @Symbol("notion")
    public static final class DescriptorImpl extends NoteProviderDescriptor {
        @Override
        public @NonNull String getDisplayName() {
            return "Notion";
        }

        @Override
        public boolean isFormulaRenderingSupported() {
            return true;
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            return fillSecretTextCredentialsItems(credentialsId);
        }

        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            return checkSecretTextCredentials(value, "Notion integration token");
        }

        @POST
        public FormValidation doCheckMaxFolderDepth(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            return FormValidation.validatePositiveInteger(value);
        }

        @POST
        public FormValidation doCheckRootPageId(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            try {
                NotionIds.normalize(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
