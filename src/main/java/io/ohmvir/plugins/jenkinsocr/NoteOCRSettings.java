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
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Example of Jenkins global configuration.
 */
@Extension
public class NoteOCRSettings extends GlobalConfiguration {
    private @Getter final String modelId;
    private @Getter final FormulaOutputType formulaOutputType;
    private @Getter final double temperature;
    private @Getter final String notionApiTokenCredentialId;
    private @Getter final String rootPageId;

    public NoteOCRSettings() {
        modelId = "";
        formulaOutputType = FormulaOutputType.LATEX;
        temperature = 0.1;
        notionApiTokenCredentialId = "";
        rootPageId = "";
    }

    @DataBoundConstructor
    public NoteOCRSettings(
            String modelId,
            FormulaOutputType formulaOutputType,
            double temperature,
            String notionApiTokenCredentialId,
            String rootPageId)
            throws FormException {
        if (modelId.isBlank()) {
            throw new FormException("Model id should not be blank.", "modelId");
        }
        this.modelId = modelId;
        if (formulaOutputType == null) {
            throw new FormException("Formula output type should not be null.", "formulaOutputType");
        }
        this.formulaOutputType = formulaOutputType;
        if (temperature < 0 || temperature > 1) {
            throw new FormException("Temperature should be between 0 and 1.", "temperature");
        }
        this.temperature = temperature;
        if (notionApiTokenCredentialId == null || notionApiTokenCredentialId.isBlank()) {
            throw new FormException(
                    "Must have a notion api token credential and it must not be blank", "notionApiTokenCredentialsId");
        }
        this.notionApiTokenCredentialId = notionApiTokenCredentialId;
        if (rootPageId.isBlank()) {
            throw new FormException("Root page id should not be blank.", "rootPageId");
        }
        this.rootPageId = rootPageId;
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
        if (value.isBlank()) {
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
    public FormValidation doCheckTemperature(@QueryParameter double temperature) {
        if (temperature < 0 || temperature > 1) {
            return FormValidation.error("Temperature should be between 0 and 1.");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckNotionApiTokenCredentialId(@QueryParameter String value) {
        if (value.isBlank()) {
            return FormValidation.error("Notion api token credentials id must not be blank");
        }
        String secretValue = SecretsUtils.getSecretText(value, null);
        if (secretValue == null || secretValue.isBlank()) {
            return FormValidation.error("Notion api token credentials id must not be blank");
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

    @POST
    public FormValidation doCheckRootPageId(@QueryParameter String value) {
        if (value.isBlank()) {
            return FormValidation.error("Root page id should not be blank.");
        }
        return FormValidation.ok();
    }
}
