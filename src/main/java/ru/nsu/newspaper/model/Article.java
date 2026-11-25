package ru.nsu.newspaper.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Article(UUID id, String title, String author, String text, List<ImageAsset> images,
                      List<StyledRange> styledRanges) {
    public Article(UUID id, String title, String author, String text, List<ImageAsset> images) {
        this(id, title, author, text, images, List.of());
    }

    public Article {
        Objects.requireNonNull(id, "id");
        title = Objects.requireNonNull(title, "title").strip();
        author = Objects.requireNonNull(author, "author").strip();
        Objects.requireNonNull(text, "text");
        images = List.copyOf(images);
        styledRanges = List.copyOf(styledRanges);
        if (title.isBlank()) {
            throw new IllegalArgumentException("Enter an article title.");
        }
        int previousEnd = 0;
        for (StyledRange range : styledRanges) {
            if (range.start() < previousEnd || range.end() > text.length()
                    || splitsCharacter(text, range.start()) || splitsCharacter(text, range.end())) {
                throw new IllegalArgumentException("Invalid article style range.");
            }
            previousEnd = range.end();
        }
    }

    public static Article create(String title, String author, String text, List<ImageAsset> images) {
        return new Article(UUID.randomUUID(), title, author, text, images);
    }

    public Article withContent(String title, String author, String text, List<ImageAsset> images) {
        var retained = new ArrayList<StyledRange>();
        for (StyledRange range : styledRanges) {
            int start = range.start();
            int end = Math.min(range.end(), text.length());
            if (start < end && splitsCharacter(text, start)) start++;
            if (start < end && splitsCharacter(text, end)) end--;
            if (start < end) retained.add(new StyledRange(start, end, range.style()));
        }
        return new Article(id, title, author, text, images, retained);
    }

    public Article style(int start, int end, TextStyle style) {
        var replacement = new StyledRange(start, end, style);
        if (end > text.length() || splitsCharacter(text, start) || splitsCharacter(text, end)) {
            throw new IllegalArgumentException("Select whole characters within the article text.");
        }
        var ranges = new ArrayList<StyledRange>();
        for (StyledRange range : styledRanges) {
            if (range.end() <= start || range.start() >= end) {
                ranges.add(range);
            } else {
                if (range.start() < start) ranges.add(new StyledRange(range.start(), start, range.style()));
                if (range.end() > end) ranges.add(new StyledRange(end, range.end(), range.style()));
            }
        }
        ranges.add(replacement);
        ranges.sort(Comparator.comparingInt(StyledRange::start));
        return new Article(id, title, author, text, images, ranges);
    }

    private static boolean splitsCharacter(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }
}
