package io.ohmvir.plugins.jenkinsocr;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import io.github.valuya.heic.HeicDecodingOptions;
import io.github.valuya.heic.HeicFormatException;
import io.github.valuya.heic.HeicImage;
import io.github.valuya.heic.PixelRegion;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.ExifFilterUtils;
import net.coobird.thumbnailator.util.exif.Orientation;

/**
 * Turns an uploaded photo into an image the model can use: decoded (including HEIC), rotated upright according to
 * its orientation, and scaled down to fit provider limits. Everything is pure Java, so no native libraries are needed
 * on the controller.
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

    /**
     * Largest HEIC photo, in pixels, that will be decoded (about 268 MB of pixel data). Generous for phone photos,
     * which are at most 48 MP, while refusing files that would exhaust the controller's memory.
     */
    static final long MAX_HEIC_PIXELS = 64L * 1024 * 1024;

    private static final HeicDecodingOptions HEIC_OPTIONS =
            HeicDecodingOptions.DEFAULT.withMaxPixelCount(MAX_HEIC_PIXELS);

    private ImagePreprocessor() {}

    /**
     * Decodes an uploaded image, including HEIC, and rotates it upright, so portrait phone photos don't arrive
     * sideways.
     *
     * @return the decoded image, or {@code null} if the data isn't in a format the server can decode
     * @throws IOException if the image is in a supported format but can't be decoded
     */
    public static BufferedImage decode(byte[] data) throws IOException {
        if (HeicImage.canLoad(data)) {
            // The decoder applies the HEIF rotation and mirroring itself. iPhones also write an EXIF orientation
            // describing the same rotation, so it must not be applied a second time.
            return decodeHeic(data);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            return null;
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

    private static BufferedImage decodeHeic(byte[] data) throws IOException {
        try {
            HeicImage heic = HeicImage.load(data, HEIC_OPTIONS);
            int width = heic.getWidth();
            int height = heic.getHeight();
            // Photos have no transparency, and dropping alpha keeps the PNG sent to the model smaller.
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            heic.decodeArgb(PixelRegion.ofSize(width, height), pixels, 0, width);
            return image;
        } catch (HeicFormatException e) {
            throw new IOException("Could not decode the HEIC photo: " + e.getMessage(), e);
        }
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
