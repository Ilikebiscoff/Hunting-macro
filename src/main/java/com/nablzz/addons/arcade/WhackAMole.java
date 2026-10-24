package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/** Mudworms pop out of 9 holes for a moment: click them. 30 seconds, a miss-click costs a point. */
final class WhackAMole implements Game {
    private static final float ROUND = 30f;
    private final Random rnd = new Random();
    private final float[] up = new float[9];   // seconds left that the worm stays up (0 = hidden)
    private final float[] hitFlash = new float[9];
    private int w = 300, h = 200, score, hits, misses, best;
    private float time, spawn;
    private boolean over;

    @Override
    public String name() {
        return "Whack-a-Worm";
    }

    @Override
    public String blurb() {
        return "Click the mudworms as they pop out of the ground. 30 seconds, no missing!";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(up, 0);
        java.util.Arrays.fill(hitFlash, 0);
        score = hits = misses = 0;
        time = ROUND;
        spawn = 0.4f;
        over = false;
    }

    @Override
    public void update(float dt) {
        for (int i = 0; i < 9; i++) {
            up[i] = Math.max(0, up[i] - dt);
            hitFlash[i] = Math.max(0, hitFlash[i] - dt);
        }
        if (over) return;
        time -= dt;
        if (time <= 0) {
            time = 0;
            over = true;
            return;
        }
        spawn -= dt;
        if (spawn <= 0) {
            float speedUp = 1f - (ROUND - time) / ROUND * 0.5f; // gets faster
            int i = rnd.nextInt(9);
            if (up[i] <= 0) up[i] = (0.55f + rnd.nextFloat() * 0.6f) * speedUp + 0.25f;
            spawn = (0.25f + rnd.nextFloat() * 0.45f) * speedUp;
        }
    }

    private int cs() {
        return Math.max(24, Math.min((h - 22) / 3, (w - 8) / 3));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF14291C);
        int cs = cs(), ox = x + (w - cs * 3) / 2, oy = y + 18 + (h - 18 - cs * 3) / 2;
        Ui.textC(g, String.format("%.0f s", time), x + w / 2, y + 4, time < 6 ? Ui.BAD : Ui.TEXT);
        for (int i = 0; i < 9; i++) {
            int cx = ox + (i % 3) * cs, cy = oy + (i / 3) * cs, r = cs / 2 - 4;
            Ui.rr(g, cx + cs / 2 - r, cy + cs / 2 + r / 3, r * 2, r, r / 2, 0xFF0B140E); // the hole
            if (up[i] > 0) {
                int body = hitFlash[i] > 0 ? 0xFFFBBF24 : 0xFF84CC16;
                Ui.rr(g, cx + cs / 2 - r + 4, cy + cs / 2 - r + 2, r * 2 - 8, r * 2 - 6, r - 3, body);
                Ui.rr(g, cx + cs / 2 - r / 2 - 2, cy + cs / 2 - r / 3, 4, 4, 2, 0xFF111827);
                Ui.rr(g, cx + cs / 2 + r / 2 - 2, cy + cs / 2 - r / 3, 4, 4, 2, 0xFF111827);
            }
        }
        if (over) {
            Ui.textC(g, "Time! " + hits + " hit, " + misses + " missed - click to play again", x + w / 2, y + h / 2 - 4, Ui.WARN);
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (over) {
            reset();
            return true;
        }
        int cs = cs(), ox = (w - cs * 3) / 2, oy = 18 + (h - 18 - cs * 3) / 2;
        int c = (int) Math.floor((mx - ox) / cs), r = (int) Math.floor((my - oy) / cs);
        if (c >= 0 && r >= 0 && c < 3 && r < 3 && up[r * 3 + c] > 0) {
            up[r * 3 + c] = 0;
            hitFlash[r * 3 + c] = 0.15f;
            hits++;
            score++;
        } else if (c >= 0 && r >= 0 && c < 3 && r < 3) {
            misses++;
            score = Math.max(0, score - 1);
        }
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 && over) {
            reset();
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
        return "Hits " + hits + "   Misses " + misses;
    }
}
