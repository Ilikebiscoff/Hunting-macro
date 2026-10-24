package com.nablzz.addons.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Price history. The API only gives the current prices, so every refresh adds one point (time, insta-sell price, sell offer,
 * weekly volumes) for the items being watched. Kept between sessions (config/nablz_bazaar_history.json). Pure Java.
 */
public final class History {
    private History() {}

    /** Points per item kept: 5 minutes apart, 3 days. */
    public static final int MAX = 864;

    /** The history of one item, oldest first. */
    public static final class Series {
        public final List<long[]> t = new ArrayList<>(); // {time ms, sellWeek, buyWeek}
        public final List<double[]> p = new ArrayList<>(); // {insta-sell, sell offer}

        public int size() {
            return t.size();
        }
    }

    private static final Map<String, Series> DATA = new HashMap<>();
    private static final Set<String> WATCH = new LinkedHashSet<>(List.of("SHARD_MUDWORM", "SHARD_MINER_ZOMBIE", "GLOWING_MUSHROOM",
            "EMPTY_CHUM_BUCKET", "FULL_CHUM_BUCKET", "GLOWY_CHUM_BAIT", "CHUM"));
    private static boolean dirty;

    public static synchronized void watch(String id) {
        if (WATCH.add(id)) dirty = true;
    }

    public static synchronized boolean watching(String id) {
        return WATCH.contains(id);
    }

    public static synchronized boolean dirty() {
        return dirty;
    }

    /** Adds the current prices of every watched item. */
    public static synchronized void record(Map<String, Item> items, long now) {
        for (String id : WATCH) {
            Item it = items.get(id);
            if (it == null) continue;
            Series s = DATA.computeIfAbsent(id, k -> new Series());
            if (!s.t.isEmpty() && now - s.t.get(s.t.size() - 1)[0] < 60_000) continue; // refreshed twice in a minute: one point is enough
            s.t.add(new long[]{now, it.sellWeek, it.buyWeek});
            s.p.add(new double[]{it.buyOrder, it.sellOffer});
            while (s.t.size() > MAX) {
                s.t.remove(0);
                s.p.remove(0);
            }
        }
        dirty = true;
    }

    /** Points of the last {@code windowMs} (0 = all). A copy. */
    public static synchronized Series series(String id, long now, long windowMs) {
        Series s = DATA.get(id), out = new Series();
        if (s == null) return out;
        for (int i = 0; i < s.t.size(); i++) {
            if (windowMs > 0 && now - s.t.get(i)[0] > windowMs) continue;
            out.t.add(s.t.get(i));
            out.p.add(s.p.get(i));
        }
        return out;
    }

    // ---- file --------------------------------------------------------------------------------------------

    public static synchronized String toJson() {
        JsonObject root = new JsonObject();
        JsonArray watch = new JsonArray();
        for (String w : WATCH) watch.add(w);
        root.add("watch", watch);
        JsonObject items = new JsonObject();
        for (var e : DATA.entrySet()) {
            JsonArray a = new JsonArray();
            Series s = e.getValue();
            for (int i = 0; i < s.size(); i++) {
                JsonArray r = new JsonArray();
                r.add(s.t.get(i)[0]);
                r.add(s.p.get(i)[0]);
                r.add(s.p.get(i)[1]);
                r.add(s.t.get(i)[1]);
                r.add(s.t.get(i)[2]);
                a.add(r);
            }
            items.add(e.getKey(), a);
        }
        root.add("items", items);
        dirty = false;
        return root.toString();
    }

    public static synchronized void fromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("watch")) root.getAsJsonArray("watch").forEach(e -> WATCH.add(e.getAsString()));
            if (!root.has("items")) return;
            for (var e : root.getAsJsonObject("items").entrySet()) {
                Series s = new Series();
                for (var r : e.getValue().getAsJsonArray()) {
                    JsonArray a = r.getAsJsonArray();
                    s.t.add(new long[]{a.get(0).getAsLong(), a.get(3).getAsLong(), a.get(4).getAsLong()});
                    s.p.add(new double[]{a.get(1).getAsDouble(), a.get(2).getAsDouble()});
                }
                DATA.put(e.getKey(), s);
            }
        } catch (RuntimeException ignored) {
            // start empty
        }
    }
}
