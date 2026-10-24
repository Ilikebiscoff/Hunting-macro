package com.nablzz.addons.planner;

/** One Bazaar product: the best prices of both books, the weekly volumes and the top of each order book. */
public final class Item {
    public final String id;
    /** Highest buy order = what an instant sell gets (the API's sell_summary[0]). 0 = nobody is buying. */
    public final double buyOrder;
    /** Lowest sell offer = what an instant buy costs (the API's buy_summary[0]). 0 = nobody is selling. */
    public final double sellOffer;
    /** Items instantly sold / instantly bought in the last week (quick_status). */
    public final long sellWeek, buyWeek;
    /** Order book depth, best price first: amounts and prices of the buy orders and of the sell offers. */
    public final double[] boAmt, boPrice, soAmt, soPrice;

    public Item(String id, double buyOrder, double sellOffer, long sellWeek, long buyWeek,
                double[] boAmt, double[] boPrice, double[] soAmt, double[] soPrice) {
        this.id = id;
        this.buyOrder = buyOrder;
        this.sellOffer = sellOffer;
        this.sellWeek = sellWeek;
        this.buyWeek = buyWeek;
        this.boAmt = boAmt;
        this.boPrice = boPrice;
        this.soAmt = soAmt;
        this.soPrice = soPrice;
    }

    public Item(String id, double buyOrder, double sellOffer, long sellWeek, long buyWeek) {
        this(id, buyOrder, sellOffer, sellWeek, buyWeek, new double[0], new double[0], new double[0], new double[0]);
    }

    /** How far the instant-sell price lies below the sell-offer price (0.3 = 30 % lower). 1 when a side is empty. */
    public double spread() {
        return sellOffer > 0 && buyOrder > 0 ? 1 - buyOrder / sellOffer : 1;
    }

    /** The smaller of the two weekly volumes: the side of the market that limits trading. */
    public long weeklyVol() {
        return Math.min(sellWeek, buyWeek);
    }

    /**
     * Average price per item when instantly selling {@code qty} items into the buy orders. Falls back to the best
     * price when the depth is unknown, and counts items beyond the known depth at the last known price.
     */
    public double instaSellAvg(double qty) {
        return walk(boAmt, boPrice, buyOrder, qty);
    }

    /** Average price per item when instantly buying {@code qty} items from the sell offers. */
    public double instaBuyAvg(double qty) {
        return walk(soAmt, soPrice, sellOffer, qty);
    }

    private static double walk(double[] amt, double[] price, double best, double qty) {
        if (qty <= 0 || amt.length == 0) return best;
        double left = qty, sum = 0, last = best;
        for (int i = 0; i < amt.length && left > 0; i++) {
            double take = Math.min(left, amt[i]);
            sum += take * price[i];
            left -= take;
            last = price[i];
        }
        if (left > 0) sum += left * last;
        return sum / qty;
    }
}
