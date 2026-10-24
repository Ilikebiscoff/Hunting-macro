package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/** Tetris: Left / Right move, Up or X rotates, Down drops faster, Space drops at once. */
final class Tetris implements Game {
    private static final int COLS = 10, ROWS = 20;
    // 4x4 shapes, as 16-bit masks per rotation are overkill: the shapes are stored as cell lists of the first rotation
    private static final int[][][] SHAPES = {
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}},  // I
            {{1, 0}, {2, 0}, {1, 1}, {2, 1}},  // O
            {{1, 0}, {0, 1}, {1, 1}, {2, 1}},  // T
            {{1, 0}, {2, 0}, {0, 1}, {1, 1}},  // S
            {{0, 0}, {1, 0}, {1, 1}, {2, 1}},  // Z
            {{0, 0}, {0, 1}, {1, 1}, {2, 1}},  // J
            {{2, 0}, {0, 1}, {1, 1}, {2, 1}}}; // L
    private static final int[] COLORS = {0xFF22D3EE, 0xFFFBBF24, 0xFFA78BFA, 0xFF34D399, 0xFFF87171, 0xFF60A5FA, 0xFFFB923C};

    private final Random rnd = new Random();
    private final int[][] board = new int[ROWS][COLS]; // 0 empty, else color index + 1
    private int w = 300, h = 200, type, rot, px, py, nextType, score, lines, level = 1;
    private float fall;
    private boolean over;

    @Override
    public String name() {
        return "Tetris";
    }

    @Override
    public String blurb() {
        return "Stack the falling blocks and clear lines. Arrows move and rotate, Space drops.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        for (int[] row : board) java.util.Arrays.fill(row, 0);
        score = 0;
        lines = 0;
        level = 1;
        over = false;
        nextType = rnd.nextInt(7);
        spawn();
    }

    private void spawn() {
        type = nextType;
        nextType = rnd.nextInt(7);
        rot = 0;
        px = 3;
        py = 0;
        fall = 0;
        if (collides(type, rot, px, py)) over = true;
    }

    /** The cells of a piece in a rotation (rotating the 4x4 box clockwise {@code r} times). */
    private static int[][] cells(int t, int r) {
        int[][] c = new int[4][2];
        for (int i = 0; i < 4; i++) {
            int x = SHAPES[t][i][0], y = SHAPES[t][i][1];
            for (int k = 0; k < r % 4; k++) {
                int nx = 3 - y, ny = x; // 90 degrees in a 4x4 box
                x = nx;
                y = ny;
            }
            c[i][0] = x;
            c[i][1] = y;
        }
        return c;
    }

    private boolean collides(int t, int r, int x, int y) {
        for (int[] c : cells(t, r)) {
            int cx = x + c[0], cy = y + c[1];
            if (cx < 0 || cx >= COLS || cy >= ROWS) return true;
            if (cy >= 0 && board[cy][cx] != 0) return true;
        }
        return false;
    }

    private void lock() {
        for (int[] c : cells(type, rot)) {
            int cx = px + c[0], cy = py + c[1];
            if (cy >= 0 && cy < ROWS) board[cy][cx] = type + 1;
        }
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < COLS; c++) if (board[r][c] == 0) full = false;
            if (!full) continue;
            cleared++;
            for (int rr = r; rr > 0; rr--) board[rr] = board[rr - 1].clone();
            board[0] = new int[COLS];
            r++; // look at the same row again
        }
        if (cleared > 0) {
            lines += cleared;
            score += new int[]{0, 100, 300, 500, 800}[Math.min(4, cleared)] * level;
            level = 1 + lines / 10;
        }
        spawn();
    }

    private boolean move(int dx, int dy) {
        if (collides(type, rot, px + dx, py + dy)) return false;
        px += dx;
        py += dy;
        return true;
    }

    @Override
    public void update(float dt) {
        if (over) return;
        fall += dt;
        float step = Math.max(0.07f, 0.8f - (level - 1) * 0.07f);
        while (fall >= step && !over) {
            fall -= step;
            if (!move(0, 1)) lock();
        }
    }

    private void cell(GuiGraphicsExtractor g, int ox, int oy, int cs, int cx, int cy, int color) {
        Ui.rr(g, ox + cx * cs + 1, oy + cy * cs + 1, cs - 2, cs - 2, 2, color);
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cs = Math.max(6, Math.min((h - 6) / ROWS, (w - 110) / COLS));
        int ox = x + (w - COLS * cs) / 2 - 30, oy = y + (h - ROWS * cs) / 2;
        Ui.rrb(g, ox - 2, oy - 2, COLS * cs + 4, ROWS * cs + 4, 3, 0xFF10131A, Ui.LINE);
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) if (board[r][c] != 0) cell(g, ox, oy, cs, c, r, COLORS[board[r][c] - 1]);
        if (!over) {
            int gy = py;
            while (!collides(type, rot, px, gy + 1)) gy++;
            for (int[] c : cells(type, rot)) {
                if (gy + c[1] >= 0) Ui.rr(g, ox + (px + c[0]) * cs + 1, oy + (gy + c[1]) * cs + 1, cs - 2, cs - 2, 2, Ui.withAlpha(COLORS[type], 60)); // landing shadow
                if (py + c[1] >= 0) cell(g, ox, oy, cs, px + c[0], py + c[1], COLORS[type]);
            }
        }
        int sx = ox + COLS * cs + 14;
        Ui.text(g, "Next", sx, oy, Ui.MUTED);
        for (int[] c : cells(nextType, 0)) Ui.rr(g, sx + c[0] * 8, oy + 14 + c[1] * 8, 7, 7, 2, COLORS[nextType]);
        Ui.text(g, "Lines " + lines, sx, oy + 56, Ui.TEXT);
        Ui.text(g, "Level " + level, sx, oy + 68, Ui.TEXT);
        if (over) {
            Ui.textC(g, "Game over", ox + COLS * cs / 2, oy + ROWS * cs / 2 - 10, Ui.BAD);
            Ui.textC(g, "Space = again", ox + COLS * cs / 2, oy + ROWS * cs / 2 + 2, Ui.MUTED);
        }
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
        if (over) {
            if (key == 32) reset();
            return key == 32;
        }
        switch (key) {
            case 263, 65 -> move(-1, 0);
            case 262, 68 -> move(1, 0);
            case 264, 83 -> {
                if (move(0, 1)) score++;
            }
            case 265, 87, 88 -> {
                int nr = (rot + 1) % 4;
                for (int kick : new int[]{0, -1, 1, -2, 2}) {
                    if (!collides(type, nr, px + kick, py)) {
                        rot = nr;
                        px += kick;
                        break;
                    }
                }
            }
            case 32 -> {
                int n = 0;
                while (move(0, 1)) n++;
                score += 2 * n;
                lock();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return over ? "Game over" : "Score " + score;
    }
}
