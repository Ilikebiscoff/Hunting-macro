package com.nablzz.addons.arcade;

import com.nablzz.addons.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/** 2048: arrow keys slide every tile; equal tiles merge. */
final class Game2048 implements Game {
    private static final int N = 4;
    private static final int[] BG = {0xFF1E222B, 0xFF3B4252, 0xFF475569, 0xFF0E7490, 0xFF0891B2, 0xFF059669, 0xFF10B981, 0xFFD97706, 0xFFF59E0B, 0xFFDC2626, 0xFFE11D48, 0xFF7C3AED, 0xFF6D28D9};
    private final Random rnd = new Random();
    private final int[][] t = new int[N][N]; // exponent: 0 empty, 1 = 2, 2 = 4 ...
    private int w = 300, h = 200, score;
    private boolean over, won;

    @Override
    public String name() {
        return "2048";
    }

    @Override
    public String blurb() {
        return "Slide and merge the tiles up to 2048. Arrow keys or WASD.";
    }

    @Override
    public void size(int w, int h) {
        this.w = w;
        this.h = h;
    }

    @Override
    public void reset() {
        for (int[] r : t) java.util.Arrays.fill(r, 0);
        score = 0;
        over = won = false;
        add();
        add();
    }

    private void add() {
        int free = 0;
        for (int[] r : t) for (int v : r) if (v == 0) free++;
        if (free == 0) return;
        int k = rnd.nextInt(free);
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            if (t[i][j] != 0) continue;
            if (k-- == 0) t[i][j] = rnd.nextInt(10) == 0 ? 2 : 1;
        }
    }

    /** Slides one line to the front; returns whether something moved. {@code line} is changed in place. */
    private boolean slide(int[] line) {
        int[] out = new int[N];
        int n = 0;
        boolean merged = false;
        for (int v : line) {
            if (v == 0) continue;
            if (n > 0 && out[n - 1] == v && !merged) {
                out[n - 1]++;
                score += 1 << out[n - 1];
                if (out[n - 1] == 11) won = true;
                merged = true;
            } else {
                out[n++] = v;
                merged = false;
            }
        }
        boolean moved = !java.util.Arrays.equals(out, line);
        System.arraycopy(out, 0, line, 0, N);
        return moved;
    }

    private boolean move(int dir) { // 0 left, 1 right, 2 up, 3 down
        boolean moved = false;
        for (int i = 0; i < N; i++) {
            int[] line = new int[N];
            for (int j = 0; j < N; j++) line[j] = switch (dir) {
                case 0 -> t[i][j];
                case 1 -> t[i][N - 1 - j];
                case 2 -> t[j][i];
                default -> t[N - 1 - j][i];
            };
            if (slide(line)) moved = true;
            for (int j = 0; j < N; j++) {
                switch (dir) {
                    case 0 -> t[i][j] = line[j];
                    case 1 -> t[i][N - 1 - j] = line[j];
                    case 2 -> t[j][i] = line[j];
                    default -> t[N - 1 - j][i] = line[j];
                }
            }
        }
        return moved;
    }

    private boolean stuck() {
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            if (t[i][j] == 0) return false;
            if (j + 1 < N && t[i][j] == t[i][j + 1]) return false;
            if (i + 1 < N && t[i][j] == t[i + 1][j]) return false;
        }
        return true;
    }

    @Override
    public void update(float dt) {
    }

    @Override
    public void render(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
        Ui.fill(g, x, y, w, h, 0xFF0B0D12);
        int cs = Math.max(20, Math.min((h - 12) / N, (w - 12) / N));
        int ox = x + (w - cs * N) / 2, oy = y + (h - cs * N) / 2;
        Ui.rr(g, ox - 3, oy - 3, cs * N + 6, cs * N + 6, 5, 0xFF10131A);
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            int v = t[i][j];
            int cx = ox + j * cs, cy = oy + i * cs;
            Ui.rr(g, cx + 2, cy + 2, cs - 4, cs - 4, 4, BG[Math.min(v, BG.length - 1)]);
            if (v > 0) Ui.textC(g, "" + (1 << v), cx + cs / 2, cy + cs / 2 - 4, v <= 2 ? 0xFFE7E9EF : 0xFFFFFFFF);
        }
        if (over) Ui.textC(g, "No moves left - Space for a new game", x + w / 2, y + 2, Ui.BAD);
        else if (won) Ui.textC(g, "2048! Keep going if you like", x + w / 2, y + 2, Ui.GOOD);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (over) {
            reset();
            return true;
        }
        return false;
    }

    @Override
    public boolean key(int key) {
        int dir = switch (key) {
            case 263, 65 -> 0;
            case 262, 68 -> 1;
            case 265, 87 -> 2;
            case 264, 83 -> 3;
            default -> -1;
        };
        if (key == 32 || key == 82) {
            reset();
            return true;
        }
        if (dir < 0) return false;
        if (!over && move(dir)) {
            add();
            if (stuck()) over = true;
        }
        return true;
    }

    @Override
    public int score() {
        return score;
    }

    @Override
    public String status() {
        return "Score " + score + "   (R = new game)";
    }
}
