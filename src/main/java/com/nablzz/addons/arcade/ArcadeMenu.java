package com.nablzz.addons.arcade;

import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The game list in three tabs (Action, Puzzle, Casino). The games are kept between visits (a half-finished board is still there). */
public final class ArcadeMenu extends Screen implements Arcade.Marker {
    static final List<Game> ACTION = List.of(new Snake(), new Pong(), new Breakout(), new Flappy(), new Tetris(), new SpaceInvaders(), new WhackAMole(), new ShardClicker());
    static final List<Game> PUZZLE = List.of(new Minesweeper(), new Game2048(), new MemoryMatch(), new LightsOut(), new Simon(), new TicTacToe(), new ConnectFour());
    static final List<Game> CASINO = List.of(new Blackjack(), new Roulette(), new Baccarat(), new VideoPoker(), new Crash(), new Plinko());
    private static final String[] TABS = {"Action", "Puzzle", "Casino"};
    private static final int GAP = 6;
    private static int tab;
    private float scroll;

    public ArcadeMenu() {
        super(Component.literal("Arcade"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static String macroLine() {
        for (Feature f : Features.all()) if (f.isRunning()) return f.name() + ": " + f.status();
        return "no macro running";
    }

    private static boolean anyRunning() {
        for (Feature f : Features.all()) if (f.isRunning()) return true;
        return false;
    }

    private List<Game> list() {
        return tab == 0 ? ACTION : tab == 1 ? PUZZLE : CASINO;
    }

    private int rowH() {
        return tab == 2 ? 42 : 46;
    }

    private int cardW() {
        return Math.min(width - 40, 420);
    }

    private int listTop() {
        return tab == 2 ? 78 : 66;
    }

    private int listBottom() {
        return height - 22;
    }

    private int total() {
        return list().size() * (rowH() + GAP) - GAP;
    }

    private float maxScroll() {
        return Math.max(0, total() - (listBottom() - listTop()));
    }

    private int tabX(int i) {
        return width / 2 - 124 + i * 84;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.alpha = 1f;
        Ui.fill(g, 0, 0, width, height, 0xE0050608);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        Ui.alpha = 1f;
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        Ui.textC(g, "ARCADE", width / 2, 8, Ui.accent);
        Ui.textC(g, anyRunning() ? "The macro keeps running while you play.  " + macroLine() : "Play any time - a running macro keeps going behind it.", width / 2, 22, anyRunning() ? Ui.GOOD : Ui.MUTED);
        for (int i = 0; i < 3; i++) {
            boolean sel = tab == i, hov = Ui.in(mx, my, tabX(i), 36, 80, 20);
            Ui.rrb(g, tabX(i), 36, 80, 20, 5, sel ? Ui.accent : hov ? Ui.CARD_HI : Ui.CARD, sel ? Ui.accent : Ui.LINE);
            Ui.textC(g, TABS[i], tabX(i) + 40, 42, sel ? 0xFFFFFFFF : Ui.MUTED);
        }
        if (tab == 2) Ui.textC(g, "Play money only. Nothing here costs real money or SkyBlock coins.", width / 2, 62, Ui.WARN);
        int cw = cardW(), x = (width - cw) / 2, rh = rowH(), top = listTop(), bottom = listBottom();
        g.enableScissor(0, top, width, bottom);
        for (int i = 0; i < list().size(); i++) {
            Game gm = list().get(i);
            int y = top + i * (rh + GAP) - (int) scroll;
            if (y + rh < top || y > bottom) continue;
            boolean hov = Ui.in(mx, my, x, y, cw, rh) && my >= top && my < bottom;
            Ui.rrb(g, x, y, cw, rh, 6, hov ? Ui.CARD_HI : Ui.CARD, hov ? Ui.accent : Ui.LINE);
            Ui.text(g, gm.name(), x + 12, y + 6, Ui.TEXT);
            java.util.List<String> bl = Ui.wrap(gm.blurb(), cw - 24 - 100);
            for (int k = 0; k < bl.size() && k < 2; k++) Ui.text(g, bl.get(k), x + 12, y + 18 + k * 11, Ui.MUTED);
            Ui.textR(g, (tab == 2 ? "Peak chips " : "Best ") + Scores.best(gm.name()), x + cw - 12, y + 6, Ui.WARN);
        }
        g.disableScissor();
        if (maxScroll() > 0) { // scroll bar
            int bh = Math.max(16, (int) ((bottom - top) * (bottom - top) / (double) total()));
            int by = top + (int) ((bottom - top - bh) * (scroll / maxScroll()));
            Ui.rr(g, x + cw + 4, top, 3, bottom - top, 1, Ui.withAlpha(0xFFFFFF, 12));
            Ui.rr(g, x + cw + 4, by, 3, bh, 1, Ui.withAlpha(0xFFFFFF, 70));
        }
        Ui.textC(g, "Esc closes   Left/Right switch tab   mouse wheel scrolls", width / 2, height - 14, Ui.DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        for (int i = 0; i < 3; i++) {
            if (Ui.in(e.x(), e.y(), tabX(i), 36, 80, 20)) {
                tab = i;
                scroll = 0;
                return true;
            }
        }
        int cw = cardW(), x = (width - cw) / 2, rh = rowH(), top = listTop(), bottom = listBottom();
        if (e.y() >= top && e.y() < bottom) {
            for (int i = 0; i < list().size(); i++) {
                int y = top + i * (rh + GAP) - (int) scroll;
                if (Ui.in(e.x(), e.y(), x, y, cw, rh)) {
                    Game gm = list().get(i);
                    gm.reset();
                    Minecraft.getInstance().setScreen(new GameScreen(gm));
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (float) sy * 24));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == 263 || e.key() == 262) { // left / right switch the tab
            tab = (tab + (e.key() == 262 ? 1 : 2)) % 3;
            scroll = 0;
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        Arcade.forget();
        Minecraft.getInstance().setScreen(null);
    }
}
