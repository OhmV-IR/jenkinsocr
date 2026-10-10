package io.ohmvir.plugins.jenkinsocr.notes;

import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Describable;
import hudson.model.TaskListener;
import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteContentParser;
import io.ohmvir.plugins.jenkinsocr.notes.content.NoteDocument;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jspecify.annotations.NonNull;

/**
 * A note-taking application that scanned notes are uploaded to.
 *
 * <p>Implementations are {@link hudson.Extension}s registered through a {@link NoteProviderDescriptor}; the one
 * configured in {@link io.ohmvir.plugins.jenkinsocr.NoteOCRSettings} is used by the {@code recognizeText} and
 * {@code uploadNote} steps. Every provider offers the same two operations:
 *
 * <ul>
 *   <li>{@link #listFolderPaths} returns the existing folders, so the OCR model can file the note in one of them;
 *   <li>{@link #upload} stores a note in a folder, creating missing folders, or appends to the note with the same
 *       title if the folder already has one.
 * </ul>
 *
 * <p>Providers render the note's text in their native format, typically from the format-neutral
 * {@link NoteDocument} returned by {@link #parse}. Providers that cannot display TeX formulas declare so with
 * {@link NoteProviderDescriptor#isFormulaRenderingSupported()}, and users are warned when formulas are requested.
 */
public abstract class NoteProvider implements Describable<NoteProvider>, ExtensionPoint {

    /**
     * Lists the folders that already exist, as {@code /}-separated paths such as {@code "Math/Algebra"}, parents
     * before their children.
     */
    public abstract List<String> listFolderPaths(@NonNull TaskListener listener)
            throws IOException, InterruptedException;

    /**
     * Stores the note. Called by {@link #upload} after the common checks.
     */
    protected abstract NoteUploadResult doUpload(@NonNull Note note, @NonNull TaskListener listener)
            throws IOException, InterruptedException;

    /**
     * Stores the note in the folder given by its path, creating missing folders, or appends its content to the
     * note with the same title in that folder.
     */
    public final NoteUploadResult upload(@NonNull Note note, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        warnIfFormulasUnsupported(note.format(), listener);
        return doUpload(note, listener);
    }

    /**
     * Warns that formulas will not render if the format contains TeX math and this provider cannot display it.
     *
     * @return {@code true} if a warning was printed
     */
    public final boolean warnIfFormulasUnsupported(@NonNull FormulaOutputType format, @NonNull TaskListener listener) {
        if (format == FormulaOutputType.PURE_TEXT || getDescriptor().isFormulaRenderingSupported()) {
            return false;
        }
        String formatName = format == FormulaOutputType.KATEX ? "KaTeX" : "LaTeX";
        String alternatives = all().stream()
                .filter(NoteProviderDescriptor::isFormulaRenderingSupported)
                .map(NoteProviderDescriptor::getDisplayName)
                .collect(Collectors.joining(", "));
        StringBuilder message = new StringBuilder()
                .append(getDescriptor().getDisplayName())
                .append(" cannot render ")
                .append(formatName)
                .append(" equations, so formulas in this note will appear as raw TeX source and the note will not")
                .append(" display as intended. Change the formula output type to PURE_TEXT in the Note OCR settings")
                .append(" (Manage Jenkins » System)");
        if (!alternatives.isEmpty()) {
            message.append(", or switch to a note provider that renders equations (")
                    .append(alternatives)
                    .append(')');
        }
        warn(listener, message.append('.').toString());
        return true;
    }

    /**
     * Parses the note's text according to its format, printing any conversion warnings.
     */
    protected final NoteDocument parse(@NonNull Note note, @NonNull TaskListener listener) {
        NoteDocument document = NoteContentParser.parse(note.text(), note.format());
        document.warnings().forEach(warning -> warn(listener, warning));
        return document;
    }

    /**
     * Prints a warning to the build log.
     */
    protected static void warn(@NonNull TaskListener listener, @NonNull String message) {
        listener.getLogger().println("[Note OCR] WARNING: " + message);
    }

    @Override
    public NoteProviderDescriptor getDescriptor() {
        return (NoteProviderDescriptor) Describable.super.getDescriptor();
    }

    /**
     * All registered note providers.
     */
    public static DescriptorExtensionList<NoteProvider, NoteProviderDescriptor> all() {
        return Jenkins.get().getDescriptorList(NoteProvider.class);
    }
}
