package com.nablzz.addons.flipper;

import com.nablzz.addons.planner.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The profit of crafting and selling, for every recipe. Pure Java. */
public final class Craft {
    private Craft() {}

    /** One evaluated recipe. */
    public static final class Result {
        public Recipe recipe;
        public double cost;        // coins for the ingredients of one craft
        public double outUnit;     // price of one result
        public double revenue;     // outCount * price, after tax
        public double profit;      // per craft
        public double margin;      // profit / cost
        public long outVolume;     // weekly volume of the result (smaller side)
        public double spread;
        public long minIngVolume;  // the thinnest ingredient market (weekly)
        public String thinnest = "", missing = "";
        public double flowPerHour; // results the market takes per hour
        public double craftsPerHour;
        public double ingredientLimit = Double.POSITIVE_INFINITY; // crafts per hour the ingredient markets allow
        public double perHour;     // profit per hour at the best rate
        public double etaHours = Double.NaN; // sell offers: hours to sell one hour of crafts
        public String status;
        public List<String> crafted = new ArrayList<>(); // ingredients that are cheaper to craft than to buy
        public boolean ok() { return "OK".equals(status); }
    }

    private static double buyPrice(Item it, Opts o) {
        if (it == null) return 0;
        if (o.buyInsta || it.buyOrder <= 0) return it.sellOffer;
        return it.buyOrder;
    }

    /** Price of one unit of an item to someone who needs it: bought, or crafted when that is cheaper. -1 = cannot get it. */
    static double unitCost(String id, Map<String, Item> prices, Opts o, Map<String, Double> memo, Set<String> visiting, int depth, List<String> craftedOut) {
        Double m = memo.get(id);
        if (m != null) return m;
        double bought = buyPrice(prices.get(id), o);
        double best = bought > 0 ? bought : -1;
        if (o.craftIngredients && depth > 0 && visiting.add(id)) {
            for (Recipe r : Recipes.making(id)) {
                double c = recipeCost(r, prices, o, memo, visiting, depth - 1, null);
                if (c < 0) continue;
                double per = c / r.outCount();
                if (best < 0 || per < best) {
                    best = per;
                    if (craftedOut != null) craftedOut.add(id);
                }
            }
            visiting.remove(id);
        }
        // only the top-level call of a recipe records what is crafted; the memo is for prices
        memo.put(id, best);
        return best;
    }

    /** Coins for one craft, or -1 when an ingredient cannot be had. */
    static double recipeCost(Recipe r, Map<String, Item> prices, Opts o, Map<String, Double> memo, Set<String> visiting, int depth, List<String> craftedOut) {
        double sum = 0;
        for (var e : r.in().entrySet()) {
            double u = unitCost(e.getKey(), prices, o, memo, visiting, depth, craftedOut);
            if (u < 0) return -1;
            sum += u * e.getValue();
        }
        return sum;
    }

    /** Units of an item per hour that can be had: what the market trades, or what the ingredients of the cheaper craft allow. */
    static double supply(String id, Map<String, Item> prices, Opts o, Map<String, Double> memo, int depth) {
        Item it = prices.get(id);
        double bought = buyPrice(it, o);
        double flow = it == null ? 0 : (o.buyInsta ? it.buyWeek : it.sellWeek) / 168.0;
        if (depth <= 0 || !o.craftIngredients) return flow;
        double bestCost = bought > 0 ? bought : Double.MAX_VALUE, lim = flow;
        for (Recipe r : Recipes.making(id)) {
            double c = recipeCost(r, prices, o, memo, new HashSet<>(), depth - 1, null);
            if (c < 0) continue;
            double per = c / r.outCount();
            if (per < bestCost * 0.999) { // crafting is cheaper: it is limited by what its own ingredients allow
                double l = Double.POSITIVE_INFINITY;
                for (var e : r.in().entrySet()) l = Math.min(l, supply(e.getKey(), prices, o, memo, depth - 1) / e.getValue() * r.outCount());
                bestCost = per;
                lim = l;
            }
        }
        return lim;
    }

    public static Result eval(Recipe r, Map<String, Item> prices, Opts o, Map<String, Double> memo) {
        Result x = new Result();
        x.recipe = r;
        Item out = prices.get(r.out());
        // which ingredients are crafted instead of bought (for the display): a cheap second pass over the direct ingredients
        double cost = 0;
        boolean missing = false;
        long minVol = Long.MAX_VALUE;
        String thin = "";
        for (var e : r.in().entrySet()) {
            Item ing = prices.get(e.getKey());
            double bought = buyPrice(ing, o);
            double u = unitCost(e.getKey(), prices, o, memo, new HashSet<>(), 2, null);
            if (u < 0) {
                missing = true;
                x.missing = e.getKey();
                continue;
            }
            if (bought > 0 && u < bought * 0.999) x.crafted.add(e.getKey());
            cost += u * e.getValue();
            if (ing != null && bought > 0 && !(u < bought * 0.999)) { // bought, not crafted
                if (ing.weeklyVol() < minVol) {
                    minVol = ing.weeklyVol();
                    thin = e.getKey();
                }
            }
            x.ingredientLimit = Math.min(x.ingredientLimit, supply(e.getKey(), prices, o, memo, 2) / e.getValue());
        }
        x.cost = cost;
        x.minIngVolume = minVol == Long.MAX_VALUE ? 0 : minVol;
        if (!thin.isEmpty()) x.thinnest = thin;
        double price = out == null ? 0 : (o.sellInsta ? out.buyOrder : out.sellOffer);
        x.outUnit = price;
        x.revenue = price * r.outCount() * (1 - o.tax);
        x.profit = x.revenue - cost;
        x.margin = cost > 0 ? x.profit / cost : 0;
        x.outVolume = out == null ? 0 : out.weeklyVol();
        x.spread = out == null ? 1 : out.spread();
        double flow = out == null ? 0 : (o.sellInsta ? out.sellWeek : out.buyWeek) / 168.0 / Math.max(1, r.outCount()); // crafts per hour the market absorbs
        x.flowPerHour = flow;
        x.craftsPerHour = Math.min(Math.min(o.craftsPerHour, flow), x.ingredientLimit);
        x.perHour = Math.max(0, x.profit) * x.craftsPerHour;
        if (out != null && !o.sellInsta) {
            double makes = o.craftsPerHour * r.outCount();
            double take = out.buyWeek / 168.0;
            x.etaHours = take <= 0 ? Double.POSITIVE_INFINITY : makes / take;
        }
        if (price <= 0 || out == null) x.status = "Not on Bazaar";
        else if (missing) x.status = "Ingredient not on Bazaar";
        else if (x.outVolume < o.minVolume) x.status = "Low liquidity";
        else if (o.minIngredientVolume > 0 && x.minIngVolume > 0 && x.minIngVolume < o.minIngredientVolume) x.status = "Thin ingredient market";
        else if (x.spread > o.maxSpread) x.status = "Wide spread";
        else if (cost > o.maxCost) x.status = "Over budget";
        else if (x.profit < o.minProfit || x.margin < o.minMargin) x.status = "Below your minimum";
        else x.status = "OK";
        return x;
    }

    public static List<Result> evalAll(Map<String, Item> prices, Opts o) {
        Map<String, Double> memo = new HashMap<>();
        List<Result> out = new ArrayList<>();
        for (Recipe r : Recipes.all()) {
            if (!prices.containsKey(r.out())) continue;
            out.add(eval(r, prices, o, memo));
        }
        return out;
    }

    /** Sort keys: 0 profit per craft, 1 margin, 2 profit per hour, 3 weekly volume. */
    public static Comparator<Result> order(int sort) {
        return switch (sort) {
            case 1 -> Comparator.comparingDouble((Result r) -> r.margin).reversed();
            case 2 -> Comparator.comparingDouble((Result r) -> r.perHour).reversed();
            case 3 -> Comparator.comparingLong((Result r) -> r.outVolume).reversed();
            default -> Comparator.comparingDouble((Result r) -> r.profit).reversed();
        };
    }

    public static List<Result> best(List<Result> all, int sort, boolean includeFlagged) {
        List<Result> out = new ArrayList<>();
        for (Result r : all) if (r.ok() || (includeFlagged && !r.status.startsWith("Not on") && !r.status.startsWith("Ingredient"))) out.add(r);
        out.sort(Comparator.comparing((Result r) -> r.ok() ? 0 : 1).thenComparing(order(sort)));
        return out;
    }
}
