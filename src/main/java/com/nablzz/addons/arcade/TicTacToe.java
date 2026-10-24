package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Tic-tac-toe against a computer that plays well but sometimes slips. Win = 3 points, draw = 1. */
final class TicTacToe implements Game {
    private static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
    private final Random rnd = new Random();
    private final int[] b = new int[9]; // 0 empty, 1 you (X), 2 computer (O)
    private int w = 300, h = 200, score, wins, draws, losses, result; // result: 0 playing, 1 you won, 2 lost, 3 draw
    private float wait;

    @Override
    public String name() {
        return "Tic-Tac-Toe";
    }

    @Override
    public String blurb() {
        return "X against a clever robot. A win is 3 points, a draw 1.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        score = wins = draws = losses = 0;
        board();
    }

    private void board() {
        java.util.Arrays.fill(b, 0);
        result = 0;
        wait = 0;
    }

    private int winner(int[] s) {
        for (int[] l : LINES) if (s[l[0]] != 0 && s[l[0]] == s[l[1]] && s[l[1]] == s[l[2]]) return s[l[0]];
        for (int v : s) if (v == 0) return 0;
        return 3;
    }

    /** Minimax score for the computer (2). */
    private int minimax(int[] s, int turn) {
        int win = winner(s);
        if (win == 2) return 1;
        if (win == 1) return -1;
        if (win == 3) return 0;
        int best = turn == 2 ? -2 : 2;
        for (int i = 0; i < 9; i++) {
            if (s[i] != 0) continue;
            s[i] = turn;
            int v = minimax(s, 3 - turn);
            s[i] = 0;
            best = turn == 2 ? Math.max(best, v) : Math.min(best, v);
        }
        return best;
    }

    private void computer() {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (b[i] == 0) free.add(i);
        if (free.isEmpty()) return;
        int move = free.get(rnd.nextInt(free.size()));
        if (rnd.nextInt(100) >= 20) { // plays the best move 80 % of the time
            int best = -2;
            List<Integer> bests = new ArrayList<>();
            for (int i : free) {
                b[i] = 2;
                int v = minimax(b, 1);
                b[i] = 0;
                if (v > best) {
                    best = v;
                    bests.clear();
                }
                if (v == best) bests.add(i);
            }
            move = bests.get(rnd.nextInt(bests.size()));
        }
        b[move] = 2;
    }

    private void finishIfOver() {
        int win = winner(b);
        if (win == 0) return;
        result = win == 1 ? 1 : win == 2 ? 2 : 3;
        if (result == 1) {
            wins++;
            score += 3;
        } else if (result == 3) {
            draws++;
            score += 1;
        } else {
            losses++;
        }
    }

    @Override
    public void update(float dt) {
        if (wait > 0) {
            wait -= dt;
            if (wait <= 0 && result == 0) {
                computer();
                finishIfOver();
            }
        }
    }

    private int cs() {
        return Math.max(24, Math.min((h - 10) / 3, (w - 10) / 3));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cs = cs(), ox = x + (w - cs * 3) / 2, oy = y + (h - cs * 3) / 2;
        for (int i = 0; i < 9; i++) {
            int cx = ox + (i % 3) * cs, cy = oy + (i / 3) * cs;
            boolean hov = result == 0 && wait <= 0 && b[i] == 0 && mx >= cx - x && mx < cx - x + cs && my >= cy - y && my < cy - y + cs;
            Ui.rr(g, cx + 2, cy + 2, cs - 4, cs - 4, 5, hov ? 0xFF2F3644 : 0xFF171A21);
            if (b[i] == 1) Ui.textC(g, "X", cx + cs / 2, cy + cs / 2 - 4, Ui.GOOD);
            if (b[i] == 2) Ui.textC(g, "O", cx + cs / 2, cy + cs / 2 - 4, 0xFFFB923C);
        }
        if (result != 0) {
            String t = result == 1 ? "You win! +3" : result == 2 ? "The robot wins" : "Draw +1";
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
        int cs = cs(), ox = (w - cs * 3) / 2, oy = (h - cs * 3) / 2;
        int c = (int) Math.floor((mx - ox) / cs), r = (int) Math.floor((my - oy) / cs);
        if (c < 0 || r < 0 || c > 2 || r > 2 || b[r * 3 + c] != 0) return false;
        b[r * 3 + c] = 1;
        finishIfOver();
        if (result == 0) wait = 0.45f;
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
