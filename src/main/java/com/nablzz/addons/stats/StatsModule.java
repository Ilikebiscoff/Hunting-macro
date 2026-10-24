package com.nablzz.addons.stats;

import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.NablzzScreen.TableLine;
import com.nablzz.addons.Notify;
import com.nablzz.addons.Ui;
import com.nablzz.addons.planner.Fmt;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Statistics screen: every saved run of every macro (Mudworm, Miner Zombie hunting, Glowing Mushrooms), with the
 * averages. Click a run to select it, delete the ones that make no sense, or let "Remove doubtful runs" do it.
 */
public final class StatsModule implements Feature {
    private static final int ORANGE = 0xFFFB923C;
    private final Map<String, String> selected = new HashMap<>();

    @Override
    public String id() {
        return "stats";
    }

    @Override
    public String name() {
        return "Statistics";
    }

    @Override
    public boolean hasRunButton() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public String status() {
        int n = 0;
        for (Feature f : tracked()) n += SessionLog.saved(f.id()).size();
        return n + " saved runs";
    }

    @Override
    public void init() {
    }

    @Override
    public void start(Minecraft mc) {
    }

    @Override
    public void stop(Minecraft mc) {
    }

    @Override
    public void saveSettings() {
    }

    private static List<Feature> tracked() {
        List<Feature> out = new ArrayList<>();
        for (Feature f : Features.all()) if (!f.sessionCounters().isEmpty()) out.add(f);
        return out;
    }

    private static String num(double v) {
        if (Math.abs(v) >= 10_000) return Fmt.coins(v);
        return Math.abs(v - Math.rint(v)) < 0.005 ? String.format(Locale.ROOT, "%,.0f", v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String metric(String name, double v) {
        return name.contains("Coins") ? Fmt.coins(v) : num(v);
    }

    private static String shortName(Feature f) {
        return f.name().replace("Galatea ", "").replace(" Hunting", "");
    }

    // ---- lines -------------------------------------------------------------------------------------------

    private List<TableLine> overview() {
        List<TableLine> out = new ArrayList<>();
        for (Feature f : tracked()) {
            SessionLog.Totals t = SessionLog.totals(f.id(), true);
            String main = f.sessionMain();
            double mv = t.metrics.getOrDefault(main, 0.0), coins = t.metrics.getOrDefault("Coins (est.)", 0.0);
            String[] c = {shortName(f), "" + t.sessions, Fmt.hours(t.hours()), main + " " + num(mv), num(t.perHour(mv)) + "/h", coins > 0 ? Fmt.coins(t.perHour(coins)) : "-"};
            out.add(new TableLine(c, 0, "Open the " + shortName(f) + " tab to see and manage every run."));
        }
        return out;
    }

    private List<TableLine> discardedLines() {
        List<TableLine> out = new ArrayList<>();
        for (String d : SessionLog.discarded()) out.add(new TableLine(new String[]{d}, ORANGE, d));
        return out;
    }

    private List<TableLine> totalsLines(Feature f) {
        SessionLog.Totals t = SessionLog.totals(f.id(), true);
        List<Map.Entry<String, Double>> es = new ArrayList<>(t.metrics.entrySet());
        String main = f.sessionMain();
        es.sort((a, b) -> {
            int ra = a.getKey().equals(main) ? 0 : a.getKey().startsWith("Drop: ") ? 2 : 1, rb = b.getKey().equals(main) ? 0 : b.getKey().startsWith("Drop: ") ? 2 : 1;
            return ra != rb ? Integer.compare(ra, rb) : Double.compare(b.getValue(), a.getValue());
        });
        List<TableLine> out = new ArrayList<>();
        for (var e : es) {
            double per = t.sessions == 0 ? 0 : e.getValue() / t.sessions;
            String[] c = {e.getKey(), metric(e.getKey(), e.getValue()), metric(e.getKey(), t.perHour(e.getValue())), metric(e.getKey(), per)};
            out.add(new TableLine(c, e.getKey().equals(main) ? Ui.accent : 0, t.sessions + " runs, " + Fmt.hours(t.hours()) + " in total"));
        }
        return out;
    }

    private TableLine runLine(Feature f, Session s, boolean live) {
        String why = live ? null : SessionLog.check(s);
        boolean sel = s.id().equals(selected.get(f.id()));
        double coins = s.metrics.getOrDefault("Coins (est.)", 0.0);
        String[] c = {(sel ? "> " : "") + s.start + (live ? " (running)" : ""), Fmt.hours(s.hours()), num(s.mainValue()), num(s.mainPerHour()) + "/h",
                coins > 0 ? Fmt.coins(s.perHour(coins)) : "-", live ? "running" : why == null ? "ok" : why};
        int[] col = {sel ? Ui.accent : 0, 0, 0, 0, 0, live ? Ui.MUTED : why == null ? Ui.GOOD : ORANGE};
        return new TableLine(c, col, "Click to select this run" + (why != null ? "  |  " + why : ""), () -> selected.put(f.id(), s.id()));
    }

    private List<TableLine> runLines(Feature f) {
        List<TableLine> out = new ArrayList<>();
        Session lv = SessionLog.live(f.id());
        if (lv != null) out.add(runLine(f, lv, true));
        for (Session s : SessionLog.saved(f.id())) {
            if (out.size() >= 40) break;
            out.add(runLine(f, s, false));
        }
        return out;
    }

    private Session sel(Feature f) {
        String id = selected.get(f.id());
        if (id == null) return null;
        Session lv = SessionLog.live(f.id());
        if (lv != null && lv.id().equals(id)) return lv;
        for (Session s : SessionLog.saved(f.id())) if (s.id().equals(id)) return s;
        return null;
    }

    private List<TableLine> selectedLines(Feature f) {
        Session s = sel(f);
        List<TableLine> out = new ArrayList<>();
        if (s == null) return out;
        for (var e : s.metrics.entrySet()) {
            out.add(new TableLine(new String[]{e.getKey(), metric(e.getKey(), e.getValue()), metric(e.getKey(), s.perHour(e.getValue()))}, e.getKey().equals(s.main) ? Ui.accent : 0, ""));
        }
        return out;
    }

    // ---- the screen --------------------------------------------------------------------------------------

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        List<Feature> fs = tracked();
        ui.tab("Overview", "O");
        ui.section("Saved runs per macro");
        ui.table(new String[]{"Macro", "Runs", "Time", "Main stat", "Per hour", "Coins/h"}, new double[]{1.6, 0.7, 1, 1.6, 1.1, 1}, new boolean[]{false, true, true, false, true, true},
                "No macro has statistics yet.", this::overview);
        ui.note("Every Start and Stop of a macro is one run. It is saved in config/nablz_sessions.json with the averages. Runs that make no sense are not saved: "
                + "under 30 seconds, nothing counted, numbers that are invalid or more than 10x your usual rate, or longer than 24 hours. Runs that only look odd "
                + "(3x faster or 5x slower than usual) are kept but marked, so you can look at them. Open a macro's tab to see and delete runs.");
        ui.section("Not saved (looked wrong)");
        ui.table(new String[]{"What was thrown away"}, new double[]{1}, new boolean[]{false}, "Nothing was thrown away since the game started.", this::discardedLines);
        ui.section("Clean up everything");
        ui.button("Remove doubtful runs", "Deletes the runs marked as odd (much faster or slower than your usual) from every macro.", "Remove", false,
                () -> Notify.msg("Removed " + SessionLog.deleteSuspicious(null) + " doubtful run(s)."));
        ui.confirmButton("Delete every saved run", "Deletes all saved runs of all macros.", "Delete all", () -> Notify.msg("Deleted " + SessionLog.deleteAll(null) + " run(s)."));

        for (Feature f : fs) {
            ui.tab(shortName(f), shortName(f).substring(0, 1));
            ui.section("Totals and averages");
            ui.table(new String[]{"What", "Total", "Per hour", "Per run"}, new double[]{2.4, 1, 1, 1}, new boolean[]{false, true, true, true},
                    "Nothing counted yet. Start the macro and stop it again.", () -> totalsLines(f));
            ui.section("Runs - click one to select it");
            ui.table(new String[]{"Started", "Time", f.sessionMain(), "Per hour", "Coins/h", "Check"}, new double[]{1.8, 0.9, 1, 1, 0.9, 2.6}, new boolean[]{false, true, true, true, true, false},
                    "No saved runs yet. A run is saved when you stop the macro.", () -> runLines(f));
            ui.section("Selected run");
            ui.info("Run", "The run you clicked in the list above.", () -> {
                Session s = sel(f);
                return s == null ? "none selected" : s.start + ", " + Fmt.hours(s.hours());
            }, Ui.TEXT);
            ui.table(new String[]{"What", "Value", "Per hour"}, new double[]{2.4, 1, 1}, new boolean[]{false, true, true}, "Click a run above to see what it counted.", () -> selectedLines(f));
            ui.button("Delete selected run", "Deletes the run you selected (not one that is still running).", "Delete", false, () -> {
                Session s = sel(f);
                if (s == null) {
                    Notify.msg("Select a run first (click it in the list).");
                } else if (s.live) {
                    Notify.msg("That run is still going. Stop the macro first.");
                } else {
                    SessionLog.delete(s.id());
                    selected.remove(f.id());
                    Notify.msg("Run deleted.");
                }
            });
            ui.section("Clean up");
            ui.button("Remove doubtful runs", "Deletes this macro's runs marked as odd (much faster or slower than your usual).", "Remove", false,
                    () -> Notify.msg("Removed " + SessionLog.deleteSuspicious(f.id()) + " doubtful run(s)."));
            ui.confirmButton("Delete all runs", "Deletes every saved run of this macro.", "Delete all", () -> {
                selected.remove(f.id());
                Notify.msg("Deleted " + SessionLog.deleteAll(f.id()) + " run(s).");
            });
        }
    }
}
