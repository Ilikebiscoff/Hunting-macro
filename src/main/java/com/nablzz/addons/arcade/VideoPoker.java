package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Jacks or Better video poker. Deal, hold the cards you want, draw once. */
final class VideoPoker extends Casino {
    private static final String[] NAMES = {"Royal Flush", "Straight Flush", "Four of a Kind", "Full House", "Flush", "Straight", "Three of a Kind", "Two Pair", "Jacks or Better"};
    private static final int[] PAY = {800, 50, 25, 9, 6, 4, 3, 2, 1};
    private final List<Integer> deck = new ArrayList<>();
    private final int[] hand = new int[5];
    private final boolean[] hold = new boolean[5];
    private int state, wager; // 0 bet, 1 hold, 2 done
    private int lastRank = -1;

    @Override
    public String name() {
        return "Video Poker";
    }

    @Override
    public String blurb() {
        return "Jacks or Better. Keep the good cards, draw once, get paid.";
    }

    @Override
    protected void onReset() {
        state = 0;
        lastRank = -1;
    }

    private void deal() {
        if (!Chips.canBet(bet)) return;
        Chips.add(-bet);
        wager = bet;
        deck.clear();
        for (int i = 0; i < 52; i++) deck.add(i);
        Collections.shuffle(deck, rnd);
        for (int i = 0; i < 5; i++) {
            hand[i] = deck.remove(deck.size() - 1);
            hold[i] = false;
        }
        state = 1;
        lastRank = -1;
        msg = "";
    }

    private void drawCards() {
        for (int i = 0; i < 5; i++) if (!hold[i]) hand[i] = deck.remove(deck.size() - 1);
        state = 2;
        lastRank = evaluate();
        if (lastRank >= 0) {
            int pay = PAY[lastRank] * wager;
            Chips.add(pay);
            say(NAMES[lastRank] + "  +" + (pay - wager), Ui.GOOD);
        } else {
            say("No winning hand  -" + wager, Ui.BAD);
        }
    }

    /** Index into NAMES / PAY, or -1 for nothing. */
    private int evaluate() {
        int[] count = new int[13];
        boolean flush = true;
        for (int i = 0; i < 5; i++) {
            count[hand[i] % 13]++;
            if (hand[i] / 13 != hand[0] / 13) flush = false;
        }
        int pairs = 0, trips = 0, quads = 0;
        boolean highPair = false;
        for (int r = 0; r < 13; r++) {
            if (count[r] == 2) {
                pairs++;
                if (r == 0 || r >= 10) highPair = true;
            }
            if (count[r] == 3) trips++;
            if (count[r] == 4) quads++;
        }
        boolean straight = false, royal = false;
        int lo = -1, run = 0, first = -1;
        for (int r = 0; r < 13; r++) {
            if (count[r] == 1) {
                if (first < 0) first = r;
                lo = r;
                run++;
            }
        }
        if (run == 5 && lo - first == 4) straight = true;
        if (run == 5 && count[0] == 1 && count[9] == 1 && count[10] == 1 && count[11] == 1 && count[12] == 1) { // A-10-J-Q-K ... ace high
            straight = true;
            royal = true;
        }
        if (straight && flush && royal) return 0;
        if (straight && flush) return 1;
        if (quads > 0) return 2;
        if (trips > 0 && pairs > 0) return 3;
        if (flush) return 4;
        if (straight) return 5;
        if (trips > 0) return 6;
        if (pairs == 2) return 7;
        if (pairs == 1 && highPair) return 8;
        return -1;
    }

    @Override
    public void update(float dt) {
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        begin(g, x, y, mx, my);
        bar();
        if (state != 1 && brokeScreen(10)) return;
        // pay table on the right
        int tx = w - 116;
        for (int i = 0; i < NAMES.length; i++) {
            boolean hit = i == lastRank && state == 2;
            text(NAMES[i], tx, 20 + i * 11, hit ? Ui.WARN : Ui.DIM);
            textR("" + PAY[i], w - 6, 20 + i * 11, hit ? Ui.WARN : Ui.DIM);
        }
        if (state == 0) {
            textC("Press Deal", tx / 2, h / 2 - 10, Ui.MUTED);
        } else {
            int cw = Math.min(40, (tx - 12) / 5 - 4), ch = (int) (cw * 1.4);
            for (int i = 0; i < 5; i++) {
                int cx = 6 + i * (cw + 4), cy = 36;
                card(cx, cy, cw, ch, hand[i], false);
                if (hold[i]) {
                    rect(cx, cy + ch + 2, cw, 10, 3, Ui.WARN);
                    textC("HELD", cx + cw / 2, cy + ch + 2, 0xFF111827);
                }
                if (state == 1) {
                    final int idx = i;
                    area(cx, cy, cw, ch + 12, () -> hold[idx] = !hold[idx]);
                }
            }
            if (state == 1) text("Click the cards you want to keep", 6, 22, Ui.MUTED);
        }
        int by = h - 22;
        if (state == 1) {
            button(8, by, 90, 18, "Draw", true, 0xFF047857, this::drawCards);
            text("wager " + wager, 106, by + 5, Ui.MUTED);
        } else {
            betRow(8, by, true);
            button(w - 90, by, 82, 18, state == 2 ? "Deal again" : "Deal", Chips.canBet(bet), 0xFF047857, this::deal);
        }
    }
}
