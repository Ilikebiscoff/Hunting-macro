package com.nablzz.addons.minerzombie;

import com.nablzz.addons.minerzombie.pathing.Planner;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Everything you can change in the settings screen (and by command), saved in
 * {@code config/huntingmacro_settings.properties} so it is still there after a restart.
 * The learned black hole timings live in their own file ({@code huntingmacro.properties}).
 */
public final class Config {
    private Config() {}

    /** ESP boxes + path line in the world. */
    public static volatile boolean highlight = true;
    /** Status panel on the screen. */
    public static volatile boolean hud = true;
    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    public static volatile int hudCorner = 1;

    private static Properties defaults;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("huntingmacro_settings.properties");
    }

    /** Call once at start: remembers the built-in values (for "reset to defaults"), then loads the saved file. */
    public static void init(HuntController c) {
        defaults = toProps(c);
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                Properties p = new Properties();
                p.load(in);
                apply(c, p);
            } catch (Exception ignored) {
                // unreadable file: keep the defaults
            }
        }
    }

    public static void save(HuntController c) {
        try (OutputStream out = Files.newOutputStream(file())) {
            toProps(c).store(out, "Hunting Macro settings");
        } catch (IOException ignored) {
            // not saved: the values stay for this session
        }
    }

    public static void resetToDefaults(HuntController c) {
        if (defaults != null) apply(c, defaults);
        save(c);
    }

    private static Properties toProps(HuntController c) {
        Properties p = new Properties();
        p.setProperty("mobFilter", c.mobFilter);
        p.setProperty("axeName", c.axeName);
        p.setProperty("holeName", c.holeName);
        p.setProperty("golden", c.golden.name());
        p.setProperty("range", "" + c.range);
        p.setProperty("refreshEvery", "" + c.refreshEvery);
        p.setProperty("hitsPerZombie", "" + c.hitsPerZombie);
        p.setProperty("hitGapTicks", "" + c.hitGapTicks);
        p.setProperty("swapDelayTicks", "" + c.swapDelayTicks);
        p.setProperty("holeCount", "" + c.holeCount);
        p.setProperty("caughtGate", "" + c.caughtGate);
        p.setProperty("caughtText", c.caughtText);
        p.setProperty("caughtMob", c.caughtMob);
        p.setProperty("caughtCounts", c.caughtCounts);
        p.setProperty("areaOn", "" + c.areaOn);
        p.setProperty("areaX1", "" + c.areaX1);
        p.setProperty("areaX2", "" + c.areaX2);
        p.setProperty("areaZ1", "" + c.areaZ1);
        p.setProperty("areaZ2", "" + c.areaZ2);
        p.setProperty("areaY1", "" + c.areaY1);
        p.setProperty("areaY2", "" + c.areaY2);
        p.setProperty("zoneGuard", "" + c.zoneGuard);
        p.setProperty("zoneText", c.zoneText);
        p.setProperty("warpCmd", c.warpCmd);
        p.setProperty("idleWarpMs", "" + c.idleWarpMs);
        p.setProperty("lavaRescue", "" + c.lavaRescue);
        p.setProperty("lavaLimit", "" + c.lavaLimit);
        p.setProperty("rescueCmd", c.rescueCmd);
        p.setProperty("liftX", "" + c.liftX);
        p.setProperty("liftY", "" + c.liftY);
        p.setProperty("liftZ", "" + c.liftZ);
        p.setProperty("liftNpc", c.liftNpc);
        p.setProperty("deepText", c.deepText);
        p.setProperty("parkour", "" + Planner.parkour);
        p.setProperty("highlight", "" + highlight);
        p.setProperty("hud", "" + hud);
        p.setProperty("hudCorner", "" + hudCorner);
        return p;
    }

    private static void apply(HuntController c, Properties p) {
        c.mobFilter = str(p, "mobFilter", c.mobFilter);
        c.axeName = str(p, "axeName", c.axeName);
        c.holeName = str(p, "holeName", c.holeName);
        try {
            c.golden = HuntController.Golden.valueOf(str(p, "golden", c.golden.name()));
        } catch (IllegalArgumentException ignored) {
            // keep
        }
        c.range = clamp(dbl(p, "range", c.range), 5, 150);
        c.refreshEvery = (int) clamp(num(p, "refreshEvery", c.refreshEvery), 0, 1000);
        c.hitsPerZombie = (int) clamp(num(p, "hitsPerZombie", c.hitsPerZombie), 1, 6);
        c.hitGapTicks = (int) clamp(num(p, "hitGapTicks", c.hitGapTicks), 1, 20);
        c.swapDelayTicks = (int) clamp(num(p, "swapDelayTicks", c.swapDelayTicks), 1, 20);
        c.holeCount = (int) clamp(num(p, "holeCount", c.holeCount), 1, 5);
        c.caughtGate = bool(p, "caughtGate", c.caughtGate);
        c.caughtText = str(p, "caughtText", c.caughtText);
        c.caughtMob = str(p, "caughtMob", c.caughtMob);
        c.caughtCounts = str(p, "caughtCounts", c.caughtCounts);
        c.areaOn = bool(p, "areaOn", c.areaOn);
        c.areaX1 = (int) num(p, "areaX1", c.areaX1);
        c.areaX2 = (int) num(p, "areaX2", c.areaX2);
        c.areaZ1 = (int) num(p, "areaZ1", c.areaZ1);
        c.areaZ2 = (int) num(p, "areaZ2", c.areaZ2);
        c.areaY1 = (int) num(p, "areaY1", c.areaY1);
        c.areaY2 = (int) num(p, "areaY2", c.areaY2);
        c.zoneGuard = bool(p, "zoneGuard", c.zoneGuard);
        c.zoneText = str(p, "zoneText", c.zoneText);
        c.warpCmd = str(p, "warpCmd", c.warpCmd);
        c.idleWarpMs = (long) clamp(num(p, "idleWarpMs", c.idleWarpMs), 1000, 600_000);
        c.lavaRescue = bool(p, "lavaRescue", c.lavaRescue);
        c.lavaLimit = (int) clamp(num(p, "lavaLimit", c.lavaLimit), 1, 50);
        c.rescueCmd = str(p, "rescueCmd", c.rescueCmd);
        c.liftX = (int) num(p, "liftX", c.liftX);
        c.liftY = (int) num(p, "liftY", c.liftY);
        c.liftZ = (int) num(p, "liftZ", c.liftZ);
        c.liftNpc = str(p, "liftNpc", c.liftNpc);
        c.deepText = str(p, "deepText", c.deepText);
        Planner.parkour = bool(p, "parkour", Planner.parkour);
        highlight = bool(p, "highlight", highlight);
        hud = bool(p, "hud", hud);
        hudCorner = (int) clamp(num(p, "hudCorner", hudCorner), 0, 3);
    }

    private static String str(Properties p, String k, String d) {
        return p.getProperty(k, d);
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }

    private static double dbl(Properties p, String k, double d) {
        try {
            return Double.parseDouble(p.getProperty(k, "" + d).trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static double num(Properties p, String k, double d) {
        return dbl(p, k, d);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
