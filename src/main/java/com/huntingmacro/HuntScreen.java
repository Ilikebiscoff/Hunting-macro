package com.huntingmacro;

import com.huntingmacro.pathing.Planner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The settings screen: sidebar with tabs, cards with toggles / sliders / text boxes, a live dashboard.
 * Everything is drawn by hand (no vanilla widgets) so it can have its own look. While it is open the macro
 * pauses (the game does not), so close it to let the macro move.
 */
public class HuntScreen extends Screen {
    private static final int SIDE = 112, HEADER = 38, PAD = 10, HINT_H = 16, ROW_H = 22, TITLE_H = 20;

    /** Set by any control that changed a setting; the file is written when the mouse is released / the screen closes. */
    private static boolean dirty;

    private final HuntController c;
    private final long openedAt = System.nanoTime();
    private final List<Tab> tabs = new ArrayList<>();
    private int tab;
    private float tabY = -1;
    private long lastFrame;
    private float scroll, scrollTarget, contentH;
    private Field focus;
    private Row dragRow;
    private boolean mouseDown;
    private String hint = "";
    private long confirmResetAt;
    private int wx, wy, ww, wh, vx, vy, vw, vh;

    public HuntScreen(HuntController controller) {
        super(Component.literal("Hunting Macro"));
        this.c = controller;
        buildTabs();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        blur();
        Config.save(c);
        super.onClose();
    }

    // ===================================================================================================
    // tabs
    // ===================================================================================================

    private void buildTabs() {
        // ---- Dashboard
        Tab t = tab("Dashboard", "D");
        Section s = t.section("Overview");
        s.add(new HeroRow());
        s.add(new TilesRow());
        s = t.section("Black hole");
        s.add(new BarRow("Black hole", "Time left on the hole you placed, or whether the next one is ready.",
                () -> {
                    double left = holeLeft();
                    return left > 0 ? Math.min(1.0, left / Math.max(1.0, c.holeTimeSec)) : (c.holeReadyNow() ? 1.0 : 0.0);
                },
                () -> {
                    double left = holeLeft();
                    if (left > 0) return String.format(Locale.ROOT, "out - %.1fs left", left);
                    return c.holeReadyNow() ? "ready" : "on cooldown";
                }));
        s.add(new InfoRow("Learned timings", "What the macro measured by itself (hit to hole, click to registered, ability cooldown).",
                () -> c.timingSamples == 0 ? "nothing yet"
                        : String.format(Locale.ROOT, "%d samples - hit>hole %.2fs - click %.2fs - cd %.1fs",
                                c.timingSamples, c.hitToHoleSec, c.useLatencySec, c.readyAfterSec), Ui.MUTED));
        s.add(new InfoRow("Safety", "Zone guard and lava incidents so far.",
                () -> (c.inZoneNow() ? "inside zone" : "OUTSIDE zone") + "  -  lava " + c.lavaIncidents() + "/" + c.lavaLimit,
                Ui.MUTED));
        s = t.section("Session");
        s.add(new ButtonRow("Reset statistics", "Sets kills, holes, shards and the session clock back to zero.", "Reset", false, () -> {
            c.resetStats();
            Session.reset();
        }));
        s.add(new ButtonRow("Reset all settings", "Puts every setting back to the built-in default (learned timings stay).", "Defaults", false, () -> {
            long now = System.currentTimeMillis();
            if (now - confirmResetAt < 3000) {
                Config.resetToDefaults(c);
                confirmResetAt = 0;
            } else {
                confirmResetAt = now;
            }
        }) {
            @Override
            String buttonText() {
                return System.currentTimeMillis() - confirmResetAt < 3000 ? "Click again" : "Defaults";
            }
        });

        // ---- Combat
        t = tab("Combat", "C");
        s = t.section("Weapon");
        s.add(text("Prime axe", "Part of the hotbar item name of your Prime Huntaxe.", () -> c.axeName, v -> c.axeName = v, 150, "name"));
        s.add(islider("Hits per zombie", "How many times each zombie is hit before the black hole.", 1, 6, () -> c.hitsPerZombie, v -> c.hitsPerZombie = v, ""));
        s.add(islider("Ticks between hits", "20 ticks = 1 second. The pet's double hit lands on the second swing.", 1, 20, () -> c.hitGapTicks, v -> c.hitGapTicks = v, " t"));
        s.add(choice("Golden axe step", "Optional step before the black hole (off by default).",
                new String[]{"Off", "Use", "Hit"}, () -> c.golden.ordinal() == 2 ? 0 : (c.golden == HuntController.Golden.USE ? 1 : 2),
                i -> c.golden = i == 1 ? HuntController.Golden.USE : (i == 2 ? HuntController.Golden.HIT : HuntController.Golden.OFF)));
        s = t.section("Targeting");
        s.add(text("Mob name filter", "Only hunt zombies whose name tag contains this. Empty = every zombie that has ESP.",
                () -> c.mobFilter, v -> c.mobFilter = v, 150, "any zombie"));
        s.add(dslider("Search range", "How far to look for zombies (the pathfinder reaches about 44 blocks).", 20, 150, 5, () -> c.range, v -> c.range = v, "%.0f blocks"));
        s.add(islider("Refresh grey zombies", "Every N kills the already-hit (grey) zombies become targets again. 0 = never.", 0, 20, () -> c.refreshEvery, v -> c.refreshEvery = v, " kills"));
        s.add(toggle("Gap jumps", "Let the pathfinder jump across one-block gaps.", () -> Planner.parkour, v -> Planner.parkour = v));

        // ---- Black hole
        t = tab("Black Hole", "B");
        s = t.section("Item");
        s.add(text("Hole item", "Part of the hotbar item name of the Medium Pocket Black Hole.", () -> c.holeName, v -> c.holeName = v, 170, "name"));
        s.add(islider("Swap delay", "Ticks to wait after switching to the hole before the single click.", 1, 20, () -> c.swapDelayTicks, v -> c.swapDelayTicks = v, " t"));
        s.add(islider("Holes per hit", "Black holes placed after every hit. One is what you want.", 1, 3, () -> c.holeCount, v -> c.holeCount = v, ""));
        s = t.section("Timing");
        s.add(toggle("Wait for catch message", "Place the next hole only after the \"You caught\" chat line of the last one.", () -> c.caughtGate, v -> c.caughtGate = v));
        s.add(text("Catch message", "Chat text that means the last hole finished.", () -> c.caughtText, v -> c.caughtText = v, 150, "You caught"));
        s.add(text("Catch mob", "The line must also contain this mob name.", () -> c.caughtMob, v -> c.caughtMob = v, 150, "any"));
        s.add(toggle("Auto-learn timings", "Measure hole time, click delay and lead from real holes and keep them up to date.", () -> c.holeAuto, v -> {
            c.holeAuto = v;
            c.saveSettings();
        }));
        s.add(dslider("Hole duration", "How long one hole needs to suck a zombie in. Setting it by hand turns auto-learning off.", 1, 30, 0.5, () -> c.holeTimeSec,
                v -> {
                    c.holeTimeSec = v;
                    c.holeAuto = false;
                    c.saveSettings();
                }, "%.1f s"));
        s.add(dslider("Hit lead", "Hit the next zombie this long before the old hole ends.", 0, 5, 0.1, () -> c.holeLeadSec,
                v -> {
                    c.holeLeadSec = v;
                    c.holeLeadManual = true;
                    c.saveSettings();
                }, "%.1f s"));
        s.add(new ButtonRow("Forget learned timings", "Throws the measured values away; they are learned again from the next holes.", "Forget", false, c::forgetLearned));

        // ---- Area & zone
        t = tab("Area", "A");
        s = t.section("Scan box");
        s.add(toggle("Use fixed box", "Only zombies inside this box get ESP and are hunted. Off = everything within the search range.", () -> c.areaOn, v -> c.areaOn = v));
        s.add(nums("X range", "West to east block coordinates (inclusive).", new String[]{"from", "to"},
                new IntSupplier[]{() -> c.areaX1, () -> c.areaX2}, new IntConsumer[]{v -> c.areaX1 = v, v -> c.areaX2 = v}));
        s.add(nums("Z range", "North to south block coordinates (inclusive).", new String[]{"from", "to"},
                new IntSupplier[]{() -> c.areaZ1, () -> c.areaZ2}, new IntConsumer[]{v -> c.areaZ1 = v, v -> c.areaZ2 = v}));
        s.add(nums("Y range", "Height of the box (zombies get a few blocks of slack).", new String[]{"from", "to"},
                new IntSupplier[]{() -> c.areaY1, () -> c.areaY2}, new IntConsumer[]{v -> c.areaY1 = v, v -> c.areaY2 = v}));
        s.add(new ButtonRow("Centre on me", "Sets X and Z to 40 blocks around where you stand (Y stays).", "Use position", false, () -> {
            var p = Minecraft.getInstance().player;
            if (p == null) return;
            c.areaX1 = p.getBlockX() - 40;
            c.areaX2 = p.getBlockX() + 40;
            c.areaZ1 = p.getBlockZ() - 40;
            c.areaZ2 = p.getBlockZ() + 40;
            dirty = true;
        }));
        s = t.section("Zone guard");
        s.add(toggle("Stay in the zone", "If the sidebar shows another area, walk back to where you were 3 moves ago.", () -> c.zoneGuard, v -> c.zoneGuard = v));
        s.add(text("Zone name", "Sidebar text of the hunting area.", () -> c.zoneText, v -> c.zoneText = v, 150, "Diamond Reserve"));
        s.add(text("Recovery command", "Run when stuck or nothing to hunt (without the slash). Empty = just re-plan.", () -> c.warpCmd, v -> c.warpCmd = v, 150, "none"));
        s.add(dslider("Idle before recovery", "Seconds without a target before the recovery command runs.", 3, 60, 1, () -> c.idleWarpMs / 1000.0, v -> c.idleWarpMs = (long) (v * 1000), "%.0f s"));

        // ---- Safety
        t = tab("Safety", "S");
        s = t.section("Lava");
        s.add(toggle("Lava rescue trip", "After too many lava incidents: warp out, take the lift back down.", () -> c.lavaRescue, v -> c.lavaRescue = v));
        s.add(islider("Incidents before rescue", "More than this many lava incidents starts the rescue.", 1, 10, () -> c.lavaLimit, v -> c.lavaLimit = v, ""));
        s.add(text("Rescue command", "Command that gets you out (without the slash).", () -> c.rescueCmd, v -> c.rescueCmd = v, 150, "warp deep"));
        s = t.section("Lift");
        s.add(nums("Lift position", "Where the Lift Operator stands (x y z).", new String[]{"x", "y", "z"},
                new IntSupplier[]{() -> c.liftX, () -> c.liftY, () -> c.liftZ}, new IntConsumer[]{v -> c.liftX = v, v -> c.liftY = v, v -> c.liftZ = v}));
        s.add(text("NPC name", "Name of the lift NPC.", () -> c.liftNpc, v -> c.liftNpc = v, 150, "Lift Operator"));
        s.add(text("Extra area text", "Another sidebar area that counts as in-zone (Deep Caverns).", () -> c.deepText, v -> c.deepText = v, 150, "Deep Caverns"));
        s.add(new ButtonRow("Test the lift trip", "Runs the whole lift trip now (closes this screen).", "Run", false, () -> {
            Config.save(c);
            Minecraft mc = Minecraft.getInstance();
            onClose();
            c.testLift(mc, true);
        }));

        // ---- Visuals
        t = tab("Visuals", "V");
        s = t.section("In the world");
        s.add(toggle("ESP and path", "Boxes around zombies (green = target, yellow = current, grey = already hit) and the planned path.", () -> Config.highlight, v -> Config.highlight = v));
        s.add(toggle("Debug output", "Prints what the scan and pathfinder see into chat every few seconds.", () -> c.debug, v -> c.debug = v));
        s = t.section("On screen");
        s.add(toggle("Status panel", "The small panel with status, kills and shards.", () -> Config.hud, v -> Config.hud = v));
        s.add(choice("Panel corner", "Where the status panel sits.", new String[]{"Top left", "Top right", "Bottom left", "Bottom right"},
                () -> Config.hudCorner, i -> Config.hudCorner = i));
        s.add(choice("Accent colour", "Colour of this screen and the panel.", Ui.ACCENT_NAMES, () -> Config.accentIdx, i -> {
            Config.accentIdx = i;
            Ui.setAccent(i);
        }));

        // ---- Updates
        t = tab("Updates", "U");
        s = t.section("Version");
        s.add(new InfoRow("Installed / latest", "Versions of this jar and of the newest build.", Updater::versionInfo, Ui.TEXT));
        s.add(new InfoRow("Last message", "What the updater said last.", () -> Updater.lastMessage, Ui.MUTED));
        s.add(new ButtonRow("Check for update", "Looks at the latest build on GitHub.", "Check", true, () -> Updater.run(false, false)));
        s.add(new ButtonRow("Download update", "Downloads the newest jar into your mods folder (hash-checked). Restart the game afterwards.", "Download", true, () -> Updater.run(false, true)));
        s = t.section("About");
        s.add(new NoteRow("Open this screen with the \"Hunting Macro\" key (Right Shift by default, change it in Controls) or /hunt gui. "
                + "The macro pauses while this screen is open - close it to let it run. /hunt help lists every command."));
        s.add(new NoteRow("Heads-up: automating the game on Hypixel breaks their rules and can get your account banned. Use at your own risk."));
    }

    private double holeLeft() {
        return Math.max(0, c.holeLeftSec());
    }

    // ===================================================================================================
    // layout + frame
    // ===================================================================================================

    private void layoutWindow() {
        ww = Math.min(width - 16, 640);
        wh = Math.min(height - 16, 390);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        vx = wx + SIDE + PAD;
        vy = wy + HEADER + 4;
        vw = ww - SIDE - PAD * 2;
        vh = wh - HEADER - 4 - HINT_H - 6;
    }

    private float openFade() {
        return Ui.ease((System.nanoTime() - openedAt) / 180_000_000f);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.alpha = openFade();
        Ui.fill(g, 0, 0, width, height, 0xB0050608);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        long now = System.nanoTime();
        Ui.dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float fade = openFade();
        Ui.alpha = fade;
        layoutWindow();
        int slide = (int) ((1f - fade) * 10);
        int oy = slide; // whole window drifts up while fading in
        wy += oy;
        vy += oy;

        Ui.shadow(g, wx, wy, ww, wh, 6);
        Ui.rrb(g, wx, wy, ww, wh, 6, Ui.WINDOW, 0xFF262B36);
        Ui.rr(g, wx + 1, wy + 1, SIDE + 6, wh - 2, 5, Ui.SIDEBAR);
        Ui.fill(g, wx + SIDE, wy + 1, 8, wh - 2, Ui.WINDOW);
        Ui.fill(g, wx + SIDE, wy + 1, 1, wh - 2, Ui.LINE);

        drawSidebar(g, mx, my);
        drawHeader(g, mx, my);
        drawContent(g, mx, my);

        // hint bar
        Ui.fill(g, vx, wy + wh - HINT_H - 4, vw, 1, Ui.LINE);
        Ui.text(g, Ui.fit(hint, vw), vx, wy + wh - HINT_H + 1, Ui.MUTED);
        Ui.alpha = 1f;
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        // logo
        int lx = wx + 12, ly = wy + 12;
        Ui.rr(g, lx, ly, 22, 22, 6, Ui.accent);
        Ui.rr(g, lx + 1, ly + 1, 20, 10, 5, Ui.withAlpha(0xFFFFFF, 40));
        Ui.textC(g, "H", lx + 11, ly + 7, 0xFFFFFFFF);
        Ui.bold(g, "Hunting", lx + 28, ly + 2, Ui.TEXT);
        Ui.text(g, "Macro", lx + 28, ly + 12, Ui.accent);

        int ty = wy + 48;
        int th = 24;
        float target = ty + tab * (th + 3);
        if (tabY < 0) tabY = target;
        tabY = Ui.approach(tabY, target, 700f);
        // moving selection pill
        Ui.rr(g, wx + 8, (int) tabY, SIDE - 14, th, 5, Ui.mix(Ui.SIDEBAR, Ui.accent, 0.20f));
        Ui.rr(g, wx + 8, (int) tabY + 4, 2, th - 8, 1, Ui.accent);
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            int y = ty + i * (th + 3);
            boolean hov = Ui.in(mx, my, wx + 8, y, SIDE - 14, th);
            t.hov = Ui.approach(t.hov, hov ? 1f : 0f, 8f);
            if (i != tab && t.hov > 0) Ui.rr(g, wx + 8, y, SIDE - 14, th, 5, Ui.withAlpha(0xFFFFFF, (int) (10 * t.hov)));
            boolean sel = i == tab;
            int ic = Ui.mix(0xFF1A1E27, Ui.accent, sel ? 0.9f : 0.15f + 0.2f * t.hov);
            Ui.rr(g, wx + 16, y + 5, 14, 14, 4, ic);
            Ui.textC(g, t.letter, wx + 23, y + 8, sel ? 0xFFFFFFFF : Ui.MUTED);
            Ui.text(g, t.name, wx + 37, y + 8, sel ? Ui.TEXT : Ui.mix(Ui.MUTED, Ui.TEXT, t.hov));
        }
        Ui.text(g, Updater.shortVersion(), wx + 14, wy + wh - 24, Ui.DIM);
        Ui.text(g, "Right Shift: open/close", wx + 14, wy + wh - 13, Ui.DIM);
    }

    private int closeX() {
        return wx + ww - 22;
    }

    private int startX() {
        return closeX() - 8 - 70;
    }

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        Tab t = tabs.get(tab);
        Ui.bold(g, t.name, vx, wy + 14, Ui.TEXT);
        Ui.fill(g, wx + SIDE + 1, wy + HEADER, ww - SIDE - 2, 1, Ui.LINE);

        boolean run = c.isRunning();
        // close
        int cx = closeX(), cy = wy + 10;
        boolean hc = Ui.in(mx, my, cx, cy, 14, 14);
        Ui.rr(g, cx, cy, 14, 14, 4, hc ? 0xFF3A2024 : Ui.CARD);
        Ui.textC(g, "x", cx + 7, cy + 3, hc ? Ui.BAD : Ui.MUTED);
        // start / stop
        int bx = startX(), by = wy + 8;
        boolean hb = Ui.in(mx, my, bx, by, 70, 18);
        int base = run ? Ui.BAD : Ui.GOOD;
        Ui.rr(g, bx, by, 70, 18, 5, hb ? Ui.mix(base, 0xFFFFFFFF, 0.15f) : Ui.mix(base, 0xFF000000, 0.35f));
        Ui.rr(g, bx + 1, by + 1, 68, 8, 4, Ui.withAlpha(0xFFFFFF, 22));
        Ui.textC(g, run ? "Stop" : "Start", bx + 35, by + 5, 0xFFFFFFFF);
        // status pill
        String st = c.phaseLabel();
        int pw = Ui.tw(st) + 24;
        int px = bx - 8 - pw;
        Ui.rr(g, px, wy + 10, pw, 14, 7, Ui.CARD);
        float pulse = run ? 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 220.0) : 1f;
        Ui.rr(g, px + 7, wy + 14, 6, 6, 3, Ui.mix(Ui.CARD, run ? Ui.GOOD : Ui.BAD, pulse));
        Ui.text(g, st, px + 17, wy + 13, Ui.TEXT);
    }

    private void layoutTab(Tab t) {
        int y = 0;
        for (Section s : t.sections) {
            s.relY = y;
            int ry = TITLE_H;
            for (Row r : s.rows) {
                r.prepare(vw - 8 - 20);
                r.relY = ry;
                ry += r.h;
            }
            s.h = ry + 6;
            y += s.h + 10;
        }
        contentH = y;
    }

    private void drawContent(GuiGraphicsExtractor g, int mx, int my) {
        Tab t = tabs.get(tab);
        layoutTab(t);
        float maxScroll = Math.max(0, contentH - vh);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll += (scrollTarget - scroll) * Math.min(1f, Ui.dt * 14f);
        if (Math.abs(scrollTarget - scroll) < 0.3f) scroll = scrollTarget;

        if (mouseDown && dragRow != null) dragRow.drag(mx);

        hint = "";
        g.enableScissor(vx - 2, vy, vx + vw + 2, vy + vh);
        int cardW = vw - 8;
        for (Section s : t.sections) {
            int sy = vy + (int) (s.relY - scroll);
            if (sy > vy + vh || sy + s.h < vy) {
                for (Row r : s.rows) r.y = Integer.MIN_VALUE / 2; // off screen: not clickable
                continue;
            }
            Ui.rrb(g, vx, sy, cardW, s.h, 5, Ui.CARD, Ui.LINE);
            Ui.hgrad(g, vx + 6, sy + 1, cardW - 12, 1, Ui.withAlpha(Ui.accent, 0), Ui.withAlpha(Ui.accent, 150));
            Ui.rr(g, vx + 10, sy + 8, 3, 8, 1, Ui.accent);
            Ui.bold(g, s.title, vx + 18, sy + 7, Ui.TEXT);
            boolean first = true;
            for (Row r : s.rows) {
                r.x = vx + 10;
                r.w = cardW - 20;
                r.y = sy + r.relY;
                if (!first) Ui.fill(g, r.x, r.y, r.w, 1, Ui.withAlpha(0xFFFFFF, 10));
                first = false;
                boolean hov = Ui.in(mx, my, r.x - 4, r.y, r.w + 8, r.h) && Ui.in(mx, my, vx, vy, vw, vh);
                r.hov = Ui.approach(r.hov, hov ? 1f : 0f, 8f);
                if (r.hov > 0.01f && r.hoverable())
                    Ui.rr(g, r.x - 4, r.y + 1, r.w + 8, r.h - 2, 4, Ui.withAlpha(0xFFFFFF, (int) (9 * r.hov)));
                if (hov) hint = r.hint;
                r.draw(g, mx, my, hov);
            }
        }
        g.disableScissor();

        // scrollbar
        if (contentH > vh) {
            int bh = Math.max(20, (int) (vh * (vh / contentH)));
            int by = vy + (int) ((vh - bh) * (maxScroll <= 0 ? 0 : scroll / maxScroll));
            Ui.rr(g, vx + vw - 3, vy, 3, vh, 1, Ui.withAlpha(0xFFFFFF, 12));
            Ui.rr(g, vx + vw - 3, by, 3, bh, 1, Ui.withAlpha(0xFFFFFF, 70));
        }
    }

    // ===================================================================================================
    // input
    // ===================================================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        int btn = e.button();
        mouseDown = true;
        boolean hadFocus = focus != null;
        Field was = focus;

        if (Ui.in(mx, my, closeX(), wy + 10, 14, 14)) {
            onClose();
            return true;
        }
        if (Ui.in(mx, my, startX(), wy + 8, 70, 18)) {
            toggleRun();
            return true;
        }
        int ty = wy + 48;
        for (int i = 0; i < tabs.size(); i++) {
            if (Ui.in(mx, my, wx + 8, ty + i * 27, SIDE - 14, 24)) {
                blur();
                if (tab != i) {
                    tab = i;
                    scroll = scrollTarget = 0;
                }
                return true;
            }
        }
        if (Ui.in(mx, my, vx, vy, vw, vh)) {
            for (Section s : tabs.get(tab).sections) {
                for (Row r : s.rows) {
                    if (my < r.y || my >= r.y + r.h) continue;
                    if (r.down(mx, my, btn)) {
                        if (r.draggable()) dragRow = r;
                        if (hadFocus && focus == was && !r.ownsFocus(was)) blur();
                        return true;
                    }
                }
            }
        }
        blur();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        mouseDown = false;
        if (dragRow != null) {
            dragRow.up();
            dragRow = null;
        }
        saveIfDirty();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        return true; // sliders follow the mouse in the render loop
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scrollTarget -= (float) (sy * 30);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (focus != null) {
            if (key == 256 || key == 257 || key == 335) { // esc / enter: leave the text box
                blur();
                return true;
            }
            focus.key(key, e.modifiers());
            return true;
        }
        if (key == 256 || key == 344) { // esc / right shift
            onClose();
            return true;
        }
        if (key == 265) scrollTarget -= 40;      // up
        else if (key == 264) scrollTarget += 40; // down
        else if (key == 266) scrollTarget -= vh; // page up
        else if (key == 267) scrollTarget += vh; // page down
        else return super.keyPressed(e);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (focus != null) {
            focus.insert(new String(Character.toChars(e.codepoint())));
            return true;
        }
        return super.charTyped(e);
    }

    private void toggleRun() {
        Minecraft mc = Minecraft.getInstance();
        if (c.isRunning()) {
            c.stop(mc);
        } else {
            Config.save(c);
            if (c.start(mc)) onClose(); // the macro pauses while a screen is open
        }
    }

    private void blur() {
        if (focus != null) {
            focus.focused = false;
            focus = null;
        }
        saveIfDirty();
    }

    private void saveIfDirty() {
        if (dirty) {
            dirty = false;
            Config.save(c);
        }
    }

    // ===================================================================================================
    // model
    // ===================================================================================================

    private Tab tab(String name, String letter) {
        Tab t = new Tab(name, letter);
        tabs.add(t);
        return t;
    }

    private static final class Tab {
        final String name, letter;
        final List<Section> sections = new ArrayList<>();
        float hov;

        Tab(String name, String letter) {
            this.name = name;
            this.letter = letter;
        }

        Section section(String title) {
            Section s = new Section(title);
            sections.add(s);
            return s;
        }
    }

    private static final class Section {
        final String title;
        final List<Row> rows = new ArrayList<>();
        int relY, h;

        Section(String title) {
            this.title = title;
        }

        void add(Row r) {
            rows.add(r);
        }
    }

    // ---- row factories ------------------------------------------------------------------------------

    private Row toggle(String label, String hint, BooleanSupplier get, Consumer<Boolean> set) {
        return new ToggleRow(label, hint, get, set);
    }

    private Row text(String label, String hint, Supplier<String> get, Consumer<String> set, int fieldW, String placeholder) {
        return new TextRow(label, hint, new Field(false, fieldW, get, set, placeholder));
    }

    private Row islider(String label, String hint, int min, int max, IntSupplier get, IntConsumer set, String suffix) {
        return new SliderRow(label, hint, min, max, 1, () -> get.getAsInt(), v -> set.accept((int) Math.round(v)),
                v -> Math.round(v) + suffix);
    }

    private Row dslider(String label, String hint, double min, double max, double step, DoubleSupplier get, DoubleConsumer set, String fmt) {
        return new SliderRow(label, hint, min, max, step, get, set, v -> String.format(Locale.ROOT, fmt, v));
    }

    private Row choice(String label, String hint, String[] options, IntSupplier get, IntConsumer set) {
        return new ChoiceRow(label, hint, options, get, set);
    }

    private Row nums(String label, String hint, String[] captions, IntSupplier[] get, IntConsumer[] set) {
        Field[] f = new Field[get.length];
        for (int i = 0; i < f.length; i++) {
            final int k = i;
            f[i] = new Field(true, 48, () -> "" + get[k].getAsInt(), v -> set[k].accept(Integer.parseInt(v)), "0");
        }
        return new NumbersRow(label, hint, captions, f);
    }

    // ---- rows -------------------------------------------------------------------------------------------

    private abstract class Row {
        final String label, hint;
        int x, y, w, h = ROW_H, relY;
        float hov;

        Row(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        void prepare(int innerW) {}

        boolean hoverable() {
            return true;
        }

        abstract void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered);

        boolean down(double mx, double my, int button) {
            return false;
        }

        boolean draggable() {
            return false;
        }

        void drag(double mx) {}

        void up() {}

        boolean ownsFocus(Field f) {
            return false;
        }

        void drawLabel(GuiGraphicsExtractor g) {
            Ui.text(g, label, x, y + 7, Ui.TEXT);
        }
    }

    private final class ToggleRow extends Row {
        final BooleanSupplier get;
        final Consumer<Boolean> set;
        float anim = -1;

        ToggleRow(String label, String hint, BooleanSupplier get, Consumer<Boolean> set) {
            super(label, hint);
            this.get = get;
            this.set = set;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            boolean on = get.getAsBoolean();
            if (anim < 0) anim = on ? 1f : 0f;
            anim = Ui.approach(anim, on ? 1f : 0f, 9f);
            int tx = x + w - 30, ty = y + 4;
            Ui.rr(g, tx, ty, 30, 14, 7, Ui.mix(Ui.OFF, Ui.accent, anim));
            int kx = tx + 2 + (int) (anim * 16);
            Ui.rr(g, kx, ty + 2, 10, 10, 5, Ui.mix(0xFFB6BCC9, 0xFFFFFFFF, anim));
            Ui.textR(g, on ? "ON" : "OFF", tx - 8, y + 7, on ? Ui.accent : Ui.DIM);
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (mx < x + w - 80) return false;
            set.accept(!get.getAsBoolean());
            dirty = true;
            return true;
        }
    }

    private final class SliderRow extends Row {
        final double min, max, step;
        final DoubleSupplier get;
        final DoubleConsumer set;
        final java.util.function.DoubleFunction<String> fmt;
        float shown = -1;

        SliderRow(String label, String hint, double min, double max, double step, DoubleSupplier get, DoubleConsumer set,
                java.util.function.DoubleFunction<String> fmt) {
            super(label, hint);
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            this.fmt = fmt;
        }

        int trackW() {
            return Math.max(70, Math.min(140, w / 3));
        }

        int trackX() {
            return x + w - trackW();
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            double v = get.getAsDouble();
            float f = (float) ((Math.max(min, Math.min(max, v)) - min) / (max - min));
            if (shown < 0) shown = f;
            shown = dragRow == this ? f : Ui.approach(shown, f, 4f);
            int tw = trackW(), tx = trackX(), cy = y + 11;
            Ui.rr(g, tx, cy - 2, tw, 4, 2, Ui.OFF);
            int fw = (int) (shown * tw);
            if (fw > 0) Ui.rr(g, tx, cy - 2, Math.max(4, fw), 4, 2, Ui.accent);
            int kx = tx + (int) (shown * (tw - 8));
            boolean act = dragRow == this || hovered;
            Ui.rr(g, kx, cy - 5, 8, 10, 4, act ? 0xFFFFFFFF : 0xFFD0D4DE);
            Ui.textR(g, fmt.apply(v), tx - 8, y + 7, Ui.TEXT);
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (mx < trackX() - 70) return false;
            drag(mx);
            return true;
        }

        @Override
        boolean draggable() {
            return true;
        }

        @Override
        void drag(double mx) {
            double f = Math.max(0, Math.min(1, (mx - trackX() - 4) / (double) (trackW() - 8)));
            double v = min + f * (max - min);
            v = Math.round(v / step) * step;
            v = Math.max(min, Math.min(max, v));
            if (Math.abs(v - get.getAsDouble()) > 1e-9) {
                set.accept(v);
                dirty = true;
            }
        }
    }

    private final class TextRow extends Row {
        final Field field;

        TextRow(String label, String hint, Field field) {
            super(label, hint);
            this.field = field;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            field.x = x + w - field.w;
            field.y = y + 3;
            field.draw(g, mx, my);
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (!field.hit(mx, my)) return false;
            if (focus != field) {
                blur();
                focus = field;
                field.focused = true;
                field.sel = true; // select all: typing replaces
            }
            field.click(mx);
            return true;
        }

        @Override
        boolean ownsFocus(Field f) {
            return f == field;
        }
    }

    private final class NumbersRow extends Row {
        final String[] captions;
        final Field[] fields;

        NumbersRow(String label, String hint, String[] captions, Field[] fields) {
            super(label, hint);
            this.captions = captions;
            this.fields = fields;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            int right = x + w;
            for (int i = fields.length - 1; i >= 0; i--) {
                Field f = fields[i];
                f.x = right - f.w;
                f.y = y + 3;
                f.draw(g, mx, my);
                int cw = Ui.tw(captions[i]);
                Ui.text(g, captions[i], f.x - 5 - cw, y + 7, Ui.DIM);
                right = f.x - 5 - cw - 10;
            }
        }

        @Override
        boolean down(double mx, double my, int button) {
            for (Field f : fields) {
                if (!f.hit(mx, my)) continue;
                if (focus != f) {
                    blur();
                    focus = f;
                    f.focused = true;
                    f.sel = true;
                }
                f.click(mx);
                return true;
            }
            return false;
        }

        @Override
        boolean ownsFocus(Field f) {
            for (Field x : fields) if (x == f) return true;
            return false;
        }
    }

    private final class ChoiceRow extends Row {
        final String[] options;
        final IntSupplier get;
        final IntConsumer set;

        ChoiceRow(String label, String hint, String[] options, IntSupplier get, IntConsumer set) {
            super(label, hint);
            this.options = options;
            this.get = get;
            this.set = set;
        }

        int bw() {
            int m = 0;
            for (String o : options) m = Math.max(m, Ui.tw(o));
            return m + 30;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            int bw = bw(), bx = x + w - bw, by = y + 3;
            boolean hb = Ui.in(mx, my, bx, by, bw, 16);
            Ui.rrb(g, bx, by, bw, 16, 4, hb ? Ui.CARD_HI : Ui.FIELD, hb ? Ui.accent : Ui.LINE);
            String s = options[Math.floorMod(get.getAsInt(), options.length)];
            Ui.textC(g, s, bx + bw / 2, by + 4, Ui.TEXT);
            Ui.text(g, "<", bx + 5, by + 4, hb ? Ui.accent : Ui.DIM);
            Ui.textR(g, ">", bx + bw - 5, by + 4, hb ? Ui.accent : Ui.DIM);
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (mx < x + w - bw()) return false;
            int n = options.length;
            int cur = Math.floorMod(get.getAsInt(), n);
            boolean back = button == 1 || mx < x + w - bw() + 16;
            set.accept(Math.floorMod(cur + (back ? -1 : 1), n));
            dirty = true;
            return true;
        }
    }

    private class ButtonRow extends Row {
        final String text;
        final boolean primary;
        final Runnable action;

        ButtonRow(String label, String hint, String text, boolean primary, Runnable action) {
            super(label, hint);
            this.text = text;
            this.primary = primary;
            this.action = action;
        }

        String buttonText() {
            return text;
        }

        int bw() {
            return Ui.tw(buttonText()) + 24;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            int bw = bw(), bx = x + w - bw, by = y + 3;
            boolean hb = Ui.in(mx, my, bx, by, bw, 16);
            int base = primary ? Ui.accent : Ui.OFF;
            Ui.rr(g, bx, by, bw, 16, 4, hb ? Ui.mix(base, 0xFFFFFFFF, 0.18f) : base);
            Ui.rr(g, bx + 1, by + 1, bw - 2, 7, 3, Ui.withAlpha(0xFFFFFF, 18));
            Ui.textC(g, buttonText(), bx + bw / 2, by + 4, 0xFFFFFFFF);
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (mx < x + w - bw()) return false;
            action.run();
            return true;
        }
    }

    private final class InfoRow extends Row {
        final Supplier<String> value;
        final int color;

        InfoRow(String label, String hint, Supplier<String> value, int color) {
            super(label, hint);
            this.value = value;
            this.color = color;
        }

        @Override
        boolean hoverable() {
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            String v = value.get();
            Ui.textR(g, Ui.fit(v == null ? "" : v, w - Ui.tw(label) - 16), x + w, y + 7, color);
        }
    }

    private final class BarRow extends Row {
        final DoubleSupplier frac;
        final Supplier<String> text;
        float shown;

        BarRow(String label, String hint, DoubleSupplier frac, Supplier<String> text) {
            super(label, hint);
            this.frac = frac;
            this.text = text;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            int bw = Math.max(120, Math.min(220, w / 2)), bx = x + w - bw, by = y + 4;
            shown = Ui.approach(shown, (float) frac.getAsDouble(), 2f);
            Ui.rr(g, bx, by, bw, 14, 4, Ui.FIELD);
            int fw = (int) (shown * (bw - 2));
            if (fw > 0) Ui.rr(g, bx + 1, by + 1, Math.max(4, fw), 12, 3, Ui.mix(Ui.accent, 0xFF000000, 0.25f));
            Ui.textC(g, text.get(), bx + bw / 2, by + 3, Ui.TEXT);
        }
    }

    private final class NoteRow extends Row {
        final String text;
        List<String> lines = List.of();
        int lastW = -1;

        NoteRow(String text) {
            super("", "");
            this.text = text;
        }

        @Override
        void prepare(int innerW) {
            if (innerW == lastW) return;
            lastW = innerW;
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (Ui.tw(tryLine) > innerW && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            lines = out;
            h = 8 + lines.size() * 11;
        }

        @Override
        boolean hoverable() {
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            int ty = y + 4;
            for (String l : lines) {
                Ui.text(g, l, x, ty, Ui.MUTED);
                ty += 11;
            }
        }
    }

    /** Dashboard: big status + start/stop. */
    private final class HeroRow extends Row {
        HeroRow() {
            super("", "Current state of the macro.");
            h = 54;
        }

        @Override
        boolean hoverable() {
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            boolean run = c.isRunning();
            float pulse = run ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 250.0) : 1f;
            Ui.rr(g, x, y + 8, 8, 8, 4, Ui.mix(Ui.CARD, run ? Ui.GOOD : Ui.BAD, pulse));
            Ui.big(g, run ? "Running" : "Stopped", x + 16, y + 6, 1.6f, run ? Ui.GOOD : Ui.TEXT);
            String sub = run ? c.phaseLabel() + "  -  " + c.zombiesInView() + " zombies in view" : "Press Start, then close this screen.";
            Ui.text(g, Ui.fit(sub, w - 130), x + 16, y + 28, Ui.MUTED);
            int bw = 96, bx = x + w - bw, by = y + 8;
            boolean hb = Ui.in(mx, my, bx, by, bw, 26);
            int base = run ? Ui.BAD : Ui.GOOD;
            Ui.rr(g, bx, by, bw, 26, 6, hb ? Ui.mix(base, 0xFFFFFFFF, 0.15f) : Ui.mix(base, 0xFF000000, 0.35f));
            Ui.rr(g, bx + 1, by + 1, bw - 2, 11, 5, Ui.withAlpha(0xFFFFFF, 22));
            Ui.textC(g, run ? "Stop macro" : "Start macro", bx + bw / 2, by + 9, 0xFFFFFFFF);
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (!Ui.in(mx, my, x + w - 96, y + 8, 96, 26)) return false;
            toggleRun();
            return true;
        }
    }

    /** Dashboard: six number tiles. */
    private final class TilesRow extends Row {
        TilesRow() {
            super("", "Counters since the macro was last reset.");
            h = 84;
        }

        @Override
        boolean hoverable() {
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            String[][] tiles = {
                    {"" + c.killCount(), "Kills"},
                    {"" + c.holesPlaced, "Black holes"},
                    {"" + c.shards, "Shards"},
                    {String.format(Locale.ROOT, "%.0f", Session.perHour(c.shards)), "Shards / hour"},
                    {"" + c.hits, "Hits landed"},
                    {Session.clock(), "Run time"},
            };
            int gap = 6, tw = (w - gap * 2) / 3, th = 36;
            for (int i = 0; i < tiles.length; i++) {
                int tx = x + (i % 3) * (tw + gap), ty = y + 4 + (i / 3) * (th + gap);
                Ui.rrb(g, tx, ty, tw, th, 5, Ui.FIELD, Ui.LINE);
                Ui.rr(g, tx + 6, ty + 8, 2, th - 16, 1, Ui.accent);
                Ui.textShadow(g, tiles[i][0], tx + 14, ty + 8, Ui.TEXT);
                Ui.text(g, tiles[i][1], tx + 14, ty + 21, Ui.MUTED);
            }
        }
    }

    // ---- text box ---------------------------------------------------------------------------------------

    private static final class Field {
        final boolean numeric;
        final int w;
        final Supplier<String> get;
        final Consumer<String> set;
        final String placeholder;
        String text = "";
        int caret, x, y;
        boolean focused, sel, bad;

        Field(boolean numeric, int w, Supplier<String> get, Consumer<String> set, String placeholder) {
            this.numeric = numeric;
            this.w = w;
            this.get = get;
            this.set = set;
            this.placeholder = placeholder;
            this.text = get.get();
            this.caret = text.length();
        }

        boolean hit(double mx, double my) {
            return Ui.in(mx, my, x, y, w, 16);
        }

        int viewStart() {
            int start = 0;
            while (start < caret && Ui.tw(text.substring(start, caret)) > w - 14) start++;
            return start;
        }

        void click(double mx) {
            sel = false;
            int start = viewStart();
            int best = text.length();
            for (int i = start; i <= text.length(); i++) {
                if (x + 6 + Ui.tw(text.substring(start, i)) >= mx) {
                    best = i;
                    break;
                }
            }
            caret = best;
        }

        void draw(GuiGraphicsExtractor g, int mx, int my) {
            if (!focused) {
                text = get.get();
                caret = Math.min(caret, text.length());
                bad = false;
            }
            boolean hb = Ui.in(mx, my, x, y, w, 16);
            int border = bad ? Ui.BAD : focused ? Ui.accent : hb ? 0xFF3A4150 : Ui.LINE;
            Ui.rrb(g, x, y, w, 16, 4, Ui.FIELD, border);
            if (text.isEmpty() && !focused) {
                Ui.text(g, Ui.fit(placeholder, w - 10), x + 6, y + 4, Ui.DIM);
                return;
            }
            int start = focused ? viewStart() : 0;
            String shown = text.substring(start);
            while (shown.length() > 1 && Ui.tw(shown) > w - 10) shown = shown.substring(0, shown.length() - 1);
            if (focused && sel && !text.isEmpty())
                Ui.rr(g, x + 5, y + 3, Math.min(w - 10, Ui.tw(shown)) + 2, 10, 2, Ui.withAlpha(Ui.accent, 110));
            Ui.text(g, shown, x + 6, y + 4, bad ? Ui.BAD : Ui.TEXT);
            if (focused && !sel && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = x + 6 + Ui.tw(text.substring(start, Math.max(start, caret)));
                Ui.fill(g, cx, y + 3, 1, 10, Ui.TEXT);
            }
        }

        void insert(String s) {
            if (numeric) {
                StringBuilder f = new StringBuilder();
                for (char ch : s.toCharArray()) if (ch == '-' || (ch >= '0' && ch <= '9')) f.append(ch);
                s = f.toString();
            } else {
                s = s.replace("\n", "").replace("\r", "");
            }
            if (s.isEmpty()) return;
            if (sel) {
                text = "";
                caret = 0;
                sel = false;
            }
            if (text.length() + s.length() > (numeric ? 7 : 80)) return;
            text = text.substring(0, caret) + s + text.substring(caret);
            caret += s.length();
            commit();
        }

        void key(int key, int mods) {
            boolean ctrl = (mods & 2) != 0 || (mods & 8) != 0;
            if (ctrl && key == 65) { // ctrl+A
                sel = true;
                return;
            }
            if (ctrl && key == 86) { // ctrl+V
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null) insert(clip);
                return;
            }
            switch (key) {
                case 259 -> { // backspace
                    if (sel) {
                        text = "";
                        caret = 0;
                        sel = false;
                    } else if (caret > 0) {
                        text = text.substring(0, caret - 1) + text.substring(caret);
                        caret--;
                    }
                    commit();
                }
                case 261 -> { // delete
                    if (sel) {
                        text = "";
                        caret = 0;
                        sel = false;
                    } else if (caret < text.length()) {
                        text = text.substring(0, caret) + text.substring(caret + 1);
                    }
                    commit();
                }
                case 263 -> {
                    sel = false;
                    caret = Math.max(0, caret - 1);
                }
                case 262 -> {
                    sel = false;
                    caret = Math.min(text.length(), caret + 1);
                }
                case 268 -> {
                    sel = false;
                    caret = 0;
                }
                case 269 -> {
                    sel = false;
                    caret = text.length();
                }
                default -> {
                }
            }
        }

        private void commit() {
            if (numeric) {
                try {
                    Integer.parseInt(text);
                    bad = false;
                    set.accept(text);
                    dirty = true;
                } catch (NumberFormatException e) {
                    bad = !text.isEmpty() && !text.equals("-");
                }
            } else {
                set.accept(text);
                dirty = true;
            }
        }
    }
}
