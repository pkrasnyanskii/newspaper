package ru.nsu.newspaper.model;

public enum PageFormat {
    A4(210, 297),
    A3(297, 420);

    private final int widthMm;
    private final int heightMm;

    PageFormat(int widthMm, int heightMm) {
        this.widthMm = widthMm;
        this.heightMm = heightMm;
    }

    public int widthMm() {
        return widthMm;
    }

    public int heightMm() {
        return heightMm;
    }
}
