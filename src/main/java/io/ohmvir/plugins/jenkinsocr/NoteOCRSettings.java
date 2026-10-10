package io.ohmvir.plugins.jenkinsocr;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.util.Collections;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.Setter;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
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
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) String notionApiTokenCredentialId = "";
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) String rootPageId = "";
    private @Getter @Setter(onMethod_ = {@DataBoundSetter}) int maxImageDimension =
            ImagePreprocessor.DEFAULT_MAX_DIMENSION;

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

    @POST
    public FormValidation doCheckMaxImageDimension(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Max image dimension is required");
        }
        try {
            int dimension = Integer.parseInt(value.trim());
            if (dimension < 1 || dimension > ImagePreprocessor.MAX_DIMENSION_LIMIT) {
                return FormValidation.error(
                        "Max image dimension should be between 1 and " + ImagePreprocessor.MAX_DIMENSION_LIMIT + ".");
            }
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error("Must be a whole number of pixels");
        }
    }

    @POST
    public FormValidation doCheckNotionApiTokenCredentialId(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Notion API token credential ID must not be blank");
        }
        String secretValue = SecretsUtils.getSecretText(value, null);
        if (secretValue == null || secretValue.isBlank()) {
            return FormValidation.error("Notion API token secret could not be loaded");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckRootPageId(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error("Root page ID should not be blank.");
        }
        return FormValidation.ok();
    }

    public ListBoxModel doFillModelIdItems() {
        return ModelData.getAllModelsListBox();
    }

    public ListBoxModel doFillNotionApiTokenCredentialIdItems(
            @AncestorInPath Item context, @QueryParameter String notionApiTokenCredentialId) {

        if (context == null
                ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.CONFIGURE)) {
            return new StandardListBoxModel().includeCurrentValue(notionApiTokenCredentialId);
        }

        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        context,
                        StandardCredentials.class,
                        Collections.emptyList(),
                        CredentialsMatchers.instanceOf(StringCredentials.class));
    }
}
