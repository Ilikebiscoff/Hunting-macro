package com.nablzz.addons.minerzombie;

import com.nablzz.addons.Feature;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Ui;
import com.nablzz.addons.Updater;
import com.nablzz.addons.minerzombie.pathing.Planner;
import com.nablzz.addons.minerzombie.pathing.WalkCache;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class MinerZombieHunting implements Feature {
    private final WalkCache cache = new WalkCache();
    private HuntController controller;
    private static HuntController instance;

    /** The hunting macro (for the settings screen and the other macro). */
    public static HuntController controller() {
        return instance;
    }

    @Override
    public void init() {
        Planner.REACH = 2.6; // melee: stand within sword reach of the zombie, not mining reach
        controller = new HuntController(cache);
        instance = controller;
        Config.init(controller);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nablzzaddons", "hunt_status"),
                (graphics, delta) -> HuntHud.render(graphics, delta, controller));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> controller.onChat(message.getString()));

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            cache.tick(mc);
            controller.tick(mc);
            Session.tick(controller.isRunning());
        });

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            controller.frame(Minecraft.getInstance());
            if (Config.highlight) {
                drawZombies();
                drawPlan();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            Minecraft mc = Minecraft.getInstance();
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("hunt");
            root.then(lit("start").executes(c -> {
                Config.save(controller);
                if (controller.start(mc)) Chat.msg("Started.");
                return 1;
            }));
            root.then(lit("gui").executes(c -> {
                NablzzAddons.openGui("minerzombie");
                return 1;
            }));
            root.then(lit("stop").executes(c -> {
                controller.stop(mc);
                Chat.msg("Stopped.");
                return 1;
            }));
            root.then(lit("status").executes(c -> {
                Chat.msg(controller.status());
                return 1;
            }));
            root.then(lit("version").executes(c -> {
                Chat.msg(Updater.versionInfo());
                return 1;
            }));
            root.then(lit("update")
                    .executes(c -> {
                        Updater.run(false, true);
                        return 1;
                    })
                    .then(lit("check").executes(c -> {
                        Updater.run(false, false);
                        return 1;
                    })));
            root.then(lit("items").executes(c -> {
                if (mc.player != null) Chat.msg(HuntController.hotbarNames(mc.player));
                return 1;
            }));
            root.then(lit("scan").executes(c -> {
                controller.scan(mc);
                return 1;
            }));
            root.then(lit("path").executes(c -> {
                Chat.msg("Last plan: " + Planner.lastStats);
                return 1;
            }));
            root.then(lit("highlight").executes(c -> {
                Config.highlight = !Config.highlight;
                Chat.msg("Highlight " + (Config.highlight ? "ON" : "OFF"));
                return 1;
            }));
            root.then(lit("mob")
                    .executes(c -> {
                        Chat.msg("Mob name filter: " + (controller.mobFilter.isEmpty() ? "any zombie" : "\"" + controller.mobFilter + "\"") + " (/hunt mob <text>, /hunt mob any)");
                        return 1;
                    })
                    .then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                        String t = StringArgumentType.getString(c, "text").trim();
                        controller.mobFilter = t.equalsIgnoreCase("any") ? "" : t;
                        Chat.msg("Mob name filter: " + (controller.mobFilter.isEmpty() ? "any zombie" : "\"" + t + "\""));
                        return 1;
                    })));
            root.then(lit("axe")
                    .executes(c -> {
                        Chat.msg("Prime axe = hotbar item whose name contains \"" + controller.axeName + "\"");
                        return 1;
                    })
                    .then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                        controller.axeName = StringArgumentType.getString(c, "text").trim();
                        Chat.msg("Prime axe name: \"" + controller.axeName + "\"");
                        return 1;
                    })));
            root.then(lit("hole")
                    .executes(c -> {
                        Chat.msg("Black hole = hotbar item whose name contains \"" + controller.holeName + "\"");
                        return 1;
                    })
                    .then(lit("name").then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                        controller.holeName = StringArgumentType.getString(c, "text").trim();
                        Chat.msg("Black hole item name: \"" + controller.holeName + "\"");
                        return 1;
                    }))));
            root.then(lit("hole")
                    .then(lit("time")
                            .executes(c -> {
                                Chat.msg("A black hole takes " + controller.holeTimeSec + "s to suck an enemy in, next hit "
                                        + controller.holeLeadSec + "s before it ends (/hunt hole time <seconds>, 0 = no waiting)");
                                return 1;
                            })
                            .then(arg("s", DoubleArgumentType.doubleArg(0, 120)).executes(c -> {
                                controller.holeTimeSec = DoubleArgumentType.getDouble(c, "s");
                                controller.holeAuto = false; // a hand-set value wins: /hunt hole auto on to learn again
                                controller.saveSettings();
                                Chat.msg("Black hole time: " + controller.holeTimeSec + "s (manual, auto-learning OFF)"
                                        + (controller.holeTimeSec == 0 ? " (no waiting between zombies)" : ""));
                                return 1;
                            })))
                    .then(lit("auto")
                            .executes(c -> {
                                Chat.msg("Auto-learning " + (controller.holeAuto ? "ON" : "OFF") + ": hole time "
                                        + String.format("%.1f", controller.holeTimeSec) + "s (" + controller.holeSamples
                                        + " samples), hit->hole " + String.format("%.2f", controller.hitToHoleSec)
                                        + "s, registers in " + String.format("%.2f", controller.useLatencySec)
                                        + "s, back after " + String.format("%.1f", controller.cooldownSec)
                                        + "s, lead " + String.format("%.2f", controller.holeLeadSec) + "s"
                                        + (controller.holeLeadManual ? " (manual)" : "") + " from " + controller.timingSamples + " hits");
                                return 1;
                            })
                            .then(lit("on").executes(c -> {
                                controller.holeAuto = true;
                                controller.holeLeadManual = false;
                                controller.saveSettings();
                                Chat.msg("Auto-learning ON: after every hit it measures the hole timings and adapts (lead too).");
                                return 1;
                            }))
                            .then(lit("off").executes(c -> {
                                controller.holeAuto = false;
                                controller.saveSettings();
                                Chat.msg("Auto-learning OFF (keeps " + controller.holeTimeSec + "s).");
                                return 1;
                            }))
                            .then(lit("reset").executes(c -> {
                                controller.forgetLearned();
                                Chat.msg("Forgot everything it learned: hole time back to 5s, lead 0.5s.");
                                return 1;
                            })))
                    .then(lit("lead").then(arg("s", DoubleArgumentType.doubleArg(0, 5)).executes(c -> {
                        controller.holeLeadSec = DoubleArgumentType.getDouble(c, "s");
                        controller.holeLeadManual = true; // hand-set: auto-learning leaves the lead alone
                        controller.saveSettings();
                        Chat.msg("Hitting the next zombie " + controller.holeLeadSec + "s before the black hole ends (manual).");
                        return 1;
                    }))));
            root.then(lit("lava")
                    .executes(c -> {
                        Chat.msg("Lava rescue " + (controller.lavaRescue ? "ON" : "OFF") + ": after more than "
                                + controller.lavaLimit + " lava incidents -> /" + controller.rescueCmd + ", walk to "
                                + controller.liftX + " " + controller.liftY + " " + controller.liftZ
                                + ", click \"" + controller.liftNpc + "\", click the diamond.");
                        return 1;
                    })
                    .then(lit("on").executes(c -> {
                        controller.lavaRescue = true;
                        Chat.msg("Lava rescue ON.");
                        return 1;
                    }))
                    .then(lit("off").executes(c -> {
                        controller.lavaRescue = false;
                        Chat.msg("Lava rescue OFF.");
                        return 1;
                    }))
                    .then(lit("limit").then(arg("n", IntegerArgumentType.integer(1, 20)).executes(c -> {
                        controller.lavaLimit = IntegerArgumentType.getInteger(c, "n");
                        Chat.msg("Rescue after more than " + controller.lavaLimit + " lava incidents.");
                        return 1;
                    }))));
            root.then(lit("lift")
                    .executes(c -> {
                        Chat.msg("Lift at " + controller.liftX + " " + controller.liftY + " " + controller.liftZ
                                + ", NPC \"" + controller.liftNpc + "\", warp \"" + controller.rescueCmd + "\"");
                        return 1;
                    })
                    .then(lit("deep")
                            .executes(c -> {
                                Chat.msg("Area name \"" + controller.deepText
                                        + "\" counts as inside the hunting zone and is a hint for the lift menu item. The lift trip "
                                        + "itself is never skipped (/hunt lift deep <text|off>)");
                                return 1;
                            })
                            .then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                                String t = StringArgumentType.getString(c, "text").trim();
                                controller.deepText = t.equalsIgnoreCase("off") ? "" : t;
                                Chat.msg(controller.deepText.isEmpty() ? "Extra in-zone area name cleared."
                                        : "Area \"" + controller.deepText + "\" now also counts as in-zone.");
                                return 1;
                            })))
                    .then(lit("go")
                            .executes(c -> {
                                controller.testLift(mc, false);
                                return 1;
                            })
                            .then(lit("warp").executes(c -> {
                                controller.testLift(mc, true);
                                return 1;
                            })))
                    .then(lit("npc").then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                        controller.liftNpc = StringArgumentType.getString(c, "text").trim();
                        Chat.msg("Lift NPC name: \"" + controller.liftNpc + "\"");
                        return 1;
                    })))
                    .then(lit("warp").then(arg("cmd", StringArgumentType.greedyString()).executes(c -> {
                        controller.rescueCmd = StringArgumentType.getString(c, "cmd").trim().replaceFirst("^/", "");
                        Chat.msg("Rescue warp: /" + controller.rescueCmd);
                        return 1;
                    })))
                    .then(arg("x", IntegerArgumentType.integer()).then(arg("y", IntegerArgumentType.integer())
                            .then(arg("z", IntegerArgumentType.integer()).executes(c -> {
                                controller.liftX = IntegerArgumentType.getInteger(c, "x");
                                controller.liftY = IntegerArgumentType.getInteger(c, "y");
                                controller.liftZ = IntegerArgumentType.getInteger(c, "z");
                                Chat.msg("Lift spot: " + controller.liftX + " " + controller.liftY + " " + controller.liftZ);
                                return 1;
                            })))));
            root.then(lit("refresh")
                    .executes(c -> {
                        Chat.msg("Grey (already hit) zombies come back every " + controller.refreshEvery
                                + " kills (/hunt refresh <n>, 0 = never)");
                        return 1;
                    })
                    .then(arg("n", IntegerArgumentType.integer(0, 100)).executes(c -> {
                        controller.refreshEvery = IntegerArgumentType.getInteger(c, "n");
                        Chat.msg(controller.refreshEvery == 0 ? "Grey zombies are never refreshed."
                                : "Grey zombies come back every " + controller.refreshEvery + " kills.");
                        return 1;
                    })));
            root.then(lit("hole")
                    .then(lit("count").then(arg("n", IntegerArgumentType.integer(1, 5)).executes(c -> {
                        controller.holeCount = IntegerArgumentType.getInteger(c, "n");
                        Chat.msg("Black holes placed after every hit: " + controller.holeCount);
                        return 1;
                    }))));
            root.then(lit("caught")
                    .executes(c -> {
                        Chat.msg("Wait for \"" + controller.caughtText + " x<" + controller.caughtCounts + "> "
                                + controller.caughtMob + "\" in chat before each black hole: "
                                + (controller.caughtGate ? "ON" : "OFF") + " | lines seen " + controller.caughtMsgs
                                + ", shards " + controller.shards
                                + " (/hunt caught on|off|text <t>|mob <t>|counts <2,3|any>)");
                        return 1;
                    })
                    .then(lit("on").executes(c -> {
                        controller.caughtGate = true;
                        Chat.msg("A new black hole waits for the chat line of the last one.");
                        return 1;
                    }))
                    .then(lit("off").executes(c -> {
                        controller.caughtGate = false;
                        Chat.msg("Black holes no longer wait for the chat line (timer only).");
                        return 1;
                    }))
                    .then(lit("text").then(arg("t", StringArgumentType.greedyString()).executes(c -> {
                        controller.caughtText = StringArgumentType.getString(c, "t").trim();
                        Chat.msg("Looking for \"" + controller.caughtText + "\" in chat.");
                        return 1;
                    })))
                    .then(lit("mob").then(arg("t", StringArgumentType.greedyString()).executes(c -> {
                        String t = StringArgumentType.getString(c, "t").trim();
                        controller.caughtMob = t.equalsIgnoreCase("any") ? "" : t;
                        Chat.msg("The caught line must name: " + (controller.caughtMob.isEmpty() ? "any mob" : "\"" + t + "\""));
                        return 1;
                    })))
                    .then(lit("counts").then(arg("list", StringArgumentType.greedyString()).executes(c -> {
                        controller.caughtCounts = StringArgumentType.getString(c, "list").trim();
                        Chat.msg("Counts that end a hole: " + controller.caughtCounts);
                        return 1;
                    }))));
            root.then(lit("swap")
                    .executes(c -> {
                        Chat.msg("Pause after swapping to the black hole: " + controller.swapDelayTicks
                                + " ticks (20 ticks = 1 s). /hunt swap <ticks>");
                        return 1;
                    })
                    .then(arg("ticks", IntegerArgumentType.integer(1, 20)).executes(c -> {
                        controller.swapDelayTicks = IntegerArgumentType.getInteger(c, "ticks");
                        Chat.msg("Pause after the swap: " + controller.swapDelayTicks + " ticks.");
                        return 1;
                    })));
            root.then(lit("debug").executes(c -> {
                controller.debug = !controller.debug;
                Chat.msg("Debug " + (controller.debug ? "ON: prints what the scan and pathing see every 4 s" : "OFF"));
                return 1;
            }));
            root.then(lit("hits")
                    .executes(c -> {
                        Chat.msg("Hits on each zombie before the black hole: " + controller.hitsPerZombie
                                + ", " + controller.hitGapTicks + " ticks apart (/hunt hits <n> [gapTicks])");
                        return 1;
                    })
                    .then(arg("n", IntegerArgumentType.integer(1, 6)).executes(c -> {
                        controller.hitsPerZombie = IntegerArgumentType.getInteger(c, "n");
                        Chat.msg("Hits on each zombie: " + controller.hitsPerZombie);
                        return 1;
                    }).then(arg("gap", IntegerArgumentType.integer(1, 20)).executes(c -> {
                        controller.hitsPerZombie = IntegerArgumentType.getInteger(c, "n");
                        controller.hitGapTicks = IntegerArgumentType.getInteger(c, "gap");
                        Chat.msg("Hits on each zombie: " + controller.hitsPerZombie + ", " + controller.hitGapTicks + " ticks apart");
                        return 1;
                    }))));
            root.then(lit("golden")
                    .executes(c -> {
                        Chat.msg("Golden axe step: " + controller.golden + " (/hunt golden use|hit|off)");
                        return 1;
                    })
                    .then(lit("use").executes(c -> setGolden(HuntController.Golden.USE, "right-click it once")))
                    .then(lit("hit").executes(c -> setGolden(HuntController.Golden.HIT, "hit the zombie with it")))
                    .then(lit("off").executes(c -> setGolden(HuntController.Golden.OFF, "skip it"))));
            root.then(lit("zone")
                    .executes(c -> {
                        Chat.msg("Zone guard " + (controller.zoneGuard ? "ON" : "OFF") + ", zone text \"" + controller.zoneText
                                + "\" (/hunt zone off|on|<sidebar text>)");
                        return 1;
                    })
                    .then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                        String t = StringArgumentType.getString(c, "text").trim();
                        if (t.equalsIgnoreCase("off")) controller.zoneGuard = false;
                        else if (t.equalsIgnoreCase("on")) controller.zoneGuard = true;
                        else { controller.zoneText = t; controller.zoneGuard = true; }
                        Chat.msg("Zone guard " + (controller.zoneGuard ? "ON (" + controller.zoneText + ")" : "OFF"));
                        return 1;
                    })));
            root.then(lit("area")
                    .executes(c -> {
                        Chat.msg("Scan area " + (controller.areaOn ? "ON" : "OFF") + ": x " + controller.areaX1 + ".."
                                + controller.areaX2 + ", z " + controller.areaZ1 + ".." + controller.areaZ2 + ", y "
                                + controller.areaY1 + ".." + controller.areaY2
                                + " (/hunt area on|off|<x1> <z1> <x2> <z2> <y1> <y2>)");
                        return 1;
                    })
                    .then(lit("on").executes(c -> {
                        controller.areaOn = true;
                        Chat.msg("Scan area ON.");
                        return 1;
                    }))
                    .then(lit("off").executes(c -> {
                        controller.areaOn = false;
                        Chat.msg("Scan area OFF (using /hunt range around you).");
                        return 1;
                    }))
                    .then(arg("x1", IntegerArgumentType.integer()).then(arg("z1", IntegerArgumentType.integer())
                            .then(arg("x2", IntegerArgumentType.integer()).then(arg("z2", IntegerArgumentType.integer())
                            .then(arg("y1", IntegerArgumentType.integer()).then(arg("y2", IntegerArgumentType.integer())
                            .executes(c -> {
                                controller.areaX1 = IntegerArgumentType.getInteger(c, "x1");
                                controller.areaZ1 = IntegerArgumentType.getInteger(c, "z1");
                                controller.areaX2 = IntegerArgumentType.getInteger(c, "x2");
                                controller.areaZ2 = IntegerArgumentType.getInteger(c, "z2");
                                controller.areaY1 = IntegerArgumentType.getInteger(c, "y1");
                                controller.areaY2 = IntegerArgumentType.getInteger(c, "y2");
                                controller.areaOn = true;
                                Chat.msg("Scan area set and ON.");
                                return 1;
                            }))))))));
            root.then(lit("range").then(arg("blocks", DoubleArgumentType.doubleArg(5, 150)).executes(c -> {
                controller.range = DoubleArgumentType.getDouble(c, "blocks");
                Chat.msg("Search range " + controller.range + " blocks.");
                return 1;
            })));
            root.then(lit("warp")
                    .executes(c -> {
                        Chat.msg("Recovery command: " + (controller.warpCmd.isBlank() ? "none" : "/" + controller.warpCmd)
                                + " (/hunt warp <command without slash>, /hunt warp off)");
                        return 1;
                    })
                    .then(arg("cmd", StringArgumentType.greedyString()).executes(c -> {
                        String t = StringArgumentType.getString(c, "cmd").trim();
                        controller.warpCmd = t.equalsIgnoreCase("off") ? "" : t;
                        Chat.msg("Recovery command: " + (controller.warpCmd.isBlank() ? "none" : "/" + controller.warpCmd));
                        return 1;
                    })));
            root.then(lit("parkour")
                    .then(lit("on").executes(c -> {
                        Planner.parkour = true;
                        Chat.msg("Gap jumps ON.");
                        return 1;
                    }))
                    .then(lit("off").executes(c -> {
                        Planner.parkour = false;
                        Chat.msg("Gap jumps OFF.");
                        return 1;
                    })));
            root.then(lit("help").executes(c -> {
                help();
                return 1;
            }));
            root.executes(c -> {
                NablzzAddons.openGui("minerzombie"); // plain /hunt opens the settings screen
                return 1;
            });
            dispatcher.register(root);
        });
    }

    private int setGolden(HuntController.Golden g, String what) {
        controller.golden = g;
        Chat.msg("Golden axe: " + what + ".");
        return 1;
    }

    private static void help() {
        Chat.msg("Commands:");
        Chat.msg(" /hunt (or /hunt gui) - open the settings screen (also Right Shift)");
        Chat.msg(" /hunt start | stop | status");
        Chat.msg(" /hunt update | update check | version - download the newest build (restart the game afterwards)");
        Chat.msg(" /hunt items - list your hotbar item names");
        Chat.msg(" /hunt scan - list zombies and name tags around you (find the right mob name)");
        Chat.msg(" /hunt mob <text|any> - name tag text of the zombies to hunt (default: any zombie)");
        Chat.msg(" /hunt axe <text> - hotbar item name of the prime hunt axe (default \"Prime Huntaxe\")");
        Chat.msg(" /hunt golden use|hit|off - what to do with the golden axe (default off)");
        Chat.msg(" /hunt hole name <text> - black hole item name");
        Chat.msg(" /hunt zone [off|on|text] - stay inside this area (sidebar text, default \"Diamond Reserve\"); if you leave, walk back to where you were 3 moves ago");
        Chat.msg(" /hunt area on|off|<x1> <z1> <x2> <z2> <y1> <y2> - only hunt zombies inside this box (default x -44..28, z -27..56, y 36..40)");
        Chat.msg(" /hunt hole auto [on|off|reset] - learn the black hole time from real holes (default ON, saved), /hunt hole time <s> | lead <s> - seconds a black hole needs to finish (default 5) and how long before that the next zombie is hit (default 0.5)");
        Chat.msg(" /hunt lava [on|off|limit <n>] - after more than 3 lava incidents: /warp deep, walk to the lift, click the Lift Operator, click the diamond");
        Chat.msg(" /hunt lift go [warp] (test the lift trip now) | lift [<x> <y> <z> | npc <text> | warp <cmd>] - lift spot (default 45 150 15), NPC name and warp command");
        Chat.msg(" /hunt refresh <n> - every n kills (default 5) the grey, already-hit zombies become targets again; lift deep <text|off> - extra sidebar area that counts as in-zone");
        Chat.msg(" /hunt hole count <n> - black holes after every hit (default 1)");
        Chat.msg(" /hunt caught [on|off|text <t>|mob <t>|counts <2,3|any>] - a new black hole waits for the \"You caught a Miner Zombie Shard!\" chat line of the last one (default ON; counts default: any, a single shard has no xN)");
        Chat.msg(" /hunt swap <ticks> - pause after swapping to the black hole before the click (default 4)");
        Chat.msg(" /hunt scan | /hunt debug - show what the scan and pathfinder see (zombies found, inside the area, nearest target, planner status)");
        Chat.msg(" /hunt hits <n> [gapTicks] - hits on each zombie before the black hole (default 2, 3 ticks apart)");
        Chat.msg(" /hunt range <blocks> - how far to look for zombies (default 80; pathing reaches ~44)");
        Chat.msg(" /hunt warp <command|off> - command to run when stuck or nothing to hunt (e.g. warp dr)");
        Chat.msg(" /hunt path - last pathfinder stats, /hunt highlight - toggle overlay, /hunt parkour on|off");
    }


    // ---- Feature ------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "minerzombie";
    }

    @Override
    public String name() {
        return "Miner Zombie Hunting";
    }

    @Override
    public boolean isRunning() {
        return controller.isRunning();
    }

    @Override
    public String status() {
        return controller.phaseLabel();
    }

    @Override
    public void start(Minecraft mc) {
        Config.save(controller);
        controller.start(mc);
    }

    @Override
    public void stop(Minecraft mc) {
        controller.stop(mc);
    }

    @Override
    public void saveSettings() {
        Config.save(controller);
    }

    private double holeLeft() {
        return Math.max(0, controller.holeLeftSec());
    }

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        HuntController c = controller;

        // ---- Dashboard
        ui.tab("Dashboard", "D");
        ui.section("Overview");
        ui.hero(() -> c.isRunning() ? c.phaseLabel() + "  -  " + c.zombiesInView() + " zombies in view"
                : "Press Start, then close this screen.", "Miner zombie hunting");
        ui.tiles(() -> new String[][]{
                {"" + c.killCount(), "Kills"},
                {"" + c.holesPlaced, "Black holes"},
                {"" + c.shards, "Shards"},
                {String.format(Locale.ROOT, "%.0f", Session.perHour(c.shards)), "Shards / hour"},
                {"" + c.hits, "Hits landed"},
                {Session.clock(), "Run time"},
        });
        ui.section("Black hole");
        ui.bar("Black hole", "Time left on the hole you placed, or whether the next one is ready.",
                () -> {
                    double left = holeLeft();
                    return left > 0 ? Math.min(1.0, left / Math.max(1.0, c.holeTimeSec)) : (c.holeReadyNow() ? 1.0 : 0.0);
                },
                () -> {
                    double left = holeLeft();
                    if (left > 0) return String.format(Locale.ROOT, "out - %.1fs left", left);
                    return c.holeReadyNow() ? "ready" : "on cooldown";
                });
        ui.info("Learned timings", "What the macro measured by itself (hit to hole, click to registered, ability cooldown).",
                () -> c.timingSamples == 0 ? "nothing yet"
                        : String.format(Locale.ROOT, "%d samples - hit>hole %.2fs - click %.2fs - cd %.1fs",
                                c.timingSamples, c.hitToHoleSec, c.useLatencySec, c.readyAfterSec), Ui.MUTED);
        ui.info("Safety", "Zone guard and lava incidents so far.",
                () -> (c.inZoneNow() ? "inside zone" : "OUTSIDE zone") + "  -  lava " + c.lavaIncidents() + "/" + c.lavaLimit,
                Ui.MUTED);
        ui.section("Session");
        ui.button("Reset statistics", "Sets kills, holes, shards and the session clock back to zero.", "Reset", false, () -> {
            c.resetStats();
            Session.reset();
        });
        ui.confirmButton("Reset all settings", "Puts every setting back to the built-in default (learned timings stay).", "Defaults", () -> Config.resetToDefaults(c));

        // ---- Combat
        ui.tab("Combat", "C");
        ui.section("Weapon");
        ui.text("Prime axe", "Part of the hotbar item name of your Prime Huntaxe.", () -> c.axeName, v -> c.axeName = v, 150, "name");
        ui.islider("Hits per zombie", "How many times each zombie is hit before the black hole.", 1, 6, () -> c.hitsPerZombie, v -> c.hitsPerZombie = v, "");
        ui.islider("Ticks between hits", "20 ticks = 1 second. The pet's double hit lands on the second swing.", 1, 20, () -> c.hitGapTicks, v -> c.hitGapTicks = v, " t");
        ui.choice("Golden axe step", "Optional step before the black hole (off by default).",
                new String[]{"Off", "Use", "Hit"}, () -> c.golden.ordinal() == 2 ? 0 : (c.golden == HuntController.Golden.USE ? 1 : 2),
                i -> c.golden = i == 1 ? HuntController.Golden.USE : (i == 2 ? HuntController.Golden.HIT : HuntController.Golden.OFF));
        ui.section("Targeting");
        ui.text("Mob name filter", "Only hunt zombies whose name tag contains this. Empty = every zombie that has ESP.",
                () -> c.mobFilter, v -> c.mobFilter = v, 150, "any zombie");
        ui.dslider("Search range", "How far to look for zombies (the pathfinder reaches about 44 blocks).", 20, 150, 5, () -> c.range, v -> c.range = v, "%.0f blocks");
        ui.islider("Refresh grey zombies", "Every N kills the already-hit (grey) zombies become targets again. 0 = never.", 0, 20, () -> c.refreshEvery, v -> c.refreshEvery = v, " kills");
        ui.toggle("Gap jumps", "Let the pathfinder jump across one-block gaps.", () -> com.nablzz.addons.minerzombie.pathing.Planner.parkour,
                v -> com.nablzz.addons.minerzombie.pathing.Planner.parkour = v);

        // ---- Black hole
        ui.tab("Black Hole", "B");
        ui.section("Item");
        ui.text("Hole item", "Part of the hotbar item name of the Medium Pocket Black Hole.", () -> c.holeName, v -> c.holeName = v, 170, "name");
        ui.islider("Swap delay", "Ticks to wait after switching to the hole before the single click.", 1, 20, () -> c.swapDelayTicks, v -> c.swapDelayTicks = v, " t");
        ui.islider("Holes per hit", "Black holes placed after every hit. One is what you want.", 1, 3, () -> c.holeCount, v -> c.holeCount = v, "");
        ui.section("Timing");
        ui.toggle("Wait for catch message", "Place the next hole only after the \"You caught\" chat line of the last one.", () -> c.caughtGate, v -> c.caughtGate = v);
        ui.text("Catch message", "Chat text that means the last hole finished.", () -> c.caughtText, v -> c.caughtText = v, 150, "You caught");
        ui.text("Catch mob", "The line must also contain this mob name.", () -> c.caughtMob, v -> c.caughtMob = v, 150, "any");
        ui.toggle("Auto-learn timings", "Measure hole time, click delay and lead from real holes and keep them up to date.", () -> c.holeAuto, v -> {
            c.holeAuto = v;
            c.saveSettings();
        });
        ui.dslider("Hole duration", "How long one hole needs to suck a zombie in. Setting it by hand turns auto-learning off.", 1, 30, 0.5, () -> c.holeTimeSec,
                v -> {
                    c.holeTimeSec = v;
                    c.holeAuto = false;
                    c.saveSettings();
                }, "%.1f s");
        ui.dslider("Hit lead", "Hit the next zombie this long before the old hole ends.", 0, 5, 0.1, () -> c.holeLeadSec,
                v -> {
                    c.holeLeadSec = v;
                    c.holeLeadManual = true;
                    c.saveSettings();
                }, "%.1f s");
        ui.button("Forget learned timings", "Throws the measured values away; they are learned again from the next holes.", "Forget", false, c::forgetLearned);

        // ---- Area & zone
        ui.tab("Area", "A");
        ui.section("Scan box");
        ui.toggle("Use fixed box", "Only zombies inside this box get ESP and are hunted. Off = everything within the search range.", () -> c.areaOn, v -> c.areaOn = v);
        ui.nums("X range", "West to east block coordinates (inclusive).", new String[]{"from", "to"},
                new IntSupplier[]{() -> c.areaX1, () -> c.areaX2}, new IntConsumer[]{v -> c.areaX1 = v, v -> c.areaX2 = v});
        ui.nums("Z range", "North to south block coordinates (inclusive).", new String[]{"from", "to"},
                new IntSupplier[]{() -> c.areaZ1, () -> c.areaZ2}, new IntConsumer[]{v -> c.areaZ1 = v, v -> c.areaZ2 = v});
        ui.nums("Y range", "Height of the box (zombies get a few blocks of slack).", new String[]{"from", "to"},
                new IntSupplier[]{() -> c.areaY1, () -> c.areaY2}, new IntConsumer[]{v -> c.areaY1 = v, v -> c.areaY2 = v});
        ui.button("Centre on me", "Sets X and Z to 40 blocks around where you stand (Y stays).", "Use position", false, () -> {
            var p = Minecraft.getInstance().player;
            if (p == null) return;
            c.areaX1 = p.getBlockX() - 40;
            c.areaX2 = p.getBlockX() + 40;
            c.areaZ1 = p.getBlockZ() - 40;
            c.areaZ2 = p.getBlockZ() + 40;
            ui.markDirty();
        });
        ui.section("Zone guard");
        ui.toggle("Stay in the zone", "If the sidebar shows another area, walk back to where you were 3 moves ago.", () -> c.zoneGuard, v -> c.zoneGuard = v);
        ui.text("Zone name", "Sidebar text of the hunting area.", () -> c.zoneText, v -> c.zoneText = v, 150, "Diamond Reserve");
        ui.text("Recovery command", "Run when stuck or nothing to hunt (without the slash). Empty = just re-plan.", () -> c.warpCmd, v -> c.warpCmd = v, 150, "none");
        ui.dslider("Idle before recovery", "Seconds without a target before the recovery command runs.", 3, 60, 1, () -> c.idleWarpMs / 1000.0, v -> c.idleWarpMs = (long) (v * 1000), "%.0f s");

        // ---- Safety
        ui.tab("Safety", "S");
        ui.section("Lava");
        ui.toggle("Lava rescue trip", "After too many lava incidents: warp out, take the lift back down.", () -> c.lavaRescue, v -> c.lavaRescue = v);
        ui.islider("Incidents before rescue", "More than this many lava incidents starts the rescue.", 1, 10, () -> c.lavaLimit, v -> c.lavaLimit = v, "");
        ui.toggle("Rescue when stuck", "If the walk gets stuck (no movement for 6 s) more than the limit in a row, do the same rescue: warp out and take the lift.", () -> c.stuckRescue, v -> c.stuckRescue = v);
        ui.islider("Stuck times before rescue", "More than this many stuck periods in a row starts the rescue. A kill resets the count.", 1, 20, () -> c.stuckLimit, v -> c.stuckLimit = v, "");
        ui.text("Rescue command", "Command that gets you out (without the slash).", () -> c.rescueCmd, v -> c.rescueCmd = v, 150, "warp deep");
        ui.section("Lift");
        ui.nums("Lift position", "Where the Lift Operator stands (x y z).", new String[]{"x", "y", "z"},
                new IntSupplier[]{() -> c.liftX, () -> c.liftY, () -> c.liftZ}, new IntConsumer[]{v -> c.liftX = v, v -> c.liftY = v, v -> c.liftZ = v});
        ui.text("NPC name", "Name of the lift NPC.", () -> c.liftNpc, v -> c.liftNpc = v, 150, "Lift Operator");
        ui.text("Extra area text", "Another sidebar area that counts as in-zone (Deep Caverns).", () -> c.deepText, v -> c.deepText = v, 150, "Deep Caverns");
        ui.button("Test the lift trip", "Runs the whole lift trip now (closes this screen).", "Run", false, () -> {
            Config.save(c);
            Minecraft mc = Minecraft.getInstance();
            ui.closeScreen();
            c.testLift(mc, true);
        });

        // ---- Visuals
        ui.tab("Visuals", "V");
        ui.section("In the world");
        ui.toggle("ESP and path", "Boxes around zombies (green = target, yellow = current, grey = already hit) and the planned path.", () -> Config.highlight, v -> Config.highlight = v);
        ui.toggle("Debug output", "Prints what the scan and pathfinder see into chat every few seconds.", () -> c.debug, v -> c.debug = v);
        ui.section("On screen");
        ui.toggle("Status panel", "The small panel with status, kills and shards.", () -> Config.hud, v -> Config.hud = v);
        ui.choice("Panel corner", "Where the status panel sits.", new String[]{"Top left", "Top right", "Bottom left", "Bottom right"},
                () -> Config.hudCorner, i -> Config.hudCorner = i);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name,
            com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private static final int GREEN = 0xFF30FF60;
    private static final int GREEN_FILL = 0x4030FF60;
    private static final int YELLOW = 0xFFFFE030;
    private static final int YELLOW_FILL = 0x50FFE030;
    private static final int CYAN = 0xFF30E0FF;
    private static final int CYAN_FILL = 0x3030E0FF;
    private static final int PURPLE = 0xFFB040FF;

    private static final int GREY = 0xFF9A9A9A;
    private static final int GREY_FILL = 0x309A9A9A;

    /** ESP on every zombie we will hunt: green = to hunt, yellow = current, grey = already hit. */
    private void drawZombies() {
        for (var z : controller.esp()) {
            var box = z.e().getBoundingBox();
            switch (z.kind()) {
                case 1 -> Gizmos.cuboid(box, GizmoStyle.strokeAndFill(YELLOW, 2.5f, YELLOW_FILL)).setAlwaysOnTop();
                case 2 -> Gizmos.cuboid(box, GizmoStyle.strokeAndFill(GREY, 1.5f, GREY_FILL)).setAlwaysOnTop();
                default -> Gizmos.cuboid(box, GizmoStyle.strokeAndFill(GREEN, 2f, GREEN_FILL)).setAlwaysOnTop();
            }
        }
    }

    private void drawPlan() {
        Planner.Leg leg = controller.currentLeg();
        if (leg == null) return;
        var pts = leg.path();
        for (int i = 0; i + 1 < pts.size(); i++)
            Gizmos.line(pts.get(i).add(0, 0.1, 0), pts.get(i + 1).add(0, 0.1, 0), CYAN, 3f).setAlwaysOnTop();
        var mv = leg.moves();
        for (int i = 0; i < pts.size() && i < mv.size(); i++) {
            if (mv.get(i) == Planner.Move.WALK) continue;
            int col = switch (mv.get(i)) {
                case STEP_UP -> 0xFFFFE030;
                case DROP -> 0xFFFF9A30;
                default -> 0xFFFF40FF;
            };
            var v = pts.get(i);
            Gizmos.cuboid(new AABB(v.x - 0.2, v.y, v.z - 0.2, v.x + 0.2, v.y + 0.4, v.z + 0.2),
                    GizmoStyle.stroke(col, 2.5f)).setAlwaysOnTop();
        }
        var st = leg.stand();
        Gizmos.cuboid(new AABB(st.getX() + 0.2, st.getY(), st.getZ() + 0.2, st.getX() + 0.8, st.getY() + 0.1, st.getZ() + 0.8),
                GizmoStyle.strokeAndFill(CYAN, 2f, CYAN_FILL)).setAlwaysOnTop();
    }
}
