package com.nablzz.addons.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides which sessions make no sense. A <b>hard</b> reason means the session is not saved at all; a <b>soft</b> reason
 * keeps it but marks it, so you can look at it and delete it. The comparisons use the rate of the main metric of the
 * same macro's earlier sessions.
 */
public final class Rules {
    private Rules() {}

    public static final long MIN_SECONDS = 30, MAX_SECONDS = 24 * 3600;

    /** Median rate (main metric per hour) of the given sessions that have one; NaN when fewer than {@code min} do. */
    public static double medianRate(List<Session> history, int min) {
        List<Double> rates = new ArrayList<>();
        for (Session h : history) if (h.seconds >= MIN_SECONDS && h.mainPerHour() > 0) rates.add(h.mainPerHour());
        if (rates.size() < min) return Double.NaN;
        Collections.sort(rates);
        int n = rates.size();
        return n % 2 == 1 ? rates.get(n / 2) : (rates.get(n / 2 - 1) + rates.get(n / 2)) / 2;
    }

    /** Why the session must not be saved, or null. {@code history} = this macro's saved sessions. */
    public static String hard(Session s, List<Session> history) {
        if (s.seconds < MIN_SECONDS) return "shorter than " + MIN_SECONDS + " seconds";
        if (s.seconds > MAX_SECONDS) return "longer than 24 hours (the macro was probably left running or the clock jumped)";
        boolean any = false;
        for (double v : s.metrics.values()) {
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return "the numbers are invalid";
            if (v > 0) any = true;
        }
        if (!any) return "nothing was counted";
        double med = medianRate(history, 5);
        if (!Double.isNaN(med) && s.mainPerHour() > med * 10) {
            return String.format(java.util.Locale.ROOT, "%.0f %s per hour is more than 10x your usual %.0f", s.mainPerHour(), s.main.toLowerCase(java.util.Locale.ROOT), med);
        }
        return null;
    }

    /** Why the session looks doubtful, or null when it looks normal. */
    public static String soft(Session s, List<Session> history) {
        if (s.mainValue() <= 0) return "no " + s.main.toLowerCase(java.util.Locale.ROOT) + " counted";
        double med = medianRate(history, 3);
        if (Double.isNaN(med)) return null;
        double r = s.mainPerHour();
        if (r > med * 3) return String.format(java.util.Locale.ROOT, "much faster than usual (%.1fx)", r / med);
        if (r < med * 0.2) return String.format(java.util.Locale.ROOT, "much slower than usual (%.1fx)", r / med);
        return null;
    }
}
