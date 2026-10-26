package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.htmlunit.html.HtmlFileInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ImageParameterDefinitionTest {
    @TempDir
    Path tmp;

    private static WorkflowJob createJob(JenkinsRule j, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.addProperty(new ParametersDefinitionProperty(new ImageParameterDefinition("NOTE_IMAGE")));
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    private static WorkflowRun buildWithUpload(JenkinsRule j, WorkflowJob p, File upload) throws Exception {
        try (JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false)) {
            HtmlPage page = wc.goTo("job/" + p.getName() + "/build?delay=0sec");
            HtmlForm form = page.getFormByName("parameters");
            HtmlFileInput input = form.getInputByName("NOTE_IMAGE");
            assertEquals("image/*", input.getAttribute("accept"));
            input.setFiles(upload);
            j.submit(form);
        }
        j.waitUntilNoActivity();
        WorkflowRun b = p.getBuildByNumber(1);
        assertNotNull(b, "submitting the parameters form should start a build");
        return b;
    }

    @Test
    void uploadedImageIsAvailableWhileTheBuildRuns(JenkinsRule j) throws Exception {
        // Not sandboxed so the script can read the parameter value directly. The image is never kept in a local
        // variable because BufferedImage is not serializable and the program is saved at the echo step.
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.addProperty(new ParametersDefinitionProperty(new ImageParameterDefinition("NOTE_IMAGE")));
        p.setDefinition(new CpsFlowDefinition(
                "def value = currentBuild.rawBuild.getAction(hudson.model.ParametersAction).getParameter('NOTE_IMAGE')\n"
                        + "echo \"size=${value.imageData.width}x${value.imageData.height}"
                        + " png=${value.imageDataPNG.length > 0}\"",
                false));
        File png = tmp.resolve("note.png").toFile();
        ImageIO.write(new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB), "png", png);

        WorkflowRun b = buildWithUpload(j, p, png);

        j.assertBuildStatusSuccess(b);
        j.assertLogContains("size=7x5 png=true", b);
        ImageParameterValue value = assertInstanceOf(
                ImageParameterValue.class, b.getAction(ParametersAction.class).getParameter("NOTE_IMAGE"));
        assertEquals("note.png", value.getOriginalFileName());
    }

    @Test
    void recognizeTextRejectsUploadsThatAreNotImages(JenkinsRule j) throws Exception {
        WorkflowJob p = createJob(j, "recognizeText(parameterName: 'NOTE_IMAGE')");
        Path text = tmp.resolve("note.txt");
        Files.writeString(text, "not an image", StandardCharsets.UTF_8);

        WorkflowRun b = buildWithUpload(j, p, text.toFile());

        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("is not in a supported image format", b);
    }
}
