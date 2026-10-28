package com.nablzz.addons;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nablzz.addons.fusion.FusionMachine;
import com.nablzz.addons.minerzombie.MinerZombieHunting;
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
    /** Id of the General module (not a feature) for {@link #openGui}. */
    public static final String GENERAL = "general";

    private static KeyMapping openKey;
    private static volatile String pendingGui;
    private static String lastModule = GENERAL;

    /** Opens the settings screen on the module with this id as soon as it is possible (a tick later, after chat closes). */
    public static void openGui(String moduleId) {
        pendingGui = moduleId;
    }

    static void rememberModule(String moduleId) {
        lastModule = moduleId;
    }

    @Override
    public void onInitializeClient() {
        Prefs.init();
        Report.init();
        Updater.cleanOld();
        // Add new features here: they get their own module in the settings screen automatically.
        Features.register(new MinerZombieHunting());
        Features.register(new GlowingMushroomMod());
        Features.register(new FusionMachine());
        Features.register(new com.nablzz.addons.mudworm.MudwormMacro());
        Features.register(new com.nablzz.addons.planner.ProfitPlanner());
        Features.register(new com.nablzz.addons.flipper.FlipperModule());
        Features.register(new com.nablzz.addons.stats.StatsModule());
        Features.register(new AvoidModule());
        com.nablzz.addons.stats.SessionLog.init();
        com.nablzz.addons.arcade.Arcade.init();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nablzzaddons.open_gui",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Updater.onJoin());

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (pendingGui == null) pendingGui = lastModule;
            }
            if (pendingGui != null) {
                if (mc.screen == null && mc.player != null) {
                    String m = pendingGui;
                    pendingGui = null;
                    mc.setScreen(new NablzzScreen(m));
                } else if (mc.screen != null && !(mc.screen instanceof ChatScreen)) {
                    pendingGui = null; // some other screen is open: ignore
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
                Features.stopAll(mc);
                Notify.msg("Everything stopped.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("report").executes(c -> {
                Report.run(Minecraft.getInstance());
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
}
