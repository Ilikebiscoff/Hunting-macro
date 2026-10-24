package com.nablzz.addons.flipper;

import com.nablzz.addons.Feature;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.NablzzScreen.TableLine;
import com.nablzz.addons.Ui;
import com.nablzz.addons.planner.Bazaar;
import com.nablzz.addons.planner.Details;
import com.nablzz.addons.planner.Fmt;
import com.nablzz.addons.planner.History;
import com.nablzz.addons.planner.Item;
import com.nablzz.addons.planner.ItemView;
import com.nablzz.addons.planner.Parser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Bazaar Flipper: finds the most profitable things to craft and sell (every recipe, with live Bazaar prices), plain
 * buy-order / sell-offer flips, an item page for every item (graphs, how to get it, what it is used in) and favourites.
 * A macro that carries a plan out can be added on top later: the ranked {@link Craft.Result}s are the plan.
 */
public final class FlipperModule implements Feature {
    private static final int GOOD = Ui.GOOD, BAD = Ui.BAD, ORANGE = 0xFFFB923C;
    private static final long[] RANGE_MS = {3_600_000L, 6 * 3_600_000L, 24 * 3_600_000L, 72 * 3_600_000L};

    private final Opts o = new Opts();
    private boolean showFlagged, favOnly;
    private String selected;
    private int craftSort, flipSort, chartRange = 2;

    // caches
    private String craftKey = "";
    private List<Craft.Result> craftAll = List.of();
    private final Map<String, Craft.Result> bestByOut = new HashMap<>();
    private String craftLinesKey = "", flipKey = "", favKey = "";
    private List<TableLine> craftLines = List.of(), flipLines = List.of(), favLines = List.of();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nablz_flipper.properties");
    }

    // ---- Feature -------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "flipper";
    }

    @Override
    public String name() {
        return "Bazaar Flipper";
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
        return Recipes.count() + " recipes";
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
        Favorites.load();
        RecipeSource.start(FabricLoader.getInstance().getConfigDir().resolve("nablz_recipes.json"));
    }

    @Override
    public void saveSettings() {
        save();
    }

    @Override
    public void report(java.util.function.Consumer<String> out) {
        out.accept("recipes: " + Recipes.count() + " (" + RecipeSource.message() + "), favourites " + Favorites.list().size()
                + ", buy " + (o.buyInsta ? "insta" : "order") + ", sell " + (o.sellInsta ? "insta" : "offer") + ", craft ingredients " + o.craftIngredients);
    }

    // ---- data ----------------------------------------------------------------------------------------------

    private Map<String, Item> prices() {
        Parser.Snapshot sn = Bazaar.snapshot();
        return sn == null ? Map.of() : sn.items();
    }

    private void ensure() {
        String key = Bazaar.version() + "|" + RecipeSource.version() + "|" + o.signature();
        if (key.equals(craftKey)) return;
        craftKey = key;
        Map<String, Item> p = prices();
        craftAll = p.isEmpty() ? List.of() : Craft.evalAll(p, o);
        bestByOut.clear();
        for (Craft.Result r : craftAll) {
            if (!r.ok()) continue;
            Craft.Result old = bestByOut.get(r.recipe.out());
            if (old == null || r.profit > old.profit) bestByOut.put(r.recipe.out(), r);
        }
    }

    private static String name(String id) {
        return Bazaar.nameOf(id);
    }

    private static String ingredients(Recipe r) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var e : r.in().entrySet()) {
            if (n++ > 0) sb.append(", ");
            if (n > 3) {
                sb.append("...");
                break;
            }
            sb.append(name(e.getKey())).append(" x").append(Fmt.coins(e.getValue()));
        }
        return sb.toString();
    }

    private int etaColor(double eta) {
        if (Double.isNaN(eta) || o.sellInsta) return 0;
        return eta > o.veryslowHours ? BAD : eta > o.slowHours ? ORANGE : 0;
    }

    private void open(String id) {
        selected = id;
        History.watch(id);
    }

    // ---- lines ---------------------------------------------------------------------------------------------

    private TableLine craftLine(Craft.Result r) {
        String id = r.recipe.out();
        String note = !r.ok() ? r.status : !r.crafted.isEmpty() ? "craft " + name(r.crafted.get(0)) + " yourself" : r.recipe.note();
        String[] c = {Favorites.has(id) ? "*" : "", name(id) + (r.recipe.outCount() > 1 ? " x" + r.recipe.outCount() : ""), Fmt.coins(r.cost), Fmt.coins(r.outUnit),
                Fmt.signed(r.profit), Fmt.pct(r.margin), Fmt.coins(r.perHour), o.sellInsta ? "-" : Fmt.hours(r.etaHours), note};
        int[] col = {Ui.WARN, 0, 0, 0, r.profit > 0 ? GOOD : BAD, r.margin > 0 ? GOOD : BAD, 0, etaColor(r.etaHours), r.ok() ? Ui.MUTED : ORANGE};
        if (!r.ok()) java.util.Arrays.fill(col, 1, 8, Ui.DIM);
        String tip = ingredients(r.recipe) + "  |  cost " + Fmt.coins(r.cost) + " -> " + Fmt.coins(r.revenue) + " after tax  |  " + Fmt.volume(r.outVolume) + " per week, "
                + String.format(Locale.ROOT, "%.0f", r.craftsPerHour) + " crafts/h possible" + (r.thinnest.isEmpty() ? "" : "  |  thinnest ingredient: " + name(r.thinnest) + " (" + Fmt.volume(r.minIngVolume) + "/wk)")
                + (r.recipe.note().isEmpty() ? "" : "  |  " + r.recipe.note()) + "  |  click for the item page";
        return new TableLine(c, col, tip, () -> open(id));
    }

    private List<TableLine> craftLines() {
        ensure();
        String key = craftKey + "|" + craftSort + "|" + showFlagged + "|" + favOnly + "|" + Favorites.version();
        if (key.equals(craftLinesKey)) return craftLines;
        craftLinesKey = key;
        List<Craft.Result> src = craftAll;
        if (favOnly) {
            List<Craft.Result> f = new ArrayList<>();
            for (Craft.Result r : craftAll) if (Favorites.has(r.recipe.out())) f.add(r);
            src = f;
        }
        List<TableLine> out = new ArrayList<>();
        for (Craft.Result r : Craft.best(src, craftSort, showFlagged)) {
            if (out.size() >= o.topN) break;
            out.add(craftLine(r));
        }
        craftLines = out;
        return out;
    }

    private List<TableLine> flipLines() {
        Map<String, Item> p = prices();
        String key = Bazaar.version() + "|" + o.signature() + "|" + flipSort + "|" + favOnly + "|" + Favorites.version();
        if (key.equals(flipKey)) return flipLines;
        flipKey = key;
        record Row(Item it, double profit, double margin, double perHour) {}
        List<Row> rows = new ArrayList<>();
        for (Item it : p.values()) {
            if (it.buyOrder <= 0 || it.sellOffer <= 0 || it.weeklyVol() < o.minVolume || it.spread() > o.maxSpread) continue;
            if (favOnly && !Favorites.has(it.id)) continue;
            double profit = it.sellOffer * (1 - o.tax) - it.buyOrder, margin = profit / it.buyOrder;
            if (profit < o.minProfit || margin < o.minMargin) continue;
            double flow = it.weeklyVol() / 168.0;
            rows.add(new Row(it, profit, margin, profit * Math.min(flow, o.flipUnitsPerHour)));
        }
        Comparator<Row> cmp = switch (flipSort) {
            case 1 -> Comparator.comparingDouble((Row r) -> r.margin).reversed();
            case 2 -> Comparator.comparingDouble((Row r) -> r.perHour).reversed();
            case 3 -> Comparator.comparingLong((Row r) -> r.it.weeklyVol()).reversed();
            default -> Comparator.comparingDouble((Row r) -> r.profit).reversed();
        };
        rows.sort(cmp);
        List<TableLine> out = new ArrayList<>();
        for (int i = 0; i < Math.min(o.topN, rows.size()); i++) {
            Row r = rows.get(i);
            Item it = r.it;
            double eta = o.flipUnitsPerHour / Math.max(1e-9, it.buyWeek / 168.0);
            String[] c = {Favorites.has(it.id) ? "*" : "", name(it.id), Fmt.coins(it.buyOrder), Fmt.coins(it.sellOffer), Fmt.signed(r.profit), Fmt.pct(r.margin),
                    Fmt.volume(it.weeklyVol()), Fmt.coins(r.perHour), it.spread() > 0.35 ? "wide spread" : ""};
            int[] col = {Ui.WARN, 0, 0, 0, GOOD, GOOD, 0, 0, ORANGE};
            String tip = "Buy with an order at " + Fmt.coins(it.buyOrder) + ", sell with an offer at " + Fmt.coins(it.sellOffer) + "  |  " + Fmt.volume(it.sellWeek) + " sold and "
                    + Fmt.volume(it.buyWeek) + " bought per week  |  click for the item page";
            final String id = it.id;
            out.add(new TableLine(c, col, tip, () -> open(id)));
        }
        flipLines = out;
        return out;
    }

    private List<TableLine> favLines() {
        ensure();
        Map<String, Item> p = prices();
        String key = craftKey + "|" + Favorites.version() + "|" + selected;
        if (key.equals(favKey)) return favLines;
        favKey = key;
        List<TableLine> out = new ArrayList<>();
        for (String id : Favorites.list()) {
            Item it = p.get(id);
            Craft.Result cr = bestByOut.get(id);
            double flip = it != null && it.buyOrder > 0 && it.sellOffer > 0 ? it.sellOffer * (1 - o.tax) - it.buyOrder : Double.NaN;
            String[] c = {name(id), it == null ? "-" : Fmt.coins(it.buyOrder), it == null ? "-" : Fmt.coins(it.sellOffer), Double.isNaN(flip) ? "-" : Fmt.signed(flip),
                    cr == null ? "-" : Fmt.signed(cr.profit), it == null ? "-" : Fmt.volume(it.weeklyVol())};
            int[] col = {0, 0, 0, Double.isNaN(flip) ? Ui.DIM : flip > 0 ? GOOD : BAD, cr == null ? Ui.DIM : cr.profit > 0 ? GOOD : BAD, 0};
            out.add(new TableLine(c, col, id + "  |  click for the item page", () -> open(id)));
        }
        favLines = out;
        return out;
    }

    private Item sel() {
        return selected == null ? null : prices().get(selected);
    }

    private List<TableLine> detailLines() {
        Item it = sel();
        if (it == null) return List.of();
        List<TableLine> out = new ArrayList<>();
        for (Details.Row r : Details.rows(it, History.series(it.id, System.currentTimeMillis(), 0), o.tax)) {
            int c = r.color() == 1 ? GOOD : r.color() == 2 ? BAD : r.color() == 3 ? ORANGE : 0;
            out.add(new TableLine(new String[]{r.label(), r.value(), r.note()}, new int[]{0, c, Ui.MUTED}, r.label() + ": " + r.value() + "  -  " + r.note()));
        }
        return out;
    }

    private List<TableLine> howLines() {
        List<TableLine> out = new ArrayList<>();
        if (selected == null) return out;
        Map<String, Item> p = prices();
        Map<String, Double> memo = new HashMap<>();
        for (Recipe r : Recipes.making(selected)) {
            Craft.Result x = Craft.eval(r, p, o, memo);
            String[] c = {ingredients(r) + (r.outCount() > 1 ? "  (makes " + r.outCount() + ")" : ""), x.missing.isEmpty() ? Fmt.coins(x.cost) : "?", Fmt.coins(x.revenue),
                    x.missing.isEmpty() ? Fmt.signed(x.profit) : "-", x.missing.isEmpty() ? (x.crafted.isEmpty() ? r.note() : "craft " + name(x.crafted.get(0)) + " yourself") : name(x.missing) + " is not on the Bazaar"};
            int[] col = {0, 0, 0, x.profit > 0 ? GOOD : BAD, Ui.MUTED};
            out.add(new TableLine(c, col, ingredients(r) + (r.note().isEmpty() ? "" : "  |  " + r.note()), null));
        }
        return out;
    }

    private List<TableLine> usedLines() {
        List<TableLine> out = new ArrayList<>();
        if (selected == null) return out;
        Map<String, Item> p = prices();
        Map<String, Double> memo = new HashMap<>();
        List<Craft.Result> rs = new ArrayList<>();
        for (Recipe r : Recipes.using(selected)) rs.add(Craft.eval(r, p, o, memo));
        rs.sort(Comparator.comparingDouble((Craft.Result r) -> r.profit).reversed());
        for (Craft.Result x : rs) {
            if (out.size() >= 25) break;
            if (!p.containsKey(x.recipe.out())) continue;
            int need = x.recipe.in().getOrDefault(selected, 0);
            String[] c = {name(x.recipe.out()), "x" + Fmt.coins(need), Fmt.coins(x.cost), Fmt.coins(x.revenue), Fmt.signed(x.profit), Fmt.pct(x.margin)};
            int[] col = {0, 0, 0, 0, x.profit > 0 ? GOOD : BAD, x.margin > 0 ? GOOD : BAD};
            final String id = x.recipe.out();
            out.add(new TableLine(c, col, ingredients(x.recipe) + "  |  click to open " + name(id), () -> open(id)));
        }
        return out;
    }

    // ---- screen --------------------------------------------------------------------------------------------

    private static final String[] CRAFT_HEAD = {"", "Item", "Cost", "Sells for", "Profit", "Margin", "Profit/h", "Sells in*", "Note"};
    private static final double[] CRAFT_W = {0.2, 2.5, 0.9, 0.9, 0.9, 0.7, 0.9, 0.8, 2.3};
    private static final boolean[] CRAFT_R = {false, false, true, true, true, true, true, true, false};

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        // ---- craft flips
        ui.tab("Craft flips", "C");
        ui.section("What to craft and sell");
        ui.info("Data", "Recipes come from the NotEnoughUpdates item repo, prices from the Bazaar API (every 5 minutes).", () -> RecipeSource.message() + "  |  prices: " + Bazaar.message(), Ui.MUTED);
        ui.choice("Buy ingredients by", "A buy order is cheaper but slower; insta-buy costs the lowest sell offer.", new String[]{"Buy order", "Insta-buy"}, () -> o.buyInsta ? 1 : 0, i -> o.buyInsta = i == 1);
        ui.choice("Sell the result by", "A sell offer earns more but can take long (see Sells in); insta-sell is immediate.", new String[]{"Sell offer", "Insta-sell"}, () -> o.sellInsta ? 1 : 0, i -> o.sellInsta = i == 1);
        ui.choice("Sort by", "How the crafts are ranked.", new String[]{"Profit per craft", "Margin", "Profit per hour", "Weekly volume"}, () -> craftSort, i -> craftSort = i);
        ui.toggle("Favourites only", "Only crafts of items you starred.", () -> favOnly, v -> favOnly = v);
        ui.section("Most profitable crafts");
        ui.tableOpenTab("Item", CRAFT_HEAD, CRAFT_W, CRAFT_R, "Nothing passes your filters, or the recipes / prices are still loading (see Data above).", this::craftLines);
        ui.note("Profit = what the result sells for after the Bazaar tax minus the ingredients. Profit/h is limited by how many crafts you manage per hour, by what the market takes, and by how fast the ingredients can be bought. "
                + "* Sells in = how long a sell offer needs to sell one hour of crafts: orange is slow, red may take days. Click a line for the item page. Some recipes need a collection level (the Note column).");

        // ---- market flips
        ui.tab("Market flips", "M");
        ui.section("Buy order -> sell offer");
        ui.choice("Sort by", "How the flips are ranked.", new String[]{"Profit per item", "Margin", "Profit per hour", "Weekly volume"}, () -> flipSort, i -> flipSort = i);
        ui.tableOpenTab("Item", new String[]{"", "Item", "Buy order", "Sell offer", "Profit", "Margin", "Volume/wk", "Profit/h*", "Note"}, new double[]{0.2, 2.6, 0.9, 0.9, 0.9, 0.7, 0.9, 0.9, 1.4},
                new boolean[]{false, false, true, true, true, true, true, true, false}, "No flip passes your filters (or no prices yet).", this::flipLines);
        ui.note("A flip = place a buy order at the top buy price, sell with an offer at the lowest offer, minus the tax. Profit/h = profit x the items per hour the market trades (at most your \"items you can trade per hour\" in Settings). "
                + "Wide spreads are a sign of thin or manipulated markets and are filtered by the maximum spread.");

        // ---- favourites
        ui.tab("Favourites", "F");
        ui.section("Your starred items");
        ui.tableOpenTab("Item", new String[]{"Item", "Insta-sell", "Sell offer", "Flip", "Best craft", "Volume/wk"}, new double[]{2.6, 1, 1, 1, 1, 1},
                new boolean[]{false, true, true, true, true, true}, "No favourites yet. Open an item (click a line anywhere) and use \"Favourite\" on its page.", this::favLines);
        ui.confirmButton("Clear favourites", "Removes every item from the favourites.", "Clear", Favorites::clear);

        // ---- item page
        ui.tab("Item", "I");
        ui.section("Item");
        ui.info("Selected", "Click a line in any list to open its item.", () -> selected == null ? "none - click an item in Craft flips, Market flips or Favourites"
                : name(selected) + "  (" + selected + ")", Ui.TEXT);
        ui.info("Favourite", "Starred items are listed in the Favourites tab and marked with * everywhere.", () -> selected != null && Favorites.has(selected) ? "yes" : "no", Ui.WARN);
        ui.button("Favourite on / off", "Stars or un-stars this item.", "Toggle", true, () -> {
            if (selected != null) Favorites.toggle(selected);
        });
        ui.choice("Graph range", "How far back the price graph goes (prices are recorded every 5 minutes while the game is open).", new String[]{"1 hour", "6 hours", "24 hours", "3 days"}, () -> chartRange, i -> chartRange = i);
        ui.canvas(() -> sel() == null ? 30 : 132, (g, x, y, w, h, mx, my) -> {
            Item it = sel();
            if (it == null) return ItemView.message(g, x, y, w, h, "Open an item from one of the lists to see its graphs and numbers here.");
            return ItemView.price(g, x, y, w, h, mx, my, it.id, name(it.id), RANGE_MS[chartRange]);
        });
        ui.canvas(() -> sel() == null ? 1 : 122, (g, x, y, w, h, mx, my) -> {
            Item it = sel();
            return it == null || h < 30 ? null : ItemView.book(g, x, y, w, h, mx, my, it);
        });
        ui.section("How to get it (crafting)");
        ui.table(new String[]{"Ingredients", "Cost", "Worth", "Profit", "Note"}, new double[]{3.4, 0.9, 0.9, 0.9, 2}, new boolean[]{false, true, true, true, false},
                "No crafting recipe for this item (it is dropped, bought, or made another way).", this::howLines);
        ui.section("Used in (what it can be crafted into)");
        ui.tableJumpTop(new String[]{"Makes", "Needs", "Craft cost", "Sells for", "Profit", "Margin"}, new double[]{2.6, 0.7, 1, 1, 1, 0.8}, new boolean[]{false, true, true, true, true, true},
                "Nothing is crafted from this item.", this::usedLines);
        ui.section("Numbers");
        ui.table(new String[]{"What", "Value", "Note"}, new double[]{1.7, 1.7, 3.8}, new boolean[]{false, true, false}, "", this::detailLines);

        // ---- settings
        ui.tab("Settings", "T");
        ui.section("Prices and filters");
        ui.dslider("Bazaar sell tax", "1.25 % by default; lower with the Bazaar Flipper levels (1.125 % / 1 %).", 0.5, 1.25, 0.125, () -> o.tax * 100, v -> o.tax = v / 100, "%.3f %%");
        ui.islider("Minimum weekly volume of the result", "Items that trade less per week (smaller side) are left out as Low liquidity.", 0, 200_000, () -> o.minVolume, v -> o.minVolume = v, "");
        ui.islider("Minimum weekly volume of ingredients", "Every bought ingredient must trade at least this much per week. 0 = do not check.", 0, 200_000, () -> o.minIngredientVolume, v -> o.minIngredientVolume = v, "");
        ui.dslider("Maximum cost of one craft", "Your capital limit.", 10_000, 500_000_000, 10_000, () -> o.maxCost, v -> o.maxCost = (long) v, "%.0f");
        ui.dslider("Minimum profit", "Crafts and flips that earn less than this are left out.", 0, 1_000_000, 500, () -> o.minProfit, v -> o.minProfit = (long) v, "%.0f");
        ui.dslider("Minimum margin", "Profit divided by cost.", 0, 2, 0.01, () -> o.minMargin * 100, v -> o.minMargin = v / 100, "%.0f %%");
        ui.dslider("Maximum spread", "Items whose instant-sell price is more than this below the sell offer are a thin market.", 0.1, 1.0, 0.05, () -> o.maxSpread * 100, v -> o.maxSpread = v / 100, "%.0f %%");
        ui.toggle("Craft ingredients yourself when cheaper", "An ingredient that is cheaper to craft than to buy is crafted (two levels deep).", () -> o.craftIngredients, v -> o.craftIngredients = v);
        ui.toggle("Show filtered crafts", "Also list crafts that were left out (low volume, below minimum ...), dimmed at the end.", () -> showFlagged, v -> showFlagged = v);
        ui.section("Speed and warnings");
        ui.islider("Crafts per hour", "How many crafts you manage per hour (caps Profit/h).", 1, 2000, () -> o.craftsPerHour, v -> o.craftsPerHour = v, " /h");
        ui.islider("Items you can trade per hour", "Caps the Profit/h of plain flips.", 10, 20_000, () -> o.flipUnitsPerHour, v -> o.flipUnitsPerHour = v, " /h");
        ui.dslider("Sell offer is slow after", "Hours to sell one hour of crafts: above this the value turns orange.", 0.5, 12, 0.5, () -> o.slowHours, v -> o.slowHours = v, "%.1f h");
        ui.dslider("Sell offer is very slow after", "Above this it turns red: it may take days.", 2, 72, 1, () -> o.veryslowHours, v -> o.veryslowHours = v, "%.0f h");
        ui.islider("Rows per table", "How many lines the ranked tables show.", 5, 60, () -> o.topN, v -> o.topN = v, "");
        ui.section("Recipes");
        ui.info("Recipes", "Where the recipes come from and how many there are.", () -> Recipes.count() + "  |  " + RecipeSource.message(), Ui.TEXT);
        ui.button("Download recipes again", "Gets the newest recipes from the NotEnoughUpdates repo (they are refreshed weekly by themselves).", "Download", false, RecipeSource::refreshNow);
        ui.note("Recipes are the crafting-table recipes of the NotEnoughUpdates item repo. Forge, NPC and other non-crafting ways are not included yet.");
    }

    // ---- persistence ---------------------------------------------------------------------------------------

    private void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            o.buyInsta = Boolean.parseBoolean(p.getProperty("buyInsta", "" + o.buyInsta));
            o.sellInsta = Boolean.parseBoolean(p.getProperty("sellInsta", "" + o.sellInsta));
            o.tax = Double.parseDouble(p.getProperty("tax", "" + o.tax));
            o.minVolume = Integer.parseInt(p.getProperty("minVolume", "" + o.minVolume));
            o.minIngredientVolume = Integer.parseInt(p.getProperty("minIngredientVolume", "" + o.minIngredientVolume));
            o.maxCost = Long.parseLong(p.getProperty("maxCost", "" + o.maxCost));
            o.minProfit = Long.parseLong(p.getProperty("minProfit", "" + o.minProfit));
            o.minMargin = Double.parseDouble(p.getProperty("minMargin", "" + o.minMargin));
            o.maxSpread = Double.parseDouble(p.getProperty("maxSpread", "" + o.maxSpread));
            o.craftsPerHour = Integer.parseInt(p.getProperty("craftsPerHour", "" + o.craftsPerHour));
            o.craftIngredients = Boolean.parseBoolean(p.getProperty("craftIngredients", "" + o.craftIngredients));
            o.topN = Integer.parseInt(p.getProperty("topN", "" + o.topN));
            o.slowHours = Double.parseDouble(p.getProperty("slowHours", "" + o.slowHours));
            o.veryslowHours = Double.parseDouble(p.getProperty("veryslowHours", "" + o.veryslowHours));
            o.flipUnitsPerHour = Integer.parseInt(p.getProperty("flipUnitsPerHour", "" + o.flipUnitsPerHour));
            showFlagged = Boolean.parseBoolean(p.getProperty("showFlagged", "" + showFlagged));
            favOnly = Boolean.parseBoolean(p.getProperty("favOnly", "" + favOnly));
            craftSort = Integer.parseInt(p.getProperty("craftSort", "" + craftSort));
            flipSort = Integer.parseInt(p.getProperty("flipSort", "" + flipSort));
        } catch (IOException | RuntimeException ignored) {
            // keep defaults
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("buyInsta", "" + o.buyInsta);
        p.setProperty("sellInsta", "" + o.sellInsta);
        p.setProperty("tax", "" + o.tax);
        p.setProperty("minVolume", "" + o.minVolume);
        p.setProperty("minIngredientVolume", "" + o.minIngredientVolume);
        p.setProperty("maxCost", "" + o.maxCost);
        p.setProperty("minProfit", "" + o.minProfit);
        p.setProperty("minMargin", "" + o.minMargin);
        p.setProperty("maxSpread", "" + o.maxSpread);
        p.setProperty("craftsPerHour", "" + o.craftsPerHour);
        p.setProperty("craftIngredients", "" + o.craftIngredients);
        p.setProperty("topN", "" + o.topN);
        p.setProperty("slowHours", "" + o.slowHours);
        p.setProperty("veryslowHours", "" + o.veryslowHours);
        p.setProperty("flipUnitsPerHour", "" + o.flipUnitsPerHour);
        p.setProperty("showFlagged", "" + showFlagged);
        p.setProperty("favOnly", "" + favOnly);
        p.setProperty("craftSort", "" + craftSort);
        p.setProperty("flipSort", "" + flipSort);
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Nablzz Addons: bazaar flipper");
        } catch (IOException ignored) {
            // not saved
        }
    }
}
