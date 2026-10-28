package com.nablzz.addons.mudworm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class Config {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mudworm-macro.properties");
    public static int baseRange = 57;
    public static double rangeMargin = 1.5;
    public static double etherCost = 10.0;
    public static boolean strictRay = false;
    public static double coneDeg = 0.1;
    public static double reach = 4.6;
    public static boolean esp = true;
    public static boolean hud = true;
    public static boolean debug = false;
    public static boolean stopOnForeignTeleport = true;
    public static boolean unstuckWarp = true;
    public static int stuckSeconds = 3;
    public static String stuckCommand = "warp murk";
    /** Lobby swap: the first command (loads the island), then the second one after it arrived. Without the slash. */
    public static String swapFirst = "warp galatea";
    public static String swapSecond = "warp murk";
    /** Seconds with no spot in view before the lobby is swapped. */
    public static int noSpotSeconds = 3;
    /** Seconds after arriving in a lobby before the spots are counted / the "nothing here" timer may run. */
    public static int settleSeconds = 3;
    /** Pause between the two swap commands (ms). */
    public static int swapGapMs = 2000;
    /** After this many lobby refreshes in a row without a mined spot, the hub routine (/hub, /warp galatea) runs. 0 = never. */
    public static int hubAfter = 2;
    /** Stuck warps (the stuck command) in a row before the hub routine runs instead. */
    public static int stuckHubAfter = 3;
    /** Lobby changes in a row without any progress before the macro gives up and stops. */
    public static int giveUpAfter = 8;
    public static int landBelow = 10;
    public static int landAbove = 12;
    public static int areaMargin = 30;
    public static double scootRange = 6.0;
    public static double maxTargetDistance = 120.0;
    public static double maxTargetHeight = 45.0;
    public static int minSpots = 3;
    public static int refreshCooldownSeconds = 45;
    public static double scootSeconds = 1.5;
    /** Silent aim for etherwarps. Never saved: it is off at every start; the way on is the mini game (BlatantGame). */
    public static volatile boolean blatant = false;
    public static boolean useAxe = false;
    public static String axeName = "Helix Chopper";
    public static int hopDelayMs = 250;
    public static int cacheChunks = 7;
    public static int areaMinX = -708;
    public static int areaMaxX = -603;
    public static int areaMinZ = -46;
    public static int areaMaxZ = 77;
    public static int patrolStep = 28;
    public static int scanMinY = 60;
    public static int scanMaxY = 200;

    private Config() {
    }

    public static void load() {
        if (!Files.exists(FILE, new LinkOption[0])) {
            Config.save();
            return;
        }
        Properties p = new Properties();
        try (BufferedReader r = Files.newBufferedReader(FILE);){
            p.load(r);
        }
        catch (IOException e) {
            return;
        }
        baseRange = Config.i(p, "baseRange", baseRange);
        rangeMargin = Config.d(p, "rangeMargin", rangeMargin);
        etherCost = Config.d(p, "etherCost", etherCost);
        strictRay = Config.b(p, "strictRay", strictRay);
        coneDeg = Config.d(p, "coneDegrees", coneDeg);
        reach = Config.d(p, "nukeReach", reach);
        esp = Config.b(p, "esp", esp);
        hud = Config.b(p, "hud", hud);
        debug = Config.b(p, "debug", debug);
        stopOnForeignTeleport = Config.b(p, "stopOnForeignTeleport", stopOnForeignTeleport);
        unstuckWarp = Config.b(p, "unstuckWarp", unstuckWarp);
        stuckSeconds = Math.max(1, Config.i(p, "stuckSeconds", stuckSeconds));
        stuckCommand = p.getProperty("stuckCommand", stuckCommand).trim();
        swapFirst = p.getProperty("swapFirst", swapFirst).trim();
        swapSecond = p.getProperty("swapSecond", swapSecond).trim();
        noSpotSeconds = Math.max(1, Math.min(60, Config.i(p, "noSpotSeconds", noSpotSeconds)));
        settleSeconds = Math.max(1, Math.min(15, Config.i(p, "settleSeconds", settleSeconds)));
        swapGapMs = Math.max(300, Math.min(8000, Config.i(p, "swapGapMs", swapGapMs)));
        hubAfter = Math.max(0, Math.min(50, Config.i(p, "hubAfter", hubAfter)));
        stuckHubAfter = Math.max(1, Math.min(50, Config.i(p, "stuckHubAfter", stuckHubAfter)));
        giveUpAfter = Math.max(1, Math.min(100, Config.i(p, "giveUpAfter", giveUpAfter)));
        landBelow = Math.max(0, Config.i(p, "landBelow", landBelow));
        landAbove = Math.max(0, Config.i(p, "landAbove", landAbove));
        areaMargin = Math.max(0, Config.i(p, "areaMargin", areaMargin));
        scootRange = Math.max(0.0, Math.min(30.0, Config.d(p, "scootRange", scootRange)));
        maxTargetDistance = Math.max(10.0, Math.min(400.0, Config.d(p, "maxDistance", maxTargetDistance)));
        maxTargetDistance = Math.max(10.0, Math.min(400.0, Config.d(p, "maxDistance", maxTargetDistance)));
        maxTargetHeight = Math.max(5.0, Math.min(300.0, Config.d(p, "maxHeightDiff", maxTargetHeight)));
        minSpots = Math.max(0, Math.min(50, Config.i(p, "minSpots", minSpots)));
        refreshCooldownSeconds = Math.max(5, Math.min(600, Config.i(p, "refreshCooldown", refreshCooldownSeconds)));
        scootSeconds = Math.max(0.2, Math.min(10.0, Config.d(p, "scootSeconds", scootSeconds)));
        useAxe = Config.b(p, "useAxe", useAxe);
        axeName = p.getProperty("axeName", axeName).trim();
        hopDelayMs = Math.max(0, Math.min(5000, Config.i(p, "hopDelay", hopDelayMs)));
        cacheChunks = Math.max(2, Math.min(12, Config.i(p, "cacheChunks", cacheChunks)));
        areaMinX = Config.i(p, "areaMinX", areaMinX);
        areaMaxX = Config.i(p, "areaMaxX", areaMaxX);
        areaMinZ = Config.i(p, "areaMinZ", areaMinZ);
        areaMaxZ = Config.i(p, "areaMaxZ", areaMaxZ);
        patrolStep = Math.max(8, Config.i(p, "patrolStep", patrolStep));
        scanMinY = Config.i(p, "scanMinY", scanMinY);
        scanMaxY = Config.i(p, "scanMaxY", scanMaxY);
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("baseRange", "" + baseRange);
        p.setProperty("rangeMargin", "" + rangeMargin);
        p.setProperty("etherCost", "" + etherCost);
        p.setProperty("strictRay", "" + strictRay);
        p.setProperty("coneDegrees", "" + coneDeg);
        p.setProperty("nukeReach", "" + reach);
        p.setProperty("esp", "" + esp);
        p.setProperty("hud", "" + hud);
        p.setProperty("debug", "" + debug);
        p.setProperty("stopOnForeignTeleport", "" + stopOnForeignTeleport);
        p.setProperty("unstuckWarp", "" + unstuckWarp);
        p.setProperty("stuckSeconds", "" + stuckSeconds);
        p.setProperty("stuckCommand", stuckCommand);
        p.setProperty("swapFirst", swapFirst);
        p.setProperty("swapSecond", swapSecond);
        p.setProperty("noSpotSeconds", "" + noSpotSeconds);
        p.setProperty("settleSeconds", "" + settleSeconds);
        p.setProperty("swapGapMs", "" + swapGapMs);
        p.setProperty("hubAfter", "" + hubAfter);
        p.setProperty("stuckHubAfter", "" + stuckHubAfter);
        p.setProperty("giveUpAfter", "" + giveUpAfter);
        p.setProperty("landBelow", "" + landBelow);
        p.setProperty("landAbove", "" + landAbove);
        p.setProperty("areaMargin", "" + areaMargin);
        p.setProperty("scootRange", "" + scootRange);
        p.setProperty("maxDistance", "" + maxTargetDistance);
        p.setProperty("maxHeightDiff", "" + maxTargetHeight);
        p.setProperty("maxDistance", "" + maxTargetDistance);
        p.setProperty("maxHeightDiff", "" + maxTargetHeight);
        p.setProperty("minSpots", "" + minSpots);
        p.setProperty("refreshCooldown", "" + refreshCooldownSeconds);
        p.setProperty("scootSeconds", "" + scootSeconds);
        p.setProperty("useAxe", "" + useAxe);
        p.setProperty("axeName", axeName);
        p.setProperty("hopDelay", "" + hopDelayMs);
        p.setProperty("cacheChunks", "" + cacheChunks);
        p.setProperty("areaMinX", "" + areaMinX);
        p.setProperty("areaMaxX", "" + areaMaxX);
        p.setProperty("areaMinZ", "" + areaMinZ);
        p.setProperty("areaMaxZ", "" + areaMaxZ);
        p.setProperty("patrolStep", "" + patrolStep);
        p.setProperty("scanMinY", "" + scanMinY);
        p.setProperty("scanMaxY", "" + scanMaxY);
        try {
            Files.createDirectories(FILE.getParent(), new FileAttribute[0]);
            try (BufferedWriter w = Files.newBufferedWriter(FILE, new OpenOption[0]);){
                p.store(w, "Mudworm Macro settings");
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private static int i(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, "" + def).trim());
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

    private static double d(Properties p, String k, double def) {
        try {
            return Double.parseDouble(p.getProperty(k, "" + def).trim());
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean b(Properties p, String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, "" + def).trim());
    }
}

