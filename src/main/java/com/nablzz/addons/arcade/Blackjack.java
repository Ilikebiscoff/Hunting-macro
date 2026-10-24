package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Blackjack: dealer stands on 17, blackjack pays 3:2, double down on the first two cards. */
final class Blackjack extends Casino {
    private static final int BET = 0, PLAY = 1, DEALER = 2, DONE = 3;
    private final List<Integer> deck = new ArrayList<>();
    private final List<Integer> player = new ArrayList<>(), dealer = new ArrayList<>();
    private int state, wager;
    private float dealerTimer;

    @Override
    public String name() {
        return "Blackjack";
    }

    @Override
    public String blurb() {
        return "Beat the dealer to 21. Hit, stand or double down.";
    }

    @Override
    protected void onReset() {
        state = BET;
        player.clear();
        dealer.clear();
    }

    private int draw() {
        if (deck.isEmpty()) {
            for (int i = 0; i < 52; i++) deck.add(i);
            Collections.shuffle(deck, rnd);
        }
        return deck.remove(deck.size() - 1);
    }

    private static int total(List<Integer> hand) {
        int sum = 0, aces = 0;
        for (int c : hand) {
            int r = c % 13;
            if (r == 0) {
                aces++;
                sum += 11;
            } else {
                sum += Math.min(10, r + 1);
            }
        }
        while (sum > 21 && aces > 0) {
            sum -= 10;
            aces--;
        }
        return sum;
    }

    private static boolean blackjack(List<Integer> hand) {
        return hand.size() == 2 && total(hand) == 21;
    }

    private void deal() {
        if (!Chips.canBet(bet)) return;
        Chips.add(-bet);
        wager = bet;
        deck.clear();
        player.clear();
        dealer.clear();
        player.add(draw());
        dealer.add(draw());
        player.add(draw());
        dealer.add(draw());
        msg = "";
        state = PLAY;
        if (blackjack(player)) {
            state = DONE;
            if (blackjack(dealer)) finish(wager, "Both have blackjack - push", Ui.MUTED);
            else finish((int) (wager * 2.5), "BLACKJACK! 3:2", Ui.GOOD);
        } else if (blackjack(dealer)) {
            state = DONE;
            finish(0, "Dealer has blackjack", Ui.BAD);
        }
    }

    private void finish(int payout, String text, int color) {
        if (payout > 0) Chips.add(payout);
        say(text + (payout > wager ? "  +" + (payout - wager) : payout < wager ? "  -" + (wager - payout) : ""), color);
        state = DONE;
    }

    private void hit() {
        player.add(draw());
        if (total(player) > 21) finish(0, "Bust!", Ui.BAD);
        else if (total(player) == 21) stand();
    }

    private void stand() {
        state = DEALER;
        dealerTimer = 0.6f;
    }

    private void doubleDown() {
        if (state != PLAY || player.size() != 2 || !Chips.canBet(wager)) return;
        Chips.add(-wager);
        wager *= 2;
        player.add(draw());
        if (total(player) > 21) finish(0, "Bust!", Ui.BAD);
        else stand();
    }

    @Override
    public void update(float dt) {
        if (state != DEALER) return;
        dealerTimer -= dt;
        if (dealerTimer > 0) return;
        if (total(dealer) < 17) {
            dealer.add(draw());
            dealerTimer = 0.7f;
            return;
        }
        int p = total(player), d = total(dealer);
        if (d > 21) finish(wager * 2, "Dealer busts - you win", Ui.GOOD);
        else if (p > d) finish(wager * 2, "You win " + p + " to " + d, Ui.GOOD);
        else if (p == d) finish(wager, "Push at " + p, Ui.MUTED);
        else finish(0, "Dealer wins " + d + " to " + p, Ui.BAD);
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        begin(g, x, y, mx, my);
        bar();
        if (state == BET && brokeScreen(10)) return;
        int cw = 26, ch = 36, gap = 30;
        boolean hide = state == PLAY;
        text("Dealer" + (state >= DEALER ? "  " + total(dealer) : ""), 8, 20, Ui.MUTED);
        for (int i = 0; i < dealer.size(); i++) card(8 + i * gap, 32, cw, ch, dealer.get(i), hide && i == 1);
        int py = Math.max(86, h - 86);
        text("You" + (player.isEmpty() ? "" : "  " + total(player)) + (wager > 0 && state != BET ? "   wager " + wager : ""), 8, py - 12, Ui.MUTED);
        for (int i = 0; i < player.size(); i++) card(8 + i * gap, py, cw, ch, player.get(i), false);

        int by = h - 22;
        if (state == PLAY) {
            button(8, by, 56, 18, "Hit", true, 0xFF047857, this::hit);
            button(68, by, 56, 18, "Stand", true, 0xFFB45309, this::stand);
            button(128, by, 70, 18, "Double", player.size() == 2 && Chips.canBet(wager), 0xFF6D28D9, this::doubleDown);
        } else if (state == BET || state == DONE) {
            betRow(8, by, true);
            button(w - 90, by, 82, 18, state == DONE ? "Deal again" : "Deal", Chips.canBet(bet), 0xFF047857, this::deal);
        } else {
            text("Dealer plays ...", 8, by + 5, Ui.MUTED);
        }
    }
}
