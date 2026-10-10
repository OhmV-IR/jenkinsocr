package io.ohmvir.plugins.jenkinsocr;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

public class NotionUploadStep extends Builder implements SimpleBuildStep {
    private final @Getter String notionText;
    private final @Getter String pagePath;
    private final @Getter String pageTitle;

    @DataBoundConstructor
    public NotionUploadStep(String notionText, String pageTitle, String pagePath) {
        this.notionText = notionText;
        this.pageTitle = pageTitle;
        this.pagePath = pagePath;
    }

    /**
     * This step only talks to the Notion API, so it must not require a workspace. Without this, Jenkins
     * dispatches to the workspace-based perform overload, which this class does not implement.
     */
    @Override
    public boolean requiresWorkspace() {
        return false;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull EnvVars env, @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Uploading notion text: " + notionText);
        NoteOCRSettings settings = NoteOCRSettings.get();
        String apiToken = SecretsUtils.getSecretText(settings.getNotionApiTokenCredentialId(), null);
        if (apiToken == null || apiToken.isBlank()) {
            throw new AbortException("Notion API token credential '" + settings.getNotionApiTokenCredentialId()
                    + "' could not be found. Configure it under Note OCR Settings.");
        }
        try {
            String createdPageId = new NotionClient(apiToken)
                    .createPageAtPathWithKatex(settings.getRootPageId(), pagePath, pageTitle, notionText);
            listener.getLogger().println("Created page with id: " + createdPageId);
        } catch (IOException | RuntimeException e) {
            listener.error("Failed to create notion page: " + e.getMessage());
            throw new AbortException("Notion sync failed: " + e.getMessage());
        }
    }

    /**
     * Traverses or creates pages along a path (e.g. "Math/Algebra")
     * creates or finds the final page using pageTitle, and adds
     * a KaTeX equation block to that final destination page.
     *
     * @param rootPageId The root Notion page ID where path resolution begins
     * @param path       Path elements separated by slashes (e.g., "Math/Semester 1/Calculus")
     * @param pageTitle  The name of the final page to hold the KaTeX block
     * @param katexText  Raw LaTeX/KaTeX string
     * @param apiToken   Notion API bearer token
     * @return The ID of the final created/resolved page
     */
    public String createPageAtPathWithKatex(
            String rootPageId, String path, String pageTitle, String katexText, String apiToken) throws Exception {
        return new NotionClient(apiToken).createPageAtPathWithKatex(rootPageId, path, pageTitle, katexText);
    }

    /**
     * Recursively traverses the Notion workspace starting at rootPageId
     * and returns a list of all valid directory path categories (e.g., "Math/Algebra").
     *
     * @param rootPageId The root page ID to start listing directories from
     * @param apiToken   The Notion API bearer token
     * @return List of relative path strings representing category folders
     */
    public static List<String> getDirectoryPaths(String rootPageId, String apiToken) throws Exception {
        return new NotionClient(apiToken).getDirectoryPaths(rootPageId);
    }

    /**
     * Formats the directory paths into a bulleted tree string suitable for LLM prompts.
     */
    public static String getDirectoryTreeFormatted(String rootPageId, String apiToken) throws Exception {
        return new NotionClient(apiToken).getDirectoryTreeFormatted(rootPageId);
    }

    @Extension
    @Symbol("notionUpload")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Upload Text Block to Notion";
        }
    }
}
