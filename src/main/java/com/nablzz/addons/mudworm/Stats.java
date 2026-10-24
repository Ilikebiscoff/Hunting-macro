package com.nablzz.addons.mudworm;

import com.nablzz.addons.planner.Bazaar;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the current Mudworm run earned: floor drops by item with an estimated coin value (the Bazaar instant-sell price at the
 * moment of the drop, after tax). The numbers are handed to the session log (see MudwormMacro.sessionCounters), which saves
 * them when the macro stops.
 */
final class Stats {
    private Stats() {}

    static final class Drop {
        long count, events, unpriced;
        double coins;
    }

    static final class Session {
        int spots, floorDrops, warps;
        double coins;
        final Map<String, Drop> drops = new LinkedHashMap<>();
    }

    private static final Pattern DROP = Pattern.compile("You found (.+?)(?:\\s+x([\\d,]+))?\\s+on the ground", Pattern.CASE_INSENSITIVE);

    private static Session cur = new Session();

    static synchronized void begin() {
        cur = new Session();
    }

    static synchronized void spot() {
        cur.spots++;
    }

    static synchronized void warp() {
        cur.warps++;
    }

    /** The run's numbers (kept after the macro stopped, until the next start). */
    static synchronized Session live() {
        return cur;
    }

    /** The HUD reset. */
    static synchronized void resetCurrent() {
        cur = new Session();
    }

    /** A "FLOOR DROP! You found Fig Log x208 on the ground!" line. */
    static synchronized void chat(String line) {
        if (!line.contains("FLOOR DROP")) return;
        cur.floorDrops++;
        Matcher m = DROP.matcher(line);
        if (!m.find()) return;
        String item = m.group(1).trim();
        long n = 1;
        if (m.group(2) != null) {
            try {
                n = Long.parseLong(m.group(2).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // count stays 1
            }
        }
        Drop d = cur.drops.computeIfAbsent(item, k -> new Drop());
        d.count += n;
        d.events++;
        double v = Bazaar.instaSellValue(item);
        if (v > 0) {
            d.coins += v * n;
            cur.coins += v * n;
        } else {
            d.unpriced++;
        }
    }
}
