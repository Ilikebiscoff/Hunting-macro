package com.nablzz.addons.fusion;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Notify;
import com.nablzz.addons.Ui;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Fusion Box macro: repeats the previous fusion and confirms it until the Hunting Box runs out of shards. */
public class FusionFeature implements Feature {
    private final FusionMacro macro = new FusionMacro();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("nablzz_fusion.properties");

    @Override public String id() { return "fusion"; }
    @Override public String name() { return "Fusion Macro"; }
    @Override public boolean isRunning() { return macro.isRunning(); }
    @Override public String status() { return macro.isRunning() ? macro.state : "Stopped"; }

    @Override
    public void init() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(macro::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("fusion");
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start").executes(c -> {
                start(Minecraft.getInstance());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(c -> {
                stop(Minecraft.getInstance());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("delay")
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("ms", IntegerArgumentType.integer(50, 5000))
                            .executes(c -> {
                                macro.baseDelayMs = IntegerArgumentType.getInteger(c, "ms");
                                saveSettings();
                                Notify.msg("Fusion click delay ~" + macro.baseDelayMs + " ms.");
                                return 1;
                            })));
            dispatcher.register(root);
        });
    }

    @Override
    public void start(Minecraft mc) {
        Features.starting(id(), mc);
        macro.start();
    }

    @Override
    public void stop(Minecraft mc) {
        if (macro.isRunning()) macro.stop("stopped");
    }

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        ui.tab("Dashboard", "D");
        ui.section("Overview");
        ui.hero(() -> macro.isRunning() ? macro.state : "Press Start, close this screen, then open the Fusion Box.",
                "Fusion Box auto-repeat");
        ui.tiles(() -> new String[][]{{"" + macro.fusions, "Fusions done"}});
        ui.info("Status", "What the macro is doing right now.", this::status, Ui.MUTED);
        ui.note("Do one fusion by hand first so 'Repeat Previous Fusion' exists, then open the Fusion Box.");

        ui.tab("Settings", "S");
        ui.section("Fusing");
        ui.islider("Click delay", "Base time between clicks (a random extra up to 50% is added).", 50, 2000,
                () -> macro.baseDelayMs, v -> macro.baseDelayMs = v, " ms");
        ui.islider("Stop below shards", "Stops when a shard stack in the Hunting Box drops under this.", 1, 100,
                () -> macro.minShards, v -> macro.minShards = v, "");
        ui.islider("Max fusions", "Stops after this many fusions. 0 = unlimited.", 0, 1000,
                () -> macro.maxFusions, v -> macro.maxFusions = v, "");
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            macro.baseDelayMs = Integer.parseInt(p.getProperty("delay", "450"));
            macro.minShards = Integer.parseInt(p.getProperty("minShards", "5"));
            macro.maxFusions = Integer.parseInt(p.getProperty("maxFusions", "0"));
        } catch (IOException | NumberFormatException ignored) {
            // keep defaults
        }
    }

    @Override
    public void saveSettings() {
        Properties p = new Properties();
        p.setProperty("delay", "" + macro.baseDelayMs);
        p.setProperty("minShards", "" + macro.minShards);
        p.setProperty("maxFusions", "" + macro.maxFusions);
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Nablzz fusion macro");
        } catch (IOException ignored) {
            // not saved
        }
    }
}
