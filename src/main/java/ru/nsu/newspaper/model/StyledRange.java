package ru.nsu.newspaper.model;

import java.util.Objects;

public record StyledRange(int start, int end, TextStyle style) {
    public StyledRange {
        Objects.requireNonNull(style);
        if (start < 0 || end <= start) throw new IllegalArgumentException("Select a block of article text.");
    }
}
