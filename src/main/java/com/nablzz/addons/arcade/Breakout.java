package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Breakout. The paddle follows the mouse (or Left/Right). Click or Space launches the ball. */
final class Breakout implements Game {
    private static final int COLS = 10, ROWS = 5;
    private static final int[] ROW_COLORS = {0xFFF43F5E, 0xFFF59E0B, 0xFFFBBF24, 0xFF34D399, 0xFF22D3EE};
    private final boolean[][] bricks = new boolean[ROWS][COLS];
    private int w = 300, h = 200, score, lives = 3, level = 1, left;
    private double padX, bx, by, vx, vy, keyMove, lastMx = -1;
    private boolean stuck = true, over;

    @Override
    public String name() {
        return "Breakout";
    }

    @Override
    public String blurb() {
        return "Smash every brick. Mouse or Left/Right, click to launch.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        score = 0;
        lives = 3;
        level = 1;
        over = false;
        fill();
        padX = w / 2.0;
        stuck = true;
    }

    private void fill() {
        left = 0;
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            bricks[r][c] = true;
            left++;
        }
        stuck = true;
    }

    private double padW() {
        return Math.max(36, 64 - (level - 1) * 4);
    }

    private double brickW() {
        return (w - 20.0) / COLS;
    }

    @Override
    public void update(float dt) {
        if (over) return;
        padX += keyMove * 300 * dt;
        padX = Math.max(padW() / 2, Math.min(w - padW() / 2, padX));
        double py = h - 16;
        if (stuck) {
            bx = padX;
            by = py - 8;
            return;
        }
        bx += vx * dt;
        by += vy * dt;
        if (bx < 4) { bx = 4; vx = Math.abs(vx); }
        if (bx > w - 4) { bx = w - 4; vx = -Math.abs(vx); }
        if (by < 4) { by = 4; vy = Math.abs(vy); }
        if (vy > 0 && by > py - 8 && by < py + 4 && Math.abs(bx - padX) < padW() / 2 + 4) {
            double rel = (bx - padX) / (padW() / 2);
            double sp = Math.min(380, Math.hypot(vx, vy) * 1.02);
            double ang = Math.max(-1.1, Math.min(1.1, rel * 1.0));
            vx = Math.sin(ang) * sp;
            vy = -Math.cos(ang) * sp;
            by = py - 8;
        }
        // bricks
        double bw = brickW(), top = 30, bh = 12;
        int c = (int) ((bx - 10) / bw), r = (int) ((by - top) / (bh + 2));
        if (c >= 0 && c < COLS && r >= 0 && r < ROWS && bricks[r][c]) {
            bricks[r][c] = false;
            left--;
            score += 10 * (ROWS - r);
            vy = -vy;
            if (left == 0) {
                level++;
                fill();
                score += 100;
            }
        }
        if (by > h + 6) {
            lives--;
            stuck = true;
            if (lives <= 0) over = true;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        if (mx != lastMx) { // only a moving mouse takes over from the keys
            lastMx = mx;
            if (mx >= 0 && mx <= w && my >= 0 && my <= h) padX = mx;
        }
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        double bw = brickW();
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (!bricks[r][c]) continue;
            Ui.rr(g, (int) (x + 10 + c * bw + 1), y + 30 + r * 14, (int) bw - 2, 12, 3, ROW_COLORS[r]);
        }
        Ui.rr(g, (int) (x + padX - padW() / 2), y + h - 16, (int) padW(), 8, 4, Ui.TEXT);
        Ui.rr(g, (int) (x + bx - 4), (int) (y + by - 4), 8, 8, 4, Ui.GOOD);
        if (stuck && !over) Ui.textC(g, "Click or Space to launch", x + w / 2, y + h / 2 + 20, Ui.MUTED);
        if (over) Ui.textC(g, "Game over - click or Space to restart", x + w / 2, y + h / 2 + 20, Ui.BAD);
    }

    private void launch() {
        if (over) {
            reset();
        } else if (stuck) {
            stuck = false;
            double sp = 190 + level * 20;
            vx = (Math.random() - 0.5) * sp * 0.7;
            vy = -Math.sqrt(sp * sp - vx * vx);
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        launch();
        return true;
    }

    @Override
    public boolean key(int key) {
        switch (key) {
            case 263, 65 -> { keyMove = -1; }
            case 262, 68 -> { keyMove = 1; }
            case 32 -> launch();
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void keyUp(int key) {
        if (key == 263 || key == 262 || key == 65 || key == 68) keyMove = 0;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return "Level " + level + "   Lives " + lives;
    }
}
