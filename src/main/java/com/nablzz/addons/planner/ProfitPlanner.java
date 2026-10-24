package com.nablzz.addons.planner;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nablzz.addons.Feature;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.NablzzScreen.TableLine;
import com.nablzz.addons.Ui;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Profit planner: for the shards you farm (Mudworm, Miner Zombie) and for Glowing Mushrooms it ranks every way of turning
 * them into coins (sell raw, every Fusion Machine recipe, the Moby routes), once for instant selling and once for sell
 * offers, with prices straight from the Bazaar API (refreshed every 5 minutes). Sell offers get a warning when the market
 * cannot absorb what you farm. A Bazaar browser (search, flips) is included.
 */
public final class ProfitPlanner implements Feature {
    private static final String[] SRC_ID = {"SHARD_MUDWORM", "SHARD_MINER_ZOMBIE", "GLOWING_MUSHROOM"};
    private static final String[] SRC_NAME = {"Mudworm", "Miner Zombie", "Glowing Mushroom"};
    private static final String[] MODE_NAME = {"Insta-sell", "Sell offer"};
    private static final Calc.Mode[] MODES = {Calc.Mode.INSTA, Calc.Mode.OFFER};
    private static final int GOOD = Ui.GOOD, BAD = Ui.BAD, WARN = Ui.WARN, ORANGE = 0xFFFB923C;

    private final Settings s = new Settings();
    private Map<String, List<Calc.Fusion>> fusions = Map.of();

    /** The evaluated options for one farmed item in one selling mode. */
    private static final class Result {
        Calc.Method raw, best;
        List<Calc.Method> ranked = List.of();
        List<TableLine> lines = List.of();
        String sentence = "-";
    }

    private final Result[][] res = new Result[3][2];
    private long cacheSig = Long.MIN_VALUE;
    private int cacheVersion = -1;

    // Bazaar browser
    private String search = "";
    private int sort;
    private String bazaarKey = "";
    private List<TableLine> bazaarLines = List.of();
    private int bazaarCount;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablz_planner.properties");
    }

    // ---- Feature -------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "planner";
    }

    @Override
    public String name() {
        return "Profit Planner";
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
        Parser.Snapshot sn = Bazaar.snapshot();
        return sn == null ? "no prices yet" : "prices " + Fmt.age(System.currentTimeMillis() - sn.fetchedAt()) + " old";
    }

    @Override
    public void start(Minecraft mc) {
    }

    @Override
    public void stop(Minecraft mc) {
    }

    @Override
    public void init() {
        load();
        try (InputStream in = ProfitPlanner.class.getResourceAsStream("/assets/nablzzaddons/planner/fusions.json")) {
            if (in != null) fusions = Parser.loadFusions(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // the routes of the mushroom still work
        }
        Bazaar.start(FabricLoader.getInstance().getConfigDir().resolve("nablz_bazaar_cache.json"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("profit").executes(c -> {
                    NablzzAddons.openGui(id());
                    return 1;
                })));
    }

    @Override
    public void report(Consumer<String> out) {
        Parser.Snapshot sn = Bazaar.snapshot();
        out.accept("bazaar: " + Bazaar.message() + (Bazaar.fromCache() ? " (copy from disk)" : "") + ", items " + (sn == null ? 0 : sn.items().size())
                + ", mixin price " + (long) ahPrice() + ", fusion rows " + fusions.values().stream().mapToInt(List::size).sum());
        out.accept("settings: buy " + (s.instaBuy ? "insta" : "order") + ", tax " + s.tax + ", min volume " + s.minWeeklyVolume + ", max cost " + s.maxCost
                + ", max spread " + s.maxSpread + ", rates " + s.mudwormPerHour + "/" + s.minerZombiePerHour + "/" + s.mushroomPerHour);
    }

    @Override
    public void saveSettings() {
        save();
    }

    // ---- computing -----------------------------------------------------------------------------------------

    private double ahPrice() {
        return s.mixinOverride > 0 ? s.mixinOverride : Bazaar.mixinPrice();
    }

    private int rate(int src) {
        return src == 0 ? s.mudwormPerHour : src == 1 ? s.minerZombiePerHour : s.mushroomPerHour;
    }

    private void ensure() {
        Parser.Snapshot sn = Bazaar.snapshot();
        int ver = Bazaar.version();
        long sig = s.signature();
        if (sn == null) {
            for (Result[] r : res) java.util.Arrays.fill(r, null);
            cacheVersion = -1;
            return;
        }
        if (ver == cacheVersion && sig == cacheSig) return;
        cacheVersion = ver;
        cacheSig = sig;
        for (int src = 0; src < 3; src++) {
            for (int m = 0; m < 2; m++) res[src][m] = compute(sn.items(), src, m);
        }
    }

    private Result compute(Map<String, Item> prices, int src, int mi) {
        Calc.Mode mode = MODES[mi];
        String id = SRC_ID[src], name = SRC_NAME[src];
        double rate = rate(src);
        List<Calc.Method> all = src < 2
                ? Calc.fusions(fusions.getOrDefault(id, List.of()), id, name, prices, mode, s, rate)
                : Calc.routes(Routes.MUSHROOM, id, prices, mode, s, rate, ahPrice());
        Result r = new Result();
        r.raw = Calc.raw(id, name, prices, mode, s, rate);
        r.ranked = Calc.rank(all, s.showFlagged);
        r.best = r.raw;
        if (!r.ranked.isEmpty() && r.ranked.get(0).ok() && r.ranked.get(0).extra > 0 && r.raw.ok()) r.best = r.ranked.get(0);
        if (!r.raw.ok() && !r.ranked.isEmpty() && r.ranked.get(0).ok()) r.best = r.ranked.get(0);

        List<TableLine> lines = new ArrayList<>();
        lines.add(line(0, r.raw, mode, r.best == r.raw));
        int n = Math.min(s.topN, r.ranked.size());
        for (int i = 0; i < n; i++) lines.add(line(i + 1, r.ranked.get(i), mode, r.ranked.get(i) == r.best));
        r.lines = lines;
        r.sentence = r.raw.ok()
                ? "raw " + Fmt.coins(r.raw.value) + " per " + name.toLowerCase(Locale.ROOT) + (r.best == r.raw ? " - selling raw is the best right now" : "  ->  best " + Fmt.coins(r.best.value) + " (" + Fmt.signed(r.best.extra) + " each)")
                : "not on the Bazaar right now";
        return r;
    }

    private String warn(Calc.Method m, Calc.Mode mode) {
        if (!m.ok()) return m.status;
        if (mode == Calc.Mode.OFFER) {
            if (m.etaHours > s.verySlowHours) return "VERY SLOW to sell";
            if (m.etaHours > s.slowHours) return "slow to sell";
        } else if (m.slip > 0.08) {
            return "thin: -" + Fmt.pct(m.slip) + " if sold at once";
        }
        return "";
    }

    private int warnColor(Calc.Method m, Calc.Mode mode) {
        if (!m.ok()) return Ui.DIM;
        if (mode == Calc.Mode.OFFER) return m.etaHours > s.verySlowHours ? BAD : m.etaHours > s.slowHours ? ORANGE : 0;
        return m.slip > 0.2 ? BAD : m.slip > 0.08 ? ORANGE : 0;
    }

    private TableLine line(int rank, Calc.Method m, Calc.Mode mode, boolean best) {
        boolean raw = m.kind.equals("Sell raw");
        String[] c = {
                raw ? "-" : "" + rank,
                raw ? "Sell raw" : m.desc,
                m.inputCost > 0 ? Fmt.coins(m.inputCost) : "-",
                Fmt.coins(m.value),
                raw ? "-" : Fmt.signed(m.extra),
                Fmt.coins(m.perHour),
                mode == Calc.Mode.OFFER ? Fmt.hours(m.etaHours) : Fmt.volume(m.vol),
                warn(m, mode)};
        int[] col = new int[c.length];
        col[3] = best ? GOOD : 0;
        col[4] = raw ? 0 : m.extra > 0 ? GOOD : BAD;
        int wc = warnColor(m, mode);
        col[6] = mode == Calc.Mode.OFFER ? wc : 0;
        col[7] = wc;
        if (!m.ok()) java.util.Arrays.fill(col, Ui.DIM);
        String tip = raw ? "Sell the farmed item as it is. " + String.format(Locale.ROOT, "Net %s each after %.2f%% tax.", Fmt.coins(m.value), s.tax * 100)
                : m.desc + "  |  cost " + Fmt.coins(m.inputCost) + ", output " + Fmt.coins(m.outUnit) + " each, net " + Fmt.coins(m.net)
                + ", ROI " + Fmt.pct(m.roi) + ", volume " + Fmt.volume(m.vol) + "/week, spread " + Fmt.pct(m.spread);
        if (mode == Calc.Mode.OFFER && !Double.isNaN(m.etaHours)) tip += String.format(Locale.ROOT, "  |  you make %.0f/h, buyers take about %.0f/h", m.outPerHour, m.outPerHour / Math.max(1e-9, m.etaHours));
        return new TableLine(c, col, tip);
    }

    // ---- the screen ----------------------------------------------------------------------------------------

    private static final String[] RANK_HEAD = {"#", "Method", "Cost", "Value each", "vs raw", "Coins/h", "Volume/wk", "Note"};
    private static final String[] RANK_HEAD_OFFER = {"#", "Method", "Cost", "Value each", "vs raw", "Coins/h", "Sells in*", "Note"};
    private static final double[] RANK_W = {0.35, 3.6, 0.9, 1.0, 0.9, 1.0, 1.0, 1.9};
    private static final boolean[] RANK_R = {false, false, true, true, true, true, true, false};

    private List<TableLine> rankLines(int src, int mode) {
        ensure();
        Result r = res[src][mode];
        return r == null ? List.of() : r.lines;
    }

    private String sentence(int src, int mode) {
        ensure();
        Result r = res[src][mode];
        return r == null ? "waiting for prices ..." : r.sentence;
    }

    private List<TableLine> overviewLines() {
        ensure();
        List<TableLine> out = new ArrayList<>();
        for (int src = 0; src < 3; src++) {
            for (int mi = 0; mi < 2; mi++) {
                Result r = res[src][mi];
                if (r == null) continue;
                Calc.Method b = r.best;
                boolean raw = b == r.raw;
                String verdict = raw ? "Sell raw" : (b.kind.equals("Moby") ? "Use this route" : "Fuse") + String.format(Locale.ROOT, " (+%.0f%%)", r.raw.value > 0 ? b.extra / r.raw.value * 100 : 0);
                String[] c = {SRC_NAME[src], MODE_NAME[mi], raw ? "Sell raw" : b.desc, Fmt.coins(b.value), Fmt.coins(b.perHour),
                        mi == 1 ? Fmt.hours(b.etaHours) : Fmt.volume(b.vol), verdict};
                int[] col = {0, mi == 0 ? 0xFF60A5FA : 0xFFA78BFA, 0, GOOD, GOOD, mi == 1 ? warnColor(b, MODES[mi]) : 0, raw ? Ui.MUTED : GOOD};
                out.add(new TableLine(c, col, raw ? "Selling " + SRC_NAME[src] + " as it is beats every other option right now." : b.desc));
            }
        }
        return out;
    }

    private List<TableLine> warningLines() {
        ensure();
        List<TableLine> out = new ArrayList<>();
        for (int src = 0; src < 3; src++) {
            for (int mi = 0; mi < 2; mi++) {
                Result r = res[src][mi];
                if (r == null) continue;
                List<Calc.Method> check = new ArrayList<>();
                check.add(r.raw);
                if (r.best != r.raw) check.add(r.best);
                for (Calc.Method m : check) {
                    String w = warn(m, MODES[mi]);
                    if (w.isEmpty() || !m.ok()) continue;
                    String what = m.kind.equals("Sell raw") ? "selling " + SRC_NAME[src] + " raw" : m.outName;
                    String text;
                    if (mi == 1) {
                        double flow = m.outPerHour / Math.max(1e-9, m.etaHours);
                        text = String.format(Locale.ROOT, "%s as a sell offer: you make about %.0f/h but buyers only take about %.0f/h - one hour of farming needs about %s to sell%s",
                                what, m.outPerHour, flow, Fmt.hours(m.etaHours), m.etaHours > s.verySlowHours ? ". It may take days: insta-sell it instead." : ". Expect to wait.");
                    } else {
                        text = String.format(Locale.ROOT, "%s insta-sold in one go: the buy orders are thin, the average price falls about %s", what, Fmt.pct(m.slip));
                    }
                    out.add(new TableLine(new String[]{SRC_NAME[src] + " / " + MODE_NAME[mi], text}, new int[]{0, warnColor(m, MODES[mi])}, text));
                }
            }
        }
        return out;
    }

    private String updatedText() {
        Parser.Snapshot sn = Bazaar.snapshot();
        if (sn == null) return "no prices yet";
        String t = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(sn.apiUpdated()).atZone(ZoneId.systemDefault()));
        return t + "  (" + Fmt.age(System.currentTimeMillis() - sn.apiUpdated()) + " ago)" + (Bazaar.fromCache() ? "  - old copy" : "");
    }

    private String nextText() {
        long left = Bazaar.nextRefreshAt() - System.currentTimeMillis();
        return left <= 0 ? "now" : "in " + Fmt.age(left);
    }

    private List<TableLine> bazaarLines() {
        Parser.Snapshot sn = Bazaar.snapshot();
        if (sn == null) return List.of();
        String key = Bazaar.version() + "|" + s.signature() + "|" + search + "|" + sort;
        if (key.equals(bazaarKey)) return bazaarLines;
        bazaarKey = key;
        String q = search.trim().toLowerCase(Locale.ROOT);
        record Row(Item it, String name, double flip, double margin) {}
        List<Row> rows = new ArrayList<>();
        for (Item it : sn.items().values()) {
            if (it.weeklyVol() < s.minWeeklyVolume && q.isEmpty()) continue;
            String name = Bazaar.nameOf(it.id);
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !it.id.toLowerCase(Locale.ROOT).contains(q)) continue;
            double flip = it.buyOrder > 0 && it.sellOffer > 0 ? it.sellOffer * (1 - s.tax) - it.buyOrder : Double.NEGATIVE_INFINITY;
            double margin = it.buyOrder > 0 && flip != Double.NEGATIVE_INFINITY ? flip / it.buyOrder : Double.NEGATIVE_INFINITY;
            rows.add(new Row(it, name, flip, margin));
        }
        Comparator<Row> cmp = switch (sort) {
            case 1 -> Comparator.comparingDouble((Row r) -> r.margin).reversed();
            case 2 -> Comparator.comparingLong((Row r) -> r.it.weeklyVol()).reversed();
            case 3 -> Comparator.comparingDouble((Row r) -> r.it.buyOrder).reversed();
            case 4 -> Comparator.comparing((Row r) -> r.name);
            default -> Comparator.comparingDouble((Row r) -> r.flip).reversed();
        };
        rows.sort(cmp);
        bazaarCount = rows.size();
        List<TableLine> out = new ArrayList<>();
        for (int i = 0; i < Math.min(40, rows.size()); i++) {
            Row r = rows.get(i);
            Item it = r.it;
            boolean flipOk = r.flip != Double.NEGATIVE_INFINITY;
            String[] c = {r.name, it.buyOrder > 0 ? Fmt.coins(it.buyOrder) : "-", it.sellOffer > 0 ? Fmt.coins(it.sellOffer) : "-", Fmt.pct(it.spread()),
                    flipOk ? Fmt.signed(r.flip) : "-", flipOk ? Fmt.pct(r.margin) : "-", Fmt.volume(it.weeklyVol())};
            int[] col = {0, 0, 0, it.spread() > s.maxSpread ? ORANGE : 0, flipOk ? (r.flip > 0 ? GOOD : BAD) : Ui.DIM, flipOk ? (r.margin > 0 ? GOOD : BAD) : Ui.DIM, 0};
            String tip = it.id + "  |  buy order " + Fmt.coins(it.buyOrder) + ", sell offer " + Fmt.coins(it.sellOffer) + "  |  " + Fmt.volume(it.sellWeek) + " sold and "
                    + Fmt.volume(it.buyWeek) + " bought per week  |  a flip = buy with an order, sell with an offer";
            out.add(new TableLine(c, col, tip));
        }
        bazaarLines = out;
        return out;
    }

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        // ---- overview
        ui.tab("Overview", "O");
        ui.section("Prices");
        ui.info("Source", "Prices come from the public Hypixel Bazaar API (no key, no typing).", () -> "api.hypixel.net / bazaar", Ui.TEXT);
        ui.info("Status", "What the last request did.", Bazaar::message, Ui.MUTED);
        ui.info("Bazaar updated", "When the API says the prices were last updated.", this::updatedText, Ui.TEXT);
        ui.info("Next refresh", "The prices refresh by themselves every 5 minutes.", this::nextText, Ui.TEXT);
        ui.button("Refresh now", "Fetches the prices right away.", "Refresh", true, Bazaar::refreshNow);
        ui.section("Best method right now");
        ui.table(new String[]{"Item", "Sell as", "Best method", "Value each", "Coins/h", "Sells in*", "Verdict"},
                new double[]{1.1, 0.9, 3.6, 0.9, 0.9, 0.9, 1.3}, new boolean[]{false, false, false, true, true, true, false},
                "Waiting for the first prices ...", this::overviewLines);
        ui.note("Value each = coins per farmed item after the Bazaar tax and the cost of inputs. Coins/h uses your farming rate (Settings). "
                + "* Sells in = how long a sell offer needs to sell one hour of farmed output (insta-sell is immediate). Hover a line for the details.");
        ui.section("Warnings");
        ui.table(new String[]{"Where", "Warning"}, new double[]{1.4, 6}, new boolean[]{false, false},
                "No warnings: the sell offers should fill in a reasonable time and the buy orders are deep enough.", this::warningLines);

        // ---- insta-sell and sell offer
        for (int mi = 0; mi < 2; mi++) {
            final int mode = mi;
            ui.tab(MODE_NAME[mi], mi == 0 ? "I" : "S");
            for (int src = 0; src < 3; src++) {
                final int sr = src;
                ui.section(SRC_NAME[src] + (src < 2 ? " shards" : ""));
                ui.info("Summary", "Sell raw or the best method right now.", () -> sentence(sr, mode), Ui.TEXT);
                ui.table(mi == 0 ? RANK_HEAD : RANK_HEAD_OFFER, RANK_W, RANK_R, "Waiting for prices, or nothing passes your filters.", () -> rankLines(sr, mode));
            }
            ui.note(mi == 0
                    ? "Insta-sell: you sell straight into the highest buy order. You get coins immediately, at the lower price. Volume/wk is the smaller side of the weekly market; \"thin\" means the buy orders are too shallow for one hour of output."
                    : "Sell offer: you list at the lowest sell offer and wait for a buyer. Better price, but it can take a long time. \"Sells in\" compares what you make per hour with what buyers take per hour - orange is slow, red may take days (change the limits in Settings).");
        }

        // ---- bazaar browser
        ui.tab("Bazaar", "B");
        ui.section("Search");
        ui.text("Search", "Part of an item name or id. Empty = the best flips with enough weekly volume.", () -> search, v -> search = v, 180, "e.g. enchanted diamond");
        ui.choice("Sort by", "How the list is ordered.", new String[]{"Flip profit", "Flip margin", "Weekly volume", "Instant-sell price", "Name"}, () -> sort, i -> sort = i);
        ui.info("Items found", "How many items match (the top 40 are shown).", () -> {
            bazaarLines();
            return "" + bazaarCount;
        }, Ui.TEXT);
        ui.section("Items");
        ui.table(new String[]{"Item", "Insta-sell", "Sell offer", "Spread", "Flip", "Margin", "Volume/wk"}, new double[]{3, 1, 1, 0.8, 1, 0.8, 1},
                new boolean[]{false, true, true, true, true, true, true}, "No items match (or no prices yet).", this::bazaarLines);
        ui.note("Flip = buy with a buy order at the top price, sell with a sell offer at the lowest offer, minus the Bazaar tax. A big margin on a small volume rarely works.");

        // ---- settings
        ui.tab("Settings", "T");
        ui.section("Prices and costs");
        ui.choice("Buy inputs by", "How partner shards and ingredients are bought: a buy order (cheaper, slower) or instantly (the lowest sell offer).",
                new String[]{"Buy order", "Insta-buy"}, () -> s.instaBuy ? 1 : 0, i -> s.instaBuy = i == 1);
        ui.dslider("Bazaar sell tax", "1.25 % by default; lower with the Bazaar Flipper levels (1.125 % / 1 %).", 0.5, 1.25, 0.125, () -> s.tax * 100, v -> s.tax = v / 100, "%.3f %%");
        ui.dslider("Output multiplier", "1 = base fusion output. Raise it if you have perks that add output.", 1, 3, 0.05, () -> s.outputMult, v -> s.outputMult = v, "x%.2f");
        ui.dslider("Glowing Mush Mixin price", "Auction House price of the Mixin. 0 = take the lowest BIN automatically.", 0, 200_000_000, 1_000_000, () -> s.mixinOverride, v -> s.mixinOverride = (long) v, "%.0f");
        ui.info("Mixin price in use", "The price the Mixin routes use.", () -> ahPrice() > 0 ? Fmt.coins(ahPrice()) + (s.mixinOverride > 0 ? " (yours)" : " (auto)") : "none found - the Mixin routes are skipped", Ui.TEXT);
        ui.section("What you farm");
        ui.islider("Mudworm shards per hour", "Used for Coins/h and for the sell-offer warning.", 10, 3000, () -> s.mudwormPerHour, v -> s.mudwormPerHour = v, " /h");
        ui.islider("Miner Zombie shards per hour", "Used for Coins/h and for the sell-offer warning.", 10, 3000, () -> s.minerZombiePerHour, v -> s.minerZombiePerHour = v, " /h");
        ui.islider("Glowing Mushrooms per hour", "Used for Coins/h and for the sell-offer warning.", 10, 5000, () -> s.mushroomPerHour, v -> s.mushroomPerHour = v, " /h");
        ui.section("Filters and warnings");
        ui.islider("Minimum weekly volume", "Outputs that trade less than this per week (smaller side) are left out as Low liquidity.", 0, 100_000, () -> s.minWeeklyVolume, v -> s.minWeeklyVolume = v, "");
        ui.dslider("Maximum spread", "Outputs whose instant-sell price is more than this below the sell offer are a thin market (Wide spread).", 0.1, 1.0, 0.05, () -> s.maxSpread * 100, v -> s.maxSpread = v / 100, "%.0f %%");
        ui.dslider("Maximum input cost", "Largest cost of bought inputs for one fusion or route (your capital limit).", 100_000, 50_000_000, 100_000, () -> s.maxCost, v -> s.maxCost = (long) v, "%.0f");
        ui.dslider("Sell offer is slow after", "Hours needed to sell one hour of farmed output: above this the line turns orange.", 0.5, 12, 0.5, () -> s.slowHours, v -> s.slowHours = v, "%.1f h");
        ui.dslider("Sell offer is very slow after", "Above this the line turns red: it may take days, insta-sell instead.", 2, 72, 1, () -> s.verySlowHours, v -> s.verySlowHours = v, "%.0f h");
        ui.islider("Rows per table", "How many ranked options are listed per item.", 3, 30, () -> s.topN, v -> s.topN = v, "");
        ui.toggle("Show filtered options", "Also list options that were left out (low volume, wide spread, over budget ...), dimmed at the end.", () -> s.showFlagged, v -> s.showFlagged = v);
        ui.note("Prices: Hypixel Bazaar API. The Mixin price comes from the lowest BIN list when available. Fusion recipes and Moby routes are from the Hypixel Profit Planner spreadsheet; new shards after a game update need new rows.");
    }

    // ---- persistence ---------------------------------------------------------------------------------------

    private void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            s.instaBuy = Boolean.parseBoolean(p.getProperty("instaBuy", "" + s.instaBuy));
            s.tax = Double.parseDouble(p.getProperty("tax", "" + s.tax));
            s.outputMult = Double.parseDouble(p.getProperty("outputMult", "" + s.outputMult));
            s.minWeeklyVolume = Integer.parseInt(p.getProperty("minWeeklyVolume", "" + s.minWeeklyVolume));
            s.maxCost = Long.parseLong(p.getProperty("maxCost", "" + s.maxCost));
            s.maxSpread = Double.parseDouble(p.getProperty("maxSpread", "" + s.maxSpread));
            s.topN = Integer.parseInt(p.getProperty("topN", "" + s.topN));
            s.showFlagged = Boolean.parseBoolean(p.getProperty("showFlagged", "" + s.showFlagged));
            s.mudwormPerHour = Integer.parseInt(p.getProperty("mudwormPerHour", "" + s.mudwormPerHour));
            s.minerZombiePerHour = Integer.parseInt(p.getProperty("minerZombiePerHour", "" + s.minerZombiePerHour));
            s.mushroomPerHour = Integer.parseInt(p.getProperty("mushroomPerHour", "" + s.mushroomPerHour));
            s.mixinOverride = Long.parseLong(p.getProperty("mixinOverride", "" + s.mixinOverride));
            s.slowHours = Double.parseDouble(p.getProperty("slowHours", "" + s.slowHours));
            s.verySlowHours = Double.parseDouble(p.getProperty("verySlowHours", "" + s.verySlowHours));
        } catch (IOException | RuntimeException ignored) {
            // keep defaults
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("instaBuy", "" + s.instaBuy);
        p.setProperty("tax", "" + s.tax);
        p.setProperty("outputMult", "" + s.outputMult);
        p.setProperty("minWeeklyVolume", "" + s.minWeeklyVolume);
        p.setProperty("maxCost", "" + s.maxCost);
        p.setProperty("maxSpread", "" + s.maxSpread);
        p.setProperty("topN", "" + s.topN);
        p.setProperty("showFlagged", "" + s.showFlagged);
        p.setProperty("mudwormPerHour", "" + s.mudwormPerHour);
        p.setProperty("minerZombiePerHour", "" + s.minerZombiePerHour);
        p.setProperty("mushroomPerHour", "" + s.mushroomPerHour);
        p.setProperty("mixinOverride", "" + s.mixinOverride);
        p.setProperty("slowHours", "" + s.slowHours);
        p.setProperty("verySlowHours", "" + s.verySlowHours);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons: profit planner");
        } catch (IOException ignored) {
            // not saved
        }
    }
}
