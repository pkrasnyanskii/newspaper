package ru.nsu.newspaper.server;

import org.junit.jupiter.api.Test;
import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Issue;
import ru.nsu.newspaper.model.PageFormat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UC-3 "Manage table of contents" (FR-3, FR-4, NFR-6). */
class TableOfContentsTest {

    @Test
    void emptyIssueHasAnEmptyTableOfContents() {
        Issue issue = Issue.create(1, LocalDate.of(2026, 11, 14), PageFormat.A4, "Empty issue");

        assertTrue(TableOfContents.of(issue).isEmpty());
    }

    @Test
    void entriesFollowArticleOrderAndShowNoPageNumberUntilPlaced() {
        Issue issue = Issue.create(2, LocalDate.of(2026, 11, 14), PageFormat.A4, "Issue");
        Article first = Article.create("First article", "Author", "Body", List.of());
        Article second = Article.create("Second article", "Author", "Body", List.of());
        issue = issue.addArticle(first).addArticle(second);

        List<TocEntry> toc = TableOfContents.of(issue);

        assertEquals(2, toc.size());
        assertEquals("First article", toc.get(0).title());
        assertTrue(toc.get(0).pageNumber().isEmpty());
        assertEquals("Second article", toc.get(1).title());
        assertTrue(toc.get(1).pageNumber().isEmpty());
    }

    @Test
    void reflectsArticleRemoval() {
        Issue issue = Issue.create(3, LocalDate.of(2026, 11, 14), PageFormat.A4, "Issue");
        Article kept = Article.create("Kept", "Author", "Body", List.of());
        Article removed = Article.create("Removed", "Author", "Body", List.of());
        issue = issue.addArticle(kept).addArticle(removed).removeArticle(removed.id());

        List<TocEntry> toc = TableOfContents.of(issue);

        assertEquals(1, toc.size());
        assertEquals("Kept", toc.get(0).title());
    }

    @Test
    void recomputingForManyArticlesStaysWellUnderOneSecond() {
        Issue issue = Issue.create(4, LocalDate.of(2026, 11, 14), PageFormat.A4, "Large issue");
        for (int i = 0; i < 2000; i++) {
            issue = issue.addArticle(Article.create("Article " + i, "Author", "Body", List.of()));
        }

        Instant start = Instant.now();
        List<TocEntry> toc = TableOfContents.of(issue);
        Duration elapsed = Duration.between(start, Instant.now());

        assertEquals(2000, toc.size());
        assertTrue(elapsed.toMillis() < 1000, "NFR-6: TOC recalculation must complete within 1 second, took " + elapsed);
    }
}
