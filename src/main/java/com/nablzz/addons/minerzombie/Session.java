package com.nablzz.addons.minerzombie;

/** Running time of the macro this game session (only counts while it is running), for the HUD and per-hour numbers. */
public final class Session {
    private Session() {}

    public static volatile long runMs;
    private static long last;

    public static void tick(boolean running) {
        long now = System.currentTimeMillis();
        if (running && last != 0) runMs += Math.min(1000, now - last);
        last = now;
    }

    public static void reset() {
        runMs = 0;
    }

    public static String clock() {
        long s = runMs / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    public static double perHour(double count) {
        return runMs < 20_000 ? 0 : count / (runMs / 3_600_000.0);
    }
}
