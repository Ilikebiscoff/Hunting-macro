package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/** Pong against a computer paddle that is a bit too slow for fast balls. Mouse or Up/Down. First to miss 3 loses. */
final class Pong implements Game {
    private static final int PAD_W = 6, PAD_H = 40;
    private final Random rnd = new Random();
    private int w = 300, h = 200, score, lives = 3;
    private double py, ay, bx, by, vx, vy, speed;
    private double keyMove;
    private boolean over, serving;
    private float serveIn;
    private double mouseY = -1, lastMy = -1;

    @Override
    public String name() {
        return "Pong";
    }

    @Override
    public String blurb() {
        return "Beat the robot paddle. Mouse or Up/Down. Three misses and you're out.";
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
        over = false;
        py = ay = h / 2.0;
        serve();
    }

    private void serve() {
        bx = w / 2.0;
        by = h / 2.0;
        speed = 170 + score * 8;
        double ang = (rnd.nextDouble() - 0.5) * 1.0;
        vx = -Math.cos(ang) * speed;
        vy = Math.sin(ang) * speed;
        serving = true;
        serveIn = 0.8f;
    }

    @Override
    public void update(float dt) {
        if (over) return;
        if (mouseY >= 0) py = mouseY;
        py += keyMove * 260 * dt;
        py = Math.max(PAD_H / 2.0, Math.min(h - PAD_H / 2.0, py));
        // the robot follows the ball, capped speed
        double target = by;
        double maxMove = (110 + score * 5) * dt;
        ay += Math.max(-maxMove, Math.min(maxMove, target - ay));
        ay = Math.max(PAD_H / 2.0, Math.min(h - PAD_H / 2.0, ay));
        if (serving) {
            serveIn -= dt;
            if (serveIn <= 0) serving = false;
            return;
        }
        bx += vx * dt;
        by += vy * dt;
        if (by < 4) {
            by = 4;
            vy = Math.abs(vy);
        }
        if (by > h - 4) {
            by = h - 4;
            vy = -Math.abs(vy);
        }
        // player paddle (left)
        if (vx < 0 && bx < 16 + PAD_W && bx > 10 && Math.abs(by - py) < PAD_H / 2.0 + 4) {
            hit(py, 1);
            score++;
        }
        // robot paddle (right)
        if (vx > 0 && bx > w - 16 - PAD_W && bx < w - 10 && Math.abs(by - ay) < PAD_H / 2.0 + 4) hit(ay, -1);
        if (bx < 0) {
            lives--;
            if (lives <= 0) over = true;
            else serve();
        }
        if (bx > w) { // the robot missed: a point for you
            score += 2;
            serve();
        }
    }

    private void hit(double paddleY, int dir) {
        speed = Math.min(420, speed * 1.06);
        double rel = Math.max(-1, Math.min(1, (by - paddleY) / (PAD_H / 2.0)));
        double ang = rel * 0.9;
        vx = dir * Math.cos(ang) * speed;
        vy = Math.sin(ang) * speed;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        if (my != lastMy) { // only a moving mouse takes over from the keys
            lastMy = my;
            if (my >= 0 && my <= h && mx >= 0 && mx <= w) mouseY = my;
        }
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        for (int i = 0; i < h; i += 12) Ui.fill(g, x + w / 2 - 1, y + i, 2, 6, 0x30FFFFFF);
        Ui.rr(g, x + 10, (int) (y + py - PAD_H / 2.0), PAD_W, PAD_H, 3, Ui.GOOD);
        Ui.rr(g, x + w - 10 - PAD_W, (int) (y + ay - PAD_H / 2.0), PAD_W, PAD_H, 3, 0xFFF59E0B);
        Ui.rr(g, (int) (x + bx - 4), (int) (y + by - 4), 8, 8, 4, Ui.TEXT);
        if (over) Ui.textC(g, "Game over - click or Space to restart", x + w / 2, y + h / 2 - 4, Ui.BAD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (over) {
            reset();
            return true;
        }
        return false;
    }

    @Override
    public boolean key(int key) {
        switch (key) {
            case 265, 87 -> keyMove = -1;
            case 264, 83 -> keyMove = 1;
            case 32 -> {
                if (over) reset();
            }
            default -> {
                return false;
            }
        }
        mouseY = -1;
        return true;
    }

    @Override
    public void keyUp(int key) {
        if (key == 265 || key == 264 || key == 87 || key == 83) keyMove = 0;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return over ? "Game over" : "Lives " + lives;
    }
}
