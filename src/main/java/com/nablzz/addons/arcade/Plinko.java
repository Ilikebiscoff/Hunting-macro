package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Drop balls down a board of pegs. The slot at the bottom is the multiplier (about 94 % return over many balls). */
final class Plinko extends Casino {
    private static final int ROWS = 8;
    private static final double[] MULT = {7, 3, 1.3, 0.7, 0.4, 0.7, 1.3, 3, 7};
    private static final float STEP = 0.2f;

    private static final class Ball {
        int[] dir = new int[ROWS];
        int stake, slot;
        float t;
        boolean done;
        float flash;
    }

    private final List<Ball> balls = new ArrayList<>();

    @Override
    public String name() {
        return "Plinko";
    }

    @Override
    public String blurb() {
        return "Drop balls through the pegs. Edges pay big, the middle pays little.";
    }

    @Override
    protected void onReset() {
        balls.clear();
    }

    private void drop() {
        if (!Chips.canBet(bet) || balls.size() >= 6) return;
        Chips.add(-bet);
        Ball b = new Ball();
        int rights = 0;
        for (int i = 0; i < ROWS; i++) {
            b.dir[i] = rnd.nextBoolean() ? 1 : -1;
            if (b.dir[i] > 0) rights++;
        }
        b.slot = rights;
        b.stake = bet;
        balls.add(b);
        msg = "";
    }

    @Override
    public void update(float dt) {
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b = balls.get(i);
            if (b.done) {
                b.flash -= dt;
                if (b.flash <= 0) balls.remove(i);
                continue;
            }
            b.t += dt;
            if (b.t >= STEP * ROWS) {
                b.done = true;
                b.flash = 1.2f;
                int pay = (int) Math.floor(b.stake * MULT[b.slot]);
                if (pay > 0) Chips.add(pay);
                say(String.format("x%.1f  %s%d", MULT[b.slot], pay >= b.stake ? "+" : "-", Math.abs(pay - b.stake)), pay >= b.stake ? Ui.GOOD : Ui.BAD);
            }
        }
    }

    @Override
    public boolean key(int key) {
        if (key == 32) {
            drop();
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        begin(g, x, y, mx, my);
        bar();
        if (balls.isEmpty() && brokeScreen(10)) return;
        int cx = w / 2, top = 28, bottom = h - 54;
        double dy = (bottom - top) / (double) (ROWS + 1);
        double dx = Math.min(30, (w - 40) / (double) (ROWS + 1));
        // pegs
        for (int r = 0; r < ROWS; r++) {
            for (int j = 0; j <= r; j++) {
                int px = (int) (cx + (j - r / 2.0) * dx), py = (int) (top + (r + 1) * dy);
                rect(px - 2, py - 2, 4, 4, 2, 0xFFCBD5E1);
            }
        }
        // slots
        for (int s = 0; s <= ROWS; s++) {
            int sx = (int) (cx + (s - ROWS / 2.0) * dx) - (int) (dx / 2) + 1;
            boolean flash = false;
            for (Ball b : balls) if (b.done && b.slot == s) flash = true;
            int col = MULT[s] >= 3 ? 0xFFB91C1C : MULT[s] >= 1 ? 0xFFB45309 : 0xFF374151;
            rect(sx, bottom + 4, (int) dx - 2, 16, 3, flash ? 0xFF10B981 : col);
            textC(MULT[s] == Math.floor(MULT[s]) ? "" + (int) MULT[s] : "" + MULT[s], sx + (int) dx / 2 - 1, bottom + 8, 0xFFFFFFFF);
        }
        // balls
        for (Ball b : balls) {
            double rowF = Math.min(ROWS, b.t / STEP);
            int row = (int) rowF;
            double frac = rowF - row;
            int sum = 0;
            for (int i = 0; i < row && i < ROWS; i++) sum += b.dir[i];
            double fromX = sum * dx / 2, toX = row < ROWS ? (sum + b.dir[row]) * dx / 2 : fromX;
            double bx = cx + fromX + (toX - fromX) * frac;
            double by = top + (row + frac) * dy + (row == 0 ? 0 : 0) - Math.sin(frac * Math.PI) * 4;
            rect((int) bx - 4, (int) by - 4, 8, 8, 4, b.done ? Ui.WARN : 0xFFF43F5E);
        }
        int by2 = h - 22;
        betRow(8, by2, true);
        button(w - 98, by2, 90, 18, balls.size() >= 6 ? "Wait ..." : "Drop ball", Chips.canBet(bet) && balls.size() < 6, 0xFF047857, this::drop);
    }
}
