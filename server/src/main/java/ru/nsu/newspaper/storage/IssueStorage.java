package ru.nsu.newspaper.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Advertisement;
import ru.nsu.newspaper.model.Cover;
import ru.nsu.newspaper.model.ImageAsset;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.PageFormat;
import ru.nsu.newspaper.model.Page;
import ru.nsu.newspaper.model.PageTemplate;
import ru.nsu.newspaper.model.StyledRange;
import ru.nsu.newspaper.model.TextStyle;

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

/** Reads and writes the Stage 1 .issue archive. */
public final class IssueStorage {
    private static final String FORMAT = "periodical-issue";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_JSON_BYTES = 20 * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 128L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_JSON_BYTES).build())
            .build());

    public void validate(Issue issue) throws IOException {
        if (mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toDocument(issue)).length > MAX_JSON_BYTES) {
            throw new IOException("Issue text exceeds the 20 MB file limit.");
        }
        if (images(issue).values().stream().mapToLong(ImageAsset::byteSize).sum() > MAX_IMAGE_BYTES) {
            throw new IOException("Issue images exceed the 128 MB file limit.");
        }
    }

    public void save(Issue issue, Path file) throws IOException {
        saveRecovery(issue, file, null);
    }

    void saveRecovery(Issue issue, Path file, byte[] recoveryMetadata) throws IOException {
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
                if (recoveryMetadata != null) {
                    zip.putNextEntry(new ZipEntry("recovery.json"));
                    zip.write(recoveryMetadata);
                    zip.closeEntry();
                }
                long totalBytes = 0;
                for (ImageAsset image : images(issue).values()) {
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
        writeImage(cover, "coverImage", issue.cover().image());
        var templates = root.putArray("templates");
        for (PageTemplate template : issue.templates()) {
            ObjectNode node = templates.addObject();
            node.put("id", template.id().toString());
            node.put("name", template.name());
            writeLayout(node, template.layout());
        }
        var boxes = new LinkedHashMap<UUID, Page.Box>();
        var pages = root.putArray("pages");
        for (Page page : issue.pages()) {
            ObjectNode node = pages.addObject();
            node.put("id", page.id().toString());
            writeLayout(node, page.layout());
            node.put("templateId", page.templateId() == null ? null : page.templateId().toString());
            var elements = node.putArray("elements");
            var standalone = node.putArray("imageElements");
            for (Page.Placement item : page.placements()) {
                elements.add(item.elementId().toString());
                if (item.image() == null) {
                    boxes.put(item.elementId(), item.box());
                } else {
                    ObjectNode image = standalone.addObject();
                    image.put("id", item.elementId().toString());
                    writeImage(image, "image", item.image());
                    writeBox(image, item.box());
                }
            }
        }
        var articles = root.putArray("articles");
        for (Article article : issue.articles()) {
            ObjectNode node = articles.addObject();
            node.put("id", article.id().toString());
            node.put("title", article.title());
            node.put("author", article.author());
            node.put("text", article.text());
            var images = node.putArray("images");
            article.images().forEach(image -> images.add(image.archivePath()));
            var styles = node.putArray("styledRanges");
            article.styledRanges().forEach(range -> styles.add(mapper.valueToTree(range)));
            writeBox(node, boxes.get(article.id()));
        }
        var advertisements = root.putArray("advertisements");
        for (Advertisement ad : issue.advertisements()) {
            ObjectNode node = advertisements.addObject();
            node.put("id", ad.id().toString());
            node.put("text", ad.text());
            writeImage(node, "image", ad.image());
            writeBox(node, boxes.get(ad.id()));
        }
        return root;
    }

    private static Map<String, ImageAsset> images(Issue issue) {
        Map<String, ImageAsset> images = new LinkedHashMap<>();
        issue.imageAssets().forEach(image -> images.putIfAbsent(image.archivePath(), image));
        return images;
    }

    private static void writeImage(ObjectNode node, String field, ImageAsset image) {
        node.put(field, image == null ? null : image.archivePath());
    }

    private void writeLayout(ObjectNode node, Page.Layout layout) {
        node.put("columns", layout.columns());
        node.set("margins", mapper.valueToTree(layout.margins()));
        node.put("headerFooterText", layout.headerFooterText());
    }

    private static void writeBox(ObjectNode node, Page.Box box) {
        if (box == null) {
            node.putNull("position");
            node.putNull("size");
        } else {
            node.putObject("position").put("x", box.x()).put("y", box.y());
            node.putObject("size").put("width", box.width()).put("height", box.height());
        }
    }

    private Issue fromDocument(JsonNode root, ZipFile zip) throws IOException {
        if (root == null || !root.isObject() || !FORMAT.equals(text(root, "format"))) {
            throw new IOException("Unsupported issue file format");
        }
        int version = integer(root, "formatVersion");
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IOException("Unsupported issue file version " + version);
        }
        JsonNode formatNode = root.path("pageFormat");
        PageFormat pageFormat = PageFormat.valueOf(text(formatNode, "name"));
        if (integer(formatNode, "width") != pageFormat.widthMm()
                || integer(formatNode, "height") != pageFormat.heightMm()) {
            throw new IOException("Invalid page format dimensions");
        }
        JsonNode coverNode = root.path("cover");
        var assets = new ArchiveImages(zip);
        var cover = new Cover(text(coverNode, "title"), assets.readOptional(coverNode.path("coverImage")));
        var placed = new LinkedHashMap<UUID, Page.Placement>();
        List<Article> articles = new ArrayList<>();
        for (JsonNode node : array(root, "articles")) {
            UUID id = id(node);
            List<ImageAsset> images = new ArrayList<>();
            for (JsonNode reference : array(node, "images")) images.add(assets.read(reference));
            List<StyledRange> ranges = new ArrayList<>();
            for (JsonNode value : array(node, "styledRanges")) {
                JsonNode style = value.path("style");
                ranges.add(new StyledRange(integer(value, "start"), integer(value, "end"),
                        new TextStyle(integer(style, "fontSize"), TextStyle.Weight.valueOf(text(style, "fontWeight")))));
            }
            articles.add(new Article(id, text(node, "title"), text(node, "author"), text(node, "text"), images, ranges));
            readPlacement(placed, node, id);
        }
        List<Advertisement> advertisements = new ArrayList<>();
        for (JsonNode node : array(root, "advertisements")) {
            UUID id = id(node);
            advertisements.add(new Advertisement(id, text(node, "text"), assets.readOptional(node.path("image"))));
            readPlacement(placed, node, id);
        }
        List<PageTemplate> templates = new ArrayList<>();
        for (JsonNode node : array(root, "templates")) {
            templates.add(new PageTemplate(id(node), text(node, "name"), readLayout(node)));
        }
        List<Page> pages = new ArrayList<>();
        for (JsonNode node : array(root, "pages")) {
            Map<UUID, Page.Placement> standalone = new LinkedHashMap<>();
            for (JsonNode image : array(node, "imageElements")) {
                UUID id = id(image);
                var item = new Page.Placement(id, readBox(image), assets.read(image.path("image")));
                if (standalone.putIfAbsent(id, item) != null) throw new IOException("Duplicate image block");
            }
            List<Page.Placement> items = new ArrayList<>();
            for (JsonNode element : array(node, "elements")) {
                if (!element.isTextual()) throw new IOException("Invalid page element id");
                UUID id = UUID.fromString(element.textValue());
                Page.Placement item = standalone.remove(id);
                if (item == null) item = placed.remove(id);
                if (item == null) throw new IOException("Missing or repeated page element " + id);
                items.add(item);
            }
            if (!standalone.isEmpty()) throw new IOException("Unreferenced image block");
            UUID templateId = node.path("templateId").isNull() ? null : UUID.fromString(text(node, "templateId"));
            pages.add(new Page(id(node), readLayout(node), templateId, items));
        }
        if (!placed.isEmpty()) throw new IOException("Positioned blocks must belong to a page");
        return new Issue(id(root), integer(root, "issueNumber"), LocalDate.parse(text(root, "publicationDate")),
                pageFormat, cover, articles, advertisements, pages, templates);
    }

    private static void readPlacement(Map<UUID, Page.Placement> placed, JsonNode node, UUID id) throws IOException {
        if (node.path("position").isNull() && node.path("size").isNull()) return;
        if (placed.putIfAbsent(id, new Page.Placement(id, readBox(node), null)) != null) {
            throw new IOException("Repeated positioned element " + id);
        }
    }

    private static Page.Box readBox(JsonNode node) throws IOException {
        JsonNode position = node.path("position");
        JsonNode size = node.path("size");
        return new Page.Box(number(position, "x"), number(position, "y"),
                number(size, "width"), number(size, "height"));
    }

    private static Page.Layout readLayout(JsonNode node) throws IOException {
        JsonNode margins = node.path("margins");
        return new Page.Layout(integer(node, "columns"),
                new Page.Margins(number(margins, "top"), number(margins, "right"),
                        number(margins, "bottom"), number(margins, "left")), text(node, "headerFooterText"));
    }

    private static double number(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isNumber()) throw new IOException("Missing or invalid " + field);
        return value.doubleValue();
    }

    private static UUID id(JsonNode node) throws IOException {
        return UUID.fromString(text(node, "id"));
    }

    private static JsonNode array(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isArray()) throw new IOException("Missing or invalid " + field);
        return value;
    }

    private static final class ArchiveImages {
        private final ZipFile zip;
        private final Map<String, ImageAsset> loaded = new LinkedHashMap<>();
        private long totalBytes;

        ArchiveImages(ZipFile zip) {
            this.zip = zip;
        }

        ImageAsset readOptional(JsonNode reference) throws IOException {
            return reference.isNull() ? null : read(reference);
        }

        ImageAsset read(JsonNode reference) throws IOException {
            if (!reference.isTextual() || !reference.textValue().matches("images/[a-f0-9]{64}\\.(png|jpg)")) {
                throw new IOException("Invalid image reference");
            }
            String path = reference.textValue();
            ImageAsset existing = loaded.get(path);
            if (existing != null) return existing;
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory() || entry.getSize() > ImageAsset.MAX_BYTES) {
                throw new IOException("Missing or oversized image: " + path);
            }
            byte[] bytes;
            try (InputStream input = zip.getInputStream(entry)) {
                bytes = input.readNBytes(ImageAsset.MAX_BYTES + 1);
            }
            totalBytes += bytes.length;
            if (totalBytes > MAX_IMAGE_BYTES) throw new IOException("Issue images exceed the 128 MB file limit.");
            ImageAsset image = ImageAsset.fromBytes(bytes);
            if (!image.archivePath().equals(path)) throw new IOException("Image content does not match its reference");
            loaded.put(path, image);
            return image;
        }
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
