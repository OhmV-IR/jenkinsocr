package io.ohmvir.plugins.jenkinsocr.notes.obsidian;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import io.ohmvir.plugins.jenkinsocr.notes.Note;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProvider;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProviderDescriptor;
import io.ohmvir.plugins.jenkinsocr.notes.NoteUploadResult;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Stores notes as Markdown files in an Obsidian vault: each folder of the note's path is a folder of the vault and
 * the note is a {@code .md} file named after its title. The vault can be on the Jenkins controller or on an agent,
 * for example a computer running Obsidian, or one that syncs the vault with Obsidian Sync, Git or a file-sync tool.
 */
public class ObsidianNoteProvider extends NoteProvider {
    private final String vaultPath;
    private String nodeName = "";

    @DataBoundConstructor
    public ObsidianNoteProvider(String vaultPath) {
        this.vaultPath = vaultPath == null ? "" : vaultPath.strip();
    }

    /**
     * The absolute path of the vault folder (or of a folder inside the vault) on {@link #getNodeName()}.
     */
    public String getVaultPath() {
        return vaultPath;
    }

    /**
     * The name of the agent holding the vault, or an empty string for the built-in node (the controller).
     */
    public String getNodeName() {
        return nodeName;
    }

    @DataBoundSetter
    public void setNodeName(String nodeName) {
        this.nodeName = nodeName == null ? "" : nodeName.strip();
    }

    @Override
    public List<String> listFolderPaths(@NonNull TaskListener listener) throws IOException, InterruptedException {
        return vault().act(new ObsidianVault.ListFolders());
    }

    @Override
    protected NoteUploadResult doUpload(@NonNull Note note, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        String markdown = note.format() == FormulaOutputType.KATEX
                ? ObsidianMarkdown.normalizeMathDelimiters(note.text()).strip()
                : ObsidianMarkdown.render(parse(note, listener));
        ObsidianVault.WriteResult result =
                vault().act(new ObsidianVault.WriteNote(note.pathSegments(), note.title(), markdown));
        return new NoteUploadResult(result.path(), result.appended());
    }

    private FilePath vault() throws AbortException {
        if (vaultPath.isEmpty()) {
            throw new AbortException("No Obsidian vault folder is configured");
        }
        Node node;
        if (nodeName.isEmpty()) {
            node = Jenkins.get();
        } else {
            node = Jenkins.get().getNode(nodeName);
            if (node == null) {
                throw new AbortException("The agent '" + nodeName + "' holding the Obsidian vault does not exist");
            }
        }
        FilePath vault = node.createPath(vaultPath);
        if (vault == null) {
            throw new AbortException("The agent '" + nodeName + "' holding the Obsidian vault is offline");
        }
        return vault;
    }

    @Extension
    @Symbol("obsidian")
    public static final class DescriptorImpl extends NoteProviderDescriptor {
        @Override
        public @NonNull String getDisplayName() {
            return "Obsidian";
        }

        @Override
        public boolean isFormulaRenderingSupported() {
            return true;
        }

        public ListBoxModel doFillNodeNameItems(@QueryParameter String nodeName) {
            ListBoxModel items = new ListBoxModel();
            items.add("Built-in node (controller)", "");
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                if (nodeName != null && !nodeName.isBlank()) {
                    items.add(nodeName);
                }
                return items;
            }
            for (Node node : Jenkins.get().getNodes()) {
                items.add(node.getNodeName());
            }
            return items;
        }

        @POST
        public FormValidation doCheckVaultPath(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("The path of the vault folder is required");
            }
            String path = value.strip();
            boolean absolute = path.startsWith("/") || path.startsWith("\\\\") || path.matches("^[A-Za-z]:[\\\\/].*");
            if (!absolute) {
                return FormValidation.error("Enter an absolute path, such as /home/me/Notes or C:\\Users\\me\\Notes");
            }
            return FormValidation.ok();
        }
    }
}
