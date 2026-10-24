package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Memory: flip two cards at a time and find the pairs. Fewer misses = a higher score. */
final class MemoryMatch implements Game {
    private static final int COLS = 4, ROWS = 4;
    private static final String SYMBOLS = "ABCDEFGH";
    private static final int[] COLORS = {0xFFF87171, 0xFFFBBF24, 0xFF34D399, 0xFF22D3EE, 0xFF60A5FA, 0xFFA78BFA, 0xFFF472B6, 0xFFFB923C};
    private final int[] card = new int[COLS * ROWS];
    private final boolean[] up = new boolean[COLS * ROWS], done = new boolean[COLS * ROWS];
    private int w = 300, h = 200, first = -1, second = -1, moves, misses, pairs, score, rounds;
    private float hide;

    @Override
    public String name() {
        return "Memory Match";
    }

    @Override
    public String blurb() {
        return "Flip the cards and find all 8 pairs with as few misses as you can.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        rounds = 0;
        score = 0;
        newBoard();
    }

    private void newBoard() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            l.add(i);
            l.add(i);
        }
        Collections.shuffle(l);
        for (int i = 0; i < card.length; i++) {
            card[i] = l.get(i);
            up[i] = done[i] = false;
        }
        first = second = -1;
        moves = misses = pairs = 0;
        hide = 0;
    }

    private int cs() {
        return Math.max(20, Math.min((h - 8) / ROWS, (w - 8) / COLS));
    }

    @Override
    public void update(float dt) {
        if (hide > 0) {
            hide -= dt;
            if (hide <= 0 && first >= 0 && second >= 0) {
                if (card[first] == card[second]) {
                    done[first] = done[second] = true;
                    pairs++;
                    if (pairs == 8) {
                        rounds++;
                        score += Math.max(10, 100 - misses * 8);
                    }
                } else {
                    up[first] = up[second] = false;
                    misses++;
                }
                first = second = -1;
            }
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cs = cs(), ox = x + (w - cs * COLS) / 2, oy = y + (h - cs * ROWS) / 2;
        for (int i = 0; i < card.length; i++) {
            int cx = ox + (i % COLS) * cs, cy = oy + (i / COLS) * cs;
            boolean shown = up[i] || done[i];
            Ui.rr(g, cx + 2, cy + 2, cs - 4, cs - 4, 5, done[i] ? Ui.withAlpha(COLORS[card[i]], 90) : shown ? 0xFF1E222B : 0xFF4338CA);
            if (shown) {
                Ui.rr(g, cx + cs / 2 - 8, cy + cs / 2 - 8, 16, 16, 8, COLORS[card[i]]);
                Ui.textC(g, "" + SYMBOLS.charAt(card[i]), cx + cs / 2, cy + cs / 2 - 4, 0xFF111827);
            } else {
                Ui.rr(g, cx + 6, cy + 6, cs - 12, cs - 12, 4, 0xFF6366F1);
            }
        }
        if (pairs == 8) Ui.textC(g, "All pairs found! Click for a new board", x + w / 2, y + 2, Ui.GOOD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (pairs == 8) {
            newBoard();
            return true;
        }
        if (hide > 0) return true;
        int cs = cs(), ox = (w - cs * COLS) / 2, oy = (h - cs * ROWS) / 2;
        int c = (int) Math.floor((mx - ox) / cs), r = (int) Math.floor((my - oy) / cs);
        if (c < 0 || r < 0 || c >= COLS || r >= ROWS) return false;
        int i = r * COLS + c;
        if (up[i] || done[i]) return true;
        up[i] = true;
        if (first < 0) {
            first = i;
        } else {
            second = i;
            moves++;
            hide = 0.8f;
        }
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 || key == 82) {
            newBoard();
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
        return "Pairs " + pairs + "/8   Misses " + misses + "   Boards done " + rounds;
    }
}
