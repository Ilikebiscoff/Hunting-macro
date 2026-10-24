package com.nablzz.addons.planner;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** The two charts of the item details: price history and order book. */
final class ItemView {
    private ItemView() {}

    static final int SELL = 0xFF60A5FA, OFFER = 0xFFFB923C;

    private static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color) {
        int steps = (int) Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        for (int s = 0; s <= steps; s++) {
            double q = (double) s / steps;
            Ui.fill(g, (int) Math.round(x0 + (x1 - x0) * q), (int) Math.round(y0 + (y1 - y0) * q), 2, 2, color);
        }
    }

    static String message(GuiGraphicsExtractor g, int x, int y, int w, int h, String text) {
        return none(g, x, y, w, h, text);
    }

    private static String none(GuiGraphicsExtractor g, int x, int y, int w, int h, String text) {
        Ui.rr(g, x, y, w, h, 4, Ui.FIELD);
        Ui.textC(g, text, x + w / 2, y + h / 2 - 4, Ui.MUTED);
        return null;
    }

    /** Insta-sell price and sell offer over time (recorded by this mod). */
    static String price(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, String id, String name, long windowMs) {
        long now = System.currentTimeMillis();
        History.Series hs = History.series(id, now, windowMs);
        if (hs.size() < 2) {
            return none(g, x, y, w, h, "Recording " + name + " ...  " + hs.size() + " point" + (hs.size() == 1 ? "" : "s") + " so far (one every 5 minutes, kept between sessions)");
        }
        Ui.rr(g, x, y, w, h, 4, Ui.FIELD);
        int gx = x + 46, gy = y + 16, gw = w - 46 - 8, gh = h - 16 - 16;
        double min = Double.MAX_VALUE, max = 0;
        for (double[] p : hs.p) {
            for (double v : p) {
                if (v <= 0) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (max <= 0) return none(g, x, y, w, h, "No prices recorded for " + name + " yet");
        double pad = Math.max((max - min) * 0.08, max * 0.01);
        min -= pad;
        max += pad;
        for (int i = 0; i <= 2; i++) {
            int ly = gy + gh - i * gh / 2;
            Ui.fill(g, gx, ly, gw, 1, Ui.withAlpha(0xFFFFFF, 18));
            Ui.textR(g, Fmt.coins(min + (max - min) * i / 2), gx - 4, ly - 4, Ui.DIM);
        }
        long t0 = hs.t.get(0)[0], t1 = hs.t.get(hs.size() - 1)[0];
        double span = Math.max(1, t1 - t0);
        for (int series = 0; series < 2; series++) {
            int color = series == 0 ? SELL : OFFER;
            double lx = Double.NaN, ly = 0;
            for (int i = 0; i < hs.size(); i++) {
                double v = hs.p.get(i)[series];
                if (v <= 0) {
                    lx = Double.NaN;
                    continue;
                }
                double px = gx + (hs.t.get(i)[0] - t0) / span * (gw - 2), py = gy + gh - (v - min) / (max - min) * gh;
                if (!Double.isNaN(lx)) line(g, lx, ly, px, py, color);
                lx = px;
                ly = py;
            }
        }
        double[] last = hs.p.get(hs.size() - 1);
        Ui.rr(g, x + 6, y + 5, 7, 7, 2, SELL);
        Ui.text(g, "Insta-sell " + Fmt.coins(last[0]), x + 17, y + 4, Ui.TEXT);
        Ui.rr(g, x + 120, y + 5, 7, 7, 2, OFFER);
        Ui.text(g, "Sell offer " + Fmt.coins(last[1]), x + 131, y + 4, Ui.TEXT);
        Ui.text(g, "-" + Fmt.age(t1 - t0), gx, y + h - 11, Ui.DIM);
        Ui.textR(g, "now", gx + gw, y + h - 11, Ui.DIM);

        if (Ui.in(mx, my, gx, gy, gw, gh)) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < hs.size(); i++) {
                double px = gx + (hs.t.get(i)[0] - t0) / span * (gw - 2);
                if (Math.abs(px - mx) < bd) {
                    bd = Math.abs(px - mx);
                    best = i;
                }
            }
            double px = gx + (hs.t.get(best)[0] - t0) / span * (gw - 2);
            Ui.fill(g, (int) px, gy, 1, gh, Ui.withAlpha(0xFFFFFF, 90));
            double[] p = hs.p.get(best);
            return String.format(Locale.ROOT, "%s ago: insta-sell %s, sell offer %s (spread %s)", Fmt.age(now - hs.t.get(best)[0]), Fmt.coins(p[0]), Fmt.coins(p[1]),
                    p[1] > 0 && p[0] > 0 ? Fmt.pct(1 - p[0] / p[1]) : "-");
        }
        return null;
    }

    /** The two order books as bars per price level: buy orders on the left (best price in the middle), sell offers on the right. */
    static String book(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, Item it) {
        int nb = Math.min(it.boAmt.length, it.boPrice.length), ns = Math.min(it.soAmt.length, it.soPrice.length);
        if (nb == 0 && ns == 0) return none(g, x, y, w, h, "No order book data (the prices are an old copy, or this item has no orders)");
        Ui.rr(g, x, y, w, h, 4, Ui.FIELD);
        double max = 1;
        for (int i = 0; i < nb; i++) max = Math.max(max, it.boAmt[i]);
        for (int i = 0; i < ns; i++) max = Math.max(max, it.soAmt[i]);
        int top = y + 16, bottom = y + h - 16, bh = bottom - top, mid = x + w / 2;
        int slot = Math.max(8, Math.min(26, (w / 2 - 14) / Math.max(1, Math.max(nb, ns))));
        Ui.text(g, "Buy orders (insta-sell goes here)", x + 6, y + 4, Ui.GOOD);
        Ui.textR(g, "Sell offers (insta-buy takes these)", x + w - 6, y + 4, OFFER);
        Ui.fill(g, x + 4, bottom, w - 8, 1, Ui.withAlpha(0xFFFFFF, 30));
        String tip = null;
        double cumB = 0, cumS = 0;
        for (int i = 0; i < nb; i++) {
            cumB += it.boAmt[i];
            int bx = mid - 6 - (i + 1) * slot, hh = (int) Math.max(2, it.boAmt[i] / max * (bh - 2));
            Ui.rr(g, bx + 1, bottom - hh, slot - 2, hh, 2, i == 0 ? Ui.GOOD : Ui.withAlpha(Ui.GOOD, 170));
            if (Ui.in(mx, my, bx, top, slot, bh + 14)) {
                tip = String.format(Locale.ROOT, "Buy orders at %s: %s items in %d order%s (%s items down to this price)", Fmt.coins(it.boPrice[i]), Fmt.coins(it.boAmt[i]),
                        it.boOrders.length > i ? it.boOrders[i] : 0, it.boOrders.length > i && it.boOrders[i] == 1 ? "" : "s", Fmt.coins(cumB));
                Ui.fill(g, bx, top, slot, bh, Ui.withAlpha(0xFFFFFF, 14));
            }
        }
        for (int i = 0; i < ns; i++) {
            cumS += it.soAmt[i];
            int bx = mid + 6 + i * slot, hh = (int) Math.max(2, it.soAmt[i] / max * (bh - 2));
            Ui.rr(g, bx + 1, bottom - hh, slot - 2, hh, 2, i == 0 ? OFFER : Ui.withAlpha(OFFER, 170));
            if (Ui.in(mx, my, bx, top, slot, bh + 14)) {
                tip = String.format(Locale.ROOT, "Sell offers at %s: %s items in %d offer%s (%s items up to this price)", Fmt.coins(it.soPrice[i]), Fmt.coins(it.soAmt[i]),
                        it.soOrders.length > i ? it.soOrders[i] : 0, it.soOrders.length > i && it.soOrders[i] == 1 ? "" : "s", Fmt.coins(cumS));
                Ui.fill(g, bx, top, slot, bh, Ui.withAlpha(0xFFFFFF, 14));
            }
        }
        if (nb > 0) {
            Ui.textR(g, Fmt.coins(it.boPrice[0]), mid - 8, y + h - 11, Ui.GOOD);
            Ui.text(g, Fmt.coins(it.boPrice[nb - 1]), mid - 6 - nb * slot, y + h - 11, Ui.DIM);
        }
        if (ns > 0) {
            Ui.text(g, Fmt.coins(it.soPrice[0]), mid + 8, y + h - 11, OFFER);
            Ui.textR(g, Fmt.coins(it.soPrice[ns - 1]), mid + 6 + ns * slot, y + h - 11, Ui.DIM);
        }
        if (nb > 0 && ns > 0) Ui.textC(g, "gap " + Fmt.coins(it.soPrice[0] - it.boPrice[0]), mid, top + 2, Ui.MUTED);
        Ui.text(g, "tallest bar = " + Fmt.coins(max) + " items", x + 6, bottom - bh + 0, Ui.DIM);
        return tip;
    }
}
