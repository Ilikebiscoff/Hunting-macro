package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** European roulette (one zero). Click numbers and the outside bets to stake chips, then spin. */
final class Roulette extends Casino {
    private static final int RED = 100, BLACK = 101, ODD = 102, EVEN = 103, LOW = 104, HIGH = 105, DOZ1 = 106, DOZ2 = 107, DOZ3 = 108;
    private static final int[] REDS = {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36};
    private final Map<Integer, Integer> stakes = new HashMap<>();
    private final List<Integer> history = new ArrayList<>();
    private boolean spinning;
    private float spinT;
    private int result, shown;

    @Override
    public String name() {
        return "Roulette";
    }

    @Override
    public String blurb() {
        return "Bet on numbers, colours, dozens ... then spin the wheel.";
    }

    @Override
    protected void onReset() {
        spinning = false;
        for (int k : stakes.keySet()) Chips.add(stakes.get(k)); // bets that were still on the table go back
        stakes.clear();
    }

    private static boolean red(int n) {
        for (int r : REDS) if (r == n) return true;
        return false;
    }

    private int totalStake() {
        int t = 0;
        for (int v : stakes.values()) t += v;
        return t;
    }

    private void place(int spot) {
        if (spinning || !Chips.canBet(bet)) return;
        Chips.add(-bet);
        stakes.merge(spot, bet, Integer::sum);
        msg = "";
    }

    private void clear() {
        if (spinning) return;
        for (int v : stakes.values()) Chips.add(v);
        stakes.clear();
    }

    private void spin() {
        if (spinning || stakes.isEmpty()) return;
        spinning = true;
        spinT = 0;
        result = rnd.nextInt(37);
        shown = rnd.nextInt(37);
        msg = "";
    }

    private int payout(int spot, int stake, int n) {
        if (spot <= 36) return n == spot ? stake * 36 : 0;
        if (n == 0) return 0;
        return switch (spot) {
            case RED -> red(n) ? stake * 2 : 0;
            case BLACK -> !red(n) ? stake * 2 : 0;
            case ODD -> n % 2 == 1 ? stake * 2 : 0;
            case EVEN -> n % 2 == 0 ? stake * 2 : 0;
            case LOW -> n <= 18 ? stake * 2 : 0;
            case HIGH -> n >= 19 ? stake * 2 : 0;
            case DOZ1 -> n <= 12 ? stake * 3 : 0;
            case DOZ2 -> n >= 13 && n <= 24 ? stake * 3 : 0;
            case DOZ3 -> n >= 25 ? stake * 3 : 0;
            default -> 0;
        };
    }

    @Override
    public void update(float dt) {
        if (!spinning) return;
        spinT += dt;
        float p = Math.min(1f, spinT / 2.4f);
        // the shown number changes quickly at first, slower at the end
        float interval = 0.04f + p * p * 0.35f;
        if ((int) (spinT / interval) != (int) ((spinT - dt) / interval)) shown = rnd.nextInt(37);
        if (spinT >= 2.6f) {
            spinning = false;
            shown = result;
            history.add(0, result);
            if (history.size() > 10) history.remove(history.size() - 1);
            int staked = totalStake(), won = 0;
            for (var e : stakes.entrySet()) won += payout(e.getKey(), e.getValue(), result);
            stakes.clear();
            if (won > 0) Chips.add(won);
            String name = result + (result == 0 ? " green" : red(result) ? " red" : " black");
            if (won > staked) say(name + " - you win +" + (won - staked), Ui.GOOD);
            else if (won == staked) say(name + " - you break even", Ui.MUTED);
            else say(name + " - you lose " + (staked - won), Ui.BAD);
        }
    }

    private int color(int n) {
        return n == 0 ? 0xFF047857 : red(n) ? 0xFFB91C1C : 0xFF1F2937;
    }

    private void cellText(int x, int y, int cw, int ch, String label, int spot, int bg) {
        boolean hov = hover(x, y, cw, ch) && !spinning;
        rect(x, y, cw - 1, ch - 1, 2, hov ? Ui.mix(bg, 0xFFFFFFFF, 0.25f) : bg);
        textC(label, x + cw / 2, y + ch / 2 - 4, 0xFFFFFFFF);
        Integer st = stakes.get(spot);
        if (st != null) {
            rect(x + cw - 11, y + 1, 10, 9, 4, 0xFFFBBF24);
            textC(st >= 1000 ? (st / 1000) + "k" : "" + st, x + cw - 6, y + 1, 0xFF111827);
        }
        area(x, y, cw, ch, () -> place(spot));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        begin(g, x, y, mx, my);
        bar();
        if (stakes.isEmpty() && !spinning && brokeScreen(10)) return;
        int cw = Math.max(18, Math.min(28, (w - 12) / 13));
        int ch = Math.max(14, Math.min(22, (h - 120) / 3));
        int gx = (w - cw * 13) / 2, gy = 20;
        // the number board: zero on the left, three rows of twelve
        cellText(gx, gy, cw, ch * 3, "0", 0, color(0));
        for (int c = 0; c < 12; c++) {
            for (int r = 0; r < 3; r++) {
                int n = c * 3 + (3 - r);
                cellText(gx + cw * (c + 1), gy + r * ch, cw, ch, "" + n, n, color(n));
            }
        }
        int oy2 = gy + ch * 3 + 3;
        int dw = cw * 4;
        cellText(gx + cw, oy2, dw, 16, "1st 12", DOZ1, 0xFF374151);
        cellText(gx + cw + dw, oy2, dw, 16, "2nd 12", DOZ2, 0xFF374151);
        cellText(gx + cw + dw * 2, oy2, dw, 16, "3rd 12", DOZ3, 0xFF374151);
        int ow = cw * 2, oy3 = oy2 + 18;
        cellText(gx + cw, oy3, ow, 16, "1-18", LOW, 0xFF374151);
        cellText(gx + cw + ow, oy3, ow, 16, "Even", EVEN, 0xFF374151);
        cellText(gx + cw + ow * 2, oy3, ow, 16, "Red", RED, 0xFFB91C1C);
        cellText(gx + cw + ow * 3, oy3, ow, 16, "Black", BLACK, 0xFF1F2937);
        cellText(gx + cw + ow * 4, oy3, ow, 16, "Odd", ODD, 0xFF374151);
        cellText(gx + cw + ow * 5, oy3, ow, 16, "19-36", HIGH, 0xFF374151);

        // the wheel result and the history
        int ry = oy3 + 22;
        rect(8, ry, 34, 24, 5, color(shown));
        textC("" + shown, 25, ry + 8, 0xFFFFFFFF);
        for (int i = 0; i < history.size(); i++) {
            int n = history.get(i);
            rect(48 + i * 18, ry + 4, 16, 16, 3, color(n));
            textC("" + n, 56 + i * 18, ry + 8, 0xFFFFFFFF);
        }
        text("On the table: " + totalStake(), 8, ry + 28, Ui.MUTED);

        int by = h - 22;
        betRow(8, by, !spinning);
        button(w - 154, by, 66, 18, "Clear", !spinning && !stakes.isEmpty(), 0xFF6B7280, this::clear);
        button(w - 84, by, 76, 18, spinning ? "Spinning" : "SPIN", !spinning && !stakes.isEmpty(), 0xFF047857, this::spin);
    }
}
