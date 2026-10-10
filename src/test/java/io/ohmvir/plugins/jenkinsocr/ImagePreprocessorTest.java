package io.ohmvir.plugins.jenkinsocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImagePreprocessorTest {

    private static byte[] resource(String name) throws IOException {
        try (InputStream is = ImagePreprocessorTest.class.getResourceAsStream(name)) {
            return is.readAllBytes();
        }
    }

    private static boolean isMostlyRed(int rgb) {
        Color color = new Color(rgb);
        return color.getRed() > 200 && color.getBlue() < 60;
    }

    private static boolean isMostlyBlue(int rgb) {
        Color color = new Color(rgb);
        return color.getBlue() > 200 && color.getRed() < 60;
    }

    @Test
    void appliesExifOrientation() throws IOException {
        // Stored as 40x20 with the left half red; orientation 6 means "rotate 90° clockwise to display".
        BufferedImage image = ImagePreprocessor.decode(resource("exif-orientation-6.jpg"));

        assertEquals(20, image.getWidth());
        assertEquals(40, image.getHeight());
        assertTrue(isMostlyRed(image.getRGB(10, 5)), "top should be the stored left (red) half");
        assertTrue(isMostlyBlue(image.getRGB(10, 35)), "bottom should be the stored right (blue) half");
    }

    @Test
    void decodesImagesWithoutExif() throws IOException {
        BufferedImage source = new BufferedImage(30, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        BufferedImage image = ImagePreprocessor.decode(png.toByteArray());

        assertEquals(30, image.getWidth());
        assertEquals(10, image.getHeight());
    }

    @Test
    void explainsThatHeicIsUnsupportedOnTheServer() throws IOException {
        byte[] heic = resource("photo.heic");

        IOException e = assertThrows(IOException.class, () -> ImagePreprocessor.decode(heic));
        assertTrue(e.getMessage().contains("HEIC"), e.getMessage());
    }

    @Test
    void rejectsDataThatIsNotAnImage() {
        assertThrows(
                IOException.class, () -> ImagePreprocessor.decode("not an image".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void scalesLargePhotosDownToTheMaxDimension() throws IOException {
        BufferedImage photo = new BufferedImage(3000, 4000, BufferedImage.TYPE_3BYTE_BGR);

        BufferedImage fitted = ImagePreprocessor.fitForModel(photo, 1568);

        assertEquals(1176, fitted.getWidth());
        assertEquals(1568, fitted.getHeight());
    }

    @Test
    void leavesSmallImagesAlone() throws IOException {
        BufferedImage small = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);

        assertSame(small, ImagePreprocessor.fitForModel(small, 1568));
    }

    @Test
    void shrinksImagesWhoseEncodingExceedsTheSizeLimit() throws IOException {
        // Random noise barely compresses, so 1600x1600 encodes to well over 5 MB of base64 PNG.
        BufferedImage noise = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0x1000000));
            }
        }

        BufferedImage fitted = ImagePreprocessor.fitForModel(noise, ImagePreprocessor.MAX_DIMENSION_LIMIT);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(fitted, "png", png);
        long base64Length = 4L * ((png.size() + 2) / 3);
        assertTrue(fitted.getWidth() < 1600, "image should have been scaled down");
        assertTrue(base64Length <= ImagePreprocessor.MAX_ENCODED_BYTES, "encoded size " + base64Length);
    }

    @Test
    void clampsTheConfiguredMaxDimension() {
        assertEquals(ImagePreprocessor.DEFAULT_MAX_DIMENSION, ImagePreprocessor.clampMaxDimension(0));
        assertEquals(ImagePreprocessor.MAX_DIMENSION_LIMIT, ImagePreprocessor.clampMaxDimension(20000));
        assertEquals(1024, ImagePreprocessor.clampMaxDimension(1024));
    }
}
