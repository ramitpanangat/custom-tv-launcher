package com.tvtiles.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The tile order and sizes, and the hidden apps, saved on the TV. */
final class LayoutStore {
    static final String CLOCK = "clock";

    static final class Item {
        final String key;
        TileSize size;

        Item(String key, TileSize size) {
            this.key = key;
            this.size = size;
        }
    }

    private final SharedPreferences prefs;

    LayoutStore(Context context) {
        prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE);
    }

    /** The saved layout, or null when nothing was saved yet (or after a reset). */
    List<Item> load() {
        String saved = prefs.getString("layout", null);
        return saved == null ? null : parse(saved.split(";"));
    }

    /** "key:size" entries, as in config.xml default_layout; malformed ones are skipped. */
    static List<Item> parse(String[] entries) {
        List<Item> items = new ArrayList<>();
        for (String entry : entries) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) continue;
            items.add(new Item(entry.substring(0, colon).trim(),
                    TileSize.parse(entry.substring(colon + 1).trim(), TileSize.MEDIUM)));
        }
        return items;
    }

    void save(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(';');
            sb.append(it.key).append(':').append(it.size.name());
        }
        prefs.edit().putString("layout", sb.toString()).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    }

    void saveHidden(Set<String> hidden) {
        prefs.edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
    }

    void reset() {
        prefs.edit().remove("layout").remove("hidden").apply();
    }
}
