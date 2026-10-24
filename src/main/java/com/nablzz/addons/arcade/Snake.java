package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/** Classic snake on a grid. Arrow keys or WASD. */
final class Snake implements Game {
    private static final int CELL = 14;
    private final Random rnd = new Random();
    private final Deque<int[]> body = new ArrayDeque<>();
    private int w, h, cols = 20, rows = 14, dx = 1, dy = 0, nx = 1, ny = 0, fx, fy, score;
    private float acc;
    private boolean dead, started;

    @Override
    public String name() {
        return "Snake";
    }

    @Override
    public String blurb() {
        return "Eat the shards, don't bite yourself. Arrow keys or WASD.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
        int c = Math.max(8, w / CELL), r = Math.max(8, h / CELL);
        if (c != cols || r != rows) {
            cols = c;
            rows = r;
            reset();
        }
    }

    @Override
    public void reset() {
        body.clear();
        for (int i = 0; i < 3; i++) body.addFirst(new int[]{cols / 3 - i + 3, rows / 2});
        dx = nx = 1;
        dy = ny = 0;
        score = 0;
        dead = false;
        started = false;
        acc = 0;
        food();
    }

    private void food() {
        for (int tries = 0; tries < 200; tries++) {
            fx = rnd.nextInt(cols);
            fy = rnd.nextInt(rows);
            boolean free = true;
            for (int[] c : body) if (c[0] == fx && c[1] == fy) free = false;
            if (free) return;
        }
    }

    @Override
    public void update(float dt) {
        if (dead || !started) return;
        acc += dt;
        float step = Math.max(0.05f, 0.14f - score * 0.003f);
        while (acc >= step) {
            acc -= step;
            dx = nx;
            dy = ny;
            int[] head = body.peekFirst();
            int hx = head[0] + dx, hy = head[1] + dy;
            if (hx < 0 || hy < 0 || hx >= cols || hy >= rows) {
                dead = true;
                return;
            }
            boolean grow = hx == fx && hy == fy;
            if (!grow) body.removeLast();
            for (int[] c : body) {
                if (c[0] == hx && c[1] == hy) {
                    dead = true;
                    return;
                }
            }
            body.addFirst(new int[]{hx, hy});
            if (grow) {
                score++;
                food();
            }
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        int ox = x + (w - cols * CELL) / 2, oy = y + (h - rows * CELL) / 2;
        Ui.fill(g, ox, oy, cols * CELL, rows * CELL, 0xFF0B0D12);
        Ui.rr(g, ox + fx * CELL + 2, oy + fy * CELL + 2, CELL - 4, CELL - 4, 5, 0xFFFBBF24);
        boolean head = true;
        for (int[] c : body) {
            Ui.rr(g, ox + c[0] * CELL + 1, oy + c[1] * CELL + 1, CELL - 2, CELL - 2, 4, head ? (dead ? Ui.BAD : 0xFF6EE7B7) : 0xFF10B981);
            head = false;
        }
        if (!started && !dead) Ui.textC(g, "Press an arrow key to start", x + w / 2, y + h / 2 - 4, Ui.TEXT);
        if (dead) Ui.textC(g, "Crash! Space restarts", x + w / 2, y + h / 2 - 4, Ui.BAD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (dead) {
            reset();
            return true;
        }
        return false;
    }

    @Override
    public boolean key(int key) {
        int tx = nx, ty = ny;
        switch (key) {
            case 265, 87 -> { tx = 0; ty = -1; }
            case 264, 83 -> { tx = 0; ty = 1; }
            case 263, 65 -> { tx = -1; ty = 0; }
            case 262, 68 -> { tx = 1; ty = 0; }
            case 32 -> {
                if (dead) reset();
                return true;
            }
            default -> {
                return false;
            }
        }
        if (dead) return true;
        if (tx != -dx || ty != -dy) {
            nx = tx;
            ny = ty;
        }
        started = true;
        return true;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return dead ? "Game over - Space to play again" : "Length " + (body.size());
    }
}
