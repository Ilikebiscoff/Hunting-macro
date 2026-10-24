package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Connect Four against a computer. Click a column to drop a disc. A win is 5 points, a draw 1. */
final class ConnectFour implements Game {
    private static final int COLS = 7, ROWS = 6;
    private final Random rnd = new Random();
    private final int[][] b = new int[ROWS][COLS]; // 0 empty, 1 you (red), 2 computer (yellow)
    private int w = 300, h = 200, score, wins, losses, draws, result;
    private float wait;

    @Override
    public String name() {
        return "Connect Four";
    }

    @Override
    public String blurb() {
        return "Get four in a row before the robot does. A win is 5 points.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        score = wins = losses = draws = 0;
        board();
    }

    private void board() {
        for (int[] r : b) java.util.Arrays.fill(r, 0);
        result = 0;
        wait = 0;
    }

    private int drop(int c, int who) {
        for (int r = ROWS - 1; r >= 0; r--) {
            if (b[r][c] == 0) {
                b[r][c] = who;
                return r;
            }
        }
        return -1;
    }

    private void undo(int c) {
        for (int r = 0; r < ROWS; r++) {
            if (b[r][c] != 0) {
                b[r][c] = 0;
                return;
            }
        }
    }

    private boolean four(int who) {
        int[][] d = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (b[r][c] != who) continue;
            for (int[] v : d) {
                int n = 1;
                for (int k = 1; k < 4; k++) {
                    int rr = r + v[0] * k, cc = c + v[1] * k;
                    if (rr < 0 || rr >= ROWS || cc < 0 || cc >= COLS || b[rr][cc] != who) break;
                    n++;
                }
                if (n == 4) return true;
            }
        }
        return false;
    }

    private boolean full() {
        for (int c = 0; c < COLS; c++) if (b[0][c] == 0) return false;
        return true;
    }

    private void computer() {
        List<Integer> open = new ArrayList<>();
        for (int c = 0; c < COLS; c++) if (b[0][c] == 0) open.add(c);
        if (open.isEmpty()) return;
        for (int c : open) { // a winning move
            drop(c, 2);
            boolean win = four(2);
            undo(c);
            if (win) {
                drop(c, 2);
                return;
            }
        }
        for (int c : open) { // block the player
            drop(c, 1);
            boolean win = four(1);
            undo(c);
            if (win) {
                drop(c, 2);
                return;
            }
        }
        List<Integer> safe = new ArrayList<>(); // do not hand the player a win on top of it
        for (int c : open) {
            drop(c, 2);
            boolean gives = false;
            if (b[0][c] == 0) {
                drop(c, 1);
                gives = four(1);
                undo(c);
            }
            undo(c);
            if (!gives) safe.add(c);
        }
        List<Integer> pool = safe.isEmpty() ? open : safe;
        int best = pool.get(rnd.nextInt(pool.size()));
        int bestScore = Integer.MIN_VALUE;
        for (int c : pool) { // prefer the middle, a little randomness
            int s = -Math.abs(c - 3) * 3 + rnd.nextInt(3);
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        drop(best, 2);
    }

    private void check() {
        if (four(1)) {
            result = 1;
            wins++;
            score += 5;
        } else if (four(2)) {
            result = 2;
            losses++;
        } else if (full()) {
            result = 3;
            draws++;
            score += 1;
        }
    }

    @Override
    public void update(float dt) {
        if (wait > 0) {
            wait -= dt;
            if (wait <= 0 && result == 0) {
                computer();
                check();
            }
        }
    }

    private int cs() {
        return Math.max(14, Math.min((h - 14) / ROWS, (w - 8) / COLS));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cs = cs(), ox = x + (w - cs * COLS) / 2, oy = y + (h - cs * ROWS) / 2 + 6;
        Ui.rr(g, ox - 3, oy - 3, cs * COLS + 6, cs * ROWS + 6, 5, 0xFF1D4ED8);
        int hoverCol = result == 0 && wait <= 0 ? (int) Math.floor((mx - (ox - x)) / (double) cs) : -1;
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            int color = b[r][c] == 1 ? 0xFFEF4444 : b[r][c] == 2 ? 0xFFFACC15 : (c == hoverCol ? 0xFF2F3A6B : 0xFF0B0D12);
            Ui.rr(g, ox + c * cs + 2, oy + r * cs + 2, cs - 4, cs - 4, (cs - 4) / 2, color);
        }
        if (hoverCol >= 0 && hoverCol < COLS) Ui.rr(g, ox + hoverCol * cs + cs / 2 - 4, oy - 12, 8, 8, 4, 0xFFEF4444);
        if (result != 0) {
            String t = result == 1 ? "You win! +5" : result == 2 ? "The robot wins" : "Draw +1";
            Ui.textC(g, t + " - click for the next round", x + w / 2, y + 2, result == 1 ? Ui.GOOD : result == 2 ? Ui.BAD : Ui.WARN);
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (result != 0) {
            board();
            return true;
        }
        if (wait > 0) return true;
        int cs = cs(), ox = (w - cs * COLS) / 2;
        int c = (int) Math.floor((mx - ox) / cs);
        if (c < 0 || c >= COLS || b[0][c] != 0) return false;
        drop(c, 1);
        check();
        if (result == 0) wait = 0.5f;
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 || key == 82) {
            board();
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
        return "Wins " + wins + "   Draws " + draws + "   Losses " + losses;
    }
}
