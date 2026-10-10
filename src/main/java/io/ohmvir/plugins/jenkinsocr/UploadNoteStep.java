package io.ohmvir.plugins.jenkinsocr;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsocr.notes.Note;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProvider;
import io.ohmvir.plugins.jenkinsocr.notes.NoteUploadResult;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Uploads a note to the note provider configured in {@link NoteOCRSettings}, returning where it was stored.
 *
 * <pre>
 * def note = recognizeText(parameterName: 'NOTE_IMAGE')
 * def location = uploadNote(text: note.text, title: note.title, path: note.path)
 * </pre>
 */
public class UploadNoteStep extends Step {
    private final String text;
    private final String title;
    private String path = "";
    private FormulaOutputType formulaOutputType;

    @DataBoundConstructor
    public UploadNoteStep(String text, String title) {
        this.text = text;
        this.title = title;
    }

    public String getText() {
        return text;
    }

    public String getTitle() {
        return title;
    }

    public String getPath() {
        return path;
    }

    @DataBoundSetter
    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    /**
     * The format of {@link #getText()}, or {@code null} to use the formula output type of the global settings,
     * which is the format {@code recognizeText} produces.
     */
    public String getFormulaOutputType() {
        return formulaOutputType == null ? null : formulaOutputType.name();
    }

    @DataBoundSetter
    public void setFormulaOutputType(String formulaOutputType) {
        if (formulaOutputType == null || formulaOutputType.isBlank()) {
            this.formulaOutputType = null;
            return;
        }
        try {
            this.formulaOutputType =
                    FormulaOutputType.valueOf(formulaOutputType.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown formulaOutputType '" + formulaOutputType + "', expected one of "
                            + Arrays.toString(FormulaOutputType.values()),
                    e);
        }
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, text, title, path, formulaOutputType);
    }

    /**
     * Uploads a note to the configured note provider, reporting the outcome in the build log.
     */
    static NoteUploadResult upload(
            String text, String title, String path, FormulaOutputType format, TaskListener listener)
            throws IOException, InterruptedException {
        NoteOCRSettings settings = NoteOCRSettings.get();
        NoteProvider provider = settings.getRequiredNoteProvider();
        if (title == null || title.isBlank()) {
            throw new AbortException("A note title is required");
        }
        Note note = new Note(title, path, text, format == null ? settings.getFormulaOutputType() : format);
        listener.getLogger()
                .println("[Note OCR] Uploading the note '" + note.title() + "' to "
                        + provider.getDescriptor().getDisplayName()
                        + (note.pathSegments().isEmpty() ? "" : " in '" + String.join("/", note.pathSegments()) + "'"));
        NoteUploadResult result;
        try {
            result = provider.upload(note, listener);
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            throw new AbortException("Uploading the note to "
                    + provider.getDescriptor().getDisplayName() + " failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        listener.getLogger()
                .println("[Note OCR] " + (result.appended() ? "Appended to" : "Created") + " the note: "
                        + result.location());
        return result;
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<String> {
        private static final long serialVersionUID = 1L;

        private final String text;
        private final String title;
        private final String path;
        private final FormulaOutputType formulaOutputType;

        Execution(
                @NonNull StepContext context,
                String text,
                String title,
                String path,
                FormulaOutputType formulaOutputType) {
            super(context);
            this.text = text;
            this.title = title;
            this.path = path;
            this.formulaOutputType = formulaOutputType;
        }

        @Override
        protected String run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            return upload(text, title, path, formulaOutputType, listener).location();
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "uploadNote";
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Upload a Note to the Configured Note Provider";
        }

        public ListBoxModel doFillFormulaOutputTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Formula output type of the Note OCR settings", "");
            for (FormulaOutputType type : FormulaOutputType.values()) {
                items.add(type.name());
            }
            return items;
        }
    }
}
