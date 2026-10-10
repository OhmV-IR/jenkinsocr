package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void uploadedImageIsAvailableToTheBuild(JenkinsRule j) throws Exception {
        WorkflowJob p = createJob(j, "def ok = true");
        File png = tmp.resolve("note.png").toFile();
        ImageIO.write(new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB), "png", png);

        WorkflowRun b = buildWithUpload(j, p, png);

        j.assertBuildStatusSuccess(b);
        ImageParameterValue value = assertInstanceOf(
                ImageParameterValue.class, b.getAction(ParametersAction.class).getParameter("NOTE_IMAGE"));
        assertEquals("note.png", value.getOriginalFileName());
        BufferedImage image = value.getImageData();
        assertNotNull(image);
        assertEquals(7, image.getWidth());
        assertEquals(5, image.getHeight());
        assertNotNull(value.getImageDataPNG());
    }

    @Test
    void recognizeTextRejectsUploadsThatAreNotImages(JenkinsRule j) throws Exception {
        WorkflowJob p = createJob(j, "recognizeText(parameterName: 'NOTE_IMAGE')");
        Path text = tmp.resolve("note.txt");
        Files.writeString(text, "not an image", StandardCharsets.UTF_8);

        WorkflowRun b = buildWithUpload(j, p, text.toFile());

        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("does not contain a readable image", b);
        ImageParameterValue value = assertInstanceOf(
                ImageParameterValue.class, b.getAction(ParametersAction.class).getParameter("NOTE_IMAGE"));
        assertNull(value.getImageData());
    }
}
