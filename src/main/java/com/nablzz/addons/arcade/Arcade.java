package com.nablzz.addons.arcade;

import com.mojang.blaze3d.platform.InputConstants;
import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.Notify;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * The secret arcade: while a macro is running, the arrow keys Up Up Down Down Left Right Left Right (each within 2 s of
 * the last) open a menu of mini games. The macro keeps running behind it: the screens implement {@link Marker}, which
 * the macros treat as "not a pause".
 */
public final class Arcade {
    private Arcade() {}

    /** Marker for the arcade screens (the macros do not pause while one is open). */
    public interface Marker {}

    private static final int U = 0, D = 1, L = 2, R = 3;
    private static final int[] CODE = {U, U, D, D, L, R, L, R};
    private static final KeyMapping[] KEYS = new KeyMapping[4];
    private static int pos;
    private static long lastAt;
    // restoring the screen after a lobby swap (the world change closes every screen)
    private static java.util.function.Supplier<Screen> restore;
    private static Object restoreLevel;
    private static long restoreAt;

    public static boolean isArcade(Screen s) {
        return s instanceof Marker;
    }

    public static void init() {
        int[] glfw = {GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT};
        String[] names = {"up", "down", "left", "right"};
        for (int i = 0; i < 4; i++) {
            KEYS[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nablzzaddons.arcade_" + names[i],
                    InputConstants.Type.KEYSYM, glfw[i], KeyMapping.Category.MISC));
        }
        ClientTickEvents.START_CLIENT_TICK.register(Arcade::tick);
    }

    private static boolean anyRunning() {
        for (Feature f : Features.all()) if (f.isRunning()) return true;
        return false;
    }

    /** The player closed the screen on purpose: do not bring it back after the next lobby swap. */
    public static void forget() {
        restore = null;
        restoreAt = 0;
    }

    private static void track(Minecraft mc) {
        Screen s = mc.screen;
        if (s instanceof GameScreen gs) {
            final Game game = gs.game();
            restore = () -> new GameScreen(game);
            restoreLevel = mc.level;
        } else if (s instanceof ArcadeMenu) {
            restore = ArcadeMenu::new;
            restoreLevel = mc.level;
        } else if (s instanceof com.nablzz.addons.NablzzScreen ns) {
            final String module = ns.currentModule();
            restore = () -> new com.nablzz.addons.NablzzScreen(module);
            restoreLevel = mc.level;
        } else if (s == null && restore != null) {
            if (!anyRunning()) {
                forget();
            } else if (mc.player != null && mc.level != null && mc.level != restoreLevel) { // a new world: the swap closed it
                long now = System.currentTimeMillis();
                if (restoreAt == 0) {
                    restoreAt = now + 1500;
                } else if (now >= restoreAt) {
                    Screen back = restore.get();
                    restoreAt = 0;
                    mc.setScreen(back);
                }
            }
        }
    }

    private static void tick(Minecraft mc) {
        track(mc);
        boolean active = mc.player != null && mc.screen == null && anyRunning();
        for (int k = 0; k < 4; k++) {
            while (KEYS[k].consumeClick()) {
                if (!active) {
                    pos = 0;
                    continue;
                }
                press(mc, k);
            }
        }
    }

    private static void press(Minecraft mc, int k) {
        long now = System.currentTimeMillis();
        if (now - lastAt > 2000) pos = 0;
        lastAt = now;
        if (k == CODE[pos]) {
            pos++;
        } else if (pos == 2 && k == U) {
            // Up Up Up: the last two still count
        } else {
            pos = k == CODE[0] ? 1 : 0;
        }
        if (pos == CODE.length) {
            pos = 0;
            Notify.msg("Arcade opened. Your macro keeps running.");
            mc.setScreen(new ArcadeMenu());
        }
    }
}
