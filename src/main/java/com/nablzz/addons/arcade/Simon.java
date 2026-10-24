package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Simon: watch the pattern, then repeat it by clicking the pads (or keys 1-4 / the arrows). It grows by one each round. */
final class Simon implements Game {
    private static final int[] COLORS = {0xFF22C55E, 0xFFEF4444, 0xFFFACC15, 0xFF3B82F6};
    private final Random rnd = new Random();
    private final List<Integer> seq = new ArrayList<>();
    private int w = 300, h = 200, showIdx, inputIdx, lit = -1, score;
    private float timer;
    private int state; // 0 not started, 1 showing, 2 your turn, 3 lost

    @Override
    public String name() {
        return "Simon";
    }

    @Override
    public String blurb() {
        return "Remember the colours and repeat them. One more step every round.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        seq.clear();
        score = 0;
        state = 0;
        lit = -1;
    }

    private void next() {
        seq.add(rnd.nextInt(4));
        state = 1;
        showIdx = 0;
        timer = 0.7f;
        lit = -1;
    }

    @Override
    public void update(float dt) {
        timer -= dt;
        if (state == 1 && timer <= 0) {
            if (lit >= 0) { // the pad was shown: a pause, then the next
                lit = -1;
                timer = 0.18f;
                showIdx++;
                if (showIdx >= seq.size()) {
                    state = 2;
                    inputIdx = 0;
                }
            } else if (showIdx < seq.size()) {
                lit = seq.get(showIdx);
                timer = Math.max(0.28f, 0.6f - seq.size() * 0.02f);
            }
        } else if (state == 2 && lit >= 0 && timer <= 0) {
            lit = -1;
        } else if (state == 3 && timer <= 0) {
            lit = -1;
        }
    }

    private int pad(double mx, double my) {
        int s = Math.min(w, h - 20) - 10, ox = (w - s) / 2, oy = 14 + (h - 14 - s) / 2;
        if (mx < ox || my < oy || mx >= ox + s || my >= oy + s) return -1;
        int c = mx < ox + s / 2.0 ? 0 : 1, r = my < oy + s / 2.0 ? 0 : 1;
        return r * 2 + c; // 0 green top-left, 1 red top-right, 2 yellow bottom-left, 3 blue bottom-right
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int s = Math.min(w, h - 20) - 10, ox = x + (w - s) / 2, oy = y + 14 + (h - 14 - s) / 2;
        for (int i = 0; i < 4; i++) {
            int px = ox + (i % 2) * (s / 2), py = oy + (i / 2) * (s / 2);
            int col = COLORS[i];
            Ui.rr(g, px + 3, py + 3, s / 2 - 6, s / 2 - 6, 10, lit == i ? col : Ui.withAlpha(col, 70));
        }
        Ui.textC(g, state == 0 ? "Click a pad to start" : state == 1 ? "Watch ..." : state == 2 ? "Your turn" : "Wrong! Click to try again", x + w / 2, y + 3,
                state == 3 ? Ui.BAD : state == 2 ? Ui.GOOD : Ui.MUTED);
    }

    private void press(int p) {
        if (p < 0) return;
        if (state == 0) {
            next();
            return;
        }
        if (state == 3) {
            reset();
            return;
        }
        if (state != 2) return;
        lit = p;
        timer = 0.2f;
        if (seq.get(inputIdx) != p) {
            state = 3;
            timer = 0.6f;
            return;
        }
        inputIdx++;
        if (inputIdx >= seq.size()) {
            score = seq.size();
            next();
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        press(pad(mx, my));
        return true;
    }

    @Override
    public boolean key(int key) {
        int p = switch (key) {
            case 49, 263 -> 0;
            case 50, 265 -> 1;
            case 51, 264 -> 2;
            case 52, 262 -> 3;
            default -> -1;
        };
        if (key == 32 && (state == 0 || state == 3)) {
            if (state == 3) reset();
            else next();
            return true;
        }
        if (p < 0) return false;
        press(p);
        return true;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return "Rounds completed " + score + "   (keys 1-4 also work)";
    }
}
