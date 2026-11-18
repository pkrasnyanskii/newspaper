package ru.nsu.newspaper.server;

import ru.nsu.newspaper.model.Article;
import ru.nsu.newspaper.model.Issue;

import java.util.List;
import java.util.Optional;

/**
 * UC-3 "Manage table of contents": the server recomputes this from the issue's current articles
 * on every read (Section 7.2: "Derived values ... are recomputed on open, not stored"; NFR-6: under
 * one second). Page numbers are empty ("-" on the Vision's UC-3 page) until an article is placed on
 * a page, which page layout (UC-5) does not exist in the model yet.
 */
public final class TableOfContents {
    private TableOfContents() {
    }

    public static List<TocEntry> of(Issue issue) {
        return issue.articles().stream()
                .map(TableOfContents::toEntry)
                .toList();
    }

    private static TocEntry toEntry(Article article) {
        return new TocEntry(article.id(), article.title(), Optional.empty());
    }
}
