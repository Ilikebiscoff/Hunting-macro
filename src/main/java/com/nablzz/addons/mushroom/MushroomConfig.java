package com.nablzz.addons.mushroom;

import com.nablzz.addons.mushroom.pathing.Planner;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Mushroom macro settings that survive a restart (the route and the avoid list have their own files). */
public final class MushroomConfig {
    private MushroomConfig() {}

    private static Properties defaults;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("glowingmushroom_settings.properties");
    }

    static void init(MacroController c, Failsafe f) {
        defaults = toProps(c, f);
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            apply(c, f, p);
        } catch (Exception ignored) {
            // unreadable: keep the defaults
        }
    }

    public static void save(MacroController c, Failsafe f) {
        try (OutputStream out = Files.newOutputStream(file())) {
            toProps(c, f).store(out, "Nablzz Addons: mushroom macro settings");
        } catch (IOException ignored) {
            // not saved
        }
    }

    public static void resetToDefaults(MacroController c, Failsafe f) {
        if (defaults != null) apply(c, f, defaults);
        save(c, f);
    }

    private static Properties toProps(MacroController c, Failsafe f) {
        Properties p = new Properties();
        p.setProperty("pathMode", "" + c.pathMode);
        p.setProperty("nuker", "" + c.nuker);
        p.setProperty("noMushroomWarpMs", "" + c.noMushroomWarpMs);
        p.setProperty("failsafe", "" + f.enabled);
        p.setProperty("zoneText", f.zoneText);
        p.setProperty("highlight", "" + GlowingMushroomMod.highlight);
        p.setProperty("hud", "" + ProfitHud.enabled);
        p.setProperty("parkour", "" + Planner.parkour);
        p.setProperty("particles", String.join(",", MushroomTracker.markers));
        return p;
    }

    private static void apply(MacroController c, Failsafe f, Properties p) {
        c.pathMode = bool(p, "pathMode", c.pathMode);
        c.nuker = bool(p, "nuker", c.nuker);
        try {
            c.noMushroomWarpMs = Math.max(0, Math.min(600_000, Long.parseLong(p.getProperty("noMushroomWarpMs", "" + c.noMushroomWarpMs).trim())));
        } catch (NumberFormatException ignored) {
            // keep
        }
        f.enabled = bool(p, "failsafe", f.enabled);
        f.zoneText = p.getProperty("zoneText", f.zoneText);
        GlowingMushroomMod.highlight = bool(p, "highlight", GlowingMushroomMod.highlight);
        ProfitHud.enabled = bool(p, "hud", ProfitHud.enabled);
        Planner.parkour = bool(p, "parkour", Planner.parkour);
        String ids = p.getProperty("particles", "").trim();
        if (!ids.isEmpty()) {
            Set<String> set = new HashSet<>();
            for (String id : ids.split(",")) if (!id.isBlank()) set.add(id.trim());
            if (!set.isEmpty()) MushroomTracker.markers = set;
        }
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }
}
