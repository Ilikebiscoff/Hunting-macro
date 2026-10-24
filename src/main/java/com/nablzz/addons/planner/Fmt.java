package com.nablzz.addons.planner;

import java.util.Locale;

/** Number formatting for the tables. */
public final class Fmt {
    private Fmt() {}

    public static String coins(double v) {
        if (Double.isNaN(v)) return "-";
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9) return sign + String.format(Locale.ROOT, "%.2fB", a / 1e9);
        if (a >= 1e6) return sign + String.format(Locale.ROOT, "%.2fM", a / 1e6);
        if (a >= 1e4) return sign + String.format(Locale.ROOT, "%.1fk", a / 1e3);
        if (a >= 1e3) return sign + String.format(Locale.ROOT, "%,.0f", a);
        return sign + String.format(Locale.ROOT, "%.0f", a);
    }

    public static String signed(double v) {
        return (v > 0 ? "+" : "") + coins(v);
    }

    public static String pct(double f) {
        return String.format(Locale.ROOT, "%.0f%%", f * 100);
    }

    public static String volume(long v) {
        if (v == Long.MAX_VALUE) return "AH";
        return coins(v);
    }

    public static String hours(double h) {
        if (Double.isNaN(h)) return "-";
        if (Double.isInfinite(h)) return "never";
        if (h < 1.0 / 60) return "<1 min";
        if (h < 1) return String.format(Locale.ROOT, "%.0f min", h * 60);
        if (h < 48) return String.format(Locale.ROOT, "%.1f h", h);
        return String.format(Locale.ROOT, "%.1f days", h / 24);
    }

    /** "WITHER_ROSE" -> "Wither Rose" (when the API has no better name). */
    public static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("[_:]")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s / 60) % 60) + " min";
    }
}
