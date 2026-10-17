package com.nablzz.addons.mudworm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts what the spots drop ("Found Fig Log x174 on the ground!", Mudworm shards) and values it at live Bazaar
 * instant-sell prices. An item is looked up as its upper-case name with underscores (Fig Log -> FIG_LOG); items
 * the Bazaar does not know count as 0 coins.
 */
public final class MudwormProfit {
    private MudwormProfit() {}

    private static final String BAZAAR = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long REFRESH_MS = 5 * 60_000;
    private static final Pattern DROP = Pattern.compile("(?i)(?:found\\s+)?(.+?)\\s*(?:x|\\+)\\s*([\\d,]+)\\s+on the ground");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Item name -> amount, in the order first seen. */
    private static final Map<String, Long> DROPS = new LinkedHashMap<>();
    private static volatile JsonObject products;
    private static volatile boolean pricesLive;
    private static long lastFetch;

    /** Returns true if the line was a drop (or a shard) and was counted. Call on the client thread. */
    public static synchronized boolean onChat(String raw) {
        String s = raw.replaceAll("§.", "").trim();
        Matcher m = DROP.matcher(s);
        if (m.find()) {
            String name = m.group(1).replaceAll("^[^A-Za-z]+", "").trim();
            long n = Long.parseLong(m.group(2).replace(",", ""));
            if (!name.isEmpty() && n > 0) {
                DROPS.merge(name, n, Long::sum);
                return true;
            }
        }
        if (s.contains("Mudworm Shard")) {
            Matcher c = Pattern.compile("x(\\d+)").matcher(s);
            DROPS.merge("Mudworm Shard", c.find() ? Long.parseLong(c.group(1)) : 1L, Long::sum);
            return true;
        }
        return false;
    }

    public static void tickPrices(long now) {
        if (now - lastFetch < REFRESH_MS) return;
        lastFetch = now;
        HttpRequest req = HttpRequest.newBuilder(URI.create(BAZAAR)).timeout(Duration.ofSeconds(15)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
            try {
                products = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("products");
                pricesLive = true;
            } catch (Exception ex) {
                pricesLive = false;
            }
        }).exceptionally(t -> {
            pricesLive = false;
            return null;
        });
    }

    public static boolean pricesLive() {
        return pricesLive;
    }

    /** Coins per item (instant sell), 0 when the Bazaar does not list it. */
    public static double price(String name) {
        JsonObject p = products;
        if (p == null) return 0;
        String id = name.toUpperCase(Locale.ROOT).replace(' ', '_').replaceAll("[^A-Z0-9_]", "");
        for (String key : new String[]{id, "SHARD_" + id.replace("_SHARD", "")}) {
            if (!p.has(key)) continue;
            JsonObject o = p.getAsJsonObject(key);
            var summary = o.getAsJsonArray("sell_summary");
            if (summary != null && !summary.isEmpty()) return summary.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
            if (o.has("quick_status")) return o.getAsJsonObject("quick_status").get("sellPrice").getAsDouble();
        }
        return 0;
    }

    public record Line(String name, long count, double coins) {}

    /** Drops, most valuable first. */
    public static synchronized List<Line> lines() {
        List<Line> out = new ArrayList<>();
        for (var e : DROPS.entrySet()) out.add(new Line(e.getKey(), e.getValue(), e.getValue() * price(e.getKey())));
        out.sort((a, b) -> Double.compare(b.coins(), a.coins()));
        return out;
    }

    public static synchronized double coins() {
        double c = 0;
        for (var e : DROPS.entrySet()) c += e.getValue() * price(e.getKey());
        return c;
    }

    public static synchronized long items() {
        long n = 0;
        for (long v : DROPS.values()) n += v;
        return n;
    }

    public static synchronized void reset() {
        DROPS.clear();
    }

    public static String fmt(double v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.ROOT, "%.1fk", v / 1_000);
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
