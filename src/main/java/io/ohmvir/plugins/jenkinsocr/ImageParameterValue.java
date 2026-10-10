package io.ohmvir.plugins.jenkinsocr;

import hudson.model.FileParameterValue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.apache.commons.fileupload2.core.FileItem;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class ImageParameterValue extends FileParameterValue {

    @DataBoundConstructor
    public ImageParameterValue(String name, FileItem file) {
        super(name, file);
    }

    public ImageParameterValue(String name, FileItem file, String filename) {
        super(name, file, filename);
    }

    /**
     * Returns the uploaded image, rotated upright according to its EXIF orientation, or {@code null} if no file
     * was uploaded.
     */
    public BufferedImage getImageData() throws IOException {
        byte[] data = null;

        if (getFile2() != null) {
            try (InputStream is = getFile2().getInputStream()) {
                data = is.readAllBytes();
            }
        }

        if ((data == null || data.length == 0) && getLocation() != null) {
            File diskFile = new File(getLocation());
            if (diskFile.exists()) {
                data = Files.readAllBytes(diskFile.toPath());
            }
        }

        return data == null || data.length == 0 ? null : ImagePreprocessor.decode(data);
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

    @Exported
    public String getImageDataPNGBase64() throws IOException {
        byte[] pngData = getImageDataPNG();
        if (pngData == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(pngData);
    }
}
