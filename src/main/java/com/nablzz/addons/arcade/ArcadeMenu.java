package com.nablzz.addons.arcade;

import com.nablzz.addons.Feature;
import com.nablzz.addons.Features;
import com.nablzz.addons.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The game list. The games are kept between visits (a half-finished Minesweeper board is still there). */
public final class ArcadeMenu extends Screen implements Arcade.Marker {
    static final List<Game> GAMES = List.of(new Snake(), new Pong(), new Breakout(), new Flappy(), new Minesweeper());
    static final List<Game> CASINO = List.of(new Blackjack(), new Roulette(), new Baccarat(), new VideoPoker(), new Crash(), new Plinko());
    private static int tab;

    private static final int ROW_H = 44, GAP = 6;

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

    private int cardW() {
        return Math.min(width - 40, 420);
    }

    private List<Game> list() {
        return tab == 0 ? GAMES : CASINO;
    }

    private int rowH() {
        return tab == 0 ? ROW_H : 34;
    }

    private int top() {
        int total = list().size() * (rowH() + GAP) - GAP;
        return Math.max(78, (height - total) / 2 + 20);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.alpha = 1f;
        Ui.fill(g, 0, 0, width, height, 0xE0050608);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        Ui.alpha = 1f;
        int cw = cardW(), x = (width - cw) / 2, y0 = top();
        Ui.textC(g, "ARCADE", width / 2, 8, Ui.accent);
        Ui.textC(g, "The macro keeps running while you play.  " + macroLine(), width / 2, 22, Ui.GOOD);
        String[] tabs = {"Games", "Casino"};
        for (int i = 0; i < 2; i++) {
            int tx = width / 2 - 82 + i * 84;
            boolean sel = tab == i, hov = Ui.in(mx, my, tx, 36, 80, 20);
            Ui.rrb(g, tx, 36, 80, 20, 5, sel ? Ui.accent : hov ? Ui.CARD_HI : Ui.CARD, sel ? Ui.accent : Ui.LINE);
            Ui.textC(g, tabs[i], tx + 40, 42, sel ? 0xFFFFFFFF : Ui.MUTED);
        }
        if (tab == 1) Ui.textC(g, "Play money only. Nothing here costs real money or SkyBlock coins.", width / 2, 62, Ui.WARN);
        int rh = rowH();
        for (int i = 0; i < list().size(); i++) {
            Game gm = list().get(i);
            int y = y0 + i * (rh + GAP);
            boolean hov = Ui.in(mx, my, x, y, cw, rh);
            Ui.rrb(g, x, y, cw, rh, 6, hov ? Ui.CARD_HI : Ui.CARD, hov ? Ui.accent : Ui.LINE);
            Ui.text(g, gm.name(), x + 12, y + 6, Ui.TEXT);
            Ui.text(g, Ui.fit(gm.blurb(), cw - 24), x + 12, y + rh - 14, Ui.MUTED);
            Ui.textR(g, (tab == 1 ? "Peak chips " : "Best ") + Scores.best(gm.name()), x + cw - 12, y + 6, Ui.WARN);
        }
        if (tab == 1) Ui.textC(g, "Chips: " + Chips.balance(), width / 2, y0 + list().size() * (rh + GAP) + 2, Ui.WARN);
        Ui.textC(g, "Esc closes   Left/Right switches tab", width / 2, height - 14, Ui.DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        for (int i = 0; i < 2; i++) {
            if (Ui.in(e.x(), e.y(), width / 2 - 82 + i * 84, 36, 80, 20)) {
                tab = i;
                return true;
            }
        }
        int cw = cardW(), x = (width - cw) / 2, y0 = top(), rh = rowH();
        for (int i = 0; i < list().size(); i++) {
            int y = y0 + i * (rh + GAP);
            if (Ui.in(e.x(), e.y(), x, y, cw, rh)) {
                Game gm = list().get(i);
                gm.reset();
                Minecraft.getInstance().setScreen(new GameScreen(gm));
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        if (e.key() == 263 || e.key() == 262) { // left / right switch the tab
            tab = 1 - tab;
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
