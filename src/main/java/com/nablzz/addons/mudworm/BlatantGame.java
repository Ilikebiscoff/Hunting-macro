package com.nablzz.addons.mudworm;

import com.nablzz.addons.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The gate in front of blatant mode: catch 5 mudworm shards before three of them hit the ground. Red bombs cost a
 * point, gold shards are worth two. The Override button in the top right skips the game.
 */
public final class BlatantGame extends Screen {
    private static final int GOAL = 5;
    private static final String[] CHEERS = {"Nice!", "Gotcha!", "Smooth!", "Mudworm down!", "Clean!", "Easy money", "Zoom!", "Slick!"};
    private static final String[] OOPS = {"Oops!", "Butterfingers", "It got away", "Too slow", "Splat"};

    private static final class Orb {
        double x, y, vy, r;
        int kind; // 0 shard, 1 bomb, 2 gold
        float phase;
    }

    private static final class Spark {
        double x, y, vx, vy;
        float life, max;
        int color;
        int size;
    }

    private final List<Orb> orbs = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final Random rnd = new Random();
    private int score, lives = 3, combo, state; // state: 0 playing, 1 lost, 2 won
    private long last;
    private float spawnIn = 0.5f, shake, flash, time, stateT, toastT;
    private String toast = "";
    private int toastColor = Ui.TEXT;
    private boolean finished;
    private boolean overridden;

    public BlatantGame() {
        super(Component.literal("Blatant mode"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    private int fw() {
        return Math.min(width - 40, 380);
    }

    private int fh() {
        return Math.min(height - 120, 230);
    }

    private int fx() {
        return (width - fw()) / 2;
    }

    private int fy() {
        return (height - fh()) / 2 + 12;
    }

    private int ovX() {
        return width - 90;
    }

    // ---- game ------------------------------------------------------------------------------------------------

    private void restart() {
        orbs.clear();
        sparks.clear();
        score = 0;
        lives = 3;
        combo = 0;
        state = 0;
        spawnIn = 0.4f;
        say("Go!", Ui.GOOD);
    }

    private void say(String s, int color) {
        toast = s;
        toastColor = color;
        toastT = 1.1f;
    }

    private void spawn() {
        Orb o = new Orb();
        double roll = rnd.nextDouble();
        o.kind = roll < 0.18 && score > 0 ? 1 : roll < 0.28 ? 2 : 0;
        o.r = o.kind == 2 ? 11 : 13;
        o.x = fx() + o.r + 6 + rnd.nextDouble() * (fw() - 2 * o.r - 12);
        o.y = fy() - o.r;
        o.vy = 70 + score * 16 + rnd.nextDouble() * 40;
        o.phase = rnd.nextFloat() * 6.28f;
        orbs.add(o);
    }

    private void burst(double x, double y, int color, int n) {
        for (int i = 0; i < n; i++) {
            Spark s = new Spark();
            double a = rnd.nextDouble() * Math.PI * 2, sp = 40 + rnd.nextDouble() * 140;
            s.x = x;
            s.y = y;
            s.vx = Math.cos(a) * sp;
            s.vy = Math.sin(a) * sp - 40;
            s.max = s.life = 0.5f + rnd.nextFloat() * 0.6f;
            s.color = color;
            s.size = 2 + rnd.nextInt(2);
            sparks.add(s);
        }
    }

    private void confetti() {
        int[] cols = {0xFF34D399, 0xFFFBBF24, 0xFFF43F5E, 0xFF22D3EE, 0xFF8B5CF6};
        for (int i = 0; i < 90; i++) burst(width / 2.0 + (rnd.nextDouble() - 0.5) * fw(), fy() + 20, cols[rnd.nextInt(cols.length)], 1);
    }

    private void update(float dt) {
        time += dt;
        shake = Math.max(0, shake - dt);
        flash = Math.max(0, flash - dt * 2.2f);
        toastT = Math.max(0, toastT - dt);
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= dt;
            s.vy += 260 * dt;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            if (s.life <= 0) sparks.remove(i);
        }
        if (state == 2) {
            stateT += dt;
            if (stateT > 1.8f) finish(false);
            return;
        }
        if (state != 0) return;
        spawnIn -= dt;
        if (spawnIn <= 0) {
            spawn();
            spawnIn = Math.max(0.36f, 0.85f - score * 0.09f) + rnd.nextFloat() * 0.25f;
        }
        for (int i = orbs.size() - 1; i >= 0; i--) {
            Orb o = orbs.get(i);
            o.y += o.vy * dt;
            if (o.y - o.r > fy() + fh()) {
                orbs.remove(i);
                if (o.kind != 1) {
                    lives--;
                    combo = 0;
                    shake = 0.3f;
                    flash = 1f;
                    burst(o.x, fy() + fh() - 4, 0xFFF87171, 10);
                    say(OOPS[rnd.nextInt(OOPS.length)], Ui.BAD);
                    if (lives <= 0) {
                        state = 1;
                        say("Out of lives - click to retry", Ui.BAD);
                        toastT = 99f;
                    }
                }
            }
        }
    }

    private void win() {
        state = 2;
        stateT = 0;
        confetti();
        say("Unlocked!", Ui.GOOD);
        toastT = 99f;
    }

    private void finish(boolean byOverride) {
        if (finished) return;
        finished = true;
        Config.blatant = true;
        Chat.msg("§cBLATANT MODE ON" + (byOverride ? " (override)" : "") + ": etherwarps send their aim to the server WITHOUT moving your camera.");
        Chat.msg("§cThat is far easier for anti-cheat and other players to spot. A ban is much more likely. It turns off at every restart, or with /mudworm blatant off.");
        onClose();
    }

    // ---- input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (Ui.in(mx, my, ovX(), 8, 82, 20)) {
            overridden = true;
            finish(true);
            return true;
        }
        if (state == 1) {
            restart();
            return true;
        }
        if (state != 0) return true;
        for (int i = orbs.size() - 1; i >= 0; i--) {
            Orb o = orbs.get(i);
            double dx = mx - o.x, dy = my - o.y;
            if (dx * dx + dy * dy > (o.r + 5) * (o.r + 5)) continue;
            orbs.remove(i);
            if (o.kind == 1) {
                score = Math.max(0, score - 1);
                combo = 0;
                shake = 0.4f;
                flash = 0.8f;
                burst(o.x, o.y, 0xFFF43F5E, 22);
                say("BOOM! -1", Ui.BAD);
            } else {
                int gain = o.kind == 2 ? 2 : 1;
                combo++;
                score += gain;
                burst(o.x, o.y, o.kind == 2 ? 0xFFFBBF24 : 0xFF34D399, o.kind == 2 ? 26 : 16);
                say(o.kind == 2 ? "GOLD! +2" : combo >= 3 ? "x" + combo + " combo!" : CHEERS[rnd.nextInt(CHEERS.length)], o.kind == 2 ? Ui.WARN : Ui.GOOD);
                if (score >= GOAL) win();
            }
            return true;
        }
        return true;
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.alpha = 1f;
        Ui.fill(g, 0, 0, width, height, 0xE0050608);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        update(dt);
        Ui.alpha = 1f;

        int sx = shake > 0 ? (int) ((rnd.nextFloat() - 0.5f) * shake * 24) : 0;
        int sy = shake > 0 ? (int) ((rnd.nextFloat() - 0.5f) * shake * 24) : 0;
        int fx = fx() + sx, fy = fy() + sy, fw = fw(), fh = fh();

        Ui.textC(g, "BLATANT MODE", width / 2, fy - 38, Ui.BAD);
        Ui.textC(g, "Catch " + GOAL + " mudworm shards to unlock it. Red bombs cost a point, gold counts double.", width / 2, fy - 24, Ui.MUTED);

        // field
        Ui.rrb(g, fx, fy, fw, fh, 6, 0xFF0B0D12, Ui.LINE);
        for (int i = 0; i < 6; i++) { // faint falling-grid lines
            int lx = fx + 20 + i * (fw - 40) / 5;
            Ui.fill(g, lx, fy + 2, 1, fh - 4, 0x14FFFFFF);
        }
        if (flash > 0) Ui.fill(g, fx + 1, fy + 1, fw - 2, fh - 2, Ui.withAlpha(0xFFF87171, (int) (flash * 60)));
        Ui.fill(g, fx + 1, fy + fh - 3, fw - 2, 2, Ui.withAlpha(0xFFF87171, 90)); // the ground

        for (Orb o : orbs) drawOrb(g, o, sx, sy);
        for (Spark s : sparks) {
            int a = Math.max(0, Math.min(255, (int) (255 * s.life / s.max)));
            Ui.fill(g, (int) s.x + sx, (int) s.y + sy, s.size, s.size, Ui.withAlpha(s.color, a));
        }

        // score pips and lives
        int px = fx, py = fy + fh + 12;
        for (int i = 0; i < GOAL; i++) Ui.rr(g, px + i * 20, py, 16, 16, 4, i < score ? Ui.GOOD : Ui.OFF);
        Ui.text(g, "Score " + Math.min(score, GOAL) + "/" + GOAL, px + GOAL * 20 + 6, py + 4, Ui.TEXT);
        for (int i = 0; i < 3; i++) Ui.rr(g, fx + fw - 3 * 20 + i * 20, py, 16, 16, 8, i < lives ? Ui.BAD : Ui.OFF);
        Ui.textR(g, "Lives", fx + fw - 66, py + 4, Ui.MUTED);

        if (toastT > 0) Ui.textC(g, toast, width / 2, fy + fh / 2 - 4, toastColor);
        if (state == 2) Ui.textC(g, "Blatant mode unlocked. Don't say we didn't warn you.", width / 2, fy + fh / 2 + 12, Ui.WARN);

        Ui.textC(g, "Blatant mode hides your head movement from you but not from the server: a ban is much more likely.", width / 2, py + 28, Ui.WARN);
        Ui.textC(g, "Esc closes this without enabling anything.", width / 2, py + 42, Ui.DIM);

        // override, top right
        boolean hov = Ui.in(mx, my, ovX(), 8, 82, 20);
        Ui.rrb(g, ovX(), 8, 82, 20, 5, hov ? Ui.CARD_HI : Ui.CARD, hov ? Ui.WARN : Ui.LINE);
        Ui.textC(g, "Override", ovX() + 41, 14, hov ? Ui.WARN : Ui.MUTED);
        if (hov) Ui.textR(g, "skips the game", ovX() - 6, 14, Ui.DIM);
    }

    private void drawOrb(GuiGraphicsExtractor g, Orb o, int sx, int sy) {
        float pulse = 1f + 0.08f * (float) Math.sin(time * 6 + o.phase);
        int r = (int) (o.r * pulse);
        int x = (int) o.x - r + sx, y = (int) o.y - r + sy, d = r * 2;
        int base = o.kind == 1 ? 0xFFB91C1C : o.kind == 2 ? 0xFFF59E0B : 0xFF10B981;
        int hi = o.kind == 1 ? 0xFFF87171 : o.kind == 2 ? 0xFFFDE68A : 0xFF6EE7B7;
        Ui.rr(g, x - 2, y - 2, d + 4, d + 4, r + 2, Ui.withAlpha(base, 70)); // glow
        Ui.rr(g, x, y, d, d, r, base);
        Ui.rr(g, x + r / 3, y + r / 4, r, r, r / 2, Ui.withAlpha(hi, 200)); // shine
        if (o.kind == 1) Ui.textC(g, "!", (int) o.x + sx, (int) o.y - 4 + sy, 0xFFFFFFFF);
        if (o.kind == 2) Ui.textC(g, "2", (int) o.x + sx, (int) o.y - 4 + sy, 0xFF78350F);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
