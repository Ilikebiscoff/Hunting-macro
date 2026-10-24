package com.nablzz.addons.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The profit maths of the planner (same rules as the Hypixel Profit Planner spreadsheet). Pure Java: no Minecraft
 * classes, so it can be tested on its own.
 */
public final class Calc {
    private Calc() {}

    public enum Mode { INSTA, OFFER }

    /** One fusion option: put {@code partnerQty} of the partner and {@code shardQty} of the base shard in, get {@code outQty}. */
    public record Fusion(String type, String partnerId, String partnerName, int partnerQty, int shardQty, String outName, String outId, int outQty) {}

    /** One Glowing Mushroom route (Moby): {@code used} mushrooms + {@code fee} coins + {@code otherQty} of another input. */
    public record Route(String title, int used, long fee, String otherId, String otherName, int otherQty, String outName, String outId, int outQty) {}

    public static final String AH = "AH";

    /** One evaluated way of turning the farmed item into coins. */
    public static final class Method {
        public String kind;       // "Sell raw", "Fuse", "Moby"
        public String desc;       // what to do
        public String outName, outId;
        public double inputCost;  // bought inputs + fees for one run
        public int used;          // farmed items one run uses
        public double outUnit;    // price of one output
        public double net;        // output revenue after tax
        public double value;      // coins per farmed item (net - cost) / used
        public double extra;      // value - raw value
        public double profit;     // for one run, compared with selling the same items raw
        public double roi;        // profit / cost
        public long vol;          // weekly volume of the output (smaller side)
        public double spread;
        public String status;     // "OK" or the reason it is flagged
        public double perHour;    // coins per hour at the farming rate
        public double outPerHour; // output items made per hour
        public double etaHours = Double.NaN;  // sell offers: hours to sell one hour of output
        public double slip;       // insta-sell: how much lower the average price gets when selling one hour of output at once
        public boolean ok() { return "OK".equals(status); }
    }

    public static double unit(Item it, Mode m) {
        if (it == null) return 0;
        return m == Mode.INSTA ? it.buyOrder : it.sellOffer;
    }

    /** Price of one input: a buy order (the top buy price), or the instant-buy price when asked or when there is no buy order. */
    public static double buyUnit(Item it, Settings s) {
        if (it == null) return 0;
        if (s.instaBuy || it.buyOrder <= 0) return it.sellOffer;
        return it.buyOrder;
    }

    /** Coins per item when selling the farmed item as it is. */
    public static double rawNet(Item it, Mode m, Settings s) {
        return unit(it, m) * (1 - s.tax);
    }

    private static double etaHours(Item out, double outPerHour) {
        if (out == null) return Double.NaN;
        double flow = out.buyWeek / 168.0; // instant buys per hour: what fills the sell offers
        if (flow <= 0) return Double.POSITIVE_INFINITY;
        return outPerHour / flow;
    }

    /** "OK" or why the option is left out; same order of checks as the spreadsheet. */
    private static String status(boolean onBazaar, boolean noInputPrice, long vol, double spread, double cost, Settings s) {
        if (!onBazaar) return "Not on Bazaar";
        if (noInputPrice) return "No input price";
        if (vol < s.minWeeklyVolume) return "Low liquidity";
        if (spread > s.maxSpread) return "Wide spread";
        if (cost > s.maxCost) return "Over budget";
        return "OK";
    }

    public static Method raw(String baseId, String baseName, Map<String, Item> prices, Mode m, Settings s, double perHour) {
        Item it = prices.get(baseId);
        Method r = new Method();
        r.kind = "Sell raw";
        r.desc = "Sell " + baseName + " as it is";
        r.outName = baseName;
        r.outId = baseId;
        r.used = 1;
        r.outUnit = unit(it, m);
        r.net = r.outUnit * (1 - s.tax);
        r.value = r.net;
        r.extra = 0;
        r.vol = it == null ? 0 : it.weeklyVol();
        r.spread = it == null ? 1 : it.spread();
        r.status = it == null || r.outUnit <= 0 ? "Not on Bazaar" : "OK";
        r.perHour = r.value * perHour;
        r.outPerHour = perHour;
        fill(r, it, m, perHour);
        return r;
    }

    private static void fill(Method r, Item out, Mode m, double outPerHour) {
        if (out == null) return;
        if (m == Mode.OFFER) r.etaHours = etaHours(out, outPerHour);
        else if (out.buyOrder > 0) r.slip = Math.max(0, 1 - out.instaSellAvg(outPerHour) / out.buyOrder);
    }

    public static Method fusion(Fusion f, String baseName, Map<String, Item> prices, Mode m, Settings s, double rawNet, double perHour) {
        Item partner = prices.get(f.partnerId());
        Item out = prices.get(f.outId());
        double pUnit = f.partnerQty() == 0 ? 0 : buyUnit(partner, s);
        Method r = new Method();
        r.kind = "Fuse";
        r.desc = (f.partnerQty() == 0 ? f.partnerName() : f.partnerName() + " x" + f.partnerQty() + " + " + baseName + " x" + f.shardQty())
                + " -> " + f.outName() + " x" + f.outQty();
        r.outName = f.outName();
        r.outId = f.outId();
        r.used = f.shardQty();
        r.inputCost = pUnit * f.partnerQty();
        double u = unit(out, m);
        boolean priced = out != null && u > 0;
        r.outUnit = priced ? u : 0;
        double gross = priced ? f.outQty() * s.outputMult * u : 0;
        r.net = gross * (1 - s.tax);
        r.value = priced ? (r.net - r.inputCost) / f.shardQty() : 0;
        r.extra = priced ? r.value - rawNet : 0;
        r.profit = priced ? r.net - r.inputCost - f.shardQty() * rawNet : 0;
        r.roi = priced && r.inputCost > 0 ? r.profit / r.inputCost : 0;
        r.vol = out == null ? 0 : out.weeklyVol();
        r.spread = out == null ? 1 : out.spread();
        r.status = status(priced, f.partnerQty() > 0 && pUnit <= 0, r.vol, r.spread, r.inputCost, s);
        r.perHour = r.value * perHour;
        r.outPerHour = perHour / f.shardQty() * f.outQty();
        fill(r, out, m, r.outPerHour);
        return r;
    }

    public static Method route(Route rt, Map<String, Item> prices, Mode m, Settings s, double rawNet, double perHour, double ahPrice) {
        boolean ah = AH.equals(rt.outId());
        Item out = ah ? null : prices.get(rt.outId());
        Item other = rt.otherQty() == 0 ? null : prices.get(rt.otherId());
        double oUnit = rt.otherQty() == 0 ? 0 : buyUnit(other, s);
        Method r = new Method();
        r.kind = "Moby";
        r.desc = rt.title();
        r.outName = rt.outName();
        r.outId = rt.outId();
        r.used = rt.used();
        r.inputCost = oUnit * rt.otherQty() + rt.fee();
        double u = ah ? ahPrice : unit(out, m);
        boolean priced = u > 0 && (ah || out != null);
        r.outUnit = priced ? u : 0;
        r.net = priced ? rt.outQty() * u * (ah ? 1 : 1 - s.tax) : 0;
        r.value = priced ? (r.net - r.inputCost) / rt.used() : 0;
        r.extra = priced ? r.value - rawNet : 0;
        r.profit = priced ? r.net - r.inputCost - rt.used() * rawNet : 0;
        r.roi = priced && r.inputCost > 0 ? r.profit / r.inputCost : 0;
        r.vol = ah ? Long.MAX_VALUE : out == null ? 0 : out.weeklyVol();
        r.spread = ah ? 0 : out == null ? 1 : out.spread();
        String st = status(priced, rt.otherQty() > 0 && oUnit <= 0, r.vol, r.spread, r.inputCost, s);
        r.status = !priced && ah ? "No AH price" : st;
        r.perHour = r.value * perHour;
        r.outPerHour = perHour / rt.used() * rt.outQty();
        if (!ah) fill(r, out, m, r.outPerHour);
        return r;
    }

    /** Ranked best first by extra coins per farmed item compared with selling raw. Flagged options come last (or are dropped). */
    public static List<Method> rank(List<Method> all, boolean keepFlagged) {
        List<Method> ok = new ArrayList<>(), bad = new ArrayList<>();
        for (Method x : all) (x.ok() ? ok : bad).add(x);
        Comparator<Method> byExtra = Comparator.comparingDouble((Method x) -> x.extra).reversed();
        ok.sort(byExtra);
        if (keepFlagged) {
            bad.removeIf(x -> "Not on Bazaar".equals(x.status));
            bad.sort(byExtra);
            ok.addAll(bad);
        }
        return ok;
    }

    public static List<Method> fusions(List<Fusion> rows, String baseId, String baseName, Map<String, Item> prices, Mode m, Settings s, double perHour) {
        double rawNet = rawNet(prices.get(baseId), m, s);
        List<Method> out = new ArrayList<>(rows.size());
        for (Fusion f : rows) out.add(fusion(f, baseName, prices, m, s, rawNet, perHour));
        return out;
    }

    public static List<Method> routes(List<Route> rows, String baseId, Map<String, Item> prices, Mode m, Settings s, double perHour, double ahPrice) {
        double rawNet = rawNet(prices.get(baseId), m, s);
        List<Method> out = new ArrayList<>(rows.size());
        for (Route r : rows) out.add(route(r, prices, m, s, rawNet, perHour, ahPrice));
        return out;
    }
}
