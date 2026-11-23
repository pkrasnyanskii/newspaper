package ru.nsu.newspaper.protocol;

public enum Role {
    EDITOR("Editor"), JOURNALIST("Journalist"), LAYOUT_DESIGNER("Layout Designer");

    private final String label;
    Role(String label) { this.label = label; }
    public boolean canPerform(Protocol.Kind kind) {
        return switch (kind) {
            case CREATE_ISSUE, UPDATE_DETAILS, PUT_ADVERTISEMENT, DELETE_ADVERTISEMENT -> this == EDITOR;
            case ADD_ARTICLE, UPDATE_ARTICLE, DELETE_ARTICLE -> this == JOURNALIST;
            case STYLE_ARTICLE, ADD_PAGE, REMOVE_PAGE, UPDATE_LAYOUT, SAVE_TEMPLATE, APPLY_TEMPLATE,
                    PLACE_BLOCK, UNPLACE_BLOCK -> this == LAYOUT_DESIGNER;
        };
    }
    public boolean canLockArticle() { return this == JOURNALIST || this == LAYOUT_DESIGNER; }
    @Override public String toString() { return label; }
}
