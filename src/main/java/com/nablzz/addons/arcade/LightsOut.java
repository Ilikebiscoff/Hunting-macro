package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/** Lights Out: clicking a light flips it and its four neighbours. Turn them all off. */
final class LightsOut implements Game {
    private static final int N = 5;
    private final Random rnd = new Random();
    private final boolean[][] on = new boolean[N][N];
    private int w = 300, h = 200, moves, solved;
    private boolean done;

    @Override
    public String name() {
        return "Lights Out";
    }

    @Override
    public String blurb() {
        return "Switch off every light. Each click also flips the lights next to it.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        solved = 0;
        puzzle();
    }

    private void toggle(int r, int c) {
        int[][] d = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] v : d) {
            int rr = r + v[0], cc = c + v[1];
            if (rr >= 0 && rr < N && cc >= 0 && cc < N) on[rr][cc] = !on[rr][cc];
        }
    }

    private void puzzle() {
        for (boolean[] r : on) java.util.Arrays.fill(r, false);
        do {
            for (int i = 0; i < 6 + solved; i++) toggle(rnd.nextInt(N), rnd.nextInt(N)); // scrambling from the solved state keeps it solvable
        } while (allOff());
        moves = 0;
        done = false;
    }

    private boolean allOff() {
        for (boolean[] r : on) for (boolean v : r) if (v) return false;
        return true;
    }

    @Override
    public void update(float dt) {
    }

    private int cs() {
        return Math.max(18, Math.min((h - 8) / N, (w - 8) / N));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cs = cs(), ox = x + (w - cs * N) / 2, oy = y + (h - cs * N) / 2;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
            boolean hov = !done && mx >= ox - x + c * cs && mx < ox - x + (c + 1) * cs && my >= oy - y + r * cs && my < oy - y + (r + 1) * cs;
            int col = on[r][c] ? 0xFFFBBF24 : (hov ? 0xFF2F3644 : 0xFF171A21);
            Ui.rr(g, ox + c * cs + 2, oy + r * cs + 2, cs - 4, cs - 4, 6, col);
            if (on[r][c]) Ui.rr(g, ox + c * cs + cs / 2 - 3, oy + r * cs + cs / 2 - 3, 6, 6, 3, 0xFFFEF3C7);
        }
        if (done) Ui.textC(g, "Solved in " + moves + " moves! Click for the next puzzle", x + w / 2, y + 2, Ui.GOOD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (done) {
            puzzle();
            return true;
        }
        int cs = cs(), ox = (w - cs * N) / 2, oy = (h - cs * N) / 2;
        int c = (int) Math.floor((mx - ox) / cs), r = (int) Math.floor((my - oy) / cs);
        if (c < 0 || r < 0 || c >= N || r >= N) return false;
        toggle(r, c);
        moves++;
        if (allOff()) {
            done = true;
            solved++;
        }
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 || key == 82) {
            puzzle();
            return true;
        }
        return false;
    }

    @Override
    public int score() {
        return solved;
    }

    @Override
    public String status() {
        return "Moves " + moves + "   Solved " + solved + "   (R = new puzzle)";
    }
}
