package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared part of the casino games: the field geometry, clickable buttons (registered while drawing, so a click always
 * hits what is on screen), the chip bar and card drawing. Everything is play money.
 */
abstract class Casino implements Game {
    static final int[] BETS = {10, 50, 100, 500, 1000};
    static final String RANKS = "A23456789TJQK";
    static final String SUITS = "SHDC";

    private record Btn(int x, int y, int w, int h, Runnable action) {}

    protected final Random rnd = new Random();
    protected int w = 400, h = 200, bet = 50;
    protected String msg = "";
    protected int msgColor = Ui.TEXT;
    private final List<Btn> btns = new ArrayList<>();
    private GuiGraphicsExtractor gg;
    private int ox, oy, mouseX, mouseY;

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public String scoreName() {
        return "Chips";
    }

    @Override
    public int score() {
        return Chips.balance();
    }

    @Override
    public void reset() {
        msg = "";
        onReset();
    }

    protected void onReset() {
    }

    @Override
    public boolean key(int key) {
        return false;
    }

    @Override
    public boolean click(double mx, double my, int button) {
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if (mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h) {
                b.action.run();
                return true;
            }
        }
        return false;
    }

    /** Call first in render. */
    protected void begin(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        gg = g;
        ox = x;
        oy = y;
        mouseX = mx;
        mouseY = my;
        btns.clear();
        Ui.fill(g, x, y, w, h, 0xFF0A3B2C); // the felt
        Ui.fill(g, x, y, w, 1, 0x40FFFFFF);
    }

    protected void say(String s, int color) {
        msg = s;
        msgColor = color;
    }

    // ---- drawing in field coordinates -------------------------------------------------------------------------

    protected void rect(int x, int y, int rw, int rh, int r, int c) {
        Ui.rr(gg, ox + x, oy + y, rw, rh, r, c);
    }

    protected void text(String s, int x, int y, int c) {
        Ui.text(gg, s, ox + x, oy + y, c);
    }

    protected void textC(String s, int cx, int y, int c) {
        Ui.textC(gg, s, ox + cx, oy + y, c);
    }

    protected void textR(String s, int right, int y, int c) {
        Ui.textR(gg, s, ox + right, oy + y, c);
    }

    protected void fill(int x, int y, int rw, int rh, int c) {
        Ui.fill(gg, ox + x, oy + y, rw, rh, c);
    }

    protected boolean hover(int x, int y, int bw, int bh) {
        return mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + bh;
    }

    protected void button(int x, int y, int bw, int bh, String label, boolean enabled, int color, Runnable action) {
        boolean hov = enabled && hover(x, y, bw, bh);
        int base = enabled ? color : 0xFF2B303B;
        rect(x, y, bw, bh, 4, hov ? Ui.mix(base, 0xFFFFFFFF, 0.25f) : base);
        textC(label, x + bw / 2, y + bh / 2 - 4, enabled ? 0xFFFFFFFF : Ui.DIM);
        if (enabled) btns.add(new Btn(x, y, bw, bh, action));
    }

    /** Invisible click area (for cards, board cells ...). */
    protected void area(int x, int y, int bw, int bh, Runnable action) {
        btns.add(new Btn(x, y, bw, bh, action));
    }

    /** Chips and message on the first line. */
    protected void bar() {
        text("Chips " + Chips.balance(), 6, 4, Ui.WARN);
        if (!msg.isEmpty()) textR(msg, w - 6, 4, msgColor);
    }

    /** The bet buttons. */
    protected void betRow(int x, int y, boolean enabled) {
        text("Bet", x, y + 5, Ui.MUTED);
        int bx = x + 24;
        for (int b : BETS) {
            String label = b >= 1000 ? (b / 1000) + "k" : "" + b;
            boolean sel = b == bet;
            final int val = b;
            button(bx, y, 30, 16, label, enabled && Chips.balance() >= 1, sel ? 0xFFB45309 : 0xFF374151, () -> bet = val);
            bx += 33;
        }
    }

    /** Shows "free chips" when the player is broke. True when it is shown (and the game should not run). */
    protected boolean brokeScreen(int minBet) {
        if (Chips.balance() >= minBet) return false;
        textC("You are out of chips.", w / 2, h / 2 - 16, Ui.BAD);
        button(w / 2 - 60, h / 2, 120, 20, "Get 500 free chips", true, 0xFF047857, Chips::refill);
        return true;
    }

    protected void card(int x, int y, int cw, int ch, int c, boolean down) {
        if (down) {
            rect(x, y, cw, ch, 4, 0xFF4338CA);
            rect(x + 3, y + 3, cw - 6, ch - 6, 3, 0xFF6366F1);
            return;
        }
        int rank = c % 13, suit = c / 13;
        boolean red = suit == 1 || suit == 2;
        rect(x, y, cw, ch, 4, 0xFFF1F5F9);
        String r = rank == 9 ? "10" : "" + RANKS.charAt(rank);
        int col = red ? 0xFFDC2626 : 0xFF111827;
        text(r, x + 3, y + 3, col);
        textC("" + SUITS.charAt(suit), x + cw / 2, y + ch - 14, col);
    }

    @Override
    public String status() {
        return "Play money only - no real money, nothing to do with SkyBlock coins.";
    }
}
