package com.nablzz.addons.arcade;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Best scores of the arcade games, kept in the config folder. */
final class Scores {
    private Scores() {}

    private static final Properties P = new Properties();
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablzz_arcade.properties");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = Files.newInputStream(file())) {
            P.load(in);
        } catch (IOException | RuntimeException ignored) {
            // no scores yet
        }
    }

    static int best(String game) {
        load();
        try {
            return Integer.parseInt(P.getProperty(game, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Stores the score when it beats the best one. True for a new record. */
    static boolean submit(String game, int score) {
        load();
        if (score <= best(game)) return false;
        P.setProperty(game, "" + score);
        try (OutputStream out = Files.newOutputStream(file())) {
            P.store(out, "Nablzz Addons arcade best scores");
        } catch (IOException ignored) {
            // not saved
        }
        return true;
    }
}
