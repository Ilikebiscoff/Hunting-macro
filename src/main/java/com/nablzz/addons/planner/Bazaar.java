package com.nablzz.addons.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the Bazaar prices fresh: a background thread asks the public Hypixel API (no key needed) every 5 minutes,
 * keeps a copy on disk so the planner still has numbers when the API is down, and also looks up the Auction House
 * price of the Glowing Mush Mixin and the item names (both best effort).
 */
public final class Bazaar {
    private Bazaar() {}

    public static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final long RETRY_MS = 60 * 1000L;
    private static final String API = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String NAMES = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String LOWEST_BIN = "https://moulberry.codes/lowestbin.json";

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Object LOCK = new Object();

    private static volatile Parser.Snapshot snapshot;
    private static volatile Map<String, String> names = new HashMap<>();
    private static volatile double mixinPrice;
    private static volatile String message = "starting ...";
    private static volatile boolean fromCache;
    private static volatile long nextAt, lastOkAt;
    private static volatile int version;
    private static Path cacheFile, historyFile;
    private static boolean started, wake;
    private static long namesAt;

    public static Parser.Snapshot snapshot() {
        return snapshot;
    }

    /** Changes every time new prices arrive. */
    public static int version() {
        return version;
    }

    public static String message() {
        return message;
    }

    public static boolean fromCache() {
        return fromCache;
    }

    public static long nextRefreshAt() {
        return nextAt;
    }

    public static double mixinPrice() {
        return mixinPrice;
    }

    /** Item id for a display name ("Fig Log" -> FIG_LOG), or null when the Bazaar does not sell it. */
    public static String idOfName(String name) {
        Parser.Snapshot sn = snapshot;
        if (sn == null || name == null) return null;
        String guess = name.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace("'", "").replace("-", "_");
        if (sn.items().containsKey(guess)) return guess;
        String want = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (var e : names.entrySet()) {
            if (e.getValue().toLowerCase(java.util.Locale.ROOT).equals(want) && sn.items().containsKey(e.getKey())) return e.getKey();
        }
        return null;
    }

    /** What one item sells for instantly after tax, 0 when it is not on the Bazaar or has no buy order. */
    public static double instaSellValue(String name) {
        String id = idOfName(name);
        Parser.Snapshot sn = snapshot;
        if (id == null || sn == null) return 0;
        Item it = sn.items().get(id);
        return it == null ? 0 : it.buyOrder * (1 - 0.0125);
    }

    public static String nameOf(String id) {
        String n = names.get(id);
        return n != null ? n : Fmt.pretty(id);
    }

    /** Fetches now instead of waiting for the next 5-minute tick. */
    public static void refreshNow() {
        synchronized (LOCK) {
            wake = true;
            LOCK.notifyAll();
        }
    }

    public static synchronized void start(Path cache) {
        if (started) return;
        started = true;
        cacheFile = cache;
        historyFile = cache.resolveSibling("nablz_bazaar_history.json");
        loadCache();
        loadHistory();
        Thread t = new Thread(Bazaar::loop, "nablzz-bazaar");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            long wait = REFRESH_MS;
            try {
                fetchBazaar();
                message = "up to date";
                lastOkAt = System.currentTimeMillis();
            } catch (Exception e) {
                wait = RETRY_MS;
                message = "could not reach the Bazaar API (" + shortErr(e) + ")" + (snapshot != null ? " - using the last prices" : "");
            }
            try {
                fetchMixin();
            } catch (Exception ignored) {
                // the Mixin route then shows "No AH price" unless you typed one in
            }
            if (names.isEmpty() || System.currentTimeMillis() - namesAt > 24 * 3600 * 1000L) {
                try {
                    fetchNames();
                } catch (Exception ignored) {
                    // names are prettified from the ids instead
                }
            }
            synchronized (LOCK) {
                nextAt = System.currentTimeMillis() + wait;
                long until = nextAt;
                while (!wake) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    try {
                        LOCK.wait(left);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                wake = false;
            }
        }
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.length() > 80 ? m.substring(0, 80) : m;
    }

    private static String get(String url, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "nablzz-addons")
                .timeout(Duration.ofSeconds(timeoutSec)).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

    private static void fetchBazaar() throws Exception {
        Parser.Snapshot s = Parser.parseBazaar(get(API, 30), System.currentTimeMillis());
        snapshot = s;
        fromCache = false;
        version++;
        saveCache(s);
        History.record(s.items(), s.fetchedAt());
        saveHistory();
    }

    private static void fetchMixin() throws Exception {
        double p = Parser.parseMixin(get(LOWEST_BIN, 30));
        if (p > 0 && p != mixinPrice) {
            mixinPrice = p;
            version++;
        }
    }

    private static void fetchNames() throws Exception {
        Map<String, String> n = Parser.parseNames(get(NAMES, 30));
        if (!n.isEmpty()) {
            names = n;
            namesAt = System.currentTimeMillis();
            version++;
        }
    }

    // ---- disk copy ---------------------------------------------------------------------------------------

    private static void saveHistory() {
        if (historyFile == null || !History.dirty()) return;
        try {
            Files.writeString(historyFile, History.toJson());
        } catch (IOException | RuntimeException ignored) {
            // no history copy
        }
    }

    private static void loadHistory() {
        if (historyFile == null || !Files.isRegularFile(historyFile)) return;
        try {
            History.fromJson(Files.readString(historyFile));
        } catch (IOException | RuntimeException ignored) {
            // start empty
        }
    }

    private static void saveCache(Parser.Snapshot s) {
        if (cacheFile == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("updated", s.apiUpdated());
            root.addProperty("fetched", s.fetchedAt());
            JsonObject items = new JsonObject();
            for (Item it : s.items().values()) {
                JsonArray a = new JsonArray();
                a.add(it.buyOrder);
                a.add(it.sellOffer);
                a.add(it.sellWeek);
                a.add(it.buyWeek);
                items.add(it.id, a);
            }
            root.add("items", items);
            Files.writeString(cacheFile, root.toString());
        } catch (IOException | RuntimeException ignored) {
            // no copy
        }
    }

    private static void loadCache() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject();
            Map<String, Item> items = new HashMap<>();
            for (var e : root.getAsJsonObject("items").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                items.put(e.getKey(), new Item(e.getKey(), a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsLong(), a.get(3).getAsLong()));
            }
            if (items.isEmpty()) return;
            snapshot = new Parser.Snapshot(items, root.get("updated").getAsLong(), root.get("fetched").getAsLong());
            fromCache = true;
            version++;
            message = "showing the copy from the last session until the API answers";
        } catch (IOException | RuntimeException ignored) {
            // no usable copy
        }
    }
}
