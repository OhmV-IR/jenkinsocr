package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NotionUploadStepTest {
    @Test
    void doesNotRequireAWorkspace(JenkinsRule j) {
        assertFalse(new NotionUploadStep("text", "Title", "Math").requiresWorkspace());
    }

    @Test
    void pipelineStepRunsOutsideANodeAndReportsMissingCredential(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(
                new CpsFlowDefinition("notionUpload(notionText: 'x', pageTitle: 'Title', pagePath: 'Math')", true));

        WorkflowRun b = j.buildAndAssertStatus(Result.FAILURE, p);

        j.assertLogContains("Notion API token credential", b);
        j.assertLogNotContains("AbstractMethodError", b);
    }

    @Test
    void freestyleBuildReportsMissingCredential(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new NotionUploadStep("x", "Title", "Math"));

        FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);

        j.assertLogContains("Notion API token credential", b);
        j.assertLogNotContains("AbstractMethodError", b);
    }

    @Test
    void freestyleConfigRoundtrip(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        NotionUploadStep step = new NotionUploadStep("\\frac{a}{b}", "Title", "Math/Algebra");
        p.getBuildersList().add(step);

        j.configRoundtrip(p);

        j.assertEqualDataBoundBeans(step, p.getBuildersList().get(NotionUploadStep.class));
    }
}
