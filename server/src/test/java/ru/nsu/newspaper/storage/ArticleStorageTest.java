package ru.nsu.newspaper.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.ImageAsset;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.PageFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ArticleStorageTest {
    @TempDir Path folder;
    private final IssueStorage storage = new IssueStorage();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsArticleOrderIdentityUnicodeTextAndEmbeddedImages() throws IOException {
        ImageAsset image = image(0x3366aa);
        Article first = Article.create("Университетская газета", "Богдан", "  Абзац 1\n\n«Абзац 2» 🌍\n", List.of(image));
        Article second = Article.create("Second", "", "Draft body", List.of(image));
        Issue issue = emptyIssue().addArticle(first).addArticle(second);
        Path file = folder.resolve("articles.issue");
        storage.save(issue, file);
        assertEquals(issue, storage.open(file));
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(1, zip.stream().filter(entry -> entry.getName().startsWith("images/")).count());
            assertEquals(ZipEntry.STORED, zip.getEntry(image.archivePath()).getMethod());
        }
    }

    @Test
    void editedDeletedAndDetachedContentStaysChangedAfterReopening() throws IOException {
        ImageAsset firstImage = image(0x3366aa);
        ImageAsset unusedImage = image(0xaa6633);
        Article first = Article.create("First", "", "Original", List.of(firstImage, unusedImage));
        Article second = Article.create("Delete me", "", "", List.of(unusedImage));
        Issue issue = emptyIssue().addArticle(first).addArticle(second);
        Path file = folder.resolve("changes.issue");
        storage.save(issue, file);
        Issue changed = storage.open(file)
                .updateArticle(first.withContent("Revised", "New author", "New body", List.of(firstImage)))
                .removeArticle(second.id());
        storage.save(changed, file);
        assertEquals(changed, storage.open(file));
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertNull(zip.getEntry(unusedImage.archivePath()));
            assertNotNull(zip.getEntry(firstImage.archivePath()));
        }
    }

    @Test
    void rejectsBlankTitlesAndDuplicateIdsInArchives() throws IOException {
        Path blank = textIssue();
        rewrite(blank, root -> ((ObjectNode) root.path("articles").get(0)).put("title", " \n "), Map.of());
        assertThrows(IOException.class, () -> storage.open(blank));
        Path file = textIssue();
        rewrite(file, root -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("articles"))
                .add(root.path("articles").get(0).deepCopy()), Map.of());
        Path duplicate = file;
        assertThrows(IOException.class, () -> storage.open(duplicate));
    }

    @Test
    void refusesMissingAndMismatchedImages() throws IOException {
        ImageAsset wanted = image(0x112233);
        Path file = folder.resolve("images.issue");
        storage.save(emptyIssue().addArticle(Article.create("Image", "", "", List.of(wanted))), file);
        rewrite(file, root -> {}, Map.of());
        assertThrows(IOException.class, () -> storage.open(file));
        rewrite(file, root -> {}, Map.of(wanted.archivePath(), image(0x445566).bytes()));
        assertThrows(IOException.class, () -> storage.open(file));
    }

    @Test
    void doesNotSilentlyDiscardFutureStylesOrPlacements() throws IOException {
        Path file = textIssue();
        rewrite(file, root -> ((ObjectNode) root.path("articles").get(0))
                .putArray("styledRanges").addObject().put("start", 0), Map.of());
        assertThrows(IOException.class, () -> storage.open(file));
        Path placed = textIssue();
        rewrite(placed, root -> ((ObjectNode) root.path("articles").get(0)).putObject("position").put("x", 10), Map.of());
        assertThrows(IOException.class, () -> storage.open(placed));
    }

    @Test
    void longTextWithinTheArchiveLimitCanBeReopened() throws IOException {
        String text = "x".repeat(20_100_000);
        Issue issue = emptyIssue().addArticle(Article.create("Long article", "", text, List.of()));
        Path file = folder.resolve("long.issue");
        storage.save(issue, file);
        assertEquals(issue, storage.open(file));
    }

    @Test
    void failedOversizeSaveKeepsPreviousFileAndRemovesTemporaryFile() throws IOException {
        Path file = textIssue();
        byte[] originalBytes = Files.readAllBytes(file);
        Issue oversized = emptyIssue().addArticle(Article.create("Too large", "", "x".repeat(21 * 1024 * 1024), List.of()));
        assertThrows(IOException.class, () -> storage.save(oversized, file));
        assertArrayEquals(originalBytes, Files.readAllBytes(file));
        try (var files = Files.list(folder)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".newspaper-")));
        }
    }

    private Issue emptyIssue() {
        return Issue.create(42, LocalDate.of(2026, 11, 11), PageFormat.A4, "News");
    }

    private Path textIssue() throws IOException {
        Path file = folder.resolve("text.issue");
        storage.save(emptyIssue().addArticle(Article.create("News", "", "Text", List.of())), file);
        return file;
    }

    private ImageAsset image(int color) throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return ImageAsset.fromBytes(out.toByteArray());
    }

    private void rewrite(Path file, Consumer<ObjectNode> change, Map<String, byte[]> images) throws IOException {
        ObjectNode document;
        try (ZipFile zip = new ZipFile(file.toFile()); var input = zip.getInputStream(zip.getEntry("issue.json"))) {
            document = (ObjectNode) mapper.readTree(input);
        }
        change.accept(document);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("issue.json"));
            zip.write(mapper.writeValueAsBytes(document));
            zip.closeEntry();
            for (var image : images.entrySet()) {
                zip.putNextEntry(new ZipEntry(image.getKey()));
                zip.write(image.getValue());
                zip.closeEntry();
            }
        }
    }
}
