package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import com.nablzz.addons.planner.Fmt;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** An idle clicker: click the shard, buy helpers that dig for you. The score is every shard you ever dug in this run. */
final class ShardClicker implements Game {
    private static final String[] NAMES = {"Pickaxe", "Little Worm", "Drill", "Huntrap", "Fusion Machine", "Galatea Farm"};
    private static final double[] BASE_COST = {15, 100, 1100, 12000, 130000, 1_400_000};
    private static final double[] RATE = {0.5, 4, 25, 140, 800, 4500}; // shards per second each

    private final int[] owned = new int[6];
    private double shards, total, perClick = 1, pulse;
    private int w = 300, h = 200;

    @Override
    public String name() {
        return "Shard Clicker";
    }

    @Override
    public String blurb() {
        return "Click the shard, buy helpers that dig for you, watch the numbers explode.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(owned, 0);
        shards = total = pulse = 0;
        perClick = 1;
    }

    private double cost(int i) {
        return Math.floor(BASE_COST[i] * Math.pow(1.15, owned[i]));
    }

    private double perSecond() {
        double s = 0;
        for (int i = 0; i < 6; i++) s += owned[i] * RATE[i];
        return s;
    }

    @Override
    public void update(float dt) {
        double gain = perSecond() * dt;
        shards += gain;
        total += gain;
        pulse = Math.max(0, pulse - dt * 4);
    }

    private int listX() {
        return w / 2 + 6;
    }

    private int rowH() {
        return Math.max(18, Math.min(30, (h - 30) / 6));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cx = x + w / 4, cy = y + h / 2 - 6, r = (int) (Math.min(w / 4 - 8, h / 3) * (1 + pulse * 0.08));
        Ui.rr(g, cx - r - 4, cy - r - 4, r * 2 + 8, r * 2 + 8, r + 4, Ui.withAlpha(Ui.GOOD, 40));
        Ui.rr(g, cx - r, cy - r, r * 2, r * 2, r, 0xFF10B981);
        Ui.rr(g, cx - r / 2, cy - r / 2 - r / 4, r, r, r / 2, Ui.withAlpha(0xFF6EE7B7, 200));
        Ui.textC(g, Fmt.coins(shards), x + w / 4, y + 4, Ui.TEXT);
        Ui.textC(g, Fmt.coins(perSecond()) + " / s    +" + Fmt.coins(perClick) + " per click", x + w / 4, y + h - 14, Ui.MUTED);
        int lx = x + listX(), rh = rowH();
        for (int i = 0; i < 6; i++) {
            int ly = y + 4 + i * rh;
            boolean can = shards >= cost(i), hov = mx >= listX() && mx < w - 4 && my >= 4 + i * rh && my < 4 + (i + 1) * rh - 2;
            Ui.rrb(g, lx, ly, w - listX() - 4, rh - 3, 4, hov && can ? 0xFF1F2A26 : 0xFF171A21, can ? Ui.GOOD : Ui.LINE);
            Ui.text(g, NAMES[i] + "  x" + owned[i], lx + 5, ly + 3, can ? Ui.TEXT : Ui.MUTED);
            Ui.text(g, "+" + Fmt.coins(RATE[i]) + "/s", lx + 5, ly + 3 + (rh >= 24 ? 10 : 0), Ui.DIM);
            Ui.textR(g, Fmt.coins(cost(i)), x + w - 8, ly + 3, can ? Ui.WARN : Ui.DIM);
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (mx >= listX()) {
            int rh = rowH();
            int i = (int) Math.floor((my - 4) / rh);
            if (i >= 0 && i < 6 && shards >= cost(i)) {
                shards -= cost(i);
                owned[i]++;
            }
            return true;
        }
        shards += perClick;
        total += perClick;
        pulse = 1;
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32) { // space also digs
            shards += perClick;
            total += perClick;
            pulse = 1;
            return true;
        }
        return false;
    }

    @Override
    public int score() {
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    @Override
    public String status() {
        return "Dug " + Fmt.coins(total) + " shards in total   (Space also digs)";
    }
}
