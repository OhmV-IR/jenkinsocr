package io.ohmvir.plugins.jenkinsocr;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.InputImageContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.InputTextContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.ModelRequest;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.TemperatureContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.ModelResponse;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.OutputTextContent;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

public class RecognizeTextBuildStep extends Step {
    private @Getter final String parameterName;
    private static final Map<FormulaOutputType, String> FORMULA_OUTPUT_TYPE_TO_PROMPT = new HashMap<>();

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void loadPrompts() throws IOException {
        for (FormulaOutputType type : FormulaOutputType.values()) {
            try (InputStream is = RecognizeTextBuildStep.class.getResourceAsStream(
                    "/prompts/" + type.name().toUpperCase() + ".md")) {
                if (is == null) {
                    Logger.getLogger(RecognizeTextBuildStep.class.getName())
                            .log(Level.WARNING, "Could not find prompt file for " + type.name());
                    continue;
                }
                FORMULA_OUTPUT_TYPE_TO_PROMPT.put(type, new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @DataBoundConstructor
    public RecognizeTextBuildStep(String parameterName) {
        this.parameterName = parameterName;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, parameterName);
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<RecognizeTextOutput> {
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
                throw new Exception("No parameters action found in the build");
            }
            ImageParameterValue paramValue = (ImageParameterValue) paramsAction.getParameter(parameterName);
            if (paramValue == null) {
                throw new Exception("Parameter " + parameterName + " not found or was not an image parameter");
            }
            ModelRequest request = new ModelRequest();
            String prompt =
                    FORMULA_OUTPUT_TYPE_TO_PROMPT.get(NoteOCRSettings.get().getFormulaOutputType());
            prompt = prompt.replace(
                    "${DIR_TREE}",
                    NotionUploadStep.getDirectoryTreeFormatted(
                            NoteOCRSettings.get().getRootPageId(),
                            SecretsUtils.getSecretText(NoteOCRSettings.get().getNotionApiTokenCredentialId(), null)));
            request.addInput(new InputTextContent(prompt));
            request.addInput(new InputImageContent(paramValue.getImageData()));
            request.addInput(new TemperatureContent(NoteOCRSettings.get().getTemperature()));
            request.requestOutputType(OutputTextContent.class);
            listener.getLogger().println("Created request, now executing...");
            ModelResponse response = request.execute(NoteOCRSettings.get().getModel());
            listener.getLogger().println("Request finished!");
            if (response == null) {
                throw new Exception("Model failed to produce a response");
            }
            OutputTextContent outputText = response.getOutputs().stream()
                    .filter(modelOutput -> modelOutput instanceof OutputTextContent)
                    .map(OutputTextContent.class::cast)
                    .findFirst()
                    .orElse(null);
            if (outputText == null) {
                throw new Exception("Model didn't produce output text");
            }
            listener.getLogger().println("Model outputted text: " + outputText.getText());
            JsonObject output =
                    new Gson().fromJson(outputText.getText(), JsonElement.class).getAsJsonObject();
            if (output.get("text").getAsString() == null) {
                throw new Exception("Model didn't produce text field in the json");
            }
            if (output.get("title").getAsString() == null) {
                throw new Exception("Model didn't produce title in the json");
            }
            if (output.get("path").getAsString() == null) {
                throw new Exception("Model didn't produce path in the json");
            }
            return new RecognizeTextOutput(
                    output.get("text").getAsString(),
                    output.get("path").getAsString(),
                    output.get("title").getAsString());
        }
    }

    @Extension
    @Symbol("recognizeText")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(Run.class);
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
