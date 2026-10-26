package io.ohmvir.plugins.jenkinsocr;

import hudson.AbortException;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.ModelsManagementLink;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.models.ModelConfiguration;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProvider;
import io.ohmvir.plugins.jenkinsocr.notes.NoteProviderDescriptor;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.Setter;
import net.sf.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

@Extension
public class NoteOCRSettings extends GlobalConfiguration {
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) String modelId = "";
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) FormulaOutputType formulaOutputType =
            FormulaOutputType.LATEX;
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) double temperature = 0.1;
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) NoteProvider noteProvider;

    public NoteOCRSettings() {
        load();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    public static NoteOCRSettings get() {
        return GlobalConfiguration.all().get(NoteOCRSettings.class);
    }

    public ModelData getModel() {
        return ModelData.get(this.modelId);
    }

    /**
     * The configured note provider.
     *
     * @throws AbortException if none is configured
     */
    public @NonNull NoteProvider getRequiredNoteProvider() throws AbortException {
        if (noteProvider == null) {
            throw new AbortException(
                    "No note provider is configured. Choose one in the Note OCR settings (Manage Jenkins » System).");
        }
        return noteProvider;
    }

    public DescriptorExtensionList<NoteProvider, NoteProviderDescriptor> getNoteProviderDescriptors() {
        return NoteProvider.all();
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Note OCR Settings";
    }

    @POST
    public FormValidation doCheckModelId(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Please enter a valid model ID");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckFormulaOutputType(@QueryParameter FormulaOutputType value) {
        if (value == null) {
            return FormValidation.error("Please enter a valid formula output type");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckTemperature(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Temperature is required");
        }
        try {
            double temp = Double.parseDouble(value);
            if (temp < 0 || temp > 1) {
                return FormValidation.error("Temperature should be between 0 and 1.");
            }
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error("Must be a valid decimal number");
        }
    }

    public ListBoxModel doFillModelIdItems(@QueryParameter String modelId) {
        ListBoxModel items = new ListBoxModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return items;
        }
        // Built from the configurations rather than ModelData.getAllModelsListBox(), which swaps the
        // display name and value, so the saved value is the model ID that ModelData.get expects.
        for (ModelConfiguration config : ModelsManagementLink.get().getModelConfigurations()) {
            items.add(new ListBoxModel.Option(
                    config.getModelIdDisplayName(),
                    config.getModelId(),
                    config.getModelId().equals(modelId)));
        }
        return items;
    }
}
