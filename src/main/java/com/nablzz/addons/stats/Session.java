package com.nablzz.addons.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/** One run of a macro, from Start to Stop. The numbers are counted during the run (not the totals of the whole game session). */
public final class Session {
    public String feature = "", name = "", start = "", main = "";
    public long startMs, seconds;
    public boolean live;
    public final Map<String, Double> metrics = new LinkedHashMap<>();

    public String id() {
        return feature + "@" + startMs;
    }

    public double hours() {
        return seconds / 3600.0;
    }

    /** Value of the main metric (what the macro is about: spots, shards ...). */
    public double mainValue() {
        return metrics.getOrDefault(main, 0.0);
    }

    public double perHour(double v) {
        return seconds <= 0 ? 0 : v / hours();
    }

    public double mainPerHour() {
        return perHour(mainValue());
    }
}
