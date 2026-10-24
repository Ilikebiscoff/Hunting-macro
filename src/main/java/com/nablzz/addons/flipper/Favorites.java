package com.nablzz.addons.flipper;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The items you starred. Kept in config/nablz_flipper_favourites.txt, one id per line. */
public final class Favorites {
    private Favorites() {}

    private static final Set<String> SET = new LinkedHashSet<>();
    private static volatile int version;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablz_flipper_favourites.txt");
    }

    /** Changes whenever the list changes (for caches). */
    public static int version() {
        return version;
    }

    public static synchronized void load() {
        SET.clear();
        try {
            if (Files.isRegularFile(file())) for (String l : Files.readAllLines(file())) if (!l.isBlank()) SET.add(l.trim());
        } catch (IOException ignored) {
            // start empty
        }
        version++;
    }

    private static void save() {
        try {
            Files.write(file(), new ArrayList<>(SET));
        } catch (IOException ignored) {
            // not saved
        }
    }

    public static synchronized boolean has(String id) {
        return SET.contains(id);
    }

    public static synchronized List<String> list() {
        return new ArrayList<>(SET);
    }

    /** Stars or un-stars an item; returns whether it is a favourite now. */
    public static synchronized boolean toggle(String id) {
        boolean now = SET.add(id);
        if (!now) SET.remove(id);
        version++;
        save();
        return now;
    }

    public static synchronized void clear() {
        SET.clear();
        version++;
        save();
    }
}
