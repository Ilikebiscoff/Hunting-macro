package com.nablzz.addons;

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
 * The settings screen of Nablzz Addons: modules (Hunting, Mushroom, General) along the top, tabs in the sidebar,
 * cards with toggles / sliders / text boxes, live dashboards.
 * Everything is drawn by hand (no vanilla widgets) so it can have its own look. While it is open the macro
 * pauses (the game does not), so close it to let the macro move.
 */
public class NablzzScreen extends Screen {
    private static final int SIDE = 138, HEADER = 38, PAD = 10, HINT_H = 16, ROW_H = 22, TITLE_H = 20;

    /** Set by any control that changed a setting; the file is written when the mouse is released / the screen closes. */
    private static boolean dirty;

    private final long openedAt = System.nanoTime();
    private final List<Module> modules = new ArrayList<>();
    private int module;
    private float tabY = -1;
    private float sideScroll;
    private long lastFrame;
    private float scroll, scrollTarget, contentH;
    private Field focus;
    private Row dragRow;
    private boolean mouseDown;
    private String hint = "";
        private int wx, wy, ww, wh, vx, vy, vw, vh;

    public NablzzScreen(String startModule) {
        super(Component.literal("Nablzz Addons"));
        for (Feature f : Features.all()) {
            Module md = new Module(f.id(), f.name());
            modules.add(md);
            md.running = f::isRunning;
            md.status = f::status;
            md.toggle = f.hasRunButton() ? () -> toggleFeature(f) : null;
            f.buildScreen(new Builder(md));
        }
        buildGeneral();
        module = modules.size() - 1; // General, unless the id matches a feature
        for (int i = 0; i < modules.size(); i++) if (modules.get(i).id.equals(startModule)) module = i;
    }

    private Module mod() {
        return modules.get(module);
    }

    private Tab cur() {
        Module md = mod();
        return md.tabs.get(md.tab);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Id of the module that is shown (to bring the screen back after a lobby swap). */
    public String currentModule() {
        return mod().id;
    }

    @Override
    public void onClose() {
        com.nablzz.addons.arcade.Arcade.forget();
        blur();
        saveAll();
        NablzzAddons.rememberModule(mod().id);
        super.onClose();
    }

    private void saveAll() {
        Features.saveAll();
        Prefs.save();
    }

    // ===================================================================================================
    // tabs
    // ===================================================================================================

    private void buildGeneral() {
        Module md = new Module(NablzzAddons.GENERAL, "General");
        modules.add(md);
        md.running = () -> {
            for (Feature f : Features.all()) if (f.isRunning()) return true;
            return false;
        };
        md.status = () -> {
            for (Feature f : Features.all()) if (f.isRunning()) return f.name();
            return "Idle";
        };
        Builder ui = new Builder(md);

        // ---- Overview
        ui.tab("Overview", "O");
        ui.section("Macros");
        for (Feature f : Features.all()) {
            ui.info(f.name(), "State of this macro.", () -> f.isRunning() ? "running - " + f.status() : "stopped", Ui.TEXT);
        }
        ui.button("Stop everything", "Stops every macro.", "Stop all", false, () -> Features.stopAll(Minecraft.getInstance()));
        ui.note("Only one macro runs at a time: starting one stops the other.");

        // ---- Appearance
        ui.tab("Appearance", "A");
        ui.section("Look");
        ui.choice("Accent colour", "Colour of this screen and the panels.", Ui.ACCENT_NAMES, () -> Prefs.accentIdx, i -> {
            Prefs.accentIdx = i;
            Ui.setAccent(i);
        });

        // ---- Report
        ui.tab("Report", "R");
        ui.section("Report for Claude");
        ui.note("Collects what the macros did lately, the state and settings of every feature, the chat, your settings files and the filtered game log, "
                + "copies it to the clipboard and saves it as config/nablzz_report.txt. Paste it in the chat when something does not work. "
                + "Same as the command /nablzz report.");
        ui.button("Copy report", "Builds the report and copies it.", "Copy", true, () -> Report.run(Minecraft.getInstance()));
        ui.info("Last report", "When the last report was made.", () -> Report.last, Ui.MUTED);

        // ---- Updates
        ui.tab("Updates", "U");
        ui.section("Version");
        ui.info("Installed / latest", "Versions of this jar and of the newest build.", Updater::versionInfo, Ui.TEXT);
        ui.info("Last message", "What the updater said last.", () -> Updater.lastMessage, Ui.MUTED);
        ui.button("Check for update", "Looks at the latest build on GitHub.", "Check", true, () -> Updater.run(false, false));
        ui.button("Download update", "Downloads the newest jar into your mods folder (hash-checked). Restart the game afterwards.", "Download", true, () -> Updater.run(false, true));
        ui.section("About");
        ui.note("Open this screen with Right Shift (change it in Controls under \"Nablzz Addons\"), /nablzz, /hunt or /glowing. "
                + "The running macro pauses while this screen is open - close it to let it run. /hunt help and /glowing help list every command.");
        ui.note("Heads-up: automating the game on Hypixel breaks their rules and can get your account banned. Use at your own risk.");
    }

    /**
     * What a {@link Feature} uses to describe its tabs: {@code tab(..)} starts a tab, {@code section(..)} a card in it,
     * and the other methods add one row to the current card.
     */
    public final class Builder {
        private final Module md;
        private Tab tab;
        private Section sec;

        private Builder(Module md) {
            this.md = md;
        }

        private void add(Row r) {
            if (sec == null) throw new IllegalStateException("call tab() and section() first");
            sec.add(r);
        }

        public void tab(String name, String letter) {
            tab = new Tab(name, letter);
            md.tabs.add(tab);
            sec = null;
        }

        public void section(String title) {
            sec = tab.section(title);
        }

        /** Big status + Start/Stop button for this feature. */
        public void hero(Supplier<String> sub, String title) {
            add(new HeroRow(md, sub, title));
        }

        /** Six stat tiles (value, caption). */
        public void tiles(Supplier<String[][]> tiles) {
            add(new TilesRow(tiles));
        }

        public void toggle(String label, String hint, BooleanSupplier get, Consumer<Boolean> set) {
            add(new ToggleRow(label, hint, get, set));
        }

        public void text(String label, String hint, Supplier<String> get, Consumer<String> set, int fieldW, String placeholder) {
            add(new TextRow(label, hint, new Field(false, fieldW, get, set, placeholder)));
        }

        public void islider(String label, String hint, int min, int max, IntSupplier get, IntConsumer set, String suffix) {
            add(new SliderRow(label, hint, min, max, 1, () -> get.getAsInt(), v -> set.accept((int) Math.round(v)),
                    v -> Math.round(v) + suffix));
        }

        public void dslider(String label, String hint, double min, double max, double step, DoubleSupplier get, DoubleConsumer set, String fmt) {
            add(new SliderRow(label, hint, min, max, step, get, set, v -> String.format(Locale.ROOT, fmt, v)));
        }

        public void choice(String label, String hint, String[] options, IntSupplier get, IntConsumer set) {
            add(new ChoiceRow(label, hint, options, get, set));
        }

        /** Row of number boxes (e.g. x / y / z). */
        public void nums(String label, String hint, String[] captions, IntSupplier[] get, IntConsumer[] set) {
            Field[] f = new Field[get.length];
            for (int i = 0; i < f.length; i++) {
                final int k = i;
                f[i] = new Field(true, 48, () -> "" + get[k].getAsInt(), v -> set[k].accept(Integer.parseInt(v)), "0");
            }
            add(new NumbersRow(label, hint, captions, f));
        }

        public void button(String label, String hint, String text, boolean primary, Runnable action) {
            add(new ButtonRow(label, hint, text, primary, action));
        }

        /** A button that needs a second click within 3 seconds (for destructive actions). */
        public void confirmButton(String label, String hint, String text, Runnable action) {
            add(new ConfirmButtonRow(label, hint, text, action));
        }

        public void info(String label, String hint, Supplier<String> value, int color) {
            add(new InfoRow(label, hint, value, color));
        }

        public void info(String label, String hint, Supplier<String> value) {
            info(label, hint, value, Ui.MUTED);
        }

        public void bar(String label, String hint, DoubleSupplier frac, Supplier<String> text) {
            add(new BarRow(label, hint, frac, text));
        }

        public void note(String text) {
            add(new NoteRow(text));
        }

        /** A text box with an Add button. */
        public void add(String label, String hint, String placeholder, String buttonLabel, Consumer<String> action) {
            add(new AddRow(label, hint, placeholder, buttonLabel, action));
        }

        /** A message in the bar at the bottom for a few seconds. */
        public void flash(String s) {
            NablzzScreen.this.flash(s);
        }

        /** Saves all settings and closes the screen (before something that needs the game, e.g. a test run). */
        public void closeScreen() {
            onClose();
        }

        /** Call after changing a setting from a button action so it is written to disk. */
        public void markDirty() {
            dirty = true;
        }
    }

    private void toggleFeature(Feature f) {
        Minecraft mc = Minecraft.getInstance();
        if (f.isRunning()) {
            f.stop(mc);
        } else {
            saveAll();
            f.start(mc);
            if (f.isRunning()) onClose(); // the macro pauses while a screen is open
            else flash("Could not start - see the chat for why.");
        }
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
        boolean flashing = System.currentTimeMillis() < flashUntil;
        Ui.text(g, Ui.fit(flashing ? flashText : hint, vw), vx, wy + wh - HINT_H + 1, flashing ? Ui.WARN : Ui.MUTED);
        Ui.alpha = 1f;
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        // logo
        int lx = wx + 12, ly = wy + 12;
        Ui.rr(g, lx, ly, 22, 22, 6, Ui.accent);
        Ui.rr(g, lx + 1, ly + 1, 20, 10, 5, Ui.withAlpha(0xFFFFFF, 40));
        Ui.textC(g, "N", lx + 11, ly + 7, 0xFFFFFFFF);
        Ui.bold(g, "Nablzz", lx + 28, ly + 2, Ui.TEXT);
        Ui.text(g, "Addons", lx + 28, ly + 12, Ui.accent);

        // the feature list (scrolls when there are more features than room)
        int ty = wy + 48, th = 24;
        int listTop = ty, listBot = wy + wh - 34;
        float maxScroll = Math.max(0, modules.size() * (th + 3) - (listBot - listTop));
        sideScroll = Math.max(0, Math.min(maxScroll, sideScroll));
        float target = ty + module * (th + 3) - sideScroll;
        if (tabY < 0) tabY = target;
        tabY = Ui.approach(tabY, target, 900f);
        g.enableScissor(wx + 1, listTop, wx + SIDE + 1, listBot);
        Ui.rr(g, wx + 8, (int) tabY, SIDE - 14, th, 5, Ui.mix(Ui.SIDEBAR, Ui.accent, 0.20f));
        Ui.rr(g, wx + 8, (int) tabY + 4, 2, th - 8, 1, Ui.accent);
        for (int i = 0; i < modules.size(); i++) {
            Module n = modules.get(i);
            int y = (int) (ty + i * (th + 3) - sideScroll);
            if (y + th < listTop || y > listBot) continue;
            boolean hov = Ui.in(mx, my, wx + 8, y, SIDE - 14, th) && my >= listTop && my < listBot;
            n.hov = Ui.approach(n.hov, hov ? 1f : 0f, 8f);
            boolean sel = i == module;
            if (!sel && n.hov > 0) Ui.rr(g, wx + 8, y, SIDE - 14, th, 5, Ui.withAlpha(0xFFFFFF, (int) (10 * n.hov)));
            boolean on = n.running.getAsBoolean();
            float pulse = on ? 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 220.0) : 1f;
            Ui.rr(g, wx + 17, y + 9, 6, 6, 3, Ui.mix(Ui.SIDEBAR, on ? Ui.GOOD : Ui.DIM, pulse));
            Ui.text(g, Ui.fit(n.name, SIDE - 44), wx + 29, y + 8, sel ? Ui.TEXT : Ui.mix(Ui.MUTED, Ui.TEXT, n.hov));
        }
        g.disableScissor();
        if (maxScroll > 0) { // a hint that there is more
            if (sideScroll < maxScroll - 1) Ui.textC(g, "v", wx + SIDE / 2, listBot + 2, Ui.DIM);
            if (sideScroll > 1) Ui.textC(g, "^", wx + SIDE / 2, listTop - 10, Ui.DIM);
        }
        Ui.text(g, Updater.shortVersion(), wx + 14, wy + wh - 24, Ui.DIM);
        Ui.text(g, "Right Shift: open/close", wx + 14, wy + wh - 13, Ui.DIM);
    }

    private int closeX() {
        return wx + ww - 22;
    }

    private int navW(int i) {
        return Ui.tw(mod().tabs.get(i).name) + 22;
    }

    private int navX(int i) {
        int x = vx;
        for (int k = 0; k < i; k++) x += navW(k) + 4;
        return x;
    }

    /** Header: the tabs of the selected feature, the status of the macro and the close button. */
    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        Ui.fill(g, wx + SIDE + 1, wy + HEADER, ww - SIDE - 2, 1, Ui.LINE);
        Module md = mod();
        boolean run = md.running.getAsBoolean();

        for (int i = 0; i < md.tabs.size(); i++) {
            Tab t = md.tabs.get(i);
            int nx = navX(i), nw = navW(i);
            boolean sel = i == md.tab, hov = Ui.in(mx, my, nx, wy + 8, nw, HEADER - 8);
            t.hov = Ui.approach(t.hov, hov ? 1f : 0f, 8f);
            if (hov && !sel) Ui.rr(g, nx, wy + 8, nw, 22, 5, Ui.withAlpha(0xFFFFFF, 12));
            if (sel) {
                Ui.rr(g, nx, wy + 8, nw, 22, 5, Ui.mix(Ui.WINDOW, Ui.accent, 0.16f));
                Ui.rr(g, nx + 6, wy + HEADER - 3, nw - 12, 2, 1, Ui.accent);
            }
            Ui.textC(g, t.name, nx + nw / 2, wy + 15, sel ? Ui.TEXT : Ui.mix(Ui.MUTED, Ui.TEXT, t.hov));
        }

        int cx = closeX(), cy = wy + 10;
        boolean hc = Ui.in(mx, my, cx, cy, 14, 14);
        Ui.rr(g, cx, cy, 14, 14, 4, hc ? 0xFF3A2024 : Ui.CARD);
        Ui.textC(g, "x", cx + 7, cy + 3, hc ? Ui.BAD : Ui.MUTED);

        // status pill (only when there is room)
        String st = md.status.get();
        int pw = Ui.tw(st) + 24;
        int px = cx - 8 - pw;
        if (px > navX(md.tabs.size()) + 4) {
            Ui.rr(g, px, wy + 10, pw, 14, 7, Ui.CARD);
            float pulse = run ? 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 220.0) : 1f;
            Ui.rr(g, px + 7, wy + 14, 6, 6, 3, Ui.mix(Ui.CARD, run ? Ui.GOOD : Ui.BAD, pulse));
            Ui.text(g, st, px + 17, wy + 13, Ui.TEXT);
        }
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
        Tab t = cur();
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
        // the feature list in the sidebar
        {
            int listTop = wy + 48, listBot = wy + wh - 34;
            for (int i = 0; i < modules.size(); i++) {
                int y = (int) (wy + 48 + i * 27 - sideScroll);
                if (my >= listTop && my < listBot && Ui.in(mx, my, wx + 8, y, SIDE - 14, 24)) {
                    blur();
                    if (module != i) {
                        module = i;
                        scroll = scrollTarget = 0;
                    }
                    return true;
                }
            }
        }
        // the tabs of the selected feature in the header
        for (int i = 0; i < mod().tabs.size(); i++) {
            if (Ui.in(mx, my, navX(i), wy + 8, navW(i), HEADER - 8)) {
                blur();
                if (mod().tab != i) {
                    mod().tab = i;
                    scroll = scrollTarget = 0;
                }
                return true;
            }
        }
        if (Ui.in(mx, my, vx, vy, vw, vh)) {
            for (Section s : cur().sections) {
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
        if (mx < wx + SIDE) {
            sideScroll -= (float) (sy * 24);
            return true;
        }
        scrollTarget -= (float) (sy * 30);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (focus != null) {
            if (key == 256 || key == 257 || key == 335) { // esc / enter: leave the text box
                if (key != 256 && focus.enter != null) focus.enter.run();
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


    private String flashText = "";
    private long flashUntil;

    private void flash(String s) {
        flashText = s;
        flashUntil = System.currentTimeMillis() + 4000;
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
            saveAll();
        }
    }

    // ===================================================================================================
    // model
    // ===================================================================================================

    private static final class Module {
        final String id, name;
        final List<Tab> tabs = new ArrayList<>();
        int tab;
        float hov;
        java.util.function.BooleanSupplier running = () -> false;
        Supplier<String> status = () -> "";
        Runnable toggle;

        Module(String id, String name) {
            this.id = id;
            this.name = name;
        }
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

        void activate() {
            action.run();
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (mx < x + w - bw()) return false;
            activate();
            return true;
        }
    }

    private final class ConfirmButtonRow extends ButtonRow {
        final Runnable real;
        long armedAt;

        ConfirmButtonRow(String label, String hint, String text, Runnable real) {
            super(label, hint, text, false, () -> {});
            this.real = real;
        }

        @Override
        String buttonText() {
            return System.currentTimeMillis() - armedAt < 3000 ? "Click again" : text;
        }

        @Override
        void activate() {
            long now = System.currentTimeMillis();
            if (now - armedAt < 3000) {
                armedAt = 0;
                real.run();
            } else {
                armedAt = now;
            }
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
        final Module owner;
        final Supplier<String> sub;
        final String title;

        HeroRow(Module owner, Supplier<String> sub, String title) {
            super("", "Current state of the macro.");
            this.owner = owner;
            this.sub = sub;
            this.title = title;
            h = 54;
        }

        @Override
        boolean hoverable() {
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            boolean run = owner.running.getAsBoolean();
            float pulse = run ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 250.0) : 1f;
            Ui.rr(g, x, y + 8, 8, 8, 4, Ui.mix(Ui.CARD, run ? Ui.GOOD : Ui.BAD, pulse));
            Ui.big(g, run ? "Running" : "Stopped", x + 16, y + 6, 1.6f, run ? Ui.GOOD : Ui.TEXT);
            Ui.text(g, Ui.fit(title + "  -  " + sub.get(), w - 130), x + 16, y + 28, Ui.MUTED);
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
            owner.toggle.run();
            return true;
        }
    }

    /** Dashboard: six number tiles. */
    private final class TilesRow extends Row {
        final Supplier<String[][]> tiles;

        TilesRow(Supplier<String[][]> tiles) {
            super("", "Counters since the macro was last reset.");
            this.tiles = tiles;
            h = 84;
        }

        @Override
        boolean hoverable() {
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            String[][] t = tiles.get();
            int gap = 6, tw = (w - gap * 2) / 3, th = 36;
            for (int i = 0; i < t.length; i++) {
                int tx = x + (i % 3) * (tw + gap), ty = y + 4 + (i / 3) * (th + gap);
                Ui.rrb(g, tx, ty, tw, th, 5, Ui.FIELD, Ui.LINE);
                Ui.rr(g, tx + 6, ty + 8, 2, th - 16, 1, Ui.accent);
                Ui.textShadow(g, t[i][0], tx + 14, ty + 8, Ui.TEXT);
                Ui.text(g, t[i][1], tx + 14, ty + 21, Ui.MUTED);
            }
        }
    }

    /** A text box with an "Add" button next to it. */
    private final class AddRow extends Row {
        final Field field;
        final String buttonLabel;
        final Consumer<String> action;
        String buf = "";

        AddRow(String label, String hint, String placeholder, String buttonLabel, Consumer<String> action) {
            super(label, hint);
            this.buttonLabel = buttonLabel;
            this.action = action;
            this.field = new Field(false, 110, () -> buf, v -> buf = v, placeholder);
            this.field.enter = this::submit;
        }

        int bw() {
            return Ui.tw(buttonLabel) + 24;
        }

        @Override
        void draw(GuiGraphicsExtractor g, int mx, int my, boolean hovered) {
            drawLabel(g);
            int bw = bw(), bx = x + w - bw, by = y + 3;
            boolean hb = Ui.in(mx, my, bx, by, bw, 16);
            Ui.rr(g, bx, by, bw, 16, 4, hb ? Ui.mix(Ui.accent, 0xFFFFFFFF, 0.18f) : Ui.accent);
            Ui.rr(g, bx + 1, by + 1, bw - 2, 7, 3, Ui.withAlpha(0xFFFFFF, 18));
            Ui.textC(g, buttonLabel, bx + bw / 2, by + 4, 0xFFFFFFFF);
            field.x = bx - 6 - field.w;
            field.y = y + 3;
            field.draw(g, mx, my);
        }

        private void submit() {
            action.accept(buf.trim());
            buf = "";
            field.text = "";
            field.caret = 0;
            field.sel = false;
        }

        @Override
        boolean down(double mx, double my, int button) {
            if (mx >= x + w - bw()) {
                submit();
                return true;
            }
            if (!field.hit(mx, my)) return false;
            if (focus != field) {
                blur();
                focus = field;
                field.focused = true;
                field.sel = false;
            }
            field.click(mx);
            return true;
        }

        @Override
        boolean ownsFocus(Field f) {
            return f == field;
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
        /** Run when Enter is pressed in the box (the "Add" boxes). */
        Runnable enter;

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
