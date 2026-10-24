package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Hosts one game: draws the field, passes input on, keeps the best score. Esc goes back to the menu. */
final class GameScreen extends Screen implements Arcade.Marker {
    private final Game game;
    private long last, lastSubmit;
    private boolean record;

    GameScreen(Game game) {
        super(Component.literal(game.name()));
        this.game = game;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int fw() {
        return Math.min(width - 40, 440);
    }

    private int fh() {
        return Math.min(height - 110, 270);
    }

    private int fx() {
        return (width - fw()) / 2;
    }

    private int fy() {
        return (height - fh()) / 2 + 6;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.alpha = 1f;
        Ui.fill(g, 0, 0, width, height, 0xE0050608);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        Ui.alpha = 1f;
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        int fx = fx(), fy = fy(), fw = fw(), fh = fh();
        game.size(fw, fh);
        game.update(dt);
        int score = game.score();
        if (score > Scores.best(game.name()) && System.currentTimeMillis() - lastSubmit > 3000) {
            record = Scores.submit(game.name(), score) || record;
            lastSubmit = System.currentTimeMillis();
        }

        Ui.text(g, game.name(), fx, fy - 26, Ui.TEXT);
        Ui.textR(g, "Score " + score + "   Best " + Math.max(score, Scores.best(game.name())) + (record ? "  NEW!" : ""), fx + fw, fy - 26, Ui.WARN);
        Ui.textC(g, "Macro: " + ArcadeMenu.macroLine(), width / 2, fy - 14, Ui.GOOD);
        Ui.rrb(g, fx - 2, fy - 2, fw + 4, fh + 4, 6, 0xFF0B0D12, Ui.LINE);
        game.render(g, fx, fy, mx - fx, my - fy);
        Ui.textC(g, game.status(), width / 2, fy + fh + 10, Ui.MUTED);

        boolean hov = Ui.in(mx, my, 8, 8, 70, 20);
        Ui.rrb(g, 8, 8, 70, 20, 5, hov ? Ui.CARD_HI : Ui.CARD, hov ? Ui.accent : Ui.LINE);
        Ui.textC(g, "< Menu", 43, 14, hov ? Ui.TEXT : Ui.MUTED);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (Ui.in(e.x(), e.y(), 8, 8, 70, 20)) {
            onClose();
            return true;
        }
        if (game.click(e.x() - fx(), e.y() - fy(), e.button())) return true;
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == 256) { // esc: back to the menu
            onClose();
            return true;
        }
        if (game.key(e.key())) return true;
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        game.keyUp(e.key());
        return super.keyReleased(e);
    }

    @Override
    public void onClose() {
        Scores.submit(game.name(), game.score());
        Minecraft.getInstance().setScreen(new ArcadeMenu());
    }
}
