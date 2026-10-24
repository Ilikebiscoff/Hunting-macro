package com.nablzz.addons.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The numbers shown for one Bazaar item (label, value, note). Pure Java. {@code color}: 0 normal, 1 good, 2 bad, 3 warning. */
public final class Details {
    private Details() {}

    public record Row(String label, String value, String note, int color) {}

    private static String per(double v) {
        return Fmt.coins(v);
    }

    /** Hours until {@code qty} items are bought/sold when {@code flowPerHour} items per hour take them. */
    private static String fill(double qty, double flowPerHour) {
        return flowPerHour <= 0 ? "never" : Fmt.hours(qty / flowPerHour);
    }

    public static List<Row> rows(Item it, History.Series hist, double tax) {
        List<Row> r = new ArrayList<>();
        double bo = it.buyOrder, so = it.sellOffer;
        double sellFlow = it.sellWeek / 168.0, buyFlow = it.buyWeek / 168.0; // per hour: instant sells fill buy orders, instant buys fill sell offers
        r.add(new Row("Insta-sell price", bo > 0 ? per(bo) : "-", "what you get selling right now (the top buy order)", 0));
        r.add(new Row("Sell offer price", so > 0 ? per(so) : "-", "what an instant buy costs (the lowest sell offer)", 0));
        if (bo > 0 && so > 0) {
            r.add(new Row("Spread", Fmt.pct(it.spread()) + "  (" + per(so - bo) + ")", it.spread() > 0.4 ? "wide: a thin or manipulated market" : "gap between the two prices", it.spread() > 0.4 ? 3 : 0));
            double flip = so * (1 - tax) - bo;
            r.add(new Row("Flip: buy order, sell offer", (flip > 0 ? "+" : "") + per(flip) + "  (" + Fmt.pct(flip / bo) + ")", "per item after " + String.format(Locale.ROOT, "%.2f", tax * 100) + "% tax, if both orders fill", flip > 0 ? 1 : 2));
            double inst = bo * (1 - tax) - so;
            r.add(new Row("Instant flip", per(inst), "buy instantly and sell instantly: usually a loss", inst > 0 ? 1 : 2));
        }
        r.add(new Row("Sold instantly / week", Fmt.coins(it.sellWeek), "by players selling into buy orders: " + String.format(Locale.ROOT, "%.0f", sellFlow) + " per hour", 0));
        r.add(new Row("Bought instantly / week", Fmt.coins(it.buyWeek), "by players buying from sell offers: " + String.format(Locale.ROOT, "%.0f", buyFlow) + " per hour", 0));
        if (it.sellWeek > 0 && it.buyWeek > 0) {
            double ratio = (double) it.buyWeek / it.sellWeek;
            r.add(new Row("Demand vs supply", String.format(Locale.ROOT, "%.2f", ratio), ratio > 1.3 ? "more instant buys than instant sells: sell offers fill quickly" : ratio < 0.77 ? "more instant sells than buys: sell offers fill slowly" : "balanced", ratio > 1.3 ? 1 : ratio < 0.77 ? 3 : 0));
        }
        r.add(new Row("Buy orders waiting", Fmt.coins(it.buyOrderCount) + " orders", Fmt.coins(it.buyOrderItems) + " items in total", 0));
        r.add(new Row("Sell offers waiting", Fmt.coins(it.sellOfferCount) + " offers", Fmt.coins(it.sellOfferItems) + " items in total", 0));
        if (it.soAmt.length > 0) {
            r.add(new Row("Ahead of a new sell offer", Fmt.coins(it.soAmt[0]) + " items", "at the lowest price now; undercut by 0.1 to be first", 0));
        }
        for (int q : new int[]{1, 64, 640, 5000}) {
            if (flowOk(buyFlow)) r.add(new Row("Sell offer of " + q + " fills in", fill(q, buyFlow), "if you are first in line; others ahead make it longer", q / Math.max(buyFlow, 1e-9) > 24 ? 3 : 0));
        }
        for (int q : new int[]{1, 64, 640, 5000}) {
            if (bo <= 0) break;
            double avg = it.instaSellAvg(q);
            double loss = 1 - avg / bo;
            r.add(new Row("Insta-sell " + q + " gets", per(avg * q) + "  (" + per(avg) + " each)", loss < 0.005 ? "no price drop" : "price drops " + Fmt.pct(loss) + " on average (book depth: " + it.boPrice.length + " levels)", loss > 0.1 ? 2 : loss > 0.03 ? 3 : 0));
        }
        for (int q : new int[]{1, 64, 640}) {
            if (so <= 0) break;
            double avg = it.instaBuyAvg(q);
            double extra = avg / so - 1;
            r.add(new Row("Insta-buy " + q + " costs", per(avg * q) + "  (" + per(avg) + " each)", extra < 0.005 ? "no price rise" : "price rises " + Fmt.pct(extra) + " on average", extra > 0.1 ? 2 : extra > 0.03 ? 3 : 0));
        }
        if (hist != null && hist.size() >= 2) {
            double min = Double.MAX_VALUE, max = 0, sum = 0;
            for (double[] p : hist.p) {
                min = Math.min(min, p[0]);
                max = Math.max(max, p[0]);
                sum += p[0];
            }
            double first = hist.p.get(0)[0], last = hist.p.get(hist.size() - 1)[0];
            long span = hist.t.get(hist.size() - 1)[0] - hist.t.get(0)[0];
            r.add(new Row("Insta-sell low / high", per(min) + " / " + per(max), "over the last " + Fmt.age(span) + " of recorded prices (average " + per(sum / hist.size()) + ")", 0));
            if (first > 0) {
                double ch = last / first - 1;
                r.add(new Row("Insta-sell change", (ch > 0 ? "+" : "") + Fmt.pct(ch), "since the first recorded point", ch > 0.02 ? 1 : ch < -0.02 ? 2 : 0));
            }
        }
        return r;
    }

    private static boolean flowOk(double flow) {
        return flow > 0;
    }
}
