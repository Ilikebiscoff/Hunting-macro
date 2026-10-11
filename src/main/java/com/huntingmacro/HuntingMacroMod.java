package com.huntingmacro;

import com.huntingmacro.pathing.Planner;
import com.huntingmacro.pathing.WalkCache;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class HuntingMacroMod implements ClientModInitializer {
    private final WalkCache cache = new WalkCache();
    private HuntController controller;
    private boolean highlight = true;

    @Override
    public void onInitializeClient() {
        Planner.REACH = 2.6; // melee: stand within sword reach of the zombie, not mining reach
        controller = new HuntController(cache);

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            cache.tick(mc);
            controller.tick(mc);
        });

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            controller.frame(Minecraft.getInstance());
            if (highlight) {
                drawZombies();
                drawPlan();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            Minecraft mc = Minecraft.getInstance();
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("hunt");
            root.then(lit("start").executes(c -> {
                if (controller.start(mc)) Chat.msg("Started.");
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
                highlight = !highlight;
                Chat.msg("Highlight " + (highlight ? "ON" : "OFF"));
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
                help();
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
        Chat.msg(" /hunt start | stop | status");
        Chat.msg(" /hunt items - list your hotbar item names");
        Chat.msg(" /hunt scan - list zombies and name tags around you (find the right mob name)");
        Chat.msg(" /hunt mob <text|any> - name tag text of the zombies to hunt (default: any zombie)");
        Chat.msg(" /hunt axe <text> - hotbar item name of the prime hunt axe (default \"Prime Huntaxe\")");
        Chat.msg(" /hunt golden use|hit|off - what to do with the golden axe (default off)");
        Chat.msg(" /hunt hole name <text> - black hole item name");
        Chat.msg(" /hunt zone [off|on|text] - stay inside this area (sidebar text, default \"Diamond Reserve\"); if you leave, walk back to where you were 3 moves ago");
        Chat.msg(" /hunt area on|off|<x1> <z1> <x2> <z2> <y1> <y2> - only hunt zombies inside this box (default x -44..28, z -27..56, y 36..40)");
        Chat.msg(" /hunt range <blocks> - how far to look for zombies (default 80; pathing reaches ~44)");
        Chat.msg(" /hunt warp <command|off> - command to run when stuck or nothing to hunt (e.g. warp dr)");
        Chat.msg(" /hunt path - last pathfinder stats, /hunt highlight - toggle overlay, /hunt parkour on|off");
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
