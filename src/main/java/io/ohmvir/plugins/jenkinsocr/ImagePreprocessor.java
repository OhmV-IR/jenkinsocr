package io.ohmvir.plugins.jenkinsocr;

import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.github.gotson.nightmonkeys.heif.imageio.plugins.HeifImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.ExifFilterUtils;
import net.coobird.thumbnailator.util.exif.Orientation;

/**
 * Turns an uploaded photo into an image the model can use: decoded (including HEIC, via libheif), rotated upright
 * according to its orientation, and scaled down to fit provider limits.
 */
public final class ImagePreprocessor {
    private static final Logger LOGGER = Logger.getLogger(ImagePreprocessor.class.getName());

    /** Longest edge sent to the model by default. Claude's recommended size, and within OpenAI's and Gemini's. */
    public static final int DEFAULT_MAX_DIMENSION = 1568;

    /** Hard cap on the longest edge; providers reject larger images outright. */
    public static final int MAX_DIMENSION_LIMIT = 8000;

    /**
     * jenkinsaisynapse sends images as base64-encoded PNG. Keep that payload under 5 MB, the strictest common
     * per-image limit.
     */
    static final long MAX_ENCODED_BYTES = 5L * 1024 * 1024;

    private static final int MAX_SHRINK_ATTEMPTS = 5;

    static {
        // Plugin jars aren't on the classpath ImageIO scans, so register the libheif-backed reader explicitly.
        // It deregisters itself if libheif can't be loaded or the JVM is older than Java 22.
        IIORegistry.getDefaultInstance().registerServiceProvider(new HeifImageReaderSpi());
    }

    private ImagePreprocessor() {}

    /** Whether HEIC/HEIF/AVIF photos can be decoded, i.e. libheif was found on this machine. */
    public static boolean isHeifSupported() {
        return ImageIO.getImageReadersByFormatName("heif").hasNext();
    }

    /**
     * Decodes an uploaded image and rotates it upright, so portrait phone photos don't arrive sideways.
     *
     * @throws IOException if the data isn't an image format the server can decode
     */
    public static BufferedImage decode(byte[] data) throws IOException {
        FileType type = FileTypeDetector.detectFileType(new BufferedInputStream(new ByteArrayInputStream(data)));
        boolean decodedByLibheif = type == FileType.Heif || type == FileType.Avif;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            if (decodedByLibheif) {
                throw new IOException(
                        "Can't decode HEIC/HEIF/AVIF photos: libheif wasn't found on the Jenkins controller. "
                                + "Install libheif with its HEVC decoder (e.g. apt-get install libheif-dev "
                                + "libheif-plugin-libde265) and run Jenkins on Java 25.");
            }
            throw new IOException("Unsupported image format: " + type.getName());
        }
        if (decodedByLibheif) {
            // libheif already applies the HEIF rotation and mirroring. iPhones also write an EXIF orientation that
            // describes the same rotation, so applying it as well would turn the photo twice.
            return image;
        }

        Orientation orientation = Orientation.typeOf(readExifOrientation(data));
        if (orientation == null || orientation == Orientation.TOP_LEFT) {
            return image;
        }
        return Thumbnails.of(image)
                .scale(1.0)
                .addFilter(ExifFilterUtils.getFilterForOrientation(orientation))
                .asBufferedImage();
    }

    /**
     * Scales the image down so its longest edge is at most {@code maxDimension} and its PNG encoding fits within
     * {@link #MAX_ENCODED_BYTES}. Images that already fit are returned unchanged; images are never scaled up.
     */
    public static BufferedImage fitForModel(BufferedImage image, int maxDimension) throws IOException {
        int limit = clampMaxDimension(maxDimension);
        BufferedImage fitted = image;
        if (Math.max(image.getWidth(), image.getHeight()) > limit) {
            fitted = Thumbnails.of(image).size(limit, limit).asBufferedImage();
        }

        for (int attempt = 0; attempt < MAX_SHRINK_ATTEMPTS; attempt++) {
            long encodedSize = base64Length(pngSize(fitted));
            if (encodedSize <= MAX_ENCODED_BYTES) {
                return fitted;
            }
            // PNG size grows roughly with pixel count, so scale each edge by the square root, with some headroom.
            double factor = Math.sqrt((double) MAX_ENCODED_BYTES / encodedSize) * 0.9;
            fitted = Thumbnails.of(fitted).scale(factor).asBufferedImage();
        }
        return fitted;
    }

    static int clampMaxDimension(int maxDimension) {
        if (maxDimension <= 0) {
            return DEFAULT_MAX_DIMENSION;
        }
        return Math.min(maxDimension, MAX_DIMENSION_LIMIT);
    }

    private static int readExifOrientation(byte[] data) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data), data.length);
            for (ExifIFD0Directory directory : metadata.getDirectoriesOfType(ExifIFD0Directory.class)) {
                if (directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                    return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                }
            }
        } catch (ImageProcessingException | MetadataException | IOException e) {
            LOGGER.log(Level.FINE, "Could not read EXIF orientation, assuming the image is upright", e);
        }
        return 1;
    }

    private static long pngSize(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", baos)) {
                throw new IOException("No PNG writer available for image type " + image.getType());
            }
            return baos.size();
        }
    }

    private static long base64Length(long bytes) {
        return 4 * ((bytes + 2) / 3);
    }
}
