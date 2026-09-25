package io.ohmvir.plugins.jenkinsocr;

import hudson.model.FileParameterValue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.apache.commons.fileupload2.core.FileItem;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;

public class ImageParameterValue extends FileParameterValue {
    @DataBoundConstructor
    public ImageParameterValue(String name, FileItem file) {
        super(name, file);
    }

    public ImageParameterValue(String name, FileItem file, String filename) {
        super(name, file, filename);
    }

    public byte[] getImageDataPNG() throws IOException {
        BufferedImage originalImage = getImageData();
        if (originalImage == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            boolean success = ImageIO.write(originalImage, "png", baos);
            if (!success) {
                throw new IOException("Failed to write the PNG image");
            }
            return baos.toByteArray();
        }
    }

    public BufferedImage getImageData() throws IOException {
        BufferedImage originalImage = null;
        if (getFile2() != null) {
            try (InputStream is = getFile2().getInputStream()) {
                originalImage = ImageIO.read(is);
            }
        }
        if (originalImage == null && getLocation() != null) {
            File diskFile = new File(getLocation());
            if (diskFile.exists()) {
                originalImage = ImageIO.read(diskFile);
            }
        }

        return originalImage;
    }

    @Exported
    public String getImageDataPNGBase64() throws IOException {
        return Base64.getEncoder().encodeToString(getImageDataPNG());
    }
}
