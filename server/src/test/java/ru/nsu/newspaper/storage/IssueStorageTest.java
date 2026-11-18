package ru.nsu.newspaper.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.PageFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueStorageTest {
    @TempDir
    Path folder;

    private final IssueStorage storage = new IssueStorage();

    @Test
    void savesAndOpensTheSameIssue() throws IOException {
        Issue issue = Issue.create(42, LocalDate.of(2026, 11, 11), PageFormat.A4, "City Gazette");
        Path file = folder.resolve("issue-42.issue");

        storage.save(issue, file);
        assertEquals(issue, storage.open(file));
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertTrue(zip.getEntry("issue.json") != null);
        }
    }

    @Test
    void rejectsDamagedIssueWithoutChangingAnOpenIssue() throws IOException {
        Issue openIssue = Issue.create(1, LocalDate.now(), PageFormat.A4, "Existing issue");
        Path damaged = folder.resolve("damaged.issue");
        Files.writeString(damaged, "not a ZIP archive");

        assertThrows(IOException.class, () -> storage.open(damaged));
        assertEquals("Existing issue", openIssue.cover().title());
    }

    @Test
    void refusesDataItCannotPreserveYet() throws IOException {
        Path file = folder.resolve("future.issue");
        String json = """
                {"format":"periodical-issue","formatVersion":1,"id":"b4a78839-f4ab-48cb-a231-b68f21066977",
                 "issueNumber":1,"publicationDate":"2026-11-11","pageFormat":{"name":"A4","width":210,"height":297},
                 "cover":{"title":"News","coverImage":null},"templates":[],"articles":[{}],"advertisements":[],"pages":[]}
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("issue.json"));
            zip.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(IOException.class, () -> storage.open(file));
    }
}
