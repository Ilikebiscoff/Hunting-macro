package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** The multiplier climbs until it crashes. Cash out before that. The crash point has a 3 % house edge. */
final class Crash extends Casino {
    private int state, wager; // 0 waiting, 1 running, 2 over
    private double t, crashAt, mult = 1.0;
    private boolean cashed;
    private final List<Double> history = new ArrayList<>();
    private final List<double[]> trail = new ArrayList<>();

    @Override
    public String name() {
        return "Crash";
    }

    @Override
    public String blurb() {
        return "Watch the multiplier climb and cash out before it crashes.";
    }

    @Override
    protected void onReset() {
        state = 0;
        mult = 1.0;
        trail.clear();
    }

    private void start() {
        if (!Chips.canBet(bet)) return;
        Chips.add(-bet);
        wager = bet;
        double u = rnd.nextDouble() * 0.999;
        crashAt = Math.max(1.0, 0.97 / (1.0 - u));
        t = 0;
        mult = 1.0;
        cashed = false;
        trail.clear();
        state = 1;
        msg = "";
    }

    private void cashOut() {
        if (state != 1 || cashed) return;
        cashed = true;
        int pay = (int) Math.floor(wager * mult);
        Chips.add(pay);
        say(String.format("Cashed out at x%.2f  +%d", mult, pay - wager), Ui.GOOD);
    }

    @Override
    public void update(float dt) {
        if (state != 1) return;
        t += dt;
        mult = Math.exp(0.18 * t);
        if (trail.isEmpty() || t - trail.get(trail.size() - 1)[0] > 0.05) trail.add(new double[]{t, mult});
        if (mult >= crashAt) {
            mult = crashAt;
            state = 2;
            history.add(0, crashAt);
            if (history.size() > 8) history.remove(history.size() - 1);
            if (!cashed) say(String.format("Crashed at x%.2f  -%d", crashAt, wager), Ui.BAD);
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        begin(g, x, y, mx, my);
        bar();
        if (state != 1 && brokeScreen(10)) return;
        // the graph
        int gx = 8, gy = 22, gw = w - 16, gh = h - 70;
        fill(gx, gy, gw, gh, 0x40000000);
        double maxT = Math.max(6.0, t * 1.05), maxM = Math.max(2.0, mult * 1.1);
        double lastX = -1, lastY = -1;
        for (double[] p : trail) {
            double px = gx + p[0] / maxT * gw, py = gy + gh - (Math.log(p[1]) / Math.log(maxM)) * gh;
            if (lastX >= 0) {
                int steps = (int) Math.max(1, Math.abs(px - lastX));
                for (int s = 0; s <= steps; s++) {
                    double q = (double) s / steps;
                    fill((int) (lastX + (px - lastX) * q), (int) (lastY + (py - lastY) * q), 2, 2, state == 2 ? Ui.BAD : Ui.GOOD);
                }
            }
            lastX = px;
            lastY = py;
        }
        int col = state == 2 ? Ui.BAD : cashed ? Ui.WARN : Ui.GOOD;
        textC(String.format("x%.2f", mult), w / 2, gy + gh / 2 - 4, col);
        if (state == 0) textC("Place a bet and start", w / 2, gy + gh / 2 + 14, Ui.MUTED);
        for (int i = 0; i < history.size(); i++) {
            double c = history.get(i);
            text(String.format("%.2f", c), gx + 4 + i * 38, gy + 2, c >= 2 ? Ui.GOOD : Ui.DIM);
        }
        int by = h - 22;
        if (state == 1) {
            button(8, by, 120, 18, cashed ? "Cashed out" : String.format("Cash out  %d", (int) Math.floor(wager * mult)), !cashed, 0xFFB45309, this::cashOut);
        } else {
            betRow(8, by, true);
            button(w - 90, by, 82, 18, state == 2 ? "Again" : "Start", Chips.canBet(bet), 0xFF047857, this::start);
        }
    }

    @Override
    public boolean key(int key) {
        if (key == 32 && state == 1) {
            cashOut();
            return true;
        }
        return false;
    }
}
