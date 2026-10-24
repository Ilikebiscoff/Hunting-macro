package com.nablzz.addons.mushroom;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.nablzz.addons.Feature;
import com.nablzz.addons.NablzzAddons;
import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Ui;
import com.nablzz.addons.mushroom.pathing.Planner;
import com.nablzz.addons.mushroom.pathing.WalkCache;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public class GlowingMushroomMod implements Feature {
    private Route route;
    private MacroController controller;
    private final WalkCache cache = new WalkCache();
    private Failsafe failsafe;
    private static GlowingMushroomMod instance;

    public static MacroController controller() {
        return instance.controller;
    }

    public static Failsafe failsafe() {
        return instance.failsafe;
    }

    public static Route route() {
        return instance.route;
    }

    @Override
    public void init() {
        route = new Route(FabricLoader.getInstance().getConfigDir().resolve("glowingmushroom_route.json"));
        controller = new MacroController(route, cache);
        failsafe = new Failsafe(controller);
        instance = this;
        MushroomConfig.init(controller, failsafe);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> failsafe.onJoin(client));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> failsafe.onDisconnect(client));
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> { if (!overlay) failsafe.onGameMessage(message.getString()); });

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            MushroomTracker.tick(mc);
            cache.tick(mc);
            controller.tick(mc);
            failsafe.tick(mc);
            ProfitTracker.tick(mc, controller.isRunning());
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nablzzaddons", "mushroom_profit"),
                (graphics, delta) -> ProfitHud.render(graphics, delta, controller::isRunning, failsafe::wanted));

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            controller.frame(Minecraft.getInstance());
            drawRoute();
            drawMushrooms();
            drawPlan();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            Minecraft mc = Minecraft.getInstance();
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("glowing");
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add").executes(c -> {
                var p = mc.player;
                route.add(p.getX(), p.getY(), p.getZ());
                Chat.msg("Added waypoint #" + route.size());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("undo").executes(c -> {
                Chat.msg(route.removeLast() ? "Removed last waypoint." : "Route is empty.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(c -> {
                route.clear();
                Chat.msg("Route cleared.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list").executes(c -> {
                Chat.msg(route.size() + " waypoints, particle=" + MushroomTracker.markers
                        + ", broken=" + controller.broken);
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start").executes(c -> {
                failsafe.begin(mc);
                if (controller.pathMode && route.size() == 0)
                    Chat.msg("Tip: record a patrol route with /glowing add so it can search when no mushrooms are known.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(c -> {
                failsafe.end(mc);
                Chat.msg("Stopped.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scan").executes(c -> {
                if (!MushroomTracker.scanning) {
                    MushroomTracker.scanning = true;
                    MushroomTracker.drainSeen();
                    Chat.msg("Counting particles. Stand near glowing mushrooms, then run /glowing scan again.");
                } else {
                    MushroomTracker.scanning = false;
                    MushroomTracker.drainSeen().forEach((k, v) -> Chat.msg(k + ": " + v));
                    Chat.msg("Pick the one that only shows on mushrooms: /glowing particle <id>");
                }
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("particle")
                    .executes(c -> {
                        Chat.msg("Current: " + MushroomTracker.markers
                                + " (/glowing particle reset = entity_effect, /glowing particle potion = all potion types)");
                        return 1;
                    })
                    .then(arg("ids", StringArgumentType.greedyString()).executes(c -> {
                        String in = StringArgumentType.getString(c, "ids").trim();
                        if (in.equalsIgnoreCase("reset")) {
                            MushroomTracker.markers = MushroomTracker.DEFAULT_PARTICLES;
                        } else if (in.equalsIgnoreCase("potion")) {
                            MushroomTracker.markers = MushroomTracker.POTION_PARTICLES;
                        } else {
                            var set = new java.util.HashSet<String>();
                            for (String id : in.split("[\\s,]+")) set.add(id.contains(":") ? id : "minecraft:" + id);
                            MushroomTracker.markers = set;
                        }
                        Chat.msg("Marker particles: " + MushroomTracker.markers);
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("nuker").executes(c -> {
                controller.nuker = !controller.nuker;
                Chat.msg("Nuker " + (controller.nuker ? "ON (breaks everything in reach, no aiming)" : "OFF (aimed mining)"));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("speed").executes(c -> {
                Chat.msg("Tab list speed: " + MacroController.readTabSpeed(mc));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
                    .executes(c -> {
                        Chat.msg("Mode: " + (controller.pathMode ? "path" : "route") + " (/glowing mode path|route)");
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("path").executes(c -> {
                        controller.pathMode = true;
                        Chat.msg("Path mode: pathfinds to the densest mushroom groups, route = patrol when none known.");
                        return 1;
                    }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("route").executes(c -> {
                        controller.pathMode = false;
                        Chat.msg("Route mode: only walks the recorded route.");
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("highlight").executes(c -> {
                highlight = !highlight;
                Chat.msg("Mushroom highlight " + (highlight ? "ON" : "OFF"));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("failsafe").executes(c -> {
                failsafe.enabled = !failsafe.enabled;
                Chat.msg("Fail-safe (reconnect, rejoin SkyBlock, /warp glowing) " + (failsafe.enabled ? "ON" : "OFF"));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("zone")
                    .executes(c -> {
                        Chat.msg("Cave is detected when a sidebar line contains: \"" + failsafe.zoneText + "\"");
                        return 1;
                    })
                    .then(arg("text", StringArgumentType.greedyString()).executes(c -> {
                        failsafe.zoneText = StringArgumentType.getString(c, "text").trim();
                        Chat.msg("Cave text set to \"" + failsafe.zoneText + "\"");
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("where").executes(c -> {
                var lines = Failsafe.sidebar(mc);
                if (lines == null) {
                    Chat.msg("No sidebar (Limbo or loading).");
                } else {
                    Chat.msg("Sidebar: " + Failsafe.sidebarTitle(mc));
                    for (String l : lines) if (!l.isBlank()) Chat.msg(" " + l);
                    Chat.msg("In cave: " + failsafe.inZone(mc));
                }
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("parkour")
                    .executes(c -> {
                        Chat.msg("Gap jumps: " + (Planner.parkour ? "ON" : "OFF") + " (/glowing parkour on|off)");
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(c -> {
                        Planner.parkour = true;
                        Chat.msg("Gap jumps ON: the pathfinder may sprint-jump over 1-block gaps.");
                        return 1;
                    }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(c -> {
                        Planner.parkour = false;
                        Chat.msg("Gap jumps OFF.");
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("path").executes(c -> {
                Chat.msg("Last plan: " + Planner.lastStats);
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("avoid")
                    .executes(c -> {
                        var l = failsafe.avoid.list();
                        Chat.msg("Avoid list (" + l.size() + "): " + (l.isEmpty() ? "empty" : String.join(", ", l)));
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
                            .then(arg("name", StringArgumentType.word()).executes(c -> {
                                String n = StringArgumentType.getString(c, "name");
                                Chat.msg(failsafe.avoid.add(n) ? "Added " + n + " to the avoid list." : n + " is already on the list.");
                                return 1;
                            })))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
                            .then(arg("name", StringArgumentType.word()).executes(c -> {
                                String n = StringArgumentType.getString(c, "name");
                                Chat.msg(failsafe.avoid.remove(n) ? "Removed " + n + "." : n + " wasn't on the list.");
                                return 1;
                            })))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list").executes(c -> {
                        var l = failsafe.avoid.list();
                        Chat.msg("Avoid list (" + l.size() + "): " + (l.isEmpty() ? "empty" : String.join(", ", l)));
                        return 1;
                    }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(c -> {
                        failsafe.avoid.clear();
                        Chat.msg("Avoid list cleared.");
                        return 1;
                    }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("check").executes(c -> {
                        String who = failsafe.avoid.scan(mc);
                        Chat.msg(who == null ? "Nobody from the list is in this lobby." : who + " is in this lobby.");
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("nomush")
                    .executes(c -> {
                        long ms = controller.noMushroomWarpMs;
                        Chat.msg("Warp when there are no reachable mushrooms for: " + (ms <= 0 ? "off" : ms / 1000 + "s")
                                + " (/glowing nomush <seconds>, 0 = off)");
                        return 1;
                    })
                    .then(arg("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 600)).executes(c -> {
                        int sec = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds");
                        controller.noMushroomWarpMs = sec * 1000L;
                        Chat.msg("Warp when there are no reachable mushrooms for: " + (sec == 0 ? "off" : sec + "s"));
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("hud").executes(c -> {
                ProfitHud.enabled = !ProfitHud.enabled;
                Chat.msg("Profit HUD " + (ProfitHud.enabled ? "ON" : "OFF"));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset").executes(c -> {
                ProfitTracker.reset();
                Chat.msg("Profit tracker reset.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("help").executes(c -> {
                help();
                return 1;
            }));
            root.executes(c -> {
                NablzzAddons.openGui("mushroom"); // plain /glowing opens the settings screen
                return 1;
            });
            dispatcher.register(root);
        });
    }


    // ---- Feature ------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return "mushroom";
    }

    @Override
    public String name() {
        return "Glowing Mushrooms";
    }

    @Override
    public boolean isRunning() {
        return controller.isRunning() || failsafe.wanted();
    }

    @Override
    public String status() {
        if (Failsafe.status != null) return Failsafe.status;
        if (controller.isRunning()) return "Mining";
        return failsafe.wanted() ? "Waiting" : "Stopped";
    }

    @Override
    public void start(Minecraft mc) {
        MushroomConfig.save(controller, failsafe);
        failsafe.begin(mc);
    }

    @Override
    public void stop(Minecraft mc) {
        failsafe.end(mc);
    }

    @Override
    public void saveSettings() {
        MushroomConfig.save(controller, failsafe);
    }

    @Override
    public java.util.Map<String, Double> sessionCounters() {
        java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
        m.put("Glowing mushrooms", (double) ProfitTracker.glowing);
        m.put("Red mushrooms", (double) ProfitTracker.red);
        m.put("Brown mushrooms", (double) ProfitTracker.brown);
        m.put("Coins (est.)", ProfitTracker.coins());
        return m;
    }

    @Override
    public String sessionMain() {
        return "Glowing mushrooms";
    }

    private static String coins(double v) {
        if (ProfitTracker.glowingPrice < 0) return "?";
        if (v >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.2fM", v / 1_000_000);
        if (v >= 1_000) return String.format(java.util.Locale.ROOT, "%.1fk", v / 1_000);
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        MacroController m = controller;
        Failsafe fs = failsafe;
        Route rt = route;
        // ---- Dashboard
        ui.tab("Dashboard", "D");
        ui.section("Overview");
        ui.hero(() -> m.isRunning() ? "Mining  -  " + MushroomTracker.MUSHROOMS.size() + " mushrooms known"
                : (fs.wanted() ? "Waiting for the fail-safe to get you back into the cave." : "Press Start, then close this screen."),
                "Glowing mushroom mining");
        ui.tiles(() -> new String[][]{
                {"" + ProfitTracker.glowing, "Glowing mushrooms"},
                {"" + (ProfitTracker.red + ProfitTracker.brown), "Red / brown"},
                {coins(ProfitTracker.coins()), "Profit (coins)"},
                {coins(ProfitTracker.perHour()), "Coins / hour"},
                {"" + MushroomTracker.MUSHROOMS.size(), "Mushrooms known"},
                {clock(ProfitTracker.macroMs), "Run time"},
        });
        ui.section("Status");
        ui.info("Fail-safe", "Reconnects, rejoins SkyBlock and warps back when something goes wrong.",
                () -> Failsafe.status != null ? Failsafe.status : (fs.enabled ? "armed" : "off"), Ui.MUTED);
        ui.info("In the cave", "Whether the sidebar shows the cave name.",
                () -> fs.inZone(Minecraft.getInstance()) ? "yes" : "no", Ui.MUTED);
        ui.info("Route", "Recorded patrol waypoints.", () -> rt.size() + " waypoints  -  " + m.broken + " blocks broken", Ui.MUTED);
        ui.button("Reset profit", "Sets the counts, profit and the clock back to zero.", "Reset", false, ProfitTracker::reset);

        // ---- Mining
        ui.tab("Mining", "M");
        ui.section("Behaviour");
        ui.choice("Mode", "Path: walk to the densest mushroom groups. Route: only walk the recorded route.",
                new String[]{"Path", "Route"}, () -> m.pathMode ? 0 : 1, i -> m.pathMode = i == 0);
        ui.toggle("Nuker", "Break everything in reach without aiming. Off = aimed mining.", () -> m.nuker, v -> m.nuker = v);
        ui.dslider("Warp when nothing to reach", "Seconds without a reachable mushroom before /warp glowing. 0 = off.", 0, 60, 1,
                () -> m.noMushroomWarpMs / 1000.0, v -> m.noMushroomWarpMs = (long) (v * 1000), "%.0f s");
        ui.toggle("Gap jumps", "Let the pathfinder sprint-jump across one-block gaps.", () -> com.nablzz.addons.mushroom.pathing.Planner.parkour,
                v -> com.nablzz.addons.mushroom.pathing.Planner.parkour = v);
        ui.section("Detection");
        ui.choice("Marker particle", "Which particles mark a glowing mushroom. Use /glowing scan to find others.",
                new String[]{"Entity effect", "All potion types"},
                () -> MushroomTracker.markers.equals(MushroomTracker.POTION_PARTICLES) ? 1 : 0,
                i -> MushroomTracker.markers = i == 1 ? MushroomTracker.POTION_PARTICLES : MushroomTracker.DEFAULT_PARTICLES);
        ui.section("Route");
        ui.info("Waypoints", "Patrol points used when no mushroom is known (and in route mode).", () -> "" + rt.size(), Ui.TEXT);
        ui.button("Add waypoint here", "Records the spot you are standing on.", "Add", true, () -> {
            var p = Minecraft.getInstance().player;
            if (p != null) rt.add(p.getX(), p.getY(), p.getZ());
        });
        ui.button("Remove last waypoint", "Undo the last recorded point.", "Undo", false, rt::removeLast);
        ui.confirmButton("Clear route", "Deletes every waypoint.", "Clear", rt::clear);

        // ---- Fail-safe
        ui.tab("Fail-safe", "F");
        ui.section("Recovery");
        ui.toggle("Fail-safe", "Reconnect after a kick, rejoin SkyBlock, /warp glowing after dying or leaving the cave.", () -> fs.enabled, v -> fs.enabled = v);
        ui.text("Cave name", "Sidebar text that means you are in the cave.", () -> fs.zoneText, v -> fs.zoneText = v, 150, "glowing");
        ui.section("Avoid list");
        ui.info("Players", "Names you never want to share a lobby with.",
                () -> fs.avoid.isEmpty() ? "empty" : String.join(", ", fs.avoid.list()), Ui.MUTED);
        ui.add("Add player", "Type a name and press Add. The macro swaps lobby if one of them is in the tab list.", "name", "Add", v -> {
            if (!v.isEmpty()) fs.avoid.add(v);
        });
        ui.button("Clear the list", "Removes every name.", "Clear", false, fs.avoid::clear);
        ui.button("Check this lobby", "Looks for listed players in the tab list now.", "Check", false, () -> {
            String who = fs.avoid.scan(Minecraft.getInstance());
            ui.flash(who == null ? "Nobody from the list is in this lobby." : who + " is in this lobby!");
        });

        // ---- Display
        ui.tab("Display", "V");
        ui.section("Overlays");
        ui.toggle("Mushroom boxes", "Green boxes on glowing mushrooms, yellow on the one being mined, plus the planned path.",
                () -> GlowingMushroomMod.highlight, v -> GlowingMushroomMod.highlight = v);
        ui.toggle("Profit panel", "The small panel with time, mushrooms and coins (top left).", () -> ProfitHud.enabled, v -> ProfitHud.enabled = v);
        ui.section("Settings");
        ui.confirmButton("Reset mushroom settings", "Puts every mushroom setting back to its default (route and avoid list stay).", "Defaults", () -> MushroomConfig.resetToDefaults(m, fs));
    }

    private static void help() {
        Chat.msg("Commands:");
        Chat.msg(" /glowing - open the settings screen (also Right Shift)");
        Chat.msg(" /glowing help - this list");
        Chat.msg(" /glowing add - add a waypoint where you stand");
        Chat.msg(" /glowing undo - remove the last waypoint");
        Chat.msg(" /glowing clear - delete the whole route");
        Chat.msg(" /glowing list - route size, particle, mushrooms broken");
        Chat.msg(" /glowing start | stop - run or stop the macro");
        Chat.msg(" /glowing mode path|route - pathfind to mushroom groups (default) or only walk the route");
        Chat.msg(" /glowing nuker - toggle nuker (break all in reach) / aimed mining");
        Chat.msg(" /glowing highlight - toggle mushroom highlight boxes");
        Chat.msg(" /glowing nomush <seconds> - /warp glowing if there is nothing to reach that long (default 3, 0 = off)");
        Chat.msg(" /glowing path - stats of the last path plan (nodes, ms, jumps)");
        Chat.msg(" /glowing parkour on|off - allow sprint-jumping over 1-block gaps (default off)");
        Chat.msg(" /glowing avoid add|remove <name>, list, clear, check - players to avoid sharing a lobby with");
        Chat.msg(" /glowing hud - toggle the profit panel (top left)");
        Chat.msg(" /glowing failsafe - toggle auto reconnect / rejoin SkyBlock / warp back");
        Chat.msg(" /glowing zone [text] - sidebar text that means you're in the cave");
        Chat.msg(" /glowing where - show the sidebar lines and whether you're in the cave");
        Chat.msg(" /glowing reset - reset profit, counts and time");
        Chat.msg(" /glowing particle [ids|reset|potion] - show/set the marker particle");
        Chat.msg(" /glowing scan - run twice near mushrooms to list particle types");
        Chat.msg(" /glowing speed - show the speed read from the tab list");
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    public static volatile boolean highlight = true;

    private static final int GREEN = 0xFF30FF60;
    private static final int GREEN_FILL = 0x4030FF60;
    private static final int YELLOW = 0xFFFFE030;
    private static final int YELLOW_FILL = 0x50FFE030;

    /** Green box on every tracked glowing mushroom, yellow on the one being mined. */
    private void drawMushrooms() {
        if (!highlight) return;
        var target = controller.currentTarget();
        for (var bp : MushroomTracker.MUSHROOMS.keySet()) {
            boolean cur = bp.equals(target);
            Gizmos.cuboid(bp, cur ? GizmoStyle.strokeAndFill(YELLOW, 2.5f, YELLOW_FILL)
                    : GizmoStyle.strokeAndFill(GREEN, 2f, GREEN_FILL)).setAlwaysOnTop();
        }
    }

    private static final int CYAN = 0xFF30E0FF;
    private static final int CYAN_FILL = 0x3030E0FF;

    /** Cyan line along the planned path, cyan box on the standing spot, number of mushrooms above it. */
    private void drawPlan() {
        if (!highlight) return;
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
                default -> 0xFFFF40FF; // GAP
            };
            var v = pts.get(i);
            Gizmos.cuboid(new AABB(v.x - 0.2, v.y, v.z - 0.2, v.x + 0.2, v.y + 0.4, v.z + 0.2),
                    GizmoStyle.stroke(col, 2.5f)).setAlwaysOnTop();
        }
        var st = leg.stand();
        Gizmos.cuboid(new AABB(st.getX() + 0.2, st.getY(), st.getZ() + 0.2, st.getX() + 0.8, st.getY() + 0.1, st.getZ() + 0.8),
                GizmoStyle.strokeAndFill(CYAN, 2f, CYAN_FILL)).setAlwaysOnTop();
        Gizmos.billboardText(leg.targets().size() + "x", new Vec3(st.getX() + 0.5, st.getY() + 2.3, st.getZ() + 0.5),
                TextGizmo.Style.forColorAndCentered(CYAN).withScale(1.3f)).setAlwaysOnTop();
    }

    private static final int RED = 0xFFFF2020;
    private static final int RED_FILL = 0x30FF2020;

    /** Red box on every waypoint, a red line to the next one (looping) and its number floating above. */
    private void drawRoute() {
        int n = route.size();
        for (int i = 0; i < n; i++) {
            Vec3 p = route.get(i);
            Gizmos.cuboid(new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 0.6, p.z + 0.3),
                    GizmoStyle.strokeAndFill(RED, 2f, RED_FILL)).setAlwaysOnTop();
            if (n > 1) {
                Vec3 q = route.get((i + 1) % n);
                Gizmos.line(p.add(0, 0.3, 0), q.add(0, 0.3, 0), RED, 3f).setAlwaysOnTop();
            }
            Gizmos.billboardText(String.valueOf(i + 1), new Vec3(p.x, p.y + 1.1, p.z),
                    TextGizmo.Style.forColorAndCentered(0xFFFFFFFF).withScale(1.6f)).setAlwaysOnTop();
        }
    }
}
