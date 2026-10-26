package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class UploadNoteStepTest {
    @Test
    void runsOutsideANodeAndReportsMissingNoteProvider(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("uploadNote(text: 'x', title: 'Title', path: 'Math')", true));

        WorkflowRun b = j.buildAndAssertStatus(Result.FAILURE, p);

        j.assertLogContains("No note provider is configured", b);
    }

    @Test
    void formulaOutputTypeIsParsedLeniently(JenkinsRule j) {
        UploadNoteStep step = new UploadNoteStep("x", "Title");

        step.setFormulaOutputType(" katex ");
        assertEquals("KATEX", step.getFormulaOutputType());
        step.setFormulaOutputType("");
        assertNull(step.getFormulaOutputType());
        assertThrows(IllegalArgumentException.class, () -> step.setFormulaOutputType("markdown"));
    }

    @Test
    void pathDefaultsToEmpty(JenkinsRule j) {
        UploadNoteStep step = new UploadNoteStep("x", "Title");

        step.setPath(null);

        assertEquals("", step.getPath());
    }
}
