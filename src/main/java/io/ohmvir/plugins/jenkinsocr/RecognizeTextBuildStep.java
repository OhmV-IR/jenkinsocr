package io.ohmvir.plugins.jenkinsocr;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.InputImageContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.InputTextContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.ModelRequest;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.TemperatureContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.ModelResponse;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.OutputTextContent;
import io.ohmvir.plugins.jenkinsocr.notes.NotePaths;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProvider;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

public class RecognizeTextBuildStep extends Step {
    private @Getter final String parameterName;

    @DataBoundConstructor
    public RecognizeTextBuildStep(String parameterName) {
        this.parameterName = parameterName;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, parameterName);
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<RecognizeTextOutput> {
        private static final long serialVersionUID = 1L;
        private final String parameterName;

        protected Execution(@NonNull StepContext context, @NonNull String parameterName) {
            super(context);
            this.parameterName = parameterName;
        }

        @Override
        protected RecognizeTextOutput run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            listener.getLogger().println("Scanning build parameters for image parameter: " + parameterName);
            ParametersAction paramsAction = run.getAction(ParametersAction.class);
            if (paramsAction == null) {
                throw new AbortException("No parameters action found in the build");
            }
            ParameterValue rawValue = paramsAction.getParameter(parameterName);
            if (rawValue == null) {
                throw new AbortException("Parameter " + parameterName + " not found");
            }
            if (!(rawValue instanceof ImageParameterValue paramValue)) {
                throw new AbortException("Parameter " + parameterName + " is not an image parameter");
            }
            BufferedImage image;
            try {
                image = paramValue.getImageData();
            } catch (IOException e) {
                throw new AbortException(e.getMessage());
            }

            NoteOCRSettings settings = NoteOCRSettings.get();
            ModelData model = settings.getModel();
            if (model == null) {
                throw new AbortException("Model '" + settings.getModelId()
                        + "' is not available. Configure a model under Note OCR Settings.");
            }
            NoteProvider noteProvider = settings.getRequiredNoteProvider();
            FormulaOutputType formulaOutputType = settings.getFormulaOutputType();
            // Warn before spending time on the model: the note application cannot show the requested formulas.
            noteProvider.warnIfFormulasUnsupported(formulaOutputType, listener);
            List<String> folderPaths;
            try {
                folderPaths = noteProvider.listFolderPaths(listener);
            } catch (AbortException e) {
                throw e;
            } catch (IOException e) {
                throw new AbortException("Failed to list the folders in "
                        + noteProvider.getDescriptor().getDisplayName() + ": " + e.getMessage());
            }
            String prompt = OcrPrompts.render(formulaOutputType, NotePaths.formatTree(folderPaths));

            ModelRequest request = new ModelRequest();
            request.addInput(new InputTextContent(prompt));
            request.addInput(new InputImageContent(image));
            request.addInput(new TemperatureContent(settings.getTemperature()));
            request.requestOutputType(OutputTextContent.class);
            listener.getLogger().println("Created request, now executing...");
            ModelResponse response = request.execute(model);
            listener.getLogger().println("Request finished!");
            if (response == null) {
                throw new AbortException("Model failed to produce a response");
            }
            OutputTextContent outputText = response.getOutputs().stream()
                    .filter(modelOutput -> modelOutput instanceof OutputTextContent)
                    .map(OutputTextContent.class::cast)
                    .findFirst()
                    .orElse(null);
            if (outputText == null) {
                throw new AbortException("Model didn't produce output text");
            }
            listener.getLogger().println("Model outputted text: " + outputText.getText());
            try {
                return OcrResponseParser.parse(outputText.getText());
            } catch (IllegalArgumentException e) {
                throw new AbortException(e.getMessage());
            }
        }
    }

    @Extension
    @Symbol("recognizeText")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Recognize Text from Image Parameter";
        }

        @Override
        public String getFunctionName() {
            return "recognizeText";
        }
    }
}
