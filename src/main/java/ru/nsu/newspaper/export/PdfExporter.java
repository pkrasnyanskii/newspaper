package ru.nsu.newspaper.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import ru.nsu.newspaper.model.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PdfExporter {
    private static final float POINTS_PER_MM = 72f / 25.4f;
    private static final float PADDING = 4f;
    private static final TextStyle SMALL = new TextStyle(9, TextStyle.Weight.NORMAL);

    public void export(Issue issue, Path file) throws IOException {
        if (issue.pages().isEmpty()) throw new IllegalArgumentException("Add at least one page before exporting.");
        Path target = file.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".newspaper-pdf-", ".tmp");
        try {
            try (PDDocument document = new PDDocument()) {
                Renderer renderer = new Renderer(document, issue);
                renderer.cover();
                for (int i = 0; i < issue.pages().size(); i++) renderer.page(i);
                document.getDocumentInformation().setTitle(issue.cover().title());
                document.save(temporary.toFile());
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public BufferedImage previewPage(Issue issue, int index) throws IOException {
        try (PDDocument document = new PDDocument()) {
            new Renderer(document, issue).page(index);
            float width = document.getPage(0).getMediaBox().getWidth();
            return new PDFRenderer(document).renderImage(0, 650 / width);
        }
    }

    private static final class Renderer {
        private final PDDocument document;
        private final Issue issue;
        private final float width;
        private final float height;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final Map<String, PDImageXObject> images = new HashMap<>();
        private final Map<GlyphKey, Glyph> glyphs = new HashMap<>();
        private final Map<java.util.UUID, Article> articles = new HashMap<>();
        private final Map<java.util.UUID, Advertisement> advertisements = new HashMap<>();

        Renderer(PDDocument document, Issue issue) throws IOException {
            this.document = document;
            this.issue = issue;
            width = points(issue.pageFormat().widthMm());
            height = points(issue.pageFormat().heightMm());
            regular = font("NotoSans-Regular.ttf");
            bold = font("NotoSans-Bold.ttf");
            issue.articles().forEach(article -> articles.put(article.id(), article));
            issue.advertisements().forEach(ad -> advertisements.put(ad.id(), ad));
        }

        void cover() throws IOException {
            float inset = points(15);
            try (var stream = sheet()) {
                float cursor = text(stream, issue.cover().title(), List.of(), new TextStyle(42, TextStyle.Weight.BOLD),
                        inset, inset, width - 2 * inset, height / 3, 1);
                String details = "Issue " + issue.issueNumber() + " · " + issue.publicationDate();
                cursor = text(stream, details, List.of(), new TextStyle(15, TextStyle.Weight.NORMAL),
                        inset, cursor + 10, width - 2 * inset, height / 2, 1);
                if (issue.cover().image() != null) {
                    picture(stream, issue.cover().image(), inset, cursor + 16,
                            width - 2 * inset, height - inset - cursor - 16);
                }
            }
        }

        void page(int index) throws IOException {
            Page page = issue.pages().get(index);
            Page.Box printable = page.printableArea(issue.pageFormat());
            float left = points(printable.x());
            float top = points(printable.y());
            float usableWidth = points(printable.width());
            float bottom = points(printable.y() + printable.height());
            try (var stream = sheet()) {
                float header = Math.max(2, top - 19);
                float footer = Math.min(height - 14, bottom + 5);
                text(stream, issue.pageHeader(page), List.of(), SMALL, left, header, usableWidth, header + 12, 1);
                text(stream, "Page " + (index + 1), List.of(), SMALL, left, footer, usableWidth, footer + 12, 1);
                for (Page.Placement item : page.placements()) renderBlock(stream, item, page.layout().columns());
            }
        }

        private void renderBlock(PDPageContentStream stream, Page.Placement item, int columns) throws IOException {
            Page.Box box = item.box();
            float left = points(box.x());
            float top = points(box.y());
            float w = points(box.width());
            float h = points(box.height());
            stream.saveGraphicsState();
            stream.addRect(left, height - top - h, w, h);
            stream.clip();
            if (item.image() != null) {
                picture(stream, item.image(), left, top, w, h);
            } else if (articles.containsKey(item.elementId())) {
                article(stream, articles.get(item.elementId()), columns, left, top, w, h);
            } else {
                advertisement(stream, advertisements.get(item.elementId()), left, top, w, h);
            }
            stream.restoreGraphicsState();
        }

        private void article(PDPageContentStream stream, Article article, int columns,
                             float left, float top, float w, float h) throws IOException {
            float end = top + h;
            float imageHeight = article.images().isEmpty() ? 0 : h / 3;
            float textEnd = end - imageHeight;
            float cursor = text(stream, article.title(), List.of(), new TextStyle(18, TextStyle.Weight.BOLD),
                    left, top, w, textEnd, 1);
            if (!article.author().isBlank()) {
                cursor = text(stream, article.author(), List.of(), SMALL, left, cursor, w, textEnd, 1);
            }
            text(stream, article.text(), article.styledRanges(), TextStyle.DEFAULT,
                    left, cursor + PADDING, w, textEnd, columns);
            float imageWidth = w / Math.max(1, article.images().size());
            for (int i = 0; i < article.images().size(); i++) {
                picture(stream, article.images().get(i), left + i * imageWidth, textEnd,
                        imageWidth - PADDING, imageHeight);
            }
        }

        private void advertisement(PDPageContentStream stream, Advertisement ad, float left, float top,
                                   float w, float h) throws IOException {
            stream.setNonStrokingColor(new Color(245, 245, 245));
            stream.addRect(left, height - top - h, w, h);
            stream.fill();
            stream.setNonStrokingColor(Color.BLACK);
            float textHeight = ad.image() == null ? h : ad.text().isBlank() ? 0 : h / 2;
            text(stream, ad.text(), List.of(), TextStyle.DEFAULT, left + PADDING, top + PADDING,
                    w - 2 * PADDING, top + textHeight, 1);
            if (ad.image() != null) picture(stream, ad.image(), left, top + textHeight, w, h - textHeight);
        }

        private float text(PDPageContentStream stream, String value, List<StyledRange> styles, TextStyle defaultStyle,
                           float x, float top, float availableWidth, float bottom, int columns) throws IOException {
            float gutter = columns == 1 ? 0 : Math.min(10, availableWidth / (columns * 4));
            float columnWidth = (availableWidth - (columns - 1) * gutter) / columns;
            if (columnWidth <= 0) return top;
            TextCursor text = new TextCursor(value, styles, defaultStyle, this);
            float y = top;
            for (int column = 0; column < columns && text.hasText(); column++) {
                y = top;
                float columnX = x + column * (columnWidth + gutter);
                while (text.hasText()) {
                    int start = text.offset;
                    Line line = text.line(columnWidth);
                    if (y + line.height() > bottom) {
                        text.offset = start;
                        break;
                    }
                    drawLine(stream, line, columnX, y);
                    y += line.height();
                }
            }
            return y;
        }

        private void drawLine(PDPageContentStream stream, Line line, float x, float y) throws IOException {
            if (line.glyphs().isEmpty()) return;
            stream.beginText();
            stream.newLineAtOffset(x, height - y - line.ascent());
            TextStyle active = null;
            StringBuilder run = new StringBuilder();
            for (Glyph glyph : line.glyphs()) {
                if (!glyph.style().equals(active)) {
                    if (!run.isEmpty()) stream.showText(run.toString());
                    run.setLength(0);
                    active = glyph.style();
                    stream.setFont(active.fontWeight() == TextStyle.Weight.BOLD ? bold : regular, active.fontSize());
                }
                run.append(glyph.value());
            }
            if (!run.isEmpty()) stream.showText(run.toString());
            stream.endText();
        }

        private Glyph glyph(int codePoint, TextStyle style) throws IOException {
            GlyphKey key = new GlyphKey(codePoint, style);
            Glyph result = glyphs.get(key);
            if (result == null) {
                PDType0Font font = style.fontWeight() == TextStyle.Weight.BOLD ? bold : regular;
                String value = new String(Character.toChars(codePoint));
                float width;
                try {
                    width = font.getStringWidth(value);
                } catch (IllegalArgumentException unsupported) {
                    value = "?";
                    width = font.getStringWidth(value);
                }
                result = new Glyph(value, style, width * style.fontSize() / 1000);
                glyphs.put(key, result);
            }
            return result;
        }

        private PDPageContentStream sheet() throws IOException {
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            return new PDPageContentStream(document, page);
        }

        private PDType0Font font(String name) throws IOException {
            try (InputStream input = PdfExporter.class.getResourceAsStream("/ru/nsu/newspaper/fonts/" + name)) {
                return PDType0Font.load(document, Objects.requireNonNull(input, name));
            }
        }

        private void picture(PDPageContentStream stream, ImageAsset asset,
                             float left, float top, float w, float h) throws IOException {
            if (w <= 0 || h <= 0) return;
            PDImageXObject image = images.get(asset.archivePath());
            if (image == null) {
                image = PDImageXObject.createFromByteArray(document, asset.bytes(), asset.archivePath());
                images.put(asset.archivePath(), image);
            }
            float scale = Math.min(w / image.getWidth(), h / image.getHeight());
            float drawnWidth = scale * image.getWidth();
            float drawnHeight = scale * image.getHeight();
            stream.drawImage(image, left + (w - drawnWidth) / 2, height - top - (h + drawnHeight) / 2,
                    drawnWidth, drawnHeight);
        }
    }

    private static final class TextCursor {
        private final String value;
        private final List<StyledRange> styles;
        private final TextStyle base;
        private final Renderer renderer;
        private int offset;

        TextCursor(String value, List<StyledRange> styles, TextStyle base, Renderer renderer) {
            this.value = value;
            this.styles = styles;
            this.base = base;
            this.renderer = renderer;
        }

        boolean hasText() {
            return offset < value.length();
        }

        Line line(float width) throws IOException {
            var glyphs = new ArrayList<Glyph>();
            float used = 0;
            int breakAt = -1;
            int resumeAt = -1;
            while (hasText()) {
                int codePoint = value.codePointAt(offset);
                int length = Character.charCount(codePoint);
                if (codePoint == '\n' || codePoint == '\r') {
                    offset += length;
                    if (codePoint == '\r' && hasText() && value.charAt(offset) == '\n') offset++;
                    break;
                }
                boolean space = Character.isWhitespace(codePoint);
                if (space && !glyphs.isEmpty()) {
                    breakAt = glyphs.size();
                    resumeAt = offset + length;
                }
                Glyph glyph = renderer.glyph(space ? ' ' : codePoint, styleAt(offset));
                if (used + glyph.width() > width && !glyphs.isEmpty()) {
                    if (breakAt > 0) {
                        glyphs.subList(breakAt, glyphs.size()).clear();
                        offset = resumeAt;
                    }
                    break;
                }
                glyphs.add(glyph);
                used += glyph.width();
                offset += length;
            }
            int size = glyphs.stream().mapToInt(glyph -> glyph.style().fontSize()).max().orElse(base.fontSize());
            return new Line(glyphs, size, size * 1.2f);
        }

        private TextStyle styleAt(int position) {
            int low = 0;
            int high = styles.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                StyledRange range = styles.get(middle);
                if (position < range.start()) high = middle - 1;
                else if (position >= range.end()) low = middle + 1;
                else return range.style();
            }
            return base;
        }
    }

    private record GlyphKey(int codePoint, TextStyle style) {}
    private record Glyph(String value, TextStyle style, float width) {}
    private record Line(List<Glyph> glyphs, float ascent, float height) {}

    private static float points(double millimeters) {
        return (float) (millimeters * POINTS_PER_MM);
    }
}
