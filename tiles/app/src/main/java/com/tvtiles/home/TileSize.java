package com.tvtiles.home;

/** Tile sizes in grid units, as on Windows Phone: the grid is TileGrid.UNITS wide, so 5 medium tiles fill a row. */
enum TileSize {
    SMALL(1, 1, "Small"),
    MEDIUM(2, 2, "Medium"),
    WIDE(4, 2, "Wide"),
    LARGE(4, 4, "Large");

    final int w, h;
    final String title;

    TileSize(int w, int h, String title) {
        this.w = w;
        this.h = h;
        this.title = title;
    }

    static TileSize parse(String name, TileSize fallback) {
        for (TileSize s : values()) if (s.name().equalsIgnoreCase(name)) return s;
        return fallback;
    }
}
