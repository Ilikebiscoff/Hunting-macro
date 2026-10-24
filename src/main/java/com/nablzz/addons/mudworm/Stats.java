package com.nablzz.addons.mudworm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nablzz.addons.planner.Bazaar;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers what the macro earned, between sessions, in config/nablz_mudworm_stats.json: per session the time, spots, every
 * floor drop by item, an estimated coin value (insta-sell price on the Bazaar at the moment of the drop, after tax) and the
 * averages (drops per hour, coins per hour ...).
 */
final class Stats {
    private Stats() {}

    static final class Drop {
        long count, events, unpriced;
        double coins;
    }

    static final class Session {
        String start = "";
        long seconds;
        int spots, floorDrops, warps;
        double coins;
        boolean live;
        final Map<String, Drop> drops = new LinkedHashMap<>();

        double perHour(double v) {
            return seconds <= 0 ? 0 : v / (seconds / 3600.0);
        }
    }

    /** Totals over several sessions. */
    static final class Totals {
        long seconds, sessions;
        int spots, floorDrops, warps;
        double coins;
        final Map<String, Drop> drops = new LinkedHashMap<>();

        double perHour(double v) {
            return seconds <= 0 ? 0 : v / (seconds / 3600.0);
        }
    }

    private static final Pattern DROP = Pattern.compile("You found (.+?)(?:\\s+x([\\d,]+))?\\s+on the ground", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int KEEP = 500;

    private static final List<Session> DONE = new ArrayList<>();
    private static Session cur;
    private static long curStartMs, lastSave;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablz_mudworm_stats.json");
    }

    static Path path() {
        return file();
    }

    // ---- recording ---------------------------------------------------------------------------------------

    static synchronized void begin() {
        if (cur != null) end();
        cur = new Session();
        cur.start = LocalDateTime.now().format(STAMP);
        cur.live = true;
        curStartMs = System.currentTimeMillis();
        lastSave = curStartMs;
    }

    static synchronized void end() {
        if (cur == null) return;
        cur.seconds = (System.currentTimeMillis() - curStartMs) / 1000;
        cur.live = false;
        if (cur.seconds >= 10 || cur.spots > 0 || cur.floorDrops > 0) {
            DONE.add(cur);
            while (DONE.size() > KEEP) DONE.remove(0);
        }
        cur = null;
        save();
    }

    static synchronized void spot() {
        if (cur != null) cur.spots++;
    }

    static synchronized void warp() {
        if (cur != null) cur.warps++;
    }

    /** A "FLOOR DROP! You found Fig Log x208 on the ground!" line. */
    static synchronized void chat(String line) {
        if (cur == null || !line.contains("FLOOR DROP")) return;
        Matcher m = DROP.matcher(line);
        if (!m.find()) {
            cur.floorDrops++;
            return;
        }
        String item = m.group(1).trim();
        long n = 1;
        if (m.group(2) != null) {
            try {
                n = Long.parseLong(m.group(2).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // count stays 1
            }
        }
        cur.floorDrops++;
        Drop d = cur.drops.computeIfAbsent(item, k -> new Drop());
        d.count += n;
        d.events++;
        double v = Bazaar.instaSellValue(item);
        if (v > 0) {
            d.coins += v * n;
            cur.coins += v * n;
        } else {
            d.unpriced++;
        }
    }

    /** Saves every minute while a session runs (so a crash loses little). */
    static synchronized void tick() {
        if (cur != null && System.currentTimeMillis() - lastSave > 60_000) save();
    }

    static synchronized void clear() {
        DONE.clear();
        if (cur != null) {
            Session fresh = new Session();
            fresh.start = cur.start;
            fresh.live = true;
            cur = fresh;
            curStartMs = System.currentTimeMillis();
        }
        save();
    }

    /** Zero the running session too (the HUD reset). Finished sessions stay. */
    static synchronized void resetCurrent() {
        if (cur == null) return;
        Session fresh = new Session();
        fresh.start = LocalDateTime.now().format(STAMP);
        fresh.live = true;
        cur = fresh;
        curStartMs = System.currentTimeMillis();
    }

    // ---- reading -----------------------------------------------------------------------------------------

    /** The running session as it is now (seconds filled in), or null. */
    static synchronized Session live() {
        if (cur == null) return null;
        cur.seconds = (System.currentTimeMillis() - curStartMs) / 1000;
        return cur;
    }

    static synchronized List<Session> finished() {
        return new ArrayList<>(DONE);
    }

    static synchronized Totals totals(boolean includeLive) {
        Totals t = new Totals();
        List<Session> all = new ArrayList<>(DONE);
        if (includeLive && cur != null) {
            live();
            all.add(cur);
        }
        for (Session s : all) {
            t.sessions++;
            t.seconds += s.seconds;
            t.spots += s.spots;
            t.floorDrops += s.floorDrops;
            t.warps += s.warps;
            t.coins += s.coins;
            for (var e : s.drops.entrySet()) {
                Drop d = t.drops.computeIfAbsent(e.getKey(), k -> new Drop());
                d.count += e.getValue().count;
                d.events += e.getValue().events;
                d.unpriced += e.getValue().unpriced;
                d.coins += e.getValue().coins;
            }
        }
        return t;
    }

    // ---- file --------------------------------------------------------------------------------------------

    private static JsonObject json(Session s) {
        JsonObject o = new JsonObject();
        o.addProperty("start", s.start);
        o.addProperty("seconds", s.seconds);
        o.addProperty("spots", s.spots);
        o.addProperty("floorDrops", s.floorDrops);
        o.addProperty("warps", s.warps);
        o.addProperty("coins", Math.round(s.coins));
        o.addProperty("coinsPerHour", Math.round(s.perHour(s.coins)));
        o.addProperty("live", s.live);
        JsonObject d = new JsonObject();
        for (var e : s.drops.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("count", e.getValue().count);
            x.addProperty("events", e.getValue().events);
            x.addProperty("coins", Math.round(e.getValue().coins));
            x.addProperty("unpriced", e.getValue().unpriced);
            d.add(e.getKey(), x);
        }
        o.add("drops", d);
        return o;
    }

    private static Session fromJson(JsonObject o) {
        Session s = new Session();
        s.start = o.has("start") ? o.get("start").getAsString() : "";
        s.seconds = o.has("seconds") ? o.get("seconds").getAsLong() : 0;
        s.spots = o.has("spots") ? o.get("spots").getAsInt() : 0;
        s.floorDrops = o.has("floorDrops") ? o.get("floorDrops").getAsInt() : 0;
        s.warps = o.has("warps") ? o.get("warps").getAsInt() : 0;
        s.coins = o.has("coins") ? o.get("coins").getAsDouble() : 0;
        if (o.has("drops")) {
            for (var e : o.getAsJsonObject("drops").entrySet()) {
                JsonObject x = e.getValue().getAsJsonObject();
                Drop d = new Drop();
                d.count = x.has("count") ? x.get("count").getAsLong() : 0;
                d.events = x.has("events") ? x.get("events").getAsLong() : 0;
                d.coins = x.has("coins") ? x.get("coins").getAsDouble() : 0;
                d.unpriced = x.has("unpriced") ? x.get("unpriced").getAsLong() : 0;
                s.drops.put(e.getKey(), d);
            }
        }
        return s;
    }

    static synchronized void load() {
        DONE.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("sessions");
            if (arr == null) return;
            for (JsonElement e : arr) DONE.add(fromJson(e.getAsJsonObject())); // a "live" one is a session that was cut off: kept as finished
        } catch (IOException | RuntimeException ignored) {
            // start empty
        }
    }

    static synchronized void save() {
        lastSave = System.currentTimeMillis();
        try {
            Totals t = totals(true);
            JsonObject root = new JsonObject();
            JsonObject tot = new JsonObject();
            tot.addProperty("sessions", t.sessions);
            tot.addProperty("hours", Math.round(t.seconds / 36.0) / 100.0);
            tot.addProperty("spots", t.spots);
            tot.addProperty("floorDrops", t.floorDrops);
            tot.addProperty("warps", t.warps);
            tot.addProperty("coins", Math.round(t.coins));
            root.add("totals", tot);
            JsonObject avg = new JsonObject();
            avg.addProperty("coinsPerHour", Math.round(t.perHour(t.coins)));
            avg.addProperty("spotsPerHour", Math.round(t.perHour(t.spots) * 10) / 10.0);
            avg.addProperty("floorDropsPerHour", Math.round(t.perHour(t.floorDrops) * 10) / 10.0);
            avg.addProperty("coinsPerSpot", t.spots == 0 ? 0 : Math.round(t.coins / t.spots));
            avg.addProperty("dropsPerSpot", t.spots == 0 ? 0 : Math.round(t.floorDrops * 100.0 / t.spots) / 100.0);
            JsonObject perItem = new JsonObject();
            for (var e : t.drops.entrySet()) {
                JsonObject x = new JsonObject();
                x.addProperty("total", e.getValue().count);
                x.addProperty("perHour", Math.round(t.perHour(e.getValue().count) * 10) / 10.0);
                x.addProperty("averagePerDrop", e.getValue().events == 0 ? 0 : Math.round(e.getValue().count * 10.0 / e.getValue().events) / 10.0);
                x.addProperty("coins", Math.round(e.getValue().coins));
                x.addProperty("coinsPerHour", Math.round(t.perHour(e.getValue().coins)));
                perItem.add(e.getKey(), x);
            }
            avg.add("perItem", perItem);
            root.add("averages", avg);
            JsonArray arr = new JsonArray();
            for (Session s : DONE) arr.add(json(s));
            if (cur != null) {
                live();
                arr.add(json(cur));
            }
            root.add("sessions", arr);
            root.addProperty("note", "Coins are estimates: the instant-sell price on the Bazaar when the drop happened, minus 1.25 % tax. Items not on the Bazaar count as 0.");
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException | RuntimeException ignored) {
            // not saved
        }
    }
}
