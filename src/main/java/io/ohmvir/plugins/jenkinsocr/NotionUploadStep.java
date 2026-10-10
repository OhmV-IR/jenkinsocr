package io.ohmvir.plugins.jenkinsocr;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProvider;
import io.ohmvir.plugins.jenkinsocr.notes.notion.NotionNoteProvider;
import java.io.IOException;
import jenkins.tasks.SimpleBuildStep;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Uploads a note to Notion.
 *
 * @deprecated use {@link UploadNoteStep} ({@code uploadNote}), which works with every note provider. This step is
 *     kept so that existing pipelines keep working, and requires Notion to be the configured note provider.
 */
@Deprecated
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

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull EnvVars env, @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        NoteProvider provider = NoteOCRSettings.get().getRequiredNoteProvider();
        if (!(provider instanceof NotionNoteProvider)) {
            throw new AbortException("notionUpload requires Notion to be the configured note provider, but "
                    + provider.getDescriptor().getDisplayName() + " is configured. Use the uploadNote step instead.");
        }
        listener.getLogger().println("[Note OCR] notionUpload is deprecated; use uploadNote instead.");
        UploadNoteStep.upload(notionText, pageTitle, pagePath, null, listener);
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
            return "Upload Text Block to Notion (deprecated, use uploadNote)";
        }
    }
}
