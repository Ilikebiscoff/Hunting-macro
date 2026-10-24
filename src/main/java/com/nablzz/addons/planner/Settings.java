package com.nablzz.addons.planner;

import java.util.Objects;

/** What the planner assumes. Everything has a slider or a toggle in the Settings tab. */
public final class Settings {
    /** Buy partner shards / ingredients with a buy order (cheaper, slower) or instantly. */
    public boolean instaBuy = false;
    /** Bazaar sell tax as a fraction (0.0125 = 1.25 %; lower with the Bazaar Flipper levels). */
    public double tax = 0.0125;
    /** 1 = base fusion output; raise it with perks that add output. */
    public double outputMult = 1.0;
    /** Outputs that trade less than this per week (the smaller side) are left out. */
    public int minWeeklyVolume = 5000;
    /** Largest cost of bought inputs for one fusion / route. */
    public long maxCost = 5_000_000;
    /** An output whose instant-sell price is more than this below its sell-offer price counts as a thin market. */
    public double maxSpread = 0.6;
    /** Rows per table. */
    public int topN = 10;
    /** Also list the options that were filtered out (low volume, wide spread, over budget ...), marked. */
    public boolean showFlagged = false;
    /** Items farmed per hour. */
    public int mudwormPerHour = 300, minerZombiePerHour = 300, mushroomPerHour = 400;
    /** Lowest BIN of the Glowing Mush Mixin. 0 = take it from the Auction House data. */
    public long mixinOverride = 0;
    /** A sell offer that needs longer than this to sell one hour of farmed output is flagged slow / very slow. */
    public double slowHours = 1.5, verySlowHours = 6.0;

    public long signature() {
        return Objects.hash(instaBuy, tax, outputMult, minWeeklyVolume, maxCost, maxSpread, topN, showFlagged, mudwormPerHour,
                minerZombiePerHour, mushroomPerHour, mixinOverride, slowHours, verySlowHours);
    }
}
