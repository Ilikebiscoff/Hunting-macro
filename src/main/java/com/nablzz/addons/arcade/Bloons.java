package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * A small Bloons tower defense. Bloons ride the path; pop them with towers before they get out (each one that does costs
 * lives). Pick a tower at the bottom (or keys 1-3) and click the field to place it; click a tower to upgrade it, right-click
 * to sell it. Space starts the next wave, F changes the speed.
 */
final class Bloons implements Game {
    // the path in 0..1 coordinates (only horizontal and vertical pieces)
    private static final double[][] PATH = {{0, 0.18}, {0.78, 0.18}, {0.78, 0.5}, {0.2, 0.5}, {0.2, 0.8}, {1.0, 0.8}};
    private static final int[] LAYER_COLOR = {0xFFEF4444, 0xFF3B82F6, 0xFF22C55E, 0xFFFACC15, 0xFFF472B6};
    private static final double[] LAYER_SPEED = {34, 42, 52, 64, 78}; // pixels per second at scale 1
    private static final String[] TOWER_NAME = {"Dart", "Tack", "Sniper"};
    private static final int[] TOWER_COST = {100, 160, 350};
    private static final int[] TOWER_COLOR = {0xFFA16207, 0xFFDC2626, 0xFF4B5563};

    private static final class Bloon {
        double t;      // fraction of the path (0 = entrance, 1 = exit)
        int layer;
    }

    private static final class Tower {
        double nx, ny;
        int type, level = 1;
        float cool;
        double spent;
    }

    private static final class Fx {
        double x0, y0, x1, y1;
        float life = 0.12f;
        int color;
    }

    private final Random rnd = new Random();
    private final List<Bloon> bloons = new ArrayList<>();
    private final List<Tower> towers = new ArrayList<>();
    private final List<Fx> fx = new ArrayList<>();
    private final List<Integer> spawnQueue = new ArrayList<>();
    private int w = 300, h = 200, money, lives, wave, pops, score, place = -1, speed = 1;
    private float spawnIn;
    private boolean running, over;
    private double[] cum = new double[PATH.length];
    private double pathLen = 1;
    private double scale = 1;

    @Override
    public String name() {
        return "Bloons";
    }

    @Override
    public String blurb() {
        return "Tower defense: place towers, pop the bloons before they escape. Space = next wave.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
        scale = Math.max(0.6, Math.min(w / 360.0, (h - 24) / 200.0));
        double total = 0;
        double fh = fieldH();
        for (int i = 1; i < PATH.length; i++) {
            total += Math.hypot((PATH[i][0] - PATH[i - 1][0]) * w, (PATH[i][1] - PATH[i - 1][1]) * fh);
            cum[i] = total;
        }
        pathLen = Math.max(1, total);
    }

    private int fieldH() {
        return h - 24;
    }

    @Override
    public void reset() {
        bloons.clear();
        towers.clear();
        fx.clear();
        spawnQueue.clear();
        money = 220;
        lives = 20;
        wave = 0;
        pops = 0;
        score = 0;
        place = -1;
        speed = 1;
        running = false;
        over = false;
    }

    private double[] at(double t) {
        double d = Math.max(0, Math.min(1, t)) * pathLen;
        double fh = fieldH();
        for (int i = 1; i < PATH.length; i++) {
            if (d <= cum[i] || i == PATH.length - 1) {
                double seg = Math.max(1e-6, cum[i] - cum[i - 1]), q = Math.max(0, Math.min(1, (d - cum[i - 1]) / seg));
                return new double[]{(PATH[i - 1][0] + (PATH[i][0] - PATH[i - 1][0]) * q) * w, (PATH[i - 1][1] + (PATH[i][1] - PATH[i - 1][1]) * q) * fh};
            }
        }
        return new double[]{0, 0};
    }

    private void startWave() {
        if (running || over) return;
        wave++;
        running = true;
        spawnQueue.clear();
        int n = Math.min(70, 8 + wave * 3);
        for (int i = 0; i < n; i++) {
            int r = rnd.nextInt(10);
            int layer = wave < 3 ? 0 : wave < 6 ? (r < 7 ? 0 : 1) : wave < 10 ? (r < 4 ? 0 : r < 8 ? 1 : 2)
                    : wave < 15 ? (r < 2 ? 0 : r < 5 ? 1 : r < 8 ? 2 : 3) : (r < 1 ? 0 : r < 3 ? 1 : r < 6 ? 2 : r < 9 ? 3 : 4);
            spawnQueue.add(layer);
        }
        spawnIn = 0.4f;
    }

    private static double range(Tower t, double scale) {
        double base = t.type == 0 ? 52 : t.type == 1 ? 38 : 9999;
        return base * scale * (1 + 0.18 * (t.level - 1));
    }

    private static float cooldown(Tower t) {
        float base = t.type == 0 ? 0.8f : t.type == 1 ? 1.1f : 1.8f;
        return base * (float) Math.pow(0.8, t.level - 1);
    }

    private void pop(Bloon b, int layers) {
        for (int i = 0; i < layers; i++) {
            money++;
            pops++;
            if (b.layer > 0) {
                b.layer--;
            } else {
                b.layer = -1;
                return;
            }
        }
    }

    @Override
    public void update(float dt0) {
        if (over) return;
        float dt = dt0 * speed;
        for (Iterator<Fx> it = fx.iterator(); it.hasNext(); ) {
            Fx f = it.next();
            f.life -= dt0;
            if (f.life <= 0) it.remove();
        }
        if (running && !spawnQueue.isEmpty()) {
            spawnIn -= dt;
            if (spawnIn <= 0) {
                Bloon b = new Bloon();
                b.layer = spawnQueue.remove(0);
                bloons.add(b);
                spawnIn = Math.max(0.22f, 0.8f - wave * 0.03f);
            }
        }
        for (Iterator<Bloon> it = bloons.iterator(); it.hasNext(); ) {
            Bloon b = it.next();
            b.t += LAYER_SPEED[Math.max(0, Math.min(4, b.layer))] * scale / pathLen * dt;
            if (b.t >= 1) {
                lives -= b.layer + 1;
                it.remove();
                if (lives <= 0) {
                    lives = 0;
                    over = true;
                    score = wave * 100 + pops;
                    return;
                }
            }
        }
        // towers
        for (Tower t : towers) {
            t.cool -= dt;
            if (t.cool > 0) continue;
            double tx = t.nx * w, ty = t.ny * fieldH(), r = range(t, scale);
            if (t.type == 1) { // tack shooter: pops up to 4 bloons around it
                int n = 0;
                for (Bloon b : bloons) {
                    if (b.layer < 0) continue;
                    double[] p = at(b.t);
                    if (Math.hypot(p[0] - tx, p[1] - ty) <= r && n < 4 + t.level) {
                        pop(b, 1);
                        n++;
                    }
                }
                if (n > 0) {
                    t.cool = cooldown(t);
                    for (int k = 0; k < 8; k++) {
                        Fx f = new Fx();
                        double a = k * Math.PI / 4;
                        f.x0 = tx;
                        f.y0 = ty;
                        f.x1 = tx + Math.cos(a) * r;
                        f.y1 = ty + Math.sin(a) * r;
                        f.color = 0xFFFCA5A5;
                        fx.add(f);
                    }
                }
            } else { // dart / sniper: the bloon furthest along the path in range
                Bloon best = null;
                double[] bp = null;
                for (Bloon b : bloons) {
                    if (b.layer < 0) continue;
                    double[] p = at(b.t);
                    if (Math.hypot(p[0] - tx, p[1] - ty) <= r && (best == null || b.t > best.t)) {
                        best = b;
                        bp = p;
                    }
                }
                if (best != null) {
                    pop(best, t.type == 2 ? 2 + t.level / 2 : 1 + (t.level >= 3 ? 1 : 0));
                    t.cool = cooldown(t);
                    Fx f = new Fx();
                    f.x0 = tx;
                    f.y0 = ty;
                    f.x1 = bp[0];
                    f.y1 = bp[1];
                    f.color = t.type == 2 ? 0xFFE5E7EB : 0xFFFDE68A;
                    fx.add(f);
                }
            }
        }
        bloons.removeIf(b -> b.layer < 0);
        if (running && spawnQueue.isEmpty() && bloons.isEmpty()) {
            running = false;
            money += 100 + wave * 6;
            score = Math.max(score, wave * 100 + pops);
        }
    }

    private boolean validSpot(double mx, double my) {
        if (my < 0 || my > fieldH() || mx < 0 || mx > w) return false;
        for (int i = 0; i <= 200; i++) {
            double[] p = at(i / 200.0);
            if (Math.hypot(p[0] - mx, p[1] - my) < 15 * scale) return false;
        }
        for (Tower t : towers) if (Math.hypot(t.nx * w - mx, t.ny * fieldH() - my) < 17 * scale) return false;
        return true;
    }

    private void ring(GuiGraphicsExtractor g, int cx, int cy, double r, int color) {
        r = Math.min(r, Math.max(w, h));
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI / 20;
            Ui.fill(g, (int) (cx + Math.cos(a) * r), (int) (cy + Math.sin(a) * r), 1, 1, color);
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        int fh = fieldH();
        Ui.fill(g, x, y, w, h, 0xFF14291C);
        // the path
        int pw = (int) (13 * scale);
        for (int i = 1; i < PATH.length; i++) {
            int x0 = (int) (PATH[i - 1][0] * w), y0 = (int) (PATH[i - 1][1] * fh), x1 = (int) (PATH[i][0] * w), y1 = (int) (PATH[i][1] * fh);
            Ui.fill(g, x + Math.min(x0, x1) - pw / 2, y + Math.min(y0, y1) - pw / 2, Math.abs(x1 - x0) + pw, Math.abs(y1 - y0) + pw, 0xFF92703A);
        }
        for (Fx f : fx) {
            int steps = 12;
            for (int s = 0; s <= steps; s++) Ui.fill(g, x + (int) (f.x0 + (f.x1 - f.x0) * s / steps), y + (int) (f.y0 + (f.y1 - f.y0) * s / steps), 1, 1, f.color);
        }
        for (Bloon b : bloons) {
            double[] p = at(b.t);
            int r = (int) (6 * scale);
            Ui.rr(g, x + (int) p[0] - r, y + (int) p[1] - r - 1, r * 2, r * 2 + 3, r, LAYER_COLOR[Math.max(0, Math.min(4, b.layer))]);
            Ui.fill(g, x + (int) p[0] - r / 3, y + (int) p[1] - r / 2, 2, 2, 0x99FFFFFF);
        }
        for (Tower t : towers) {
            int cx = x + (int) (t.nx * w), cy = y + (int) (t.ny * fh), r = (int) (7 * scale);
            Ui.rr(g, cx - r, cy - r, r * 2, r * 2, r, TOWER_COLOR[t.type]);
            Ui.rr(g, cx - r / 2, cy - r / 2, r, r, r / 2, 0xFFE5E7EB);
            for (int l = 0; l < t.level; l++) Ui.fill(g, cx - 5 + l * 4, cy + r + 1, 3, 2, Ui.WARN);
        }
        // placing preview
        if (place >= 0 && my >= 0 && my < fh) {
            boolean ok = validSpot(mx, my) && money >= TOWER_COST[place];
            Tower tmp = new Tower();
            tmp.type = place;
            ring(g, x + mx, y + my, range(tmp, scale), ok ? Ui.GOOD : Ui.BAD);
            Ui.rr(g, x + mx - 6, y + my - 6, 12, 12, 6, Ui.withAlpha(TOWER_COLOR[place], ok ? 200 : 90));
        } else if (my >= 0 && my < fh) { // hovering a tower: its range and the upgrade price
            for (Tower t : towers) {
                if (Math.hypot(t.nx * w - mx, t.ny * fh - my) < 11 * scale) {
                    ring(g, x + (int) (t.nx * w), y + (int) (t.ny * fh), range(t, scale), 0xAAFFFFFF);
                    Ui.textC(g, t.level >= 4 ? "max level" : "upgrade " + upgradeCost(t) + "   (right-click sells for " + (int) (t.spent * 0.7) + ")", x + w / 2, y + fh - 12, Ui.TEXT);
                }
            }
        }
        Ui.text(g, "$" + money, x + 4, y + 3, Ui.WARN);
        Ui.textC(g, "Wave " + wave, x + w / 2, y + 3, Ui.TEXT);
        Ui.textR(g, "Lives " + lives, x + w - 4, y + 3, lives <= 5 ? Ui.BAD : Ui.TEXT);
        // the bar at the bottom
        int by = y + fh + 2, bw = Math.max(40, (w - 90) / 3 - 4);
        for (int i = 0; i < 3; i++) {
            int bx = x + 2 + i * (bw + 4);
            boolean sel = place == i, can = money >= TOWER_COST[i];
            Ui.rrb(g, bx, by, bw, 20, 4, sel ? 0xFF1F3A2B : 0xFF171A21, sel ? Ui.GOOD : can ? Ui.LINE : 0xFF2B303B);
            Ui.text(g, (i + 1) + " " + TOWER_NAME[i] + " " + TOWER_COST[i], bx + 4, by + 6, can ? Ui.TEXT : Ui.DIM);
        }
        int gx = x + w - 84;
        Ui.rrb(g, gx, by, 40, 20, 4, running ? 0xFF171A21 : 0xFF14532D, running ? Ui.LINE : Ui.GOOD);
        Ui.textC(g, over ? "Again" : running ? "..." : "Go", gx + 20, by + 6, running ? Ui.DIM : 0xFFFFFFFF);
        Ui.rrb(g, gx + 44, by, 38, 20, 4, 0xFF171A21, Ui.LINE);
        Ui.textC(g, speed + "x", gx + 63, by + 6, Ui.TEXT);
        if (over) Ui.textC(g, "The bloons got away on wave " + wave + "! Score " + score, x + w / 2, y + fh / 2 - 4, Ui.BAD);
        else if (!running && wave == 0) Ui.textC(g, "Place a tower, then press Go (Space)", x + w / 2, y + fh - 12, Ui.MUTED);
    }

    private int upgradeCost(Tower t) {
        return (int) (TOWER_COST[t.type] * 0.6 * t.level);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        int fh = fieldH();
        if (over) {
            reset();
            return true;
        }
        if (my >= fh) { // the bar
            int bw = Math.max(40, (w - 90) / 3 - 4), gx = w - 84;
            for (int i = 0; i < 3; i++) {
                int bx = 2 + i * (bw + 4);
                if (mx >= bx && mx < bx + bw) place = place == i ? -1 : i;
            }
            if (mx >= gx && mx < gx + 40) startWave();
            else if (mx >= gx + 44 && mx < gx + 82) speed = speed % 3 + 1;
            return true;
        }
        if (button == 1) { // sell
            for (Iterator<Tower> it = towers.iterator(); it.hasNext(); ) {
                Tower t = it.next();
                if (Math.hypot(t.nx * w - mx, t.ny * fh - my) < 11 * scale) {
                    money += (int) (t.spent * 0.7);
                    it.remove();
                    return true;
                }
            }
            place = -1;
            return true;
        }
        for (Tower t : towers) { // upgrade
            if (Math.hypot(t.nx * w - mx, t.ny * fh - my) < 11 * scale) {
                int c = upgradeCost(t);
                if (t.level < 4 && money >= c) {
                    money -= c;
                    t.spent += c;
                    t.level++;
                }
                return true;
            }
        }
        if (place >= 0 && money >= TOWER_COST[place] && validSpot(mx, my)) {
            Tower t = new Tower();
            t.nx = mx / w;
            t.ny = my / fh;
            t.type = place;
            t.spent = TOWER_COST[place];
            money -= TOWER_COST[place];
            towers.add(t);
        }
        return true;
    }

    @Override
    public boolean key(int key) {
        switch (key) {
            case 49 -> place = place == 0 ? -1 : 0;
            case 50 -> place = place == 1 ? -1 : 1;
            case 51 -> place = place == 2 ? -1 : 2;
            case 32 -> {
                if (over) reset();
                else startWave();
            }
            case 70 -> speed = speed % 3 + 1;
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public int score() {
        return Math.max(score, wave * 100 + pops);
    }

    @Override
    public String status() {
        return "Wave " + wave + "   Popped " + pops + "   (keys 1-3 pick a tower, Space = go, F = speed)";
    }
}
