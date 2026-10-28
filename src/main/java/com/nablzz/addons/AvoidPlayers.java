package com.nablzz.addons;

import com.nablzz.addons.mudworm.Macro;
import com.nablzz.addons.mushroom.AvoidList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The avoid list for every macro: names of players you don't want to share a lobby with (friends, other macroers).
 * Glowing Mushrooms checks it itself (its fail-safe); this class adds the same check for the other lobby-bound macros
 * while they run: Galatea Mudworm swaps lobby, Miner Zombie Hunting stops.
 */
public final class AvoidPlayers {
    private AvoidPlayers() {}

    /** Set in the Avoid list module / by /nablzz avoid. */
    public static volatile boolean enabled = true;

    public static final AvoidList LIST = load();
    private static int ticks;

    private static AvoidList load() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path now = dir.resolve("nablz_avoid.json"), old = dir.resolve("glowingmushroom_avoid.json");
        boolean migrate = !Files.exists(now) && Files.exists(old); // the list used to belong to Glowing Mushrooms only
        AvoidList l = new AvoidList(now);
        if (migrate) for (String n : new AvoidList(old).list()) l.add(n);
        return l;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AvoidPlayers::tick);
    }

    private static void tick(Minecraft mc) {
        if (++ticks < 20) return; // once a second is plenty
        ticks = 0;
        if (!enabled || mc.player == null || LIST.isEmpty()) return;
        String who = LIST.scan(mc);
        Macro m = Macro.INSTANCE;
        if (who == null) {
            m.avoidClear();
            return;
        }
        Feature hunt = Features.byId("minerzombie");
        if (hunt != null && hunt.isRunning()) {
            Notify.msg(who + " is in this lobby - Miner Zombie Hunting stopped. Change lobby, then start it again.");
            hunt.stop(mc);
        }
        if (m.running()) m.avoidSwap(mc, who);
    }
}
