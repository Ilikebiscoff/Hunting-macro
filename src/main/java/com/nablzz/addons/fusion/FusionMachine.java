package com.nablzz.addons.fusion;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Notify;
import com.nablzz.addons.Ui;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fusion Box macro (Hypixel SkyBlock Hunting Box fusions): in the "Fusion Box" menu it clicks "Repeat Previous
 * Fusion", in the "Confirm Fusion" menu it clicks "Fusion", and starts over, until the shards run out.
 * <p>
 * Start it, then open the Fusion Box yourself (the settings screen closes on start). It reads the menu titles
 * and item names, so it does not depend on slot numbers (they are only a fallback).
 */
public class FusionMachine implements Feature {
    private static final String BOX_TITLE = "fusion box", CONFIRM_TITLE = "confirm fusion";
    private static final String REPEAT_NAME = "repeat previous fusion", FUSE_NAME = "fusion";
    /** Slots seen in the recording: "Repeat Previous Fusion" and the confirm "Fusion" item. */
    private static final int REPEAT_SLOT = 47, FUSE_SLOT = 33;
    private static final Pattern OBTAINED = Pattern.compile("You obtained (.+?)(?: x(\\d+))?\\s*$");

    // settings
    public volatile int clickDelayMs = 350;
    public volatile int jitterMs = 150;
    /** 0 = until the shards run out. */
    public volatile int maxFusions = 0;

    // state
    private volatile boolean running;
    private final Random rand = new Random();
    private long armedAt, runMs, lastTick;
    private AbstractContainerMenu seenMenu, clickedMenu;
    private long seenAt, clickedAt, jitter, missingSince;
    private String stage = "Stopped";

    // stats
    private volatile int fusions, confirmClicks;
    private volatile long obtained;
    private volatile String lastResult = "-";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablzz_fusion.properties");
    }

    // ---- Feature ------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "fusion";
    }

    @Override
    public String name() {
        return "Fusion Machine";
    }

    @Override
    public void init() {
        load();
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChat(message.getString());
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("fusion");
            root.executes(c -> {
                NablzzAddons.openGui(id());
                return 1;
            });
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start").executes(c -> {
                start(Minecraft.getInstance());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(c -> {
                stop(Minecraft.getInstance());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("max")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                            .<FabricClientCommandSource, Integer>argument("n", IntegerArgumentType.integer(0, 100000)).executes(c -> {
                                maxFusions = IntegerArgumentType.getInteger(c, "n");
                                save();
                                Notify.msg("Fusion limit: " + (maxFusions == 0 ? "none" : maxFusions));
                                return 1;
                            })));
            dispatcher.register(root);
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String status() {
        return running ? stage : "Stopped";
    }

    @Override
    public void start(Minecraft mc) {
        if (mc.player == null) return;
        Features.starting(id(), mc);
        save();
        confirmClicks = 0;
        seenMenu = clickedMenu = null;
        missingSince = 0;
        armedAt = System.currentTimeMillis();
        stage = "Open the Fusion Box";
        running = true;
        Notify.msg("Fusion macro armed: open the Fusion Box now (it clicks Repeat Previous Fusion and Fusion until the shards run out).");
    }

    @Override
    public void stop(Minecraft mc) {
        if (!running) return;
        running = false;
        stage = "Stopped";
        Notify.msg("Fusion macro stopped: " + fusions + " fusions, " + obtained + " shards obtained.");
    }

    @Override
    public void saveSettings() {
        save();
    }

    // ---- the loop -----------------------------------------------------------------------------------------

    private void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (running && lastTick != 0) runMs += Math.min(1000, now - lastTick);
        lastTick = now;
        if (!running || mc.player == null) return;

        if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) {
            stage = "Open the Fusion Box";
            seenMenu = null;
            if (now - armedAt > 120_000 && fusions == 0) {
                Notify.msg("The Fusion Box was never opened - stopping.");
                stop(mc);
            }
            return;
        }
        armedAt = now; // a menu is open: the "never opened" timer restarts when it closes
        String title = cs.getTitle().getString().toLowerCase(Locale.ROOT);
        boolean box = title.contains(BOX_TITLE), confirm = title.contains(CONFIRM_TITLE);
        if (!box && !confirm) {
            stage = "Waiting for the Fusion Box";
            return;
        }
        var menu = cs.getMenu();
        if (menu != seenMenu) { // a new window: wait a moment (with a random extra) before the click
            seenMenu = menu;
            seenAt = now;
            jitter = jitterMs <= 0 ? 0 : rand.nextInt(jitterMs + 1);
            missingSince = 0;
        }
        if (menu == clickedMenu && now - clickedAt < 3000) {
            stage = confirm ? "Fusing" : "Waiting for the confirm menu";
            return; // already clicked in this window: wait for the next one (retry after 3 s)
        }
        if (now - seenAt < clickDelayMs + jitter) return;

        int n = Math.max(0, menu.slots.size() - 36); // the last 36 slots are the player's inventory
        int slot = box ? find(menu, n, REPEAT_NAME, false) : find(menu, n, FUSE_NAME, true);
        if (slot < 0) { // not by name: the slot seen in the recording, if something is there
            int fallback = box ? REPEAT_SLOT : FUSE_SLOT;
            if (fallback < n && !menu.slots.get(fallback).getItem().isEmpty() && !box) slot = fallback;
        }
        if (slot < 0) {
            stage = box ? "Looking for Repeat Previous Fusion" : "Looking for the Fusion button";
            if (missingSince == 0) missingSince = now;
            if (now - missingSince > 3000) {
                Notify.msg(box
                        ? "No \"Repeat Previous Fusion\" in the Fusion Box (out of shards, or do one fusion by hand first) - stopping."
                        : "No \"Fusion\" button in the confirm menu - stopping.");
                mc.player.closeContainer();
                stop(mc);
            }
            return;
        }
        if (box && maxFusions > 0 && confirmClicks >= maxFusions) {
            Notify.msg("Reached the limit of " + maxFusions + " fusions.");
            mc.player.closeContainer();
            stop(mc);
            return;
        }
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
        clickedMenu = menu;
        clickedAt = now;
        if (confirm) confirmClicks++;
        stage = confirm ? "Fusing" : "Repeating the last fusion";
    }

    /** Slot index (menu part only) whose item name matches (contains, or equals when {@code exact}). */
    private static int find(AbstractContainerMenu menu, int n, String name, boolean exact) {
        for (int i = 0; i < n; i++) {
            var st = menu.slots.get(i).getItem();
            if (st.isEmpty()) continue;
            String nm = st.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
            if (exact ? nm.equals(name) : nm.contains(name)) return i;
        }
        return -1;
    }

    private void onChat(String raw) {
        String s = raw.replaceAll("§.", "").trim();
        int at = s.indexOf("You obtained");
        if (at < 0 || !s.contains("Shard")) return;
        Matcher m = OBTAINED.matcher(s.substring(at));
        if (!m.find()) return;
        int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        fusions++;
        obtained += count;
        lastResult = m.group(1) + " x" + count;
    }

    // ---- settings screen ----------------------------------------------------------------------------------

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        ui.tab("Dashboard", "D");
        ui.section("Overview");
        ui.hero(() -> running ? stage : "Press Start, close this screen, then open the Fusion Box.", "Fusion Box repeater");
        ui.tiles(() -> new String[][]{
                {"" + fusions, "Fusions"},
                {"" + obtained, "Shards obtained"},
                {String.format(Locale.ROOT, "%.0f", runMs < 20_000 ? 0 : fusions / (runMs / 3_600_000.0)), "Fusions / hour"},
                {clock(runMs), "Run time"},
                {lastResult, "Last result"},
                {maxFusions == 0 ? "no limit" : confirmClicks + " / " + maxFusions, "This run"},
        });
        ui.section("How it works");
        ui.note("Start the macro, close this screen and open the Fusion Box (right-click the machine) after doing one fusion by hand. "
                + "It clicks \"Repeat Previous Fusion\", then \"Fusion\" in the confirm menu, and repeats until the shards run out.");
        ui.button("Reset statistics", "Sets the counters and the clock back to zero.", "Reset", false, () -> {
            fusions = 0;
            obtained = 0;
            lastResult = "-";
            runMs = 0;
        });

        ui.tab("Settings", "S");
        ui.section("Clicking");
        ui.islider("Click delay", "How long to wait after a menu opens before clicking (ms). Lower is faster but looks less human.", 150, 1500,
                () -> clickDelayMs, v -> clickDelayMs = v, " ms");
        ui.islider("Random extra", "Up to this many extra ms are added at random to every click.", 0, 600, () -> jitterMs, v -> jitterMs = v, " ms");
        ui.islider("Fusion limit", "Stop after this many fusions. 0 = until the shards run out.", 0, 2000, () -> maxFusions, v -> maxFusions = v, "");
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    // ---- persistence --------------------------------------------------------------------------------------

    private void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            clickDelayMs = clamp(Integer.parseInt(p.getProperty("clickDelayMs", "" + clickDelayMs).trim()), 100, 5000);
            jitterMs = clamp(Integer.parseInt(p.getProperty("jitterMs", "" + jitterMs).trim()), 0, 2000);
            maxFusions = clamp(Integer.parseInt(p.getProperty("maxFusions", "" + maxFusions).trim()), 0, 100000);
        } catch (Exception ignored) {
            // keep defaults
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("clickDelayMs", "" + clickDelayMs);
        p.setProperty("jitterMs", "" + jitterMs);
        p.setProperty("maxFusions", "" + maxFusions);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons: fusion machine");
        } catch (IOException ignored) {
            // not saved
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
