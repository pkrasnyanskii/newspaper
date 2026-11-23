package ru.nsu.newspaper.model;

import java.util.Objects;
import java.util.UUID;

public record Advertisement(UUID id, String text, ImageAsset image) {
    public Advertisement {
        Objects.requireNonNull(id);
        text = Objects.requireNonNull(text).strip();
        if (text.isEmpty() && image == null) throw new IllegalArgumentException("Enter advertisement text or choose an image.");
    }
}
