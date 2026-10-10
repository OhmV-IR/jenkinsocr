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
import java.util.Optional;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Stores notes as Notion pages below a root page: each folder of the note's path is a sub-page, and the note
 * itself is a page whose content is made of native Notion blocks, with formulas as Notion equations.
 */
public class NotionNoteProvider extends NoteProvider {
    /** Guards against unexpectedly deep (or cyclic) page hierarchies when listing folders. */
    private static final int MAX_FOLDER_DEPTH = 10;

    private final String credentialsId;
    private final String rootPageId;

    @DataBoundConstructor
    public NotionNoteProvider(String credentialsId, String rootPageId) {
        this.credentialsId = credentialsId;
        this.rootPageId = rootPageId;
    }

    /**
     * The ID of the "Secret text" credential holding the Notion integration token.
     */
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * The ID or URL of the page below which notes are stored.
     */
    public String getRootPageId() {
        return rootPageId;
    }

    @Override
    public List<String> listFolderPaths(@NonNull TaskListener listener) throws IOException, InterruptedException {
        List<String> paths = new ArrayList<>();
        collectFolderPaths(client(), rootPage(), "", 0, paths);
        return paths;
    }

    private static void collectFolderPaths(
            NotionClient client, String parentId, String parentPath, int depth, List<String> paths)
            throws IOException, InterruptedException {
        if (depth >= MAX_FOLDER_DEPTH) {
            return;
        }
        for (NotionClient.ChildPage page : client.listChildPages(parentId)) {
            String title = page.title().strip();
            if (title.isEmpty()) {
                continue;
            }
            String path = parentPath.isEmpty() ? title : parentPath + "/" + title;
            paths.add(path);
            collectFolderPaths(client, page.id(), path, depth + 1, paths);
        }
    }

    @Override
    protected NoteUploadResult doUpload(@NonNull Note note, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        NotionClient client = client();
        String parentId = rootPage();
        for (String folder : note.pathSegments()) {
            parentId = findOrCreatePage(client, parentId, folder, listener);
        }

        Optional<String> existing = client.findChildPage(parentId, note.title());
        String pageId;
        if (existing.isPresent()) {
            pageId = existing.get();
            listener.getLogger().println("[Note OCR] Appending to the existing Notion page '" + note.title() + "'");
        } else {
            pageId = client.createPage(parentId, note.title());
            listener.getLogger().println("[Note OCR] Created the Notion page '" + note.title() + "'");
        }

        NoteDocument document = parse(note, listener);
        NotionBlockRenderer renderer = new NotionBlockRenderer();
        List<NotionBlock> blocks = new ArrayList<>();
        if (existing.isPresent() && !document.blocks().isEmpty()) {
            blocks.add(new NotionBlock("divider", JsonNodeFactory.instance.objectNode(), List.of()));
        }
        blocks.addAll(renderer.render(document));
        renderer.getWarnings().forEach(warning -> warn(listener, warning));
        client.appendBlocks(pageId, blocks);
        return new NoteUploadResult(NotionIds.pageUrl(pageId), existing.isPresent());
    }

    private static String findOrCreatePage(NotionClient client, String parentId, String title, TaskListener listener)
            throws IOException, InterruptedException {
        Optional<String> existing = client.findChildPage(parentId, title);
        if (existing.isPresent()) {
            return existing.get();
        }
        listener.getLogger().println("[Note OCR] Creating the Notion folder page '" + title + "'");
        return client.createPage(parentId, title);
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
