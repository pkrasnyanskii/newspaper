package ru.nsu.newspaper.server;

import ru.nsu.newspaper.model.Issue;

import java.util.List;
import java.util.Optional;

/**
 * UC-3 "Manage table of contents": page numbers are derived from the current issue layout.
 */
public final class TableOfContents {
    private TableOfContents() {
    }

    public static List<TocEntry> of(Issue issue) {
        return issue.contents().stream()
                .map(entry -> new TocEntry(entry.articleId(), entry.title(), Optional.ofNullable(entry.pageNumber())))
                .toList();
    }
}
