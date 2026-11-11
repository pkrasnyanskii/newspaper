package ru.nsu.newspaper.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Cover;
import ru.nsu.newspaper.model.ImageAsset;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.PageFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

/** Stores the currently supported issue fields in the Stage 1 .issue archive. */
public final class IssueStorage {
    private static final String FORMAT = "periodical-issue";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_JSON_BYTES = 20 * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 128L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_JSON_BYTES).build())
            .build());

    public void save(Issue issue, Path file) throws IOException {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(file, "file");
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, ".newspaper-", ".tmp");
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toDocument(issue));
            if (json.length > MAX_JSON_BYTES) {
                throw new IOException("Issue text exceeds the 20 MB file limit.");
            }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.putNextEntry(new ZipEntry("issue.json"));
                zip.write(json);
                zip.closeEntry();
                Map<String, ImageAsset> images = new LinkedHashMap<>();
                for (Article article : issue.articles()) {
                    for (ImageAsset image : article.images()) images.putIfAbsent(image.archivePath(), image);
                }
                long totalBytes = 0;
                for (ImageAsset image : images.values()) {
                    byte[] bytes = image.bytes();
                    totalBytes += bytes.length;
                    if (totalBytes > MAX_IMAGE_BYTES) {
                        throw new IOException("Issue images exceed the 128 MB file limit.");
                    }
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    ZipEntry imageEntry = new ZipEntry(image.archivePath());
                    imageEntry.setMethod(ZipEntry.STORED);
                    imageEntry.setSize(bytes.length);
                    imageEntry.setCompressedSize(bytes.length);
                    imageEntry.setCrc(crc.getValue());
                    zip.putNextEntry(imageEntry);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic save is unavailable for " + parent, e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Issue open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("issue.json");
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_JSON_BYTES) {
                throw new IOException("Missing or invalid issue.json");
            }
            byte[] data;
            try (InputStream input = zip.getInputStream(entry)) {
                data = input.readNBytes(MAX_JSON_BYTES + 1);
            }
            if (data.length > MAX_JSON_BYTES) {
                throw new IOException("issue.json is too large");
            }
            return fromDocument(mapper.readTree(data), zip);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("Invalid issue file", e);
        }
    }

    private ObjectNode toDocument(Issue issue) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("formatVersion", FORMAT_VERSION);
        root.put("id", issue.id().toString());
        root.put("issueNumber", issue.issueNumber());
        root.put("publicationDate", issue.publicationDate().toString());
        ObjectNode pageFormat = root.putObject("pageFormat");
        pageFormat.put("name", issue.pageFormat().name());
        pageFormat.put("width", issue.pageFormat().widthMm());
        pageFormat.put("height", issue.pageFormat().heightMm());
        ObjectNode cover = root.putObject("cover");
        cover.put("title", issue.cover().title());
        cover.putNull("coverImage");
        root.putArray("templates");
        var articles = root.putArray("articles");
        for (Article article : issue.articles()) {
            ObjectNode node = articles.addObject();
            node.put("id", article.id().toString());
            node.put("title", article.title());
            node.put("author", article.author());
            node.put("text", article.text());
            var images = node.putArray("images");
            article.images().forEach(image -> images.add(image.archivePath()));
            node.putArray("styledRanges");
            // Articles have no placement until page layout is implemented.
            node.putNull("position");
            node.putNull("size");
        }
        root.putArray("advertisements");
        root.putArray("pages");
        return root;
    }

    private Issue fromDocument(JsonNode root, ZipFile zip) throws IOException {
        if (root == null || !root.isObject() || !FORMAT.equals(text(root, "format"))
                || integer(root, "formatVersion") != FORMAT_VERSION) {
            throw new IOException("Unsupported issue file format");
        }
        for (String field : new String[]{"templates", "advertisements", "pages"}) {
            JsonNode array = root.path(field);
            if (!array.isArray() || !array.isEmpty()) {
                throw new IOException("This version cannot open an issue with " + field);
            }
        }
        JsonNode formatNode = root.path("pageFormat");
        PageFormat pageFormat = PageFormat.valueOf(text(formatNode, "name"));
        if (integer(formatNode, "width") != pageFormat.widthMm()
                || integer(formatNode, "height") != pageFormat.heightMm()) {
            throw new IOException("Invalid page format dimensions");
        }
        JsonNode coverNode = root.path("cover");
        if (!coverNode.path("coverImage").isNull()) {
            throw new IOException("This version cannot open cover images yet");
        }
        JsonNode articleNodes = root.path("articles");
        if (!articleNodes.isArray()) throw new IOException("Missing or invalid articles");
        List<Article> articles = new ArrayList<>();
        Map<String, ImageAsset> loadedImages = new LinkedHashMap<>();
        long totalBytes = 0;
        for (JsonNode node : articleNodes) {
            JsonNode styles = node.path("styledRanges");
            if (!styles.isArray() || !styles.isEmpty()
                    || !node.path("position").isNull() || !node.path("size").isNull()) {
                throw new IOException("This version cannot open styled or placed articles yet");
            }
            JsonNode references = node.path("images");
            if (!references.isArray()) throw new IOException("Missing or invalid article images");
            List<ImageAsset> images = new ArrayList<>();
            for (JsonNode reference : references) {
                if (!reference.isTextual()
                        || !reference.textValue().matches("images/[a-f0-9]{64}\\.(png|jpg)")) {
                    throw new IOException("Invalid article image reference");
                }
                String path = reference.textValue();
                ImageAsset image = loadedImages.get(path);
                if (image == null) {
                    ZipEntry imageEntry = zip.getEntry(path);
                    if (imageEntry == null || imageEntry.isDirectory() || imageEntry.getSize() > ImageAsset.MAX_BYTES) {
                        throw new IOException("Missing or oversized article image: " + path);
                    }
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(imageEntry)) {
                        bytes = input.readNBytes(ImageAsset.MAX_BYTES + 1);
                    }
                    totalBytes += bytes.length;
                    if (totalBytes > MAX_IMAGE_BYTES) throw new IOException("Issue images exceed the 128 MB file limit.");
                    image = ImageAsset.fromBytes(bytes);
                    if (!image.archivePath().equals(path)) throw new IOException("Article image content does not match its reference");
                    loadedImages.put(path, image);
                }
                images.add(image);
            }
            articles.add(new Article(UUID.fromString(text(node, "id")), text(node, "title"),
                    text(node, "author"), text(node, "text"), images));
        }
        return new Issue(UUID.fromString(text(root, "id")), integer(root, "issueNumber"),
                LocalDate.parse(text(root, "publicationDate")), pageFormat,
                new Cover(text(coverNode, "title")), articles);
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IOException("Missing or invalid " + field);
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("Missing or invalid " + field);
        }
        return value.intValue();
    }
}
