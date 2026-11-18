package ru.nsu.newspaper.protocol;

public enum Role {
    EDITOR("Editor"), JOURNALIST("Journalist"), LAYOUT_DESIGNER("Layout Designer");

    private final String label;
    Role(String label) { this.label = label; }
    public boolean canPerform(Protocol.Kind kind) {
        return switch (kind) {
            case CREATE_ISSUE, UPDATE_DETAILS -> this == EDITOR;
            case ADD_ARTICLE, UPDATE_ARTICLE, DELETE_ARTICLE -> this == JOURNALIST;
        };
    }
    public boolean canLockArticle() { return this == JOURNALIST || this == LAYOUT_DESIGNER; }
    @Override public String toString() { return label; }
}
