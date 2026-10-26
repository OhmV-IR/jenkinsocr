package io.ohmvir.plugins.jenkinsocr.notes;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Describes a kind of {@link NoteProvider}.
 */
public abstract class NoteProviderDescriptor extends Descriptor<NoteProvider> {

    /**
     * Whether the note application displays TeX formulas ({@code $...$} and {@code $$...$$}) as rendered equations.
     * If not, users are warned when the OCR model is asked for KaTeX or LaTeX output.
     */
    public abstract boolean isFormulaRenderingSupported();

    /**
     * Lists the global "Secret text" credentials, for credential fields of provider configurations.
     */
    protected static ListBoxModel fillSecretTextCredentialsItems(String currentValue) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(currentValue);
        }
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, List.of(), CredentialsMatchers.always())
                .includeCurrentValue(currentValue);
    }

    /**
     * Validates that a "Secret text" credential with the given ID exists and is not empty.
     *
     * @param what a description of the secret for error messages, e.g. "Notion API token"
     */
    protected static FormValidation checkSecretTextCredentials(String credentialsId, String what) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (credentialsId == null || credentialsId.isBlank()) {
            return FormValidation.error("Select the " + what + " credential");
        }
        String secret = SecretsUtils.getSecretText(credentialsId, null);
        if (secret == null || secret.isBlank()) {
            return FormValidation.error(
                    "No non-empty \"Secret text\" credential with ID '" + credentialsId + "' was found");
        }
        return FormValidation.ok();
    }
}
