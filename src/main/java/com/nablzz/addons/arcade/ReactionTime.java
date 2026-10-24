package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Reaction time: wait for the screen to turn green, then click (or press Space) as fast as you can. Five tries. */
final class ReactionTime implements Game {
    private static final int TRIES = 5;
    private final Random rnd = new Random();
    private final List<Long> times = new ArrayList<>(); // ms
    private int w = 300, h = 200, state, score; // 0 ready, 1 waiting, 2 go, 3 shown a result, 4 too soon, 5 finished all tries
    private long goAtNs, lastMs;
    private int bestAvg = Integer.MAX_VALUE;

    @Override
    public String name() {
        return "Reaction Time";
    }

    @Override
    public String blurb() {
        return "Wait for green, then click as fast as you can. Five tries, the average counts.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        times.clear();
        state = 0;
        score = 0;
        bestAvg = Integer.MAX_VALUE;
    }

    private void begin() {
        state = 1;
        goAtNs = System.nanoTime() + (long) ((1.4 + rnd.nextDouble() * 3.6) * 1e9);
    }

    private long average() {
        long sum = 0;
        for (long t : times) sum += t;
        return times.isEmpty() ? 0 : sum / times.size();
    }

    @Override
    public void update(float dt) {
        if (state == 1 && System.nanoTime() >= goAtNs) state = 2; // the green moment is the scheduled time, not the frame
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        int bg = switch (state) {
            case 1 -> 0xFF7F1D1D;
            case 2 -> 0xFF15803D;
            case 4 -> 0xFF92400E;
            default -> 0xFF1E3A5F;
        };
        Ui.fill(g, x, y, w, h, bg);
        String big = switch (state) {
            case 0 -> "Click to start";
            case 1 -> "Wait for green ...";
            case 2 -> "CLICK NOW!";
            case 3 -> lastMs + " ms";
            case 4 -> "Too soon!";
            default -> "Average " + average() + " ms";
        };
        Ui.textC(g, big, x + w / 2, y + h / 2 - 12, 0xFFFFFFFF);
        String small = switch (state) {
            case 0 -> "Five tries. Click (or Space) the moment the screen turns green.";
            case 3 -> "Click for the next try  (" + times.size() + "/" + TRIES + ")";
            case 4 -> "You clicked before it turned green. Click to try again.";
            case 5 -> "Score " + score + "  (1000 minus your average)   best average " + (bestAvg == Integer.MAX_VALUE ? "-" : bestAvg + " ms") + " - click to go again";
            default -> "";
        };
        Ui.textC(g, small, x + w / 2, y + h / 2 + 2, 0xCCFFFFFF);
        for (int i = 0; i < times.size(); i++) Ui.textC(g, times.get(i) + " ms", x + 34 + i * Math.max(48, (w - 68) / TRIES), y + h - 16, 0xBBFFFFFF);
    }

    private void press() {
        switch (state) {
            case 0, 4 -> begin();
            case 1 -> {
                state = 4;
            }
            case 2 -> {
                lastMs = Math.max(1, (System.nanoTime() - goAtNs) / 1_000_000);
                times.add(lastMs);
                if (times.size() >= TRIES) {
                    long avg = average();
                    state = 5;
                    score = (int) Math.max(0, 1000 - avg);
                    bestAvg = (int) Math.min(bestAvg, avg);
                } else {
                    state = 3;
                }
            }
            case 3 -> begin();
            default -> {
                times.clear();
                begin();
            }
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        press();
        return true;
    }

    @Override
    public boolean key(int key) {
        if (key == 32 || key == 257) {
            press();
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
        return times.isEmpty() ? "Space or click" : "Average so far " + average() + " ms over " + times.size() + " tr" + (times.size() == 1 ? "y" : "ies");
    }
}
