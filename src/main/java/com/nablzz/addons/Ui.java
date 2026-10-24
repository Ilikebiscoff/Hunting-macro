package com.nablzz.addons;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small drawing toolkit for the settings screen and the HUD: rounded rectangles, colour blending, text helpers. */
public final class Ui {
    private Ui() {}

    /** Global fade (0..1) for the open animation; every colour goes through {@link #mul}. */
    public static float alpha = 1f;
    /** Seconds since the last frame (set by the screen), for frame-rate independent animation. */
    public static float dt = 0.016f;

    public static final String[] ACCENT_NAMES = {"Violet", "Cyan", "Emerald", "Amber", "Rose"};
    public static final int[] ACCENTS = {0xFF8B5CF6, 0xFF22D3EE, 0xFF34D399, 0xFFF59E0B, 0xFFF43F5E};
    public static int accent = ACCENTS[0];

    public static final int WINDOW = 0xF4101218;
    public static final int SIDEBAR = 0xF40B0D11;
    public static final int CARD = 0xFF171A21;
    public static final int CARD_HI = 0xFF1E222B;
    public static final int LINE = 0xFF252A35;
    public static final int FIELD = 0xFF0E1015;
    public static final int OFF = 0xFF2B303B;
    public static final int TEXT = 0xFFE7E9EF;
    public static final int MUTED = 0xFF8D94A3;
    public static final int DIM = 0xFF5B6272;
    public static final int GOOD = 0xFF34D399;
    public static final int BAD = 0xFFF87171;
    public static final int WARN = 0xFFFBBF24;

    public static void setAccent(int idx) {
        accent = ACCENTS[Math.floorMod(idx, ACCENTS.length)];
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static int mul(int c) {
        int a = (int) ((c >>> 24) * alpha);
        return (a << 24) | (c & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ab = (b >>> 24);
        int ra = (a >> 16) & 255, rb = (b >> 16) & 255;
        int ga = (a >> 8) & 255, gb = (b >> 8) & 255;
        int ba = a & 255, bb = b & 255;
        return ((int) (aa + (ab - aa) * t) << 24) | ((int) (ra + (rb - ra) * t) << 16)
                | ((int) (ga + (gb - ga) * t) << 8) | (int) (ba + (bb - ba) * t);
    }

    public static int withAlpha(int c, int a) {
        return (a << 24) | (c & 0xFFFFFF);
    }

    /** Moves {@code cur} toward {@code target}, about {@code speed} per second. */
    public static float approach(float cur, float target, float speed) {
        float step = speed * dt;
        if (cur < target) return Math.min(target, cur + step);
        return Math.max(target, cur - step);
    }

    public static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, mul(c));
    }

    /** Rounded rectangle (radius up to ~6) built from stacked 1 px rows. */
    public static void rr(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int c) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            fill(g, x, y, w, h, c);
            return;
        }
        int col = mul(c);
        for (int i = 0; i < r; i++) {
            double d = r - i - 0.5;
            int dx = r - (int) Math.round(Math.sqrt(r * (double) r - d * d));
            g.fill(x + dx, y + i, x + w - dx, y + i + 1, col);
            g.fill(x + dx, y + h - 1 - i, x + w - dx, y + h - i, col);
        }
        g.fill(x, y + r, x + w, y + h - r, col);
    }

    /** Rounded rectangle with a 1 px border. */
    public static void rrb(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int border) {
        rr(g, x, y, w, h, r, border);
        rr(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    /** Vertical gradient, drawn as 1 px lines (only for small areas). */
    public static void vgrad(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
        for (int i = 0; i < h; i++) fill(g, x, y + i, w, 1, mix(top, bottom, h <= 1 ? 0 : i / (float) (h - 1)));
    }

    public static void hgrad(GuiGraphicsExtractor g, int x, int y, int w, int h, int left, int right) {
        for (int i = 0; i < w; i++) fill(g, x + i, y, 1, h, mix(left, right, w <= 1 ? 0 : i / (float) (w - 1)));
    }

    public static int tw(String s) {
        return font().width(s);
    }

    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(font(), s, x, y, mul(c), false);
    }

    public static void textShadow(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(font(), s, x, y, mul(c), true);
    }

    public static void textR(GuiGraphicsExtractor g, String s, int right, int y, int c) {
        text(g, s, right - tw(s), y, c);
    }

    public static void textC(GuiGraphicsExtractor g, String s, int cx, int y, int c) {
        text(g, s, cx - tw(s) / 2, y, c);
    }

    /** Pseudo-bold: the same text drawn twice, one pixel apart. */
    public static void bold(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        text(g, s, x, y, c);
        text(g, s, x + 1, y, c);
    }

    /** Text at a larger size (pose scale), top-left at x,y. */
    public static void big(GuiGraphicsExtractor g, String s, int x, int y, float scale, int c) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        text(g, s, 0, 0, c);
        text(g, s, 1, 0, c);
        g.pose().popMatrix();
    }

    /** Cuts the text with ".." so it fits in {@code maxW}. */
    public static String fit(String s, int maxW) {
        if (tw(s) <= maxW) return s;
        while (s.length() > 1 && tw(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    /** Word-wraps the text to {@code maxW} pixels (a word longer than that is cut). Never returns an empty list. */
    public static java.util.List<String> wrap(String s, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) {
            out.add("");
            return out;
        }
        maxW = Math.max(12, maxW);
        for (String para : s.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                while (tw(word) > maxW && word.length() > 1) {
                    int cut = word.length();
                    while (cut > 1 && tw(word.substring(0, cut)) > maxW) cut--;
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (tw(tryLine) > maxW && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /** Soft drop shadow made of a few translucent rings. */
    public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        for (int i = 6; i >= 1; i--) rr(g, x - i, y - i + 3, w + i * 2, h + i * 2, r + i, withAlpha(0x000000, 10 + (7 - i) * 3));
    }
}
