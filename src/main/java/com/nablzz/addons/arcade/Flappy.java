package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A mudworm shard that flaps through gaps between pillars. Space, Up or click to flap. */
final class Flappy implements Game {
    private static final double GAP = 70, PIPE_W = 26, SPEED = 95;
    private final Random rnd = new Random();
    private final List<double[]> pipes = new ArrayList<>(); // {x, gapCenterY, passed}
    private int w = 300, h = 200, score;
    private double y, vy, spawnX, wob;
    private boolean started, dead;

    @Override
    public String name() {
        return "Flappy Shard";
    }

    @Override
    public String blurb() {
        return "Flap through the gaps. Space, Up or click.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        pipes.clear();
        y = h / 2.0;
        vy = 0;
        score = 0;
        started = false;
        dead = false;
        spawnX = w + 20;
    }

    private void flap() {
        if (dead) {
            reset();
            return;
        }
        started = true;
        vy = -190;
    }

    @Override
    public void update(float dt) {
        wob += dt * 6;
        if (!started || dead) return;
        vy += 520 * dt;
        y += vy * dt;
        for (double[] p : pipes) p[0] -= SPEED * dt;
        spawnX -= SPEED * dt;
        if (spawnX < w - 150) {
            pipes.add(new double[]{w + 10, 45 + rnd.nextDouble() * (h - 90), 0});
            spawnX = w + 10;
        }
        pipes.removeIf(p -> p[0] < -PIPE_W - 4);
        for (double[] p : pipes) {
            if (p[2] == 0 && p[0] + PIPE_W < w * 0.28 - 8) {
                p[2] = 1;
                score++;
            }
            double bx = w * 0.28;
            if (bx + 7 > p[0] && bx - 7 < p[0] + PIPE_W && (y - 7 < p[1] - GAP / 2 || y + 7 > p[1] + GAP / 2)) dead = true;
        }
        if (y > h - 7 || y < 7) dead = true;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y0, int mx, int my) {
        Ui.fill(g, x, y0, w, h, 0xFF0B0D12);
        for (double[] p : pipes) {
            int px = (int) (x + p[0]);
            int topH = (int) (p[1] - GAP / 2), botY = (int) (p[1] + GAP / 2);
            Ui.rr(g, px, y0, (int) PIPE_W, topH, 3, 0xFF2B7A5C);
            Ui.rr(g, px, y0 + botY, (int) PIPE_W, h - botY, 3, 0xFF2B7A5C);
            Ui.fill(g, px + 3, y0 + topH - 5, (int) PIPE_W - 6, 3, 0xFF6EE7B7);
            Ui.fill(g, px + 3, y0 + botY + 2, (int) PIPE_W - 6, 3, 0xFF6EE7B7);
        }
        int sx = (int) (x + w * 0.28), sy = (int) (y0 + (started ? y : h / 2.0 + Math.sin(wob) * 6));
        Ui.rr(g, sx - 8, sy - 8, 16, 16, 8, dead ? Ui.BAD : 0xFF10B981);
        Ui.rr(g, sx - 4, sy - 6, 7, 7, 3, 0xAA6EE7B7);
        Ui.fill(g, x, y0 + h - 2, w, 2, 0x40F87171);
        Ui.textC(g, "" + score, x + w / 2, y0 + 8, Ui.TEXT);
        if (!started && !dead) Ui.textC(g, "Flap to start", x + w / 2, y0 + h / 2 + 24, Ui.MUTED);
        if (dead) Ui.textC(g, "Splat! Flap to try again", x + w / 2, y0 + h / 2 + 24, Ui.BAD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        flap();
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 || key == 265 || key == 87) {
            flap();
            return true;
        }
        return false;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return dead ? "Game over" : "Pillars passed " + score;
    }
}
