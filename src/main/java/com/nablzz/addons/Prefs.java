package com.nablzz.addons;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings shared by both macros (look of the settings screen). */
public final class Prefs {
    private Prefs() {}

    public static volatile int accentIdx = 0;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablzzaddons.properties");
    }

    public static void init() {
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                Properties p = new Properties();
                p.load(in);
                accentIdx = Math.max(0, Math.min(Ui.ACCENTS.length - 1, Integer.parseInt(p.getProperty("accent", "0").trim())));
                AvoidPlayers.enabled = Boolean.parseBoolean(p.getProperty("avoidOn", "true").trim());
            } catch (Exception ignored) {
                // keep defaults
            }
        }
        Ui.setAccent(accentIdx);
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("accent", "" + accentIdx);
        p.setProperty("avoidOn", "" + AvoidPlayers.enabled);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons");
        } catch (IOException ignored) {
            // not saved
        }
    }
}
