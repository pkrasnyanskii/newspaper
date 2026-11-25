package ru.nsu.newspaper.model;

import java.util.Objects;

public record Cover(String title, ImageAsset image) {
    public Cover(String title) {
        this(title, null);
    }

    public Cover {
        Objects.requireNonNull(title, "title");
    }
}
