package com.nablzz.addons.stats;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.Notify;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers every run of every macro. When a macro with {@link Feature#sessionCounters()} starts, a session opens; while it
 * runs the counters are added up (a reset of a counter in the middle is handled); when it stops the session is checked by
 * {@link Rules}: a run that makes no sense is thrown away, the rest is saved in config/nablz_sessions.json together with
 * the averages (per hour, per session).
 */
public final class SessionLog {
    private SessionLog() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int KEEP = 1000;

    private static final class Track {
        final Session s = new Session();
        final Map<String, Double> last = new HashMap<>();
        long lastTick;
        double seconds;
    }

    private static final List<Session> SAVED = new ArrayList<>();
    private static final List<String> DISCARDED = new ArrayList<>();
    private static final Map<String, Track> TRACKS = new HashMap<>();
    private static long lastSave;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablz_sessions.json");
    }

    public static void init() {
        load();
        ClientTickEvents.START_CLIENT_TICK.register(mc -> tick());
    }

    // ---- recording ---------------------------------------------------------------------------------------

    private static synchronized void tick() {
        long now = System.currentTimeMillis();
        for (Feature f : Features.all()) {
            Map<String, Double> c = f.sessionCounters();
            if (c.isEmpty()) continue;
            Track t = TRACKS.get(f.id());
            if (f.isRunning()) {
                if (t == null) begin(f, c, now);
                else accumulate(t, c, now);
            } else if (t != null) {
                accumulate(t, c, now);
                end(f, t);
            }
        }
        if (!TRACKS.isEmpty() && now - lastSave > 60_000) save();
    }

    private static void begin(Feature f, Map<String, Double> c, long now) {
        Track t = new Track();
        t.s.feature = f.id();
        t.s.name = f.name();
        t.s.main = f.sessionMain();
        t.s.startMs = now;
        t.s.start = LocalDateTime.now().format(STAMP);
        t.s.live = true;
        t.lastTick = now;
        t.last.putAll(c); // what the counters showed already does not belong to this run
        TRACKS.put(f.id(), t);
    }

    private static void accumulate(Track t, Map<String, Double> c, long now) {
        t.seconds += Math.min(2.0, (now - t.lastTick) / 1000.0);
        t.lastTick = now;
        for (var e : c.entrySet()) {
            double cur = e.getValue(), prev = t.last.getOrDefault(e.getKey(), 0.0);
            double delta = cur >= prev ? cur - prev : cur; // a counter that went down was reset: it counts again from 0
            if (delta != 0 || t.s.metrics.containsKey(e.getKey())) t.s.metrics.merge(e.getKey(), delta, Double::sum);
            t.last.put(e.getKey(), cur);
        }
    }

    private static void end(Feature f, Track t) {
        TRACKS.remove(f.id());
        Session s = t.s;
        s.seconds = (long) t.seconds;
        s.live = false;
        String why = Rules.hard(s, history(s.feature, null));
        if (why != null) {
            DISCARDED.add(0, s.start + "  " + s.name + ": " + why);
            while (DISCARDED.size() > 12) DISCARDED.remove(DISCARDED.size() - 1);
            Notify.msg("Session not saved (" + why + ").");
        } else {
            SAVED.add(s);
            while (SAVED.size() > KEEP) SAVED.remove(0);
            Notify.msg(String.format(java.util.Locale.ROOT, "Session saved: %d min, %s %.0f.", s.seconds / 60, s.main.toLowerCase(java.util.Locale.ROOT), s.mainValue()));
        }
        save();
    }

    // ---- reading and managing -----------------------------------------------------------------------------

    private static List<Session> history(String feature, Session except) {
        List<Session> out = new ArrayList<>();
        for (Session s : SAVED) if (s.feature.equals(feature) && s != except) out.add(s);
        return out;
    }

    /** This macro's saved sessions, newest first. */
    public static synchronized List<Session> saved(String feature) {
        List<Session> out = history(feature, null);
        java.util.Collections.reverse(out);
        return out;
    }

    /** The run in progress, with the numbers so far, or null. */
    public static synchronized Session live(String feature) {
        Track t = TRACKS.get(feature);
        if (t == null) return null;
        t.s.seconds = (long) t.seconds;
        return t.s;
    }

    public static synchronized List<String> discarded() {
        return new ArrayList<>(DISCARDED);
    }

    /** Why a saved session looks doubtful, or null. */
    public static synchronized String check(Session s) {
        return Rules.soft(s, history(s.feature, s));
    }

    public static synchronized boolean delete(String id) {
        boolean r = SAVED.removeIf(s -> s.id().equals(id));
        if (r) save();
        return r;
    }

    /** Removes the doubtful sessions (feature = null: of every macro). Returns how many. */
    public static synchronized int deleteSuspicious(String feature) {
        List<Session> bad = new ArrayList<>();
        for (Session s : SAVED) if ((feature == null || s.feature.equals(feature)) && Rules.soft(s, history(s.feature, s)) != null) bad.add(s);
        SAVED.removeAll(bad);
        if (!bad.isEmpty()) save();
        return bad.size();
    }

    public static synchronized int deleteAll(String feature) {
        int n = 0;
        for (var it = SAVED.iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (feature == null || s.feature.equals(feature)) {
                it.remove();
                n++;
            }
        }
        if (n > 0) save();
        return n;
    }

    /** Sum of every metric over the saved sessions of a macro (plus the running one when {@code withLive}). */
    public static synchronized Totals totals(String feature, boolean withLive) {
        Totals t = new Totals();
        List<Session> all = history(feature, null);
        Session lv = withLive ? live(feature) : null;
        if (lv != null) all.add(lv);
        for (Session s : all) {
            t.sessions++;
            t.seconds += s.seconds;
            for (var e : s.metrics.entrySet()) t.metrics.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return t;
    }

    public static final class Totals {
        public int sessions;
        public long seconds;
        public final Map<String, Double> metrics = new LinkedHashMap<>();

        public double hours() {
            return seconds / 3600.0;
        }

        public double perHour(double v) {
            return seconds <= 0 ? 0 : v / hours();
        }
    }

    // ---- file --------------------------------------------------------------------------------------------

    private static JsonObject json(Session s) {
        JsonObject o = new JsonObject();
        o.addProperty("feature", s.feature);
        o.addProperty("name", s.name);
        o.addProperty("main", s.main);
        o.addProperty("start", s.start);
        o.addProperty("startMs", s.startMs);
        o.addProperty("seconds", s.seconds);
        o.addProperty("live", s.live);
        JsonObject m = new JsonObject();
        for (var e : s.metrics.entrySet()) m.addProperty(e.getKey(), Math.round(e.getValue() * 100) / 100.0);
        o.add("metrics", m);
        o.addProperty("mainPerHour", Math.round(s.mainPerHour() * 10) / 10.0);
        return o;
    }

    private static Session fromJson(JsonObject o) {
        Session s = new Session();
        s.feature = o.get("feature").getAsString();
        s.name = o.has("name") ? o.get("name").getAsString() : s.feature;
        s.main = o.has("main") ? o.get("main").getAsString() : "";
        s.start = o.has("start") ? o.get("start").getAsString() : "";
        s.startMs = o.has("startMs") ? o.get("startMs").getAsLong() : 0;
        s.seconds = o.has("seconds") ? o.get("seconds").getAsLong() : 0;
        s.live = o.has("live") && o.get("live").getAsBoolean();
        if (o.has("metrics")) for (var e : o.getAsJsonObject("metrics").entrySet()) s.metrics.put(e.getKey(), e.getValue().getAsDouble());
        return s;
    }

    private static synchronized void load() {
        SAVED.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("sessions");
            if (arr == null) return;
            for (JsonElement e : arr) {
                Session s = fromJson(e.getAsJsonObject());
                if (s.live) { // the game ended while it ran: keep it only if it makes sense
                    s.live = false;
                    if (Rules.hard(s, history(s.feature, null)) != null) continue;
                }
                SAVED.add(s);
            }
        } catch (IOException | RuntimeException ignored) {
            // start empty
        }
    }

    private static synchronized void save() {
        lastSave = System.currentTimeMillis();
        try {
            JsonObject root = new JsonObject();
            JsonObject avgs = new JsonObject();
            Map<String, String> names = new LinkedHashMap<>();
            for (Session s : SAVED) names.putIfAbsent(s.feature, s.name);
            for (var n : names.entrySet()) {
                Totals t = totals(n.getKey(), false);
                JsonObject a = new JsonObject();
                a.addProperty("name", n.getValue());
                a.addProperty("sessions", t.sessions);
                a.addProperty("hours", Math.round(t.hours() * 100) / 100.0);
                JsonObject perHour = new JsonObject(), perSession = new JsonObject(), total = new JsonObject();
                for (var e : t.metrics.entrySet()) {
                    total.addProperty(e.getKey(), Math.round(e.getValue() * 100) / 100.0);
                    perHour.addProperty(e.getKey(), Math.round(t.perHour(e.getValue()) * 10) / 10.0);
                    perSession.addProperty(e.getKey(), t.sessions == 0 ? 0 : Math.round(e.getValue() / t.sessions * 10) / 10.0);
                }
                a.add("totals", total);
                a.add("perHour", perHour);
                a.add("perSession", perSession);
                avgs.add(n.getKey(), a);
            }
            root.add("averages", avgs);
            JsonArray arr = new JsonArray();
            for (Session s : SAVED) arr.add(json(s));
            for (Track t : TRACKS.values()) {
                t.s.seconds = (long) t.seconds;
                arr.add(json(t.s));
            }
            root.add("sessions", arr);
            root.addProperty("note", "Runs that made no sense (under 30 s, nothing counted, impossible rates) are not saved. Delete doubtful ones in the Statistics screen of /nablzz.");
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException | RuntimeException ignored) {
            // not saved
        }
    }
}
