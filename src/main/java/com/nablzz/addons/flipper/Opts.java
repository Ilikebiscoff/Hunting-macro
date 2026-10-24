package com.nablzz.addons.flipper;

import java.util.Objects;

/** Settings of the flip calculations. */
public final class Opts {
    /** Ingredients: true = insta-buy (lowest sell offer), false = a buy order (the top buy price, cheaper but slower). */
    public boolean buyInsta = false;
    /** The result: true = insta-sell (the top buy order), false = a sell offer (the lowest offer, more coins, slower). */
    public boolean sellInsta = false;
    public double tax = 0.0125;
    /** Least items per week the result must trade (smaller side). */
    public int minVolume = 5000;
    /** Least items per week every ingredient must trade; 0 = do not check. */
    public int minIngredientVolume = 1000;
    /** Most coins one craft may cost. */
    public long maxCost = 20_000_000;
    public long minProfit = 1000;
    public double minMargin = 0.02;
    /** The insta-sell price may be at most this far below the sell offer (a thin market otherwise). */
    public double maxSpread = 0.6;
    /** Crafts you manage per hour; caps the profit per hour. */
    public int craftsPerHour = 120;
    /** Let an ingredient be crafted from its own recipe when that is cheaper than buying it. */
    public boolean craftIngredients = true;
    public int topN = 25;
    /** A sell offer needing longer than this to sell what you craft in an hour is slow / very slow. */
    public double slowHours = 1.5, veryslowHours = 6;
    /** Items you can trade per hour (caps the profit per hour of plain flips). */
    public int flipUnitsPerHour = 640;

    public long signature() {
        return Objects.hash(buyInsta, sellInsta, tax, minVolume, minIngredientVolume, maxCost, minProfit, minMargin, maxSpread, craftsPerHour,
                craftIngredients, topN, slowHours, veryslowHours, flipUnitsPerHour);
    }
}
