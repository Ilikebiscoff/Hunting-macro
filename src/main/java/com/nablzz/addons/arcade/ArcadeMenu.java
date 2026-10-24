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

    private static final int ROW_H = 44, GAP = 8;

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

    private int top() {
        int total = GAMES.size() * (ROW_H + GAP) - GAP;
        return Math.max(60, (height - total) / 2 + 10);
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
        Ui.textC(g, "ARCADE", width / 2, y0 - 40, Ui.accent);
        Ui.textC(g, "The macro keeps running while you play.", width / 2, y0 - 26, Ui.MUTED);
        Ui.textC(g, macroLine(), width / 2, y0 - 14, Ui.GOOD);
        for (int i = 0; i < GAMES.size(); i++) {
            Game gm = GAMES.get(i);
            int y = y0 + i * (ROW_H + GAP);
            boolean hov = Ui.in(mx, my, x, y, cw, ROW_H);
            Ui.rrb(g, x, y, cw, ROW_H, 6, hov ? Ui.CARD_HI : Ui.CARD, hov ? Ui.accent : Ui.LINE);
            Ui.text(g, gm.name(), x + 12, y + 8, Ui.TEXT);
            Ui.text(g, Ui.fit(gm.blurb(), cw - 24), x + 12, y + 24, Ui.MUTED);
            Ui.textR(g, "Best " + Scores.best(gm.name()), x + cw - 12, y + 8, Ui.WARN);
        }
        Ui.textC(g, "Esc closes", width / 2, y0 + GAMES.size() * (ROW_H + GAP) + 4, Ui.DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        int cw = cardW(), x = (width - cw) / 2, y0 = top();
        for (int i = 0; i < GAMES.size(); i++) {
            int y = y0 + i * (ROW_H + GAP);
            if (Ui.in(e.x(), e.y(), x, y, cw, ROW_H)) {
                Game gm = GAMES.get(i);
                gm.reset();
                Minecraft.getInstance().setScreen(new GameScreen(gm));
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
