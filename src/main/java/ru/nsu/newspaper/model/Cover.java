package ru.nsu.newspaper.model;

import java.util.Objects;

public record Cover(String title) {
    public Cover {
        Objects.requireNonNull(title, "title");
    }
}
