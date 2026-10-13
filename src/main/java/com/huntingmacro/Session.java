package com.huntingmacro;

/** Running time of the macro this game session (only counts while it is running), for the HUD and per-hour numbers. */
final class Session {
    private Session() {}

    static volatile long runMs;
    private static long last;

    static void tick(boolean running) {
        long now = System.currentTimeMillis();
        if (running && last != 0) runMs += Math.min(1000, now - last);
        last = now;
    }

    static void reset() {
        runMs = 0;
    }

    static String clock() {
        long s = runMs / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static double perHour(double count) {
        return runMs < 20_000 ? 0 : count / (runMs / 3_600_000.0);
    }
}
