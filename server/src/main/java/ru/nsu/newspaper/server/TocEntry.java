package ru.nsu.newspaper.server;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One row of the table of contents (UC-3): an article's title and its page number, if placed. */
public record TocEntry(UUID articleId, String title, Optional<Integer> pageNumber) {
    public TocEntry {
        Objects.requireNonNull(articleId, "articleId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(pageNumber, "pageNumber");
    }
}
