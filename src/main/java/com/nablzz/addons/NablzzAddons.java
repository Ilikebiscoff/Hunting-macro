package com.nablzz.addons;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nablzz.addons.hunting.HuntController;
import com.nablzz.addons.hunting.HuntingMacroMod;
import com.nablzz.addons.mushroom.GlowingMushroomMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Nablzz Addons: the diamond reserve hunting macro and the glowing mushroom macro in one mod, with one settings
 * screen (Right Shift, /nablzz, /hunt or /glowing) and one updater. Only one macro runs at a time.
 */
public class NablzzAddons implements ClientModInitializer {
    public static final int HUNT = 0, MUSHROOM = 1, GENERAL = 2;

    private static KeyMapping openKey;
    private static volatile int pendingGui = -1;
    private static int lastModule = HUNT;

    /** Opens the settings screen on the given module as soon as it is possible (a tick later, after chat closes). */
    public static void openGui(int module) {
        pendingGui = module;
    }

    static void rememberModule(int module) {
        lastModule = module;
    }

    @Override
    public void onInitializeClient() {
        Prefs.init();
        new HuntingMacroMod().onInitializeClient();
        new GlowingMushroomMod().onInitializeClient();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nablzzaddons.open_gui",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Updater.onJoin());

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (pendingGui < 0) pendingGui = lastModule;
            }
            if (pendingGui >= 0) {
                if (mc.screen == null && mc.player != null) {
                    int m = pendingGui;
                    pendingGui = -1;
                    mc.setScreen(new NablzzScreen(m));
                } else if (mc.screen != null && !(mc.screen instanceof ChatScreen)) {
                    pendingGui = -1; // some other screen is open: ignore
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("nablzz");
            root.executes(c -> {
                openGui(lastModule);
                return 1;
            });
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(c -> {
                Minecraft mc = Minecraft.getInstance();
                HuntingMacroMod.controller().stop(mc);
                GlowingMushroomMod.failsafe().end(mc);
                Notify.msg("Everything stopped.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("version").executes(c -> {
                Notify.msg(Updater.versionInfo());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("update")
                    .executes(c -> {
                        Updater.run(false, true);
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("check").executes(c -> {
                        Updater.run(false, false);
                        return 1;
                    })));
            dispatcher.register(root);
        });
    }

    // ---- only one macro at a time ------------------------------------------------------------------------

    /** The hunting macro is about to start: the mushroom macro (and its fail-safe) must let go of the controls. */
    public static void huntingStarting(Minecraft mc) {
        var f = GlowingMushroomMod.failsafe();
        if (f != null && (f.wanted() || GlowingMushroomMod.controller().isRunning())) f.end(mc);
    }

    /** The mushroom macro is about to start. */
    public static void mushroomStarting(Minecraft mc) {
        HuntController h = HuntingMacroMod.controller();
        if (h != null && h.isRunning()) h.stop(mc);
    }
}
