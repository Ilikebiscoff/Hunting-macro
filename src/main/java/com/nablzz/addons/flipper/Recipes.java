package com.nablzz.addons.flipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * All crafting recipes, read from the NotEnoughUpdates item repo (one JSON file per item with a 3x3 grid of "ITEM_ID:amount").
 * Pure Java apart from Gson, so it can be tested on its own.
 */
public final class Recipes {
    private Recipes() {}

    private static volatile Map<String, List<Recipe>> byOut = Map.of();
    private static volatile Map<String, List<Recipe>> byIngredient = Map.of();

    public static synchronized void set(List<Recipe> all) {
        Map<String, List<Recipe>> out = new HashMap<>(), ing = new HashMap<>();
        for (Recipe r : all) {
            out.computeIfAbsent(r.out(), k -> new ArrayList<>()).add(r);
            for (String i : r.in().keySet()) ing.computeIfAbsent(i, k -> new ArrayList<>()).add(r);
        }
        byIngredient = ing;
        byOut = out;
    }

    public static int count() {
        int n = 0;
        for (List<Recipe> l : byOut.values()) n += l.size();
        return n;
    }

    /** Recipes that make this item. */
    public static List<Recipe> making(String id) {
        return byOut.getOrDefault(id, List.of());
    }

    /** Recipes that use this item as an ingredient. */
    public static List<Recipe> using(String id) {
        return byIngredient.getOrDefault(id, List.of());
    }

    public static List<Recipe> all() {
        List<Recipe> out = new ArrayList<>();
        for (List<Recipe> l : byOut.values()) out.addAll(l);
        return out;
    }

    /** NEU writes damage variants as INK_SACK-3, the Bazaar as INK_SACK:3. */
    public static String normalizeId(String id) {
        return id.matches("[A-Z0-9_]+-\\d+") ? id.replaceFirst("-(\\d+)$", ":$1") : id;
    }

    private static final String[] SLOTS = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};

    /** The crafting recipes of one NEU item file. */
    public static List<Recipe> parseItem(String json) {
        List<Recipe> out = new ArrayList<>();
        JsonObject d;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return out;
            d = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            return out;
        }
        if (!d.has("internalname")) return out;
        String id = normalizeId(d.get("internalname").getAsString());
        String note = d.has("crafttext") ? d.get("crafttext").getAsString().replaceAll("§.", "").trim() : "";
        List<JsonObject> recipes = new ArrayList<>();
        if (d.has("recipes") && d.get("recipes").isJsonArray()) {
            for (JsonElement r : d.getAsJsonArray("recipes")) if (r.isJsonObject()) recipes.add(r.getAsJsonObject());
        } else if (d.has("recipe") && d.get("recipe").isJsonObject()) {
            recipes.add(d.getAsJsonObject("recipe"));
        }
        for (JsonObject r : recipes) {
            if (r.has("type") && !r.get("type").getAsString().equals("crafting")) continue; // forge, trades ... are not crafts
            Map<String, Integer> in = new LinkedHashMap<>();
            for (String slot : SLOTS) {
                if (!r.has(slot) || !r.get(slot).isJsonPrimitive()) continue;
                String v = r.get(slot).getAsString().trim();
                if (v.isEmpty()) continue;
                int cut = v.lastIndexOf(':');
                int n = 1;
                String item = v;
                if (cut > 0 && v.substring(cut + 1).matches("\\d+")) {
                    n = Integer.parseInt(v.substring(cut + 1));
                    item = v.substring(0, cut);
                }
                in.merge(normalizeId(item), n, Integer::sum);
            }
            String outId = r.has("overrideOutputId") ? normalizeId(r.get("overrideOutputId").getAsString()) : id;
            int count = r.has("count") ? Math.max(1, r.get("count").getAsInt()) : 1;
            if (in.isEmpty() || in.containsKey(outId)) continue;
            out.add(new Recipe(outId, count, in, note));
        }
        return out;
    }

    /** The master zip of the NEU repo: every items/*.json inside it. */
    public static List<Recipe> parseZip(InputStream zip) throws IOException {
        List<Recipe> out = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".json") || !n.contains("/items/")) continue;
                String body = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                out.addAll(parseItem(body));
            }
        }
        return out;
    }

    // ---- cache -------------------------------------------------------------------------------------------

    public static String toJson(List<Recipe> all, long fetchedAt) {
        JsonObject root = new JsonObject();
        root.addProperty("fetched", fetchedAt);
        JsonArray arr = new JsonArray();
        for (Recipe r : all) {
            JsonObject o = new JsonObject();
            o.addProperty("out", r.out());
            o.addProperty("n", r.outCount());
            JsonObject in = new JsonObject();
            for (var e : r.in().entrySet()) in.addProperty(e.getKey(), e.getValue());
            o.add("in", in);
            if (!r.note().isEmpty()) o.addProperty("note", r.note());
            arr.add(o);
        }
        root.add("recipes", arr);
        return root.toString();
    }

    /** Fills the index from cache JSON. Returns the time it was fetched (ms), or 0 when unusable. */
    public static long fromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<Recipe> all = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("recipes")) {
                JsonObject o = e.getAsJsonObject();
                Map<String, Integer> in = new LinkedHashMap<>();
                for (var i : o.getAsJsonObject("in").entrySet()) in.put(i.getKey(), i.getValue().getAsInt());
                all.add(new Recipe(o.get("out").getAsString(), o.get("n").getAsInt(), in, o.has("note") ? o.get("note").getAsString() : ""));
            }
            if (all.isEmpty()) return 0;
            set(all);
            return root.get("fetched").getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
