package io.ohmvir.plugins.jenkinsocr;

import hudson.Extension;
import hudson.cli.CLICommand;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import jakarta.servlet.ServletException;
import java.io.IOException;
import net.sf.json.JSONObject;
import org.apache.commons.fileupload2.core.FileItem;
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
        return null;
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) throws IOException, InterruptedException {
        return null;
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req) {
        try {
            FileItem<?> item = req.getFileItem2(getName());
            if (item == null || item.getName().isEmpty()) {
                return null;
            }
            return new ImageParameterValue(getName(), item);
        } catch (ServletException | IOException e) {
            throw new IllegalArgumentException("Failed to upload image", e);
        }
    }

    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        public @NonNull String getDisplayName() {
            return "Image Upload Parameter (Camera Support)";
        }
    }
}
