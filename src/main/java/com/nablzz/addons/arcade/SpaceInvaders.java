package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shoot the descending invaders. Left / Right (or the mouse) move, Space or a click fires. Three lives. */
final class SpaceInvaders implements Game {
    private static final int COLS = 8, ROWS = 4;
    private final Random rnd = new Random();
    private final boolean[][] alive = new boolean[ROWS][COLS];
    private final List<double[]> shots = new ArrayList<>(), bombs = new ArrayList<>(); // {x, y}
    private int w = 300, h = 200, score, lives = 3, wave = 1, left;
    private double shipX, formX, formY, dir = 1, move, keyDir, lastMx = -1;
    private float cooldown, bombIn, hurt;
    private boolean over;

    @Override
    public String name() {
        return "Space Invaders";
    }

    @Override
    public String blurb() {
        return "Shoot the invaders before they land. Left/Right or mouse to move, Space or click to fire.";
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
        wave = 1;
        over = false;
        shipX = w / 2.0;
        newWave();
    }

    private void newWave() {
        for (boolean[] r : alive) java.util.Arrays.fill(r, true);
        left = ROWS * COLS;
        formX = 10;
        formY = 18;
        dir = 1;
        shots.clear();
        bombs.clear();
        bombIn = 1f;
    }

    private double gapX() {
        return Math.min(26, (w - 30) / (double) COLS);
    }

    @Override
    public void update(float dt) {
        if (over) return;
        cooldown = Math.max(0, cooldown - dt);
        hurt = Math.max(0, hurt - dt);
        shipX = Math.max(10, Math.min(w - 10, shipX + keyDir * 190 * dt));
        // the formation
        double speed = 16 + wave * 5 + (ROWS * COLS - left) * 1.6;
        formX += dir * speed * dt;
        double width = gapX() * (COLS - 1) + 14;
        if (formX < 4 || formX + width > w - 4) {
            dir = -dir;
            formX = Math.max(4, Math.min(w - 4 - width, formX));
            formY += 10;
        }
        if (formY + ROWS * 16 >= h - 22) {
            lives = 0;
            over = true;
            return;
        }
        // shots and bombs
        for (var it = shots.iterator(); it.hasNext(); ) {
            double[] s = it.next();
            s[1] -= 260 * dt;
            if (s[1] < 0) {
                it.remove();
                continue;
            }
            int c = (int) Math.floor((s[0] - formX + 7) / gapX()), r = (int) Math.floor((s[1] - formY) / 16);
            if (c >= 0 && c < COLS && r >= 0 && r < ROWS && alive[r][c] && Math.abs(s[0] - (formX + c * gapX() + 7)) < 8) {
                alive[r][c] = false;
                left--;
                score += 10 * (ROWS - r);
                it.remove();
            }
        }
        bombIn -= dt;
        if (bombIn <= 0 && left > 0) {
            bombIn = Math.max(0.3f, 1.1f - wave * 0.08f) * (0.5f + rnd.nextFloat());
            for (int tries = 0; tries < 12; tries++) {
                int c = rnd.nextInt(COLS), r = ROWS - 1;
                while (r >= 0 && !alive[r][c]) r--;
                if (r >= 0) {
                    bombs.add(new double[]{formX + c * gapX() + 7, formY + r * 16 + 12});
                    break;
                }
            }
        }
        for (var it = bombs.iterator(); it.hasNext(); ) {
            double[] b = it.next();
            b[1] += (90 + wave * 8) * dt;
            if (b[1] > h - 16 && Math.abs(b[0] - shipX) < 11) {
                it.remove();
                lives--;
                hurt = 0.4f;
                if (lives <= 0) over = true;
            } else if (b[1] > h) {
                it.remove();
            }
        }
        if (left == 0) {
            wave++;
            score += 50;
            newWave();
        }
    }

    private void fire() {
        if (over) {
            reset();
            return;
        }
        if (cooldown > 0) return;
        shots.add(new double[]{shipX, h - 20});
        cooldown = 0.28f;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        if (mx != lastMx && mx >= 0 && mx <= w) {
            lastMx = mx;
            shipX = Math.max(10, Math.min(w - 10, mx));
        }
        Ui.fill(g, x, y, w, h, 0xFF05060A);
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (!alive[r][c]) continue;
            int ix = (int) (x + formX + c * gapX()), iy = (int) (y + formY + r * 16);
            int col = new int[]{0xFFF87171, 0xFFFB923C, 0xFFFBBF24, 0xFF34D399}[r];
            Ui.rr(g, ix, iy, 14, 10, 3, col);
            Ui.fill(g, ix + 2, iy + 10, 3, 3, col);
            Ui.fill(g, ix + 9, iy + 10, 3, 3, col);
            Ui.fill(g, ix + 3, iy + 3, 2, 2, 0xFF05060A);
            Ui.fill(g, ix + 9, iy + 3, 2, 2, 0xFF05060A);
        }
        for (double[] s : shots) Ui.fill(g, (int) (x + s[0]) - 1, (int) (y + s[1]), 2, 7, Ui.GOOD);
        for (double[] b : bombs) Ui.rr(g, (int) (x + b[0]) - 2, (int) (y + b[1]), 4, 7, 2, 0xFFF43F5E);
        int sx = (int) (x + shipX), sy = y + h - 14;
        Ui.rr(g, sx - 10, sy + 3, 20, 7, 3, hurt > 0 ? Ui.BAD : 0xFF60A5FA);
        Ui.rr(g, sx - 3, sy - 2, 6, 7, 2, hurt > 0 ? Ui.BAD : 0xFF93C5FD);
        Ui.text(g, "Wave " + wave, x + 4, y + 3, Ui.MUTED);
        Ui.textR(g, "Lives " + lives, x + w - 4, y + 3, Ui.MUTED);
        if (over) Ui.textC(g, "Invaded! Click or Space for a new game", x + w / 2, y + h / 2 - 4, Ui.BAD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        fire();
        return true;
    }

    @Override
    public boolean key(int key) {
        switch (key) {
            case 263, 65 -> keyDir = -1;
            case 262, 68 -> keyDir = 1;
            case 32, 265, 87 -> fire();
            default -> {
                return false;
            }
        }
        lastMx = -2;
        return true;
    }

    @Override
    public void keyUp(int key) {
        if (key == 263 || key == 262 || key == 65 || key == 68) keyDir = 0;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return "Score " + score;
    }
}
