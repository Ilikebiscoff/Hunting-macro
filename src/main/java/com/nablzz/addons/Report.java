package com.nablzz.addons;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A text report of what the mod did lately and everything needed to find a problem: versions, the state of every
 * feature, what the macros printed, the chat, the settings files and the filtered game log. It is saved to
 * config/nablzz_report.txt and copied to the clipboard (button in General / Report, or /nablzz report).
 */
public final class Report {
    private Report() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> EVENTS = new ArrayDeque<>();
    private static final Deque<String> CHAT = new ArrayDeque<>();
    public static volatile String last = "no report yet";

    /** Hook the server chat in (the messages of the mod itself arrive through {@link #event}). */
    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String s = clean(message.getString());
            if (s.isBlank() || s.contains("items stashed away") || s.contains("CLICK HERE")) return;
            add(CHAT, s, 250);
        });
    }

    /** Something the mod or a macro told the player (all three chat helpers call this). */
    public static void event(String s) {
        add(EVENTS, clean(s), 400);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
    }

    private static void add(Deque<String> q, String s, int cap) {
        synchronized (q) {
            q.addLast(LocalDateTime.now().format(TIME) + "  " + s);
            while (q.size() > cap) q.removeFirst();
        }
    }

    private static List<String> copy(Deque<String> q) {
        synchronized (q) {
            return new ArrayList<>(q);
        }
    }

    /** Builds the report, saves it and copies it to the clipboard. */
    public static void run(Minecraft mc) {
        String text = build(mc);
        Path file = FabricLoader.getInstance().getConfigDir().resolve("nablzz_report.txt");
        String saved = "";
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            saved = " Saved to config/nablzz_report.txt.";
        } catch (IOException e) {
            saved = " (could not save the file: " + e.getMessage() + ")";
        }
        try {
            mc.keyboardHandler.setClipboard(text);
            last = LocalDateTime.now().format(TIME) + ", " + text.split("\n").length + " lines, copied";
            Notify.msg("Report copied to the clipboard (" + text.split("\n").length + " lines)." + saved + " Paste it to Claude.");
        } catch (RuntimeException e) {
            last = "saved only";
            Notify.msg("Report ready." + saved + " The clipboard did not work: open that file and paste it.");
        }
    }

    public static String build(Minecraft mc) {
        StringBuilder sb = new StringBuilder(32_000);
        line(sb, "=== NABLZZ ADDONS REPORT  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ===");
        section(sb, "Versions");
        line(sb, "Nablzz Addons: " + Updater.versionInfo());
        FabricLoader.getInstance().getModContainer("minecraft")
                .ifPresent(m -> line(sb, "Minecraft: " + m.getMetadata().getVersion().getFriendlyString()));
        line(sb, "Fabric Loader: " + FabricLoader.getInstance().getModContainer("fabricloader")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        line(sb, "Java: " + System.getProperty("java.version") + " on " + System.getProperty("os.name"));

        section(sb, "Features");
        for (Feature f : Features.all()) {
            line(sb, f.name() + ": " + (f.isRunning() ? "RUNNING" : "stopped") + " - " + f.status());
            try {
                f.report(s -> line(sb, "    " + s));
            } catch (RuntimeException e) {
                line(sb, "    (report failed: " + e + ")");
            }
        }

        section(sb, "Player and world");
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            line(sb, "not in a world");
        } else {
            line(sb, String.format(Locale.ROOT, "position %.1f %.1f %.1f, yaw %.0f pitch %.0f, on ground %s, sneaking %s",
                    p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround(), p.isShiftKeyDown()));
            line(sb, "dimension: " + mc.level.dimension());
            StringBuilder hb = new StringBuilder("hotbar (selected " + (p.getInventory().getSelectedSlot() + 1) + "): ");
            for (int i = 0; i < 9; i++) {
                var st = p.getInventory().getItem(i);
                hb.append(i + 1).append('=').append(st.isEmpty() ? "-" : clean(st.getHoverName().getString())).append("; ");
            }
            line(sb, hb.toString());
            var lines = com.nablzz.addons.mushroom.Failsafe.sidebar(mc);
            line(sb, "scoreboard: " + com.nablzz.addons.mushroom.Failsafe.sidebarTitle(mc));
            if (lines != null) for (String l : lines) line(sb, "    " + l);
        }

        section(sb, "What the mod said lately (oldest first)");
        for (String e : copy(EVENTS)) line(sb, e);

        section(sb, "Chat lately");
        for (String c : copy(CHAT)) line(sb, c);

        section(sb, "Settings files");
        Path cfg = FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> files = Files.list(cfg)) {
            for (Path f : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(n.startsWith("nablz") || n.startsWith("huntingmacro") || n.startsWith("glowingmushroom"))) continue;
                if (n.equals("nablz_report.txt") || n.contains("pathmemory") || n.endsWith("_spots.json")) continue; // big, not useful
                String body = Files.readString(f, StandardCharsets.UTF_8);
                if (n.endsWith(".json")) { // squeeze the JSON files (routes) onto few lines
                    body = body.replaceAll("\\s+", "").replaceAll("(-?\\d+\\.\\d)\\d+", "$1");
                    line(sb, "--- " + f.getFileName() + " (compact) ---");
                } else {
                    line(sb, "--- " + f.getFileName() + " ---");
                }
                if (body.length() > 3000) body = body.substring(0, 3000) + "\n... (cut)";
                for (String l : body.split("\n")) if (!l.startsWith("#")) line(sb, l);
            }
        } catch (IOException | RuntimeException e) {
            line(sb, "could not read the config folder: " + e);
        }

        section(sb, "Game log (latest.log, filtered, last lines)");
        Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> keep = new ArrayList<>();
            for (String l : all) if (!noise(l)) keep.add(l);
            int from = Math.max(0, keep.size() - 350);
            for (int i = from; i < keep.size(); i++) line(sb, keep.get(i));
        } catch (IOException | RuntimeException e) {
            line(sb, "could not read the log: " + e);
        }

        section(sb, "Loaded mods");
        StringBuilder mods = new StringBuilder();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            String id = m.getMetadata().getId();
            if (id.startsWith("fabric-") || id.equals("java") || id.equals("mixinextras")) continue;
            mods.append(id).append(' ').append(m.getMetadata().getVersion().getFriendlyString()).append("; ");
        }
        line(sb, mods.toString());
        line(sb, "=== END OF REPORT ===");
        return sb.toString();
    }

    /** Lines that only bury the useful ones (skin signature spam, texture atlases, stack frames of those errors ...). */
    private static boolean noise(String l) {
        if (l.isBlank()) return true;
        String t = l.stripLeading();
        if (t.startsWith("at ") || t.startsWith("java.security.SignatureException")) return true;
        return l.contains("Failed to verify signature") || l.contains("invalid signature for textures")
                || l.contains("unknown team") || l.contains("TextureAtlas") || l.contains("ModelManager")
                || l.contains("Dynamic Transforms") || l.contains("worker threads") || l.contains("UnihexProvider")
                || l.contains("passengers for unknown entity") || l.contains("stashed away") || l.contains("CLICK HERE")
                || l.contains("Missing texture") || l.contains("missing texture") || l.contains("fishing hook");
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('\n').append("---- ").append(title).append(" ----").append('\n');
    }

    private static void line(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }
}
