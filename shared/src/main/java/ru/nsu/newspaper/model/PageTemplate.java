package ru.nsu.newspaper.model;

import java.util.Objects;
import java.util.UUID;

public record PageTemplate(UUID id, String name, Page.Layout layout) {
    public PageTemplate {
        Objects.requireNonNull(id);
        name = Objects.requireNonNull(name).strip();
        Objects.requireNonNull(layout);
        if (name.isEmpty()) throw new IllegalArgumentException("Enter a template name.");
    }
}
