package io.ohmvir.plugins.jenkinsocr;

import hudson.EnvVars;
import hudson.model.ParameterValue;
import hudson.model.Run;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.apache.commons.fileupload2.core.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * An image uploaded when starting a build. The image is kept on disk by {@link ImageUploadStorage} (so it survives
 * a Jenkins restart) and is deleted once the build that received it finishes.
 */
@ExportedBean
public class ImageParameterValue extends ParameterValue {

    private static final long serialVersionUID = 1L;

    private final String originalFileName;

    /** Id of the stored upload in {@link ImageUploadStorage}. */
    private final String uploadId;

    public ImageParameterValue(String name, FileItem file) throws IOException {
        this(name, file, FilenameUtils.getName(file.getName()));
    }

    public ImageParameterValue(String name, FileItem file, String filename) throws IOException {
        super(name);
        this.originalFileName = filename;
        try (InputStream is = file.getInputStream()) {
            this.uploadId = ImageUploadStorage.store(is);
        }
    }

    @Exported
    public String getOriginalFileName() {
        return originalFileName;
    }

    String getUploadId() {
        return uploadId;
    }

    /** @return whether the uploaded image is still stored, i.e. the build that received it has not finished yet */
    public boolean isAvailable() {
        return uploadId != null && ImageUploadStorage.exists(uploadId);
    }

    @Override
    public Object getValue() {
        return originalFileName;
    }

    @Override
    public void buildEnvironment(Run<?, ?> build, EnvVars env) {
        if (originalFileName != null) {
            env.put(name, originalFileName);
        }
    }

    public BufferedImage getImageData() throws IOException {
        if (!isAvailable()) {
            throw new IOException("The image uploaded for parameter " + getName()
                    + " is no longer available. Uploaded images are deleted when the build that received them"
                    + " finishes, so start a new build and upload the image again.");
        }
        File imageFile = ImageUploadStorage.getImageFile(uploadId);
        BufferedImage image = ImageIO.read(imageFile);
        if (image == null) {
            throw new IOException("The file uploaded for parameter " + getName() + " (" + originalFileName
                    + ") is not in a supported image format");
        }
        return image;
    }

    public byte[] getImageDataPNG() throws IOException {
        BufferedImage originalImage = getImageData();
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
        if (!isAvailable()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(getImageDataPNG());
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), uploadId);
    }

    /** Only values referring to the same upload are equal, so separate uploads are never merged in the queue. */
    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj) || getClass() != obj.getClass()) {
            return false;
        }
        return Objects.equals(uploadId, ((ImageParameterValue) obj).uploadId);
    }

    @Override
    public String toString() {
        return "(ImageParameterValue) " + getName() + "='" + originalFileName + "'";
    }

    @Override
    public String getShortDescription() {
        return name + "=" + originalFileName;
    }
}
