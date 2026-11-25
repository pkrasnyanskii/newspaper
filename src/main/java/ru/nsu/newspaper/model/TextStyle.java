package ru.nsu.newspaper.model;

import java.util.Objects;

public record TextStyle(int fontSize, Weight fontWeight) {
    public static final TextStyle DEFAULT = new TextStyle(12, Weight.NORMAL);

    public TextStyle {
        Objects.requireNonNull(fontWeight);
        if (fontSize < 6 || fontSize > 96) {
            throw new IllegalArgumentException("Enter a font size from 6 to 96 pt.");
        }
    }

    public enum Weight { NORMAL, BOLD }
}
