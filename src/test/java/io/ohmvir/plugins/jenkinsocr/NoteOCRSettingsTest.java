package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Descriptor;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.ModelsManagementLink;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.models.ModelConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class NoteOCRSettingsTest {
    private static class TestModelConfiguration extends ModelConfiguration {
        TestModelConfiguration(String modelName, String modelDisplayName) throws Descriptor.FormException {
            super(modelName, modelDisplayName);
        }

        @Override
        public String getProviderType() {
            return "test";
        }
    }

    private static void secure(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader"));
    }

    @Test
    void hasSensibleDefaults(JenkinsRule j) {
        NoteOCRSettings settings = NoteOCRSettings.get();

        assertEquals("", settings.getModelId());
        assertEquals(FormulaOutputType.LATEX, settings.getFormulaOutputType());
        assertEquals(0.1, settings.getTemperature());
        assertEquals("", settings.getNotionApiTokenCredentialId());
        assertEquals("", settings.getRootPageId());
    }

    @Test
    void settingsArePersisted(JenkinsRule j) {
        NoteOCRSettings settings = NoteOCRSettings.get();
        settings.setModelId("test:model");
        settings.setFormulaOutputType(FormulaOutputType.KATEX);
        settings.setTemperature(0.4);
        settings.setNotionApiTokenCredentialId("notion-token");
        settings.setRootPageId("root-page");
        settings.save();

        NoteOCRSettings reloaded = new NoteOCRSettings();

        assertEquals("test:model", reloaded.getModelId());
        assertEquals(FormulaOutputType.KATEX, reloaded.getFormulaOutputType());
        assertEquals(0.4, reloaded.getTemperature());
        assertEquals("notion-token", reloaded.getNotionApiTokenCredentialId());
        assertEquals("root-page", reloaded.getRootPageId());
    }

    @Test
    void temperatureValidation(JenkinsRule j) {
        NoteOCRSettings settings = NoteOCRSettings.get();

        assertEquals(FormValidation.Kind.OK, settings.doCheckTemperature("0").kind);
        assertEquals(FormValidation.Kind.OK, settings.doCheckTemperature("0.7").kind);
        assertEquals(FormValidation.Kind.OK, settings.doCheckTemperature("1").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckTemperature("1.5").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckTemperature("-0.1").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckTemperature("warm").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckTemperature("").kind);
    }

    @Test
    void requiredFieldValidation(JenkinsRule j) {
        NoteOCRSettings settings = NoteOCRSettings.get();

        assertEquals(FormValidation.Kind.ERROR, settings.doCheckModelId(" ").kind);
        assertEquals(FormValidation.Kind.OK, settings.doCheckModelId("test:model").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckRootPageId("").kind);
        assertEquals(FormValidation.Kind.OK, settings.doCheckRootPageId("root-page").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckFormulaOutputType(null).kind);
        assertEquals(FormValidation.Kind.OK, settings.doCheckFormulaOutputType(FormulaOutputType.KATEX).kind);
    }

    @Test
    void credentialValidationRequiresAdministerAndAnExistingCredential(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, "notion-token", "Notion", Secret.fromString("secret")));
        secure(j);
        NoteOCRSettings settings = NoteOCRSettings.get();

        try (ACLContext ignored = ACL.as(User.getById("admin", true))) {
            assertEquals(FormValidation.Kind.OK, settings.doCheckNotionApiTokenCredentialId("notion-token").kind);
            assertEquals(FormValidation.Kind.ERROR, settings.doCheckNotionApiTokenCredentialId("missing").kind);
            assertEquals(FormValidation.Kind.ERROR, settings.doCheckNotionApiTokenCredentialId("").kind);
        }
        try (ACLContext ignored = ACL.as(User.getById("reader", true))) {
            assertThrows(AccessDeniedException.class, () -> settings.doCheckNotionApiTokenCredentialId("notion-token"));
        }
    }

    @Test
    void modelOptionsSubmitTheModelId(JenkinsRule j) throws Exception {
        ModelsManagementLink.get().getModelConfigurations().add(new TestModelConfiguration("gpt", "GPT"));
        ModelsManagementLink.get().getModelConfigurations().add(new TestModelConfiguration("other", "Other"));

        ListBoxModel items = NoteOCRSettings.get().doFillModelIdItems("test:gpt");

        assertEquals(2, items.size());
        ListBoxModel.Option option = items.get(0);
        assertEquals("test:GPT", option.name);
        assertEquals("test:gpt", option.value);
        assertTrue(option.selected);
        assertEquals("test:other", items.get(1).value);
    }

    @Test
    void modelOptionsAreHiddenFromNonAdministrators(JenkinsRule j) throws Exception {
        ModelsManagementLink.get().getModelConfigurations().add(new TestModelConfiguration("gpt", "GPT"));
        secure(j);

        try (ACLContext ignored = ACL.as(User.getById("reader", true))) {
            assertTrue(NoteOCRSettings.get().doFillModelIdItems(null).isEmpty());
        }
    }
}
