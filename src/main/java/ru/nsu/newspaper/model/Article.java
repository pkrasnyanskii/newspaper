package ru.nsu.newspaper.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Article(UUID id, String title, String author, String text, List<ImageAsset> images) {
    public Article {
        Objects.requireNonNull(id, "id");
        title = Objects.requireNonNull(title, "title").strip();
        author = Objects.requireNonNull(author, "author").strip();
        Objects.requireNonNull(text, "text");
        images = List.copyOf(images);
        if (title.isBlank()) {
            throw new IllegalArgumentException("Enter an article title.");
        }
    }

    public static Article create(String title, String author, String text, List<ImageAsset> images) {
        return new Article(UUID.randomUUID(), title, author, text, images);
    }

    public Article withContent(String title, String author, String text, List<ImageAsset> images) {
        return new Article(id, title, author, text, images);
    }
}
