package ru.nsu.newspaper.model;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ImageAssetTest {
    @Test
    void recognizesPngAndJpegByContent() throws IOException {
        assertTrue(ImageAsset.fromBytes(imageBytes("png")).archivePath().matches("images/[a-f0-9]{64}\\.png"));
        assertTrue(ImageAsset.fromBytes(imageBytes("jpeg")).archivePath().matches("images/[a-f0-9]{64}\\.jpg"));
    }

    @Test
    void rejectsInvalidAndUnsupportedImageData() throws IOException {
        assertThrows(IOException.class, () -> ImageAsset.fromBytes("not an image".getBytes()));
        assertThrows(IOException.class, () -> ImageAsset.fromBytes(new byte[0]));
        assertThrows(IOException.class, () -> ImageAsset.fromBytes(imageBytes("gif")));
    }

    @Test
    void ownsItsBytesAndUsesContentIdentity() throws IOException {
        byte[] supplied = imageBytes("png");
        ImageAsset image = ImageAsset.fromBytes(supplied);
        ImageAsset copy = ImageAsset.fromBytes(supplied);
        supplied[0] = 0;
        byte[] returned = image.bytes();
        returned[0] = 0;
        assertEquals(copy, image);
        assertArrayEquals(copy.bytes(), image.bytes());
        assertEquals(copy.archivePath(), image.archivePath());
    }

    private byte[] imageBytes(String format) throws IOException {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x33aa66);
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }
}
