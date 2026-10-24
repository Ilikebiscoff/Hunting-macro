package com.nablzz.addons.planner;

import java.util.List;

/** The Glowing Mushroom routes through Moby (Glowing Mushroom Cave), as in the Hypixel Profit Planner spreadsheet. */
public final class Routes {
    private Routes() {}

    public static final String MIXIN = "Glowing Mush Mixin (AH)";

    public static final List<Calc.Route> MUSHROOM = List.of(
            new Calc.Route("Moby: buy an Empty Chum Bucket, sell it", 8, 2000, "", "-", 0, "Empty Chum Bucket", "EMPTY_CHUM_BUCKET", 1),
            new Calc.Route("Moby: Empty Chum Bucket, fill with 10 Chum, sell the Full Chum Bucket", 8, 2000, "CHUM", "Chum", 10, "Full Chum Bucket", "FULL_CHUM_BUCKET", 1),
            new Calc.Route("Moby: Glowy Chum Bait (buying the Full Chum Bucket)", 4, 0, "FULL_CHUM_BUCKET", "Full Chum Bucket", 1, "Glowy Chum Bait", "GLOWY_CHUM_BAIT", 16),
            new Calc.Route("Moby: Glowy Chum Bait (making your own Full Chum Bucket)", 12, 2000, "CHUM", "Chum", 10, "Glowy Chum Bait", "GLOWY_CHUM_BAIT", 16),
            new Calc.Route("Moby: Glowing Mush Mixin (buying 64 Full Chum Buckets)", 80, 0, "FULL_CHUM_BUCKET", "Full Chum Bucket", 64, MIXIN, Calc.AH, 1),
            new Calc.Route("Moby: Glowing Mush Mixin (making all 64 buckets yourself)", 592, 128000, "CHUM", "Chum", 640, MIXIN, Calc.AH, 1));
}
