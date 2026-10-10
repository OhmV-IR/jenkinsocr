package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RecognizeTextBuildStepTest {
    private static WorkflowJob createJob(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("recognizeText(parameterName: 'NOTE_IMAGE')", true));
        return p;
    }

    @Test
    void failsWhenBuildHasNoParameters(JenkinsRule j) throws Exception {
        WorkflowJob p = createJob(j);

        WorkflowRun b = j.buildAndAssertStatus(Result.FAILURE, p);

        j.assertLogContains("No parameters action found in the build", b);
    }

    @Test
    void failsWhenParameterIsMissing(JenkinsRule j) throws Exception {
        WorkflowJob p = createJob(j);
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("OTHER", "x")));

        WorkflowRun b = j.buildAndAssertStatus(Result.FAILURE, p);

        j.assertLogContains("Parameter NOTE_IMAGE not found", b);
    }

    @Test
    void failsWhenParameterIsNotAnImage(JenkinsRule j) throws Exception {
        WorkflowJob p = createJob(j);
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("NOTE_IMAGE", "x")));

        WorkflowRun b = j.buildAndAssertStatus(Result.FAILURE, p);

        j.assertLogContains("Parameter NOTE_IMAGE is not an image parameter", b);
        j.assertLogNotContains("ClassCastException", b);
    }

    @Test
    void outputGettersArePermittedInTheSandbox(JenkinsRule j) throws Exception {
        RecognizeTextOutput output = new RecognizeTextOutput("text", "Math", "Title");
        for (String getter : new String[] {"getText", "getPath", "getTitle"}) {
            assertTrue(
                    Whitelist.all().permitsMethod(RecognizeTextOutput.class.getMethod(getter), output, new Object[0]),
                    getter + " should be callable from a sandboxed pipeline");
        }
    }
}
