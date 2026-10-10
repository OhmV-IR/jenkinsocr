package io.ohmvir.plugins.jenkinsocr;

import hudson.Extension;
import hudson.cli.CLICommand;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import jakarta.servlet.ServletException;
import java.io.IOException;
import net.sf.json.JSONObject;
import org.apache.commons.fileupload2.core.FileItem;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest2;

public class ImageParameterDefinition extends ParameterDefinition {

    @DataBoundConstructor
    public ImageParameterDefinition(String name) {
        super(name);
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        return createValueFromUpload(req, "Failed to upload image from form submission");
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) throws IOException, InterruptedException {
        return null;
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req) {
        return createValueFromUpload(req, "Failed to upload image");
    }

    private ParameterValue createValueFromUpload(StaplerRequest2 req, String errorMessage) {
        try {
            FileItem<?> item = req.getFileItem2(getName());
            if (item == null || item.getName() == null || item.getName().isEmpty()) {
                return null;
            }
            ImageParameterValue value = new ImageParameterValue(getName(), item);
            value.setDescription(getDescription());
            return value;
        } catch (ServletException | IOException e) {
            throw new IllegalArgumentException(errorMessage, e);
        }
    }

    @Extension
    @Symbol("imageParameter")
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        public @NonNull String getDisplayName() {
            return "Image Upload Parameter (Camera Support)";
        }
    }
}
