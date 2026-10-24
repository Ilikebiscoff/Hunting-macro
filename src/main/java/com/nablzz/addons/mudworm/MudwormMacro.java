package com.nablzz.addons.mudworm;

import com.nablzz.addons.Feature;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.nablzz.addons.mudworm.Chat;
import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.Dev;
import com.nablzz.addons.mudworm.Hud;
import com.nablzz.addons.mudworm.Macro;
import com.nablzz.addons.mudworm.SpotScanner;
import com.nablzz.addons.mudworm.path.Pathfinder;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class MudwormMacro implements Feature {
    private static final Macro M = Macro.INSTANCE;

    @Override
    public void init() {
        Config.load();
        SpotScanner.loadSites();
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping((KeyMapping)new KeyMapping("key.nablzzaddons.mudworm_toggle", InputConstants.Type.KEYSYM, 75, KeyMapping.Category.MISC));
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) {
                if (mc.player == null || mc.screen != null) continue;
                M.toggle(mc);
            }
            SpotScanner.tick(mc);
            M.tick(mc);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> M.onChat(message.getString()));
        HudElementRegistry.addLast((Identifier)Identifier.fromNamespaceAndPath((String)"mudwormmacro", (String)"status"), Hud::render);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (M.running() && mc.player != null && (mc.screen == null || com.nablzz.addons.arcade.Arcade.isArcade(mc.screen))) {
                MudwormMacro.M.aim.frame(mc, mc.player);
            }
            if (Config.esp) {
                MudwormMacro.drawBoxes();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder root = (LiteralArgumentBuilder)MudwormMacro.lit("mudworm").executes(c -> MudwormMacro.help());
            root.then(MudwormMacro.lit("gui").executes(c -> {
                NablzzAddons.openGui(id());
                return 1;
            }));
            root.then(MudwormMacro.lit("start").executes(c -> MudwormMacro.run(mc -> M.start((Minecraft)mc))));
            root.then(MudwormMacro.lit("stop").executes(c -> MudwormMacro.run(mc -> M.stop((Minecraft)mc, null))));
            root.then(MudwormMacro.lit("toggle").executes(c -> MudwormMacro.run(M::toggle)));
            root.then(MudwormMacro.lit("help").executes(c -> MudwormMacro.help()));
            root.then(MudwormMacro.lit("dev").executes(c -> MudwormMacro.run(Dev::run)));
            root.then(MudwormMacro.lit("status").executes(c -> {
                Chat.msg(M.status() + " | spots known: " + SpotScanner.SPOTS.size() + " | sites: " + SpotScanner.SITES.size() + " | done: " + MudwormMacro.M.spotsDone + " | warps: " + MudwormMacro.M.warps);
                return 1;
            }));
            root.then(MudwormMacro.lit("reset").executes(c -> {
                M.resetStats();
                Chat.msg("Status panel reset: time, spots, drops and etherwarps are back to 0.");
                return 1;
            }));
            root.then(MudwormMacro.lit("spots").executes(c -> {
                if (SpotScanner.SPOTS.isEmpty()) {
                    Chat.msg("No spots in sight.");
                }
                for (BlockPos b : SpotScanner.SPOTS.keySet()) {
                    Chat.msg("Spot at " + b.toShortString());
                }
                return 1;
            }));
            root.then(MudwormMacro.lit("esp").executes(c -> {
                Config.esp = !Config.esp;
                return MudwormMacro.flag("Boxes", Config.esp);
            }));
            root.then(MudwormMacro.lit("hud").executes(c -> {
                Config.hud = !Config.hud;
                return MudwormMacro.flag("Status panel", Config.hud);
            }));
            root.then(MudwormMacro.lit("debug").executes(c -> {
                Config.debug = !Config.debug;
                return MudwormMacro.flag("Debug messages", Config.debug);
            }));
            root.then(MudwormMacro.lit("strict").executes(c -> {
                Config.strictRay = !Config.strictRay;
                return MudwormMacro.flag("Strict ray (only air lets the etherwarp through)", Config.strictRay);
            }));
            root.then(MudwormMacro.lit("stuckwarp").executes(c -> {
                Config.unstuckWarp = !Config.unstuckWarp;
                return MudwormMacro.flag("Warp when stuck", Config.unstuckWarp);
            }));
            root.then(MudwormMacro.lit("stuckcmd").then(RequiredArgumentBuilder.argument((String)"command", (ArgumentType)StringArgumentType.greedyString()).executes(c -> {
                Config.stuckCommand = StringArgumentType.getString((CommandContext)c, (String)"command").trim();
                Config.save();
                Chat.msg("When stuck for " + Config.stuckSeconds + " s it runs: /" + Config.stuckCommand);
                return 1;
            })));
            root.then(MudwormMacro.lit("stucktime").then(RequiredArgumentBuilder.argument((String)"seconds", (ArgumentType)IntegerArgumentType.integer((int)1, (int)60)).executes(c -> {
                Config.stuckSeconds = IntegerArgumentType.getInteger((CommandContext)c, (String)"seconds");
                Config.save();
                Chat.msg("Stuck timer: " + Config.stuckSeconds + " s.");
                return 1;
            })));
            root.then(MudwormMacro.lit("failsafe").executes(c -> {
                Config.stopOnForeignTeleport = !Config.stopOnForeignTeleport;
                return MudwormMacro.flag("Stop when teleported by something else", Config.stopOnForeignTeleport);
            }));
            root.then(MudwormMacro.lit("range").then(RequiredArgumentBuilder.argument((String)"blocks", (ArgumentType)IntegerArgumentType.integer((int)10, (int)61)).executes(c -> {
                Config.baseRange = IntegerArgumentType.getInteger((CommandContext)c, (String)"blocks");
                Config.save();
                Chat.msg("Base etherwarp range: " + Config.baseRange + " (+ tuners on the item).");
                return 1;
            })));
            root.then(MudwormMacro.lit("ethercost").then(RequiredArgumentBuilder.argument((String)"blocks", (ArgumentType)DoubleArgumentType.doubleArg((double)1.0, (double)100.0)).executes(c -> {
                Config.etherCost = DoubleArgumentType.getDouble((CommandContext)c, (String)"blocks");
                Config.save();
                Chat.msg("Hop cost: " + Config.etherCost);
                return 1;
            })));
            root.then(((LiteralArgumentBuilder)MudwormMacro.lit("area").then(MudwormMacro.lit("marsh").executes(c -> {
                Config.areaMinX = -708;
                Config.areaMaxX = -603;
                Config.areaMinZ = -46;
                Config.areaMaxZ = 77;
                Config.save();
                Chat.msg("Patrol area: Moonglade Marsh.");
                return 1;
            }))).then(MudwormMacro.lit("here").then(RequiredArgumentBuilder.argument((String)"radius", (ArgumentType)IntegerArgumentType.integer((int)10, (int)400)).executes(c -> {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) {
                    return 0;
                }
                int r = IntegerArgumentType.getInteger((CommandContext)c, (String)"radius");
                Config.areaMinX = p.getBlockX() - r;
                Config.areaMaxX = p.getBlockX() + r;
                Config.areaMinZ = p.getBlockZ() - r;
                Config.areaMaxZ = p.getBlockZ() + r;
                Config.save();
                Chat.msg("Patrol area: " + 2 * r + "x" + 2 * r + " around you.");
                return 1;
            }))));
            root.then(((LiteralArgumentBuilder)MudwormMacro.lit("blacklist").executes(c -> {
                Chat.msg("Blacklisted spots this lobby: " + SpotScanner.BLACKLIST.size() + " " + String.valueOf(SpotScanner.BLACKLIST));
                return 1;
            })).then(MudwormMacro.lit("clear").executes(c -> {
                SpotScanner.BLACKLIST.clear();
                Chat.msg("Blacklist cleared.");
                return 1;
            })));
            root.then(((LiteralArgumentBuilder)((LiteralArgumentBuilder)MudwormMacro.lit("axe").executes(c -> {
                LocalPlayer pl = Minecraft.getInstance().player;
                if (pl == null) {
                    return 0;
                }
                ItemStack held = pl.getInventory().getItem(pl.getInventory().getSelectedSlot());
                if (held.isEmpty()) {
                    Chat.msg("Hold the axe in your hand first, then run /mudworm axe.");
                    return 0;
                }
                if (pl.getInventory().getSelectedSlot() == Macro.aotvSlot(pl)) {
                    Chat.msg("That is the Aspect of the Void. Hold the axe you want to mine with, then run /mudworm axe.");
                    return 0;
                }
                Config.axeName = held.getHoverName().getString().replaceAll("\u00a7.", "").trim();
                Config.useAxe = true;
                Config.save();
                Chat.msg("Axe on: using '" + Config.axeName + "' (hotbar slot " + (pl.getInventory().getSelectedSlot() + 1) + ") to mine spots. /mudworm axe off turns it off, /mudworm axe name <text> sets a shorter name.");
                return 1;
            })).then(MudwormMacro.lit("off").executes(c -> {
                Config.useAxe = false;
                Config.save();
                Chat.msg("Axe off.");
                return 1;
            }))).then(MudwormMacro.lit("name").then(RequiredArgumentBuilder.argument((String)"text", (ArgumentType)StringArgumentType.greedyString()).executes(c -> {
                Config.axeName = StringArgumentType.getString((CommandContext)c, (String)"text").trim();
                Config.save();
                LocalPlayer pl = Minecraft.getInstance().player;
                Chat.msg("Axe name set to '" + Config.axeName + "' (any hotbar item whose name contains it, not case sensitive)" + (pl != null && Macro.slotByName(pl, Config.axeName) < 0 ? " - not found in the hotbar right now." : "."));
                return 1;
            }))));
            root.then(MudwormMacro.lit("scoottime").then(RequiredArgumentBuilder.argument((String)"seconds", (ArgumentType)DoubleArgumentType.doubleArg((double)0.2, (double)10.0)).executes(c -> {
                Config.scootSeconds = DoubleArgumentType.getDouble((CommandContext)c, (String)"seconds");
                Config.save();
                Chat.msg("One scoot lasts at most " + Config.scootSeconds + " s, then the not-moving timer starts again.");
                return 1;
            })));
            root.then(((LiteralArgumentBuilder)MudwormMacro.lit("blatant").executes(c -> {
                Chat.msg("Blatant mode is " + (Config.blatant ? "ON" : "off") + ". /mudworm blatant on  opens the mini game that unlocks it; /mudworm blatant off  turns it off.");
                return 1;
            })).then(MudwormMacro.lit("on").executes(c -> {
                openBlatantGame();
                return 1;
            })).then(MudwormMacro.lit("off").executes(c -> {
                Config.blatant = false;
                Chat.msg("Blatant mode off.");
                return 1;
            })));
            root.then(MudwormMacro.lit("maxdist").then(RequiredArgumentBuilder.argument((String)"blocks", (ArgumentType)DoubleArgumentType.doubleArg((double)10.0, (double)400.0)).executes(c -> {
                Config.maxTargetDistance = DoubleArgumentType.getDouble((CommandContext)c, (String)"blocks");
                Config.save();
                Chat.msg("Only spots within " + Config.maxTargetDistance + " blocks are targeted; farther ones are ignored.");
                return 1;
            })));
            root.then(MudwormMacro.lit("minspots").then(RequiredArgumentBuilder.argument((String)"count", (ArgumentType)IntegerArgumentType.integer((int)0, (int)50)).executes(c -> {
                Config.minSpots = IntegerArgumentType.getInteger((CommandContext)c, (String)"count");
                Config.save();
                Chat.msg((String)(Config.minSpots <= 0 ? "Lobby refresh is off." : "Fewer than " + Config.minSpots + " live spots in the lobby: it swaps lobby (/" + Config.swapFirst + " then /" + Config.swapSecond + ") (cooldown " + Config.refreshCooldownSeconds + " s between switches)."));
                return 1;
            })));
            root.then(MudwormMacro.lit("refreshcooldown").then(RequiredArgumentBuilder.argument((String)"seconds", (ArgumentType)IntegerArgumentType.integer((int)5, (int)600)).executes(c -> {
                Config.refreshCooldownSeconds = IntegerArgumentType.getInteger((CommandContext)c, (String)"seconds");
                Config.save();
                Chat.msg("At least " + Config.refreshCooldownSeconds + " s between lobby switches.");
                return 1;
            })));
            root.then(MudwormMacro.lit("scoot").then(RequiredArgumentBuilder.argument((String)"blocks", (ArgumentType)DoubleArgumentType.doubleArg((double)0.0, (double)30.0)).executes(c -> {
                Config.scootRange = DoubleArgumentType.getDouble((CommandContext)c, (String)"blocks");
                Config.save();
                Chat.msg((String)(Config.scootRange <= 0.0 ? "Scoot is off." : "Scoot: spots within " + Config.scootRange + " blocks are walked to with W/A/S/D (no head turning) instead of teleported to."));
                return 1;
            })));
            root.then(MudwormMacro.lit("hopdelay").then(RequiredArgumentBuilder.argument((String)"ms", (ArgumentType)IntegerArgumentType.integer((int)0, (int)5000)).executes(c -> {
                Config.hopDelayMs = IntegerArgumentType.getInteger((CommandContext)c, (String)"ms");
                Config.save();
                Chat.msg("Extra wait before every etherwarp: " + Config.hopDelayMs + " ms.");
                return 1;
            })));
            root.then(MudwormMacro.lit("yband").then(RequiredArgumentBuilder.argument((String)"below", (ArgumentType)IntegerArgumentType.integer((int)0, (int)200)).then(RequiredArgumentBuilder.argument((String)"above", (ArgumentType)IntegerArgumentType.integer((int)0, (int)200)).executes(c -> {
                Config.landBelow = IntegerArgumentType.getInteger((CommandContext)c, (String)"below");
                Config.landAbove = IntegerArgumentType.getInteger((CommandContext)c, (String)"above");
                Config.save();
                Chat.msg("Hops may land " + Config.landBelow + " blocks below to " + Config.landAbove + " above the corridor between you and the spot.");
                return 1;
            }))));
            root.then(MudwormMacro.lit("sites").then(MudwormMacro.lit("clear").executes(c -> {
                SpotScanner.clearSites();
                Chat.msg("Forgot every remembered spot site.");
                return 1;
            })));
            root.then(MudwormMacro.lit("reload").executes(c -> {
                Config.load();
                Chat.msg("Settings reloaded.");
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    /** Opens the mini game on the next tick (a command typed in chat closes the chat screen right after it ran). */
    static void openBlatantGame() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new BlatantGame()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return LiteralArgumentBuilder.literal((String)name);
    }

    private static int run(Consumer<Minecraft> action) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> action.accept(mc));
        return 1;
    }

    private static int flag(String what, boolean on) {
        Config.save();
        Chat.msg(what + ": " + (on ? "on" : "off"));
        return 1;
    }

    private static int help() {
        for (String s : List.of("/mudworm start | stop | toggle (key K)", "/mudworm gui (opens the settings) | status | spots | reset (zero the status panel) | dev (copies a diagnostic report)", "/mudworm esp | hud | debug | strict | failsafe", "/mudworm stuckwarp | stuckcmd <command> | stucktime <seconds>", "/mudworm range <10-61> | ethercost <blocks>", "/mudworm area marsh | area here <radius>", "/mudworm axe (takes the item in your hand) | axe off | axe name <text>  (use an axe for mining spots)", "/mudworm minspots <n> | refreshcooldown <seconds>  (fewer than n spots in the lobby: swap lobby, 0 = off)", "/mudworm maxdist <blocks>  (spots farther than this are never targeted, default 120)", "/mudworm scoottime <seconds> | scoot <blocks>  (walk to spots this close with WASD, 0 = off)", "/mudworm hopdelay <ms>  (extra wait before each teleport, default 250)", "/mudworm yband <below> <above>  (how far hops may stray up/down from the way to the spot)", "/mudworm blacklist | blacklist clear", "/mudworm sites clear | reload")) {
            Chat.msg(s);
        }
        return 1;
    }

    private static void drawBoxes() {
        Pathfinder.Goal g = M.goal();
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            boolean cur = M.running() && g != null && s.equals((Object)g.spot());
            boolean bad = SpotScanner.BLACKLIST.contains(s);
            boolean off = !M.isExposed(s);
            int stroke = bad ? -49088 : (off ? -8355712 : (cur ? -8144 : -13566112));
            int fill = bad ? 822034496 : (off ? 0x20808080 : (cur ? 1358946352 : 1076952928));
            Gizmos.cuboid((BlockPos)s, (GizmoStyle)GizmoStyle.strokeAndFill((int)stroke, (float)(cur ? 2.5f : 2.0f), (int)fill)).setAlwaysOnTop();
        }
        if (!M.running()) {
            return;
        }
        List<Pathfinder.Step> steps = M.steps();
        Vec3 prev = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.position();
        for (int i = M.stepIdx(); i < steps.size() && prev != null; ++i) {
            Pathfinder.Step st = steps.get(i);
            Vec3 at = new Vec3((double)st.feet().getX() + 0.5, (double)st.feet().getY() + 0.1, (double)st.feet().getZ() + 0.5);
            if (st.shot() != null) {
                Gizmos.line((Vec3)prev.add(0.0, 1.2, 0.0), (Vec3)at, (int)-48897, (float)2.0f).setAlwaysOnTop();
                Gizmos.cuboid((BlockPos)st.shot().target(), (GizmoStyle)GizmoStyle.stroke((int)-48897, (float)2.0f)).setAlwaysOnTop();
            } else {
                Gizmos.line((Vec3)prev.add(0.0, 0.1, 0.0), (Vec3)at, (int)-13573889, (float)3.0f).setAlwaysOnTop();
            }
            prev = at;
        }
    }

    // ---- Feature (Nablzz Addons) -----------------------------------------------------------------------------

    @Override
    public String id() {
        return "mudworm";
    }

    @Override
    public String name() {
        return "Galatea Mudworm";
    }

    @Override
    public boolean isRunning() {
        return M.running();
    }

    @Override
    public String status() {
        return M.running() ? M.status() : "Stopped";
    }

    @Override
    public void start(Minecraft mc) {
        if (mc.player == null) return;
        com.nablzz.addons.Features.starting(id(), mc);
        M.start(mc);
    }

    @Override
    public void stop(Minecraft mc) {
        M.stop(mc, null);
    }

    @Override
    public void saveSettings() {
        Config.save();
    }

    @Override
    public void report(Consumer<String> out) {
        Minecraft mc = Minecraft.getInstance();
        String text;
        try {
            text = Dev.build(mc);
        } catch (Throwable t) {
            text = "report failed: " + t;
        }
        for (String line : text.split("\n")) out.accept(line);
    }

    private static String clock(long ms) {
        long s = ms / 1000L;
        return String.format("%02d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L);
    }

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        ui.tab("Dashboard", "D");
        ui.section("Overview");
        ui.hero(() -> M.running() ? M.status() + "  -  " + SpotScanner.SPOTS.size() + " spots in sight"
                : "Press Start (or K in game). Hold an Aspect of the Void with Ether Transmission in the hotbar.", "Galatea mudworm spots");
        ui.tiles(() -> new String[][]{
                {"" + M.spotsDone, "Spots done"},
                {"" + M.floorDrops, "Floor drops"},
                {"" + M.shards, "Mudworm shards"},
                {"" + M.warps, "Etherwarps"},
                {"" + SpotScanner.SPOTS.size(), "Spots in sight"},
                {clock(M.totalMs()), "Run time"},
        });
        ui.note("Hypixel does not allow macros: using this can get your account banned.");
        ui.confirmButton("Reset the numbers", "Sets the time, spots done, floor drops, shards and etherwarps (status panel and these tiles) back to zero. Same as /mudworm reset.", "Reset", M::resetStats);
        ui.button("Copy diagnostic report", "Copies what the macro sees (hotbar, spots, terrain, the planner) to the clipboard and saves it in the config folder.", "Copy", false,
                () -> Dev.run(Minecraft.getInstance()));

        ui.tab("Etherwarp", "E");
        ui.section("Range and timing");
        ui.islider("Base range", "Etherwarp range without upgrades (the tuners on the item add to it).", 10, 61, () -> Config.baseRange, v -> Config.baseRange = v, " blocks");
        ui.dslider("Range margin", "Blocks kept free at the far end so a hop is never planned at the very limit.", 0, 5, 0.5, () -> Config.rangeMargin, v -> Config.rangeMargin = v, "%.1f");
        ui.dslider("Hop cost", "How many blocks of walking one hop counts for when planning (higher = walks more).", 1, 60, 1, () -> Config.etherCost, v -> Config.etherCost = v, "%.0f blocks");
        ui.islider("Wait before each hop", "Extra pause before every etherwarp (ms).", 0, 3000, () -> Config.hopDelayMs, v -> Config.hopDelayMs = v, " ms");
        ui.toggle("Strict ray", "Only air lets the etherwarp ray through (safer, plans fewer hops).", () -> Config.strictRay, v -> Config.strictRay = v);
        ui.section("Where hops may land");
        ui.islider("Below the way to the spot", "How far below the straight way to the spot a hop may land.", 0, 60, () -> Config.landBelow, v -> Config.landBelow = v, " blocks");
        ui.islider("Above the way to the spot", "How far above the straight way to the spot a hop may land.", 0, 60, () -> Config.landAbove, v -> Config.landAbove = v, " blocks");

        ui.tab("Spots", "S");
        ui.section("Which spots");
        ui.dslider("Farthest spot", "Spots farther than this are ignored.", 10, 400, 5, () -> Config.maxTargetDistance, v -> Config.maxTargetDistance = v, "%.0f blocks");
        ui.dslider("Height difference", "Spots more than this many blocks above or below you are ignored.", 5, 300, 5, () -> Config.maxTargetHeight, v -> Config.maxTargetHeight = v, "%.0f blocks");
        ui.section("Mining a spot");
        ui.toggle("Use an axe", "Switch to the named hotbar item to mine spots (otherwise the Aspect of the Void in your hand is used).", () -> Config.useAxe, v -> Config.useAxe = v);
        ui.text("Axe name", "Part of the hotbar item name (not case sensitive). Or hold the axe and run /mudworm axe.", () -> Config.axeName, v -> Config.axeName = v, 150, "Helix Chopper");
        ui.section("Close spots");
        ui.dslider("Walk range", "Spots this close are walked to with W/A/S/D instead of teleported to. 0 = off.", 0, 30, 1, () -> Config.scootRange, v -> Config.scootRange = v, "%.0f blocks");
        ui.dslider("Walk time", "Longest single walk before the not-moving timer starts again.", 0.2, 10, 0.2, () -> Config.scootSeconds, v -> Config.scootSeconds = v, "%.1f s");

        ui.tab("Lobby", "L");
        ui.section("Patrol area");
        ui.note("With no spot in sight the macro patrols this box. Presets: Moonglade Marsh, or a box around where you stand.");
        ui.islider("West edge (X min)", "", -1500, 1500, () -> Config.areaMinX, v -> Config.areaMinX = v, "");
        ui.islider("East edge (X max)", "", -1500, 1500, () -> Config.areaMaxX, v -> Config.areaMaxX = v, "");
        ui.islider("North edge (Z min)", "", -1500, 1500, () -> Config.areaMinZ, v -> Config.areaMinZ = v, "");
        ui.islider("South edge (Z max)", "", -1500, 1500, () -> Config.areaMaxZ, v -> Config.areaMaxZ = v, "");
        ui.button("Moonglade Marsh", "Sets the patrol area to Moonglade Marsh.", "Set", false, () -> {
            Config.areaMinX = -708;
            Config.areaMaxX = -603;
            Config.areaMinZ = -46;
            Config.areaMaxZ = 77;
            Config.save();
        });
        ui.button("Around me", "Sets a 120 x 120 patrol box around where you stand.", "Set", false, () -> {
            var p = Minecraft.getInstance().player;
            if (p == null) return;
            Config.areaMinX = p.getBlockX() - 60;
            Config.areaMaxX = p.getBlockX() + 60;
            Config.areaMinZ = p.getBlockZ() - 60;
            Config.areaMaxZ = p.getBlockZ() + 60;
            Config.save();
        });
        ui.section("New lobby");
        ui.islider("Refresh below", "Fewer than this many live spots in the lobby: swap lobby (the two commands below). 0 = off.", 0, 50, () -> Config.minSpots, v -> Config.minSpots = v, " spots");
        ui.islider("Refresh cooldown", "At least this long between lobby switches.", 5, 600, () -> Config.refreshCooldownSeconds, v -> Config.refreshCooldownSeconds = v, " s");
        ui.text("Swap: first command", "Run first when the lobby is swapped (without the slash). /warp galatea loads the island, so more spots are around.", () -> Config.swapFirst, v -> Config.swapFirst = v, 150, "warp galatea");
        ui.text("Swap: second command", "Run a few seconds after the first one arrived (without the slash).", () -> Config.swapSecond, v -> Config.swapSecond = v, 150, "warp murk");
        ui.islider("Nothing here: swap after", "Seconds with no spot in view (after arriving) before the lobby is swapped. Lower = faster to give up on an empty lobby.", 1, 30, () -> Config.noSpotSeconds, v -> Config.noSpotSeconds = v, " s");
        ui.islider("Settle time", "Seconds after arriving in a lobby before it counts spots or starts the nothing-here timer (the terrain and particles need a moment).", 1, 10, () -> Config.settleSeconds, v -> Config.settleSeconds = v, " s");
        ui.islider("Gap between the swap commands", "Pause between the first and the second swap command.", 500, 6000, () -> Config.swapGapMs, v -> Config.swapGapMs = v, " ms");
        ui.islider("Hub routine after", "After this many lobby refreshes in a row that found nothing to mine, do /hub then /warp galatea instead. 0 = never.", 0, 10, () -> Config.hubAfter, v -> Config.hubAfter = v, " refreshes");
        ui.button("Forget blacklist", "Clears the spots skipped in this lobby.", "Clear", false, SpotScanner.BLACKLIST::clear);

        ui.tab("Safety", "F");
        ui.section("Stuck");
        ui.toggle("Warp when stuck", "Run the command below when the macro has not moved for the time below.", () -> Config.unstuckWarp, v -> Config.unstuckWarp = v);
        ui.islider("Stuck after", "Seconds without moving.", 1, 60, () -> Config.stuckSeconds, v -> Config.stuckSeconds = v, " s");
        ui.text("Stuck command", "Without the slash.", () -> Config.stuckCommand, v -> Config.stuckCommand = v, 150, "warp murk");
        ui.section("Blatant mode");
        ui.note("Blatant mode sends the etherwarp aim to the server WITHOUT turning your camera. It is much easier for anti-cheat and other players to spot: a ban is far more likely. It is off at every start.");
        ui.info("State", "Whether silent aim is on.", () -> Config.blatant ? "ON" : "off", Ui.BAD);
        ui.button("Enable blatant mode", "Opens a mini game: catch 5 shards to unlock it (or use the Override in its top right corner).", "Play", false, MudwormMacro::openBlatantGame);
        ui.button("Turn blatant mode off", "Back to normal aiming.", "Off", false, () -> Config.blatant = false);
        ui.section("Fail-safe");
        ui.toggle("Stop when moved by something else", "Stops the macro if you are teleported by anything but its own etherwarp.", () -> Config.stopOnForeignTeleport, v -> Config.stopOnForeignTeleport = v);

        ui.tab("Display", "V");
        ui.section("Overlays");
        ui.toggle("Status panel", "Top left: status, time, spots and drops.", () -> Config.hud, v -> Config.hud = v);
        ui.toggle("ESP boxes", "Boxes on the spots and the planned path.", () -> Config.esp, v -> Config.esp = v);
        ui.toggle("Debug messages", "Print every decision in chat.", () -> Config.debug, v -> Config.debug = v);
    }
}
