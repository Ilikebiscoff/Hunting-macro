package com.nablzz.addons.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the JSON the planner uses. No Minecraft classes, so it can be tested on its own. */
public final class Parser {
    private Parser() {}

    /** The Bazaar at one moment. {@code apiUpdated} is the API's own timestamp (ms since 1970), {@code fetchedAt} when we got it. */
    public record Snapshot(Map<String, Item> items, long apiUpdated, long fetchedAt) {}

    private static final int DEPTH = 12;

    private static double num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    /** {amounts, prices, orders per level}. */
    private static double[][] levels(JsonArray arr) {
        int n = arr == null ? 0 : Math.min(DEPTH, arr.size());
        double[] amt = new double[n], price = new double[n], orders = new double[n];
        for (int i = 0; i < n; i++) {
            JsonObject l = arr.get(i).getAsJsonObject();
            amt[i] = num(l, "amount");
            price[i] = num(l, "pricePerUnit");
            orders[i] = num(l, "orders");
        }
        return new double[][]{amt, price, orders};
    }

    private static int[] ints(double[] d) {
        int[] o = new int[d.length];
        for (int i = 0; i < d.length; i++) o[i] = (int) d[i];
        return o;
    }

    /** https://api.hypixel.net/v2/skyblock/bazaar */
    public static Snapshot parseBazaar(String json, long now) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("success") && !root.get("success").getAsBoolean()) throw new IllegalStateException("the API said success = false");
        JsonObject products = root.getAsJsonObject("products");
        if (products == null || products.size() == 0) throw new IllegalStateException("no products in the answer");
        Map<String, Item> items = new HashMap<>(products.size() * 2);
        for (var e : products.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject p = e.getValue().getAsJsonObject();
            double[][] buyOrders = levels(p.getAsJsonArray("sell_summary")); // sell_summary = the buy orders (instant-sell price)
            double[][] sellOffers = levels(p.getAsJsonArray("buy_summary"));  // buy_summary = the sell offers (instant-buy price)
            JsonObject q = p.getAsJsonObject("quick_status");
            double bo = buyOrders[1].length > 0 ? buyOrders[1][0] : 0, so = sellOffers[1].length > 0 ? sellOffers[1][0] : 0;
            items.put(e.getKey(), new Item(e.getKey(), bo, so, (long) num(q, "sellMovingWeek"), (long) num(q, "buyMovingWeek"),
                    buyOrders[0], buyOrders[1], sellOffers[0], sellOffers[1], ints(buyOrders[2]), ints(sellOffers[2]),
                    (long) num(q, "sellOrders"), (long) num(q, "buyOrders"), (long) num(q, "sellVolume"), (long) num(q, "buyVolume")));
        }
        return new Snapshot(items, root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : now, now);
    }

    /** https://api.hypixel.net/v2/resources/skyblock/items : item id to display name. */
    public static Map<String, String> parseNames(String json) {
        Map<String, String> out = new HashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("items");
        if (arr == null) return out;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("id") && o.has("name")) out.put(o.get("id").getAsString(), o.get("name").getAsString());
        }
        return out;
    }

    /** Cheapest auction House BIN of the Glowing Mush Mixin in a "lowest BIN" file ({"ITEM_ID": price, ...}); 0 when none. */
    public static double parseMixin(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        double best = 0;
        for (var e : root.entrySet()) {
            String k = e.getKey().toUpperCase(Locale.ROOT);
            if (!k.contains("MUSH") || !k.contains("MIXIN")) continue;
            double v = e.getValue().getAsDouble();
            if (v > 0 && (best == 0 || v < best)) best = v;
        }
        return best;
    }

    /** The fusion table: {"SHARD_X": [[type, partnerId, partnerName, partnerQty, shardQty, outName, outId, outQty], ...], ...}. */
    public static Map<String, List<Calc.Fusion>> loadFusions(Reader in) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        Map<String, List<Calc.Fusion>> out = new LinkedHashMap<>();
        for (var e : root.entrySet()) {
            List<Calc.Fusion> rows = new ArrayList<>();
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                rows.add(new Calc.Fusion(a.get(0).getAsString(), a.get(1).getAsString(), a.get(2).getAsString(), a.get(3).getAsInt(),
                        a.get(4).getAsInt(), a.get(5).getAsString(), a.get(6).getAsString(), a.get(7).getAsInt()));
            }
            out.put(e.getKey(), rows);
        }
        return out;
    }
}
