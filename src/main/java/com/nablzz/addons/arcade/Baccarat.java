package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Punto banco baccarat with the real third-card rules. Player pays 1:1, Banker 0.95:1 (5 % commission), Tie 8:1. */
final class Baccarat extends Casino {
    private static final int PLAYER = 0, BANKER = 1, TIE = 2;
    private final List<Integer> player = new ArrayList<>(), banker = new ArrayList<>();
    private final List<int[]> order = new ArrayList<>(); // {side, index} in the order the cards are shown
    private int side = PLAYER, wager, revealed;
    private boolean active;
    private float timer;

    @Override
    public String name() {
        return "Baccarat";
    }

    @Override
    public String blurb() {
        return "Bet on Player, Banker or a Tie. Closest to 9 wins.";
    }

    @Override
    protected void onReset() {
        active = false;
        player.clear();
        banker.clear();
        order.clear();
    }

    private static int value(int card) {
        int r = card % 13 + 1; // ace = 1
        return r >= 10 ? 0 : r;
    }

    private static int total(List<Integer> hand, int count) {
        int s = 0;
        for (int i = 0; i < count && i < hand.size(); i++) s += value(hand.get(i));
        return s % 10;
    }

    private int card() {
        return rnd.nextInt(52); // a fresh shoe each coup
    }

    private void deal() {
        if (active || !Chips.canBet(bet)) return;
        Chips.add(-bet);
        wager = bet;
        player.clear();
        banker.clear();
        order.clear();
        player.add(card());
        banker.add(card());
        player.add(card());
        banker.add(card());
        order.add(new int[]{PLAYER, 0});
        order.add(new int[]{BANKER, 0});
        order.add(new int[]{PLAYER, 1});
        order.add(new int[]{BANKER, 1});
        int p = total(player, 2), b = total(banker, 2);
        if (p < 8 && b < 8) { // no natural: the third card rules
            boolean playerDrew = false;
            int p3 = -1;
            if (p <= 5) {
                player.add(card());
                p3 = value(player.get(2));
                playerDrew = true;
                order.add(new int[]{PLAYER, 2});
            }
            boolean bankerDraws;
            if (!playerDrew) {
                bankerDraws = b <= 5;
            } else {
                bankerDraws = switch (b) {
                    case 0, 1, 2 -> true;
                    case 3 -> p3 != 8;
                    case 4 -> p3 >= 2 && p3 <= 7;
                    case 5 -> p3 >= 4 && p3 <= 7;
                    case 6 -> p3 == 6 || p3 == 7;
                    default -> false;
                };
            }
            if (bankerDraws) {
                banker.add(card());
                order.add(new int[]{BANKER, 2});
            }
        }
        revealed = 0;
        timer = 0.4f;
        active = true;
        msg = "";
    }

    private void settle() {
        active = false;
        int p = total(player, player.size()), b = total(banker, banker.size());
        int winner = p > b ? PLAYER : b > p ? BANKER : TIE;
        int pay = 0;
        if (winner == TIE) {
            if (side == TIE) pay = wager * 9;
            else pay = wager; // player / banker bets are returned on a tie
        } else if (side == winner) {
            pay = side == PLAYER ? wager * 2 : (int) Math.floor(wager * 1.95);
        }
        if (pay > 0) Chips.add(pay);
        String who = winner == TIE ? "Tie" : winner == PLAYER ? "Player" : "Banker";
        String text = who + " wins " + p + " - " + b;
        if (winner == TIE) text = "Tie " + p + " - " + b;
        if (pay > wager) say(text + "  +" + (pay - wager), Ui.GOOD);
        else if (pay == wager) say(text + "  (bet returned)", Ui.MUTED);
        else say(text + "  -" + wager, Ui.BAD);
    }

    @Override
    public void update(float dt) {
        if (!active) return;
        timer -= dt;
        if (timer > 0) return;
        if (revealed < order.size()) {
            revealed++;
            timer = 0.55f;
        } else {
            settle();
        }
    }

    private boolean shown(int sideId, int idx) {
        for (int i = 0; i < order.size() && i < revealed; i++) {
            if (order.get(i)[0] == sideId && order.get(i)[1] == idx) return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        begin(g, x, y, mx, my);
        bar();
        if (!active && brokeScreen(10)) return;
        int cw = 28, ch = 40, gap = 32;
        int pCount = 0, bCount = 0;
        for (int i = 0; i < player.size(); i++) if (shown(PLAYER, i)) pCount++;
        for (int i = 0; i < banker.size(); i++) if (shown(BANKER, i)) bCount++;
        text("Player" + (pCount > 0 ? "  " + total(player, pCount) : ""), 10, 20, side == PLAYER ? Ui.WARN : Ui.MUTED);
        text("Banker" + (bCount > 0 ? "  " + total(banker, bCount) : ""), w / 2 + 10, 20, side == BANKER ? Ui.WARN : Ui.MUTED);
        for (int i = 0; i < player.size(); i++) if (shown(PLAYER, i)) card(10 + i * gap, 34, cw, ch, player.get(i), false);
        for (int i = 0; i < banker.size(); i++) if (shown(BANKER, i)) card(w / 2 + 10 + i * gap, 34, cw, ch, banker.get(i), false);
        if (player.isEmpty()) textC("Choose a side, set your bet, press Deal", w / 2, 50, Ui.MUTED);

        int sy = Math.max(86, h - 66);
        String[] names = {"Player 1:1", "Banker 0.95:1", "Tie 8:1"};
        int bw = Math.min(110, (w - 24) / 3);
        for (int i = 0; i < 3; i++) {
            final int id = i;
            button(8 + i * (bw + 4), sy, bw, 18, names[i], !active, side == i ? 0xFFB45309 : 0xFF374151, () -> side = id);
        }
        int by = h - 22;
        betRow(8, by, !active);
        button(w - 90, by, 82, 18, active ? "Dealing ..." : "Deal", !active && Chips.canBet(bet), 0xFF047857, this::deal);
    }
}
