package ru.nsu.newspaper.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.Page;
import ru.nsu.newspaper.model.PageFormat;
import ru.nsu.newspaper.model.TextStyle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfExporterTest {
    @TempDir Path directory;

    private final PdfExporter exporter = new PdfExporter();
    private final Issue issue = Issue.create(42, LocalDate.of(2026, 11, 11), PageFormat.A3, "Городские новости");

    @Test
    void rendersCoverAndNumberedPagesAtIssueSizeWithSelectableCyrillicText() throws IOException {
        Article article = Article.create("Репортаж", "Автор", "Русский текст статьи", List.of())
                .style(0, 7, new TextStyle(19, TextStyle.Weight.BOLD));
        Issue laidOut = issue.addPage().addPage().addArticle(article);
        var second = laidOut.pages().getLast().id();
        laidOut = laidOut.updateLayout(second, new Page.Layout(2, Page.DEFAULT_LAYOUT.margins(), "Газета"))
                .place(second, article.id(), new Page.Box(15, 20, 200, 150));
        Path file = directory.resolve("issue.pdf");

        exporter.export(laidOut, file);

        try (var document = Loader.loadPDF(file.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            for (var page : document.getPages()) {
                assertEquals(297 * 72 / 25.4, page.getMediaBox().getWidth(), 0.01);
                assertEquals(420 * 72 / 25.4, page.getMediaBox().getHeight(), 0.01);
            }
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Городские новости"), text);
            assertTrue(text.contains("Русский текст статьи"), text);
            assertTrue(text.contains("Page 2"), text);
            assertTrue(text.contains("Issue 42"), text);
        }
    }

    @Test
    void rejectsAnIssueWithoutPagesAndLeavesAnExistingTargetIntact() throws IOException {
        Path file = directory.resolve("existing.pdf");
        Files.writeString(file, "Previous file");
        assertThrows(IllegalArgumentException.class, () -> exporter.export(issue, file));
        assertEquals("Previous file", Files.readString(file));
    }

    @Test
    void failedExportRemovesTemporaryFileAndPreservesTheTarget() throws IOException {
        Path occupied = Files.createDirectory(directory.resolve("issue.pdf"));
        Path marker = Files.writeString(occupied.resolve("marker"), "keep");
        assertThrows(IOException.class, () -> exporter.export(issue.addPage(), occupied));
        assertEquals("keep", Files.readString(marker));
        try (var children = Files.list(directory)) {
            assertEquals(List.of(occupied), children.toList());
        }
    }
}
