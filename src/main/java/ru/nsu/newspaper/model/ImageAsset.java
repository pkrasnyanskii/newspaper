package ru.nsu.newspaper.model;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** An immutable embedded PNG or JPEG, independent of its original file location. */
public final class ImageAsset {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final long MAX_PIXELS = 25_000_000;
    private final byte[] bytes;
    private final String archivePath;

    private ImageAsset(byte[] bytes, String extension) {
        this.bytes = bytes;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            archivePath = "images/" + hash + "." + extension;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static ImageAsset fromBytes(byte[] source) throws IOException {
        if (source.length == 0 || source.length > MAX_BYTES) {
            throw new IOException("Choose a PNG or JPEG image smaller than 20 MB.");
        }
        byte[] bytes = source.clone();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("This file is not a readable PNG or JPEG image.");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new IOException("Only PNG and JPEG images are supported.");
                }
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) {
                    throw new IOException("Image dimensions must not exceed 25 megapixels.");
                }
                reader.read(0); // Validate pixel data as well as the file header.
                return new ImageAsset(bytes, format.equals("jpeg") ? "jpg" : "png");
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("Cannot read this image.", e);
        }
    }

    public byte[] bytes() { return bytes.clone(); }
    public String archivePath() { return archivePath; }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageAsset image && Arrays.equals(bytes, image.bytes);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(bytes); }
}
