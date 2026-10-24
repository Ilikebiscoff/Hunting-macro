package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/** Minesweeper, 9 x 9 with 10 mines. Left click reveals, right click (or Shift+click) flags. The score counts your wins. */
final class Minesweeper implements Game {
    private static final int N = 9, MINES = 10;
    private static final int[] NUM_COLORS = {0, 0xFF60A5FA, 0xFF34D399, 0xFFF87171, 0xFFA78BFA, 0xFFFBBF24, 0xFF22D3EE, 0xFFE7E9EF, 0xFF8D94A3};
    private final Random rnd = new Random();
    private final boolean[][] mine = new boolean[N][N], open = new boolean[N][N], flag = new boolean[N][N];
    private final int[][] near = new int[N][N];
    private int w = 300, h = 200, wins, flags;
    private boolean placed, dead, won;
    private double time;

    @Override
    public String name() {
        return "Minesweeper";
    }

    @Override
    public String blurb() {
        return "9 x 9, 10 mines. Left click reveals, right click flags.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            mine[i][j] = open[i][j] = flag[i][j] = false;
            near[i][j] = 0;
        }
        placed = dead = won = false;
        flags = 0;
        time = 0;
    }

    private int cell() {
        return Math.max(8, Math.min((w - 10) / N, (h - 10) / N));
    }

    private void place(int sx, int sy) {
        int put = 0;
        while (put < MINES) {
            int i = rnd.nextInt(N), j = rnd.nextInt(N);
            if (mine[i][j] || (Math.abs(i - sx) <= 1 && Math.abs(j - sy) <= 1)) continue; // the first click is safe
            mine[i][j] = true;
            put++;
        }
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            int c = 0;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                int ni = i + a, nj = j + b;
                if (ni >= 0 && nj >= 0 && ni < N && nj < N && mine[ni][nj]) c++;
            }
            near[i][j] = c;
        }
        placed = true;
    }

    private void flood(int i, int j) {
        if (i < 0 || j < 0 || i >= N || j >= N || open[i][j] || flag[i][j]) return;
        open[i][j] = true;
        if (near[i][j] != 0) return;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if (a != 0 || b != 0) flood(i + a, j + b);
    }

    private void checkWin() {
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) if (!mine[i][j] && !open[i][j]) return;
        won = true;
        wins++;
    }

    @Override
    public void update(float dt) {
        if (placed && !dead && !won) time += dt;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        int cs = cell(), ox = x + (w - cs * N) / 2, oy = y + (h - cs * N) / 2;
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int hi = -1, hj = -1;
        if (!dead && !won) {
            int ci = (int) Math.floor((mx - (ox - x)) / (double) cs), cj = (int) Math.floor((my - (oy - y)) / (double) cs);
            if (ci >= 0 && cj >= 0 && ci < N && cj < N) {
                hi = ci;
                hj = cj;
            }
        }
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            int cx = ox + i * cs, cy = oy + j * cs;
            boolean boom = dead && mine[i][j];
            int bg = open[i][j] ? 0xFF171A21 : (i == hi && j == hj ? 0xFF2F3644 : 0xFF252A35);
            if (boom) bg = Ui.BAD;
            Ui.rr(g, cx + 1, cy + 1, cs - 2, cs - 2, 3, bg);
            if (open[i][j] && near[i][j] > 0) Ui.textC(g, "" + near[i][j], cx + cs / 2, cy + cs / 2 - 4, NUM_COLORS[near[i][j]]);
            else if (flag[i][j] && !open[i][j]) Ui.textC(g, "F", cx + cs / 2, cy + cs / 2 - 4, Ui.WARN);
            else if (boom) Ui.textC(g, "*", cx + cs / 2, cy + cs / 2 - 4, 0xFFFFFFFF);
        }
        if (dead) Ui.textC(g, "BOOM - click for a new board", x + w / 2, y + 4, Ui.BAD);
        if (won) Ui.textC(g, "Cleared! Click for a new board", x + w / 2, y + 4, Ui.GOOD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (dead || won) {
            reset();
            return true;
        }
        int cs = cell(), ox = (w - cs * N) / 2, oy = (h - cs * N) / 2;
        int i = (int) Math.floor((mx - ox) / cs), j = (int) Math.floor((my - oy) / cs);
        if (i < 0 || j < 0 || i >= N || j >= N) return false;
        if (button == 1) { // right click: flag
            if (!open[i][j]) {
                flag[i][j] = !flag[i][j];
                flags += flag[i][j] ? 1 : -1;
            }
            return true;
        }
        if (flag[i][j] || open[i][j]) return true;
        if (!placed) place(i, j);
        if (mine[i][j]) {
            dead = true;
            for (int a = 0; a < N; a++) for (int b = 0; b < N; b++) if (mine[a][b]) open[a][b] = false;
            return true;
        }
        flood(i, j);
        checkWin();
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 || key == 82) { // space / R: new board
            reset();
            return true;
        }
        return false;
    }

    @Override
    public int score() {
        return wins;
    }

    @Override
    public String status() {
        return "Mines left " + (MINES - flags) + "   Time " + (int) time + " s   Wins " + wins + "   (R = new board)";
    }
}
