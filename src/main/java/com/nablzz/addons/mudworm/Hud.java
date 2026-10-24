package com.nablzz.addons.mudworm;

import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.Macro;
import com.nablzz.addons.mudworm.SpotScanner;
import java.util.ArrayList;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class Hud {
    private static final int BG = -1341123560;
    private static final int HEADER = -803725272;
    private static final int ACCENT = -13566112;
    private static final int TITLE = -1;
    private static final int LABEL = -6248272;
    private static final int VALUE = -1513240;

    private Hud() {
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Macro m = Macro.INSTANCE;
        if (!Config.hud || mc.player == null || mc.options.hideGui) {
            return;
        }
        if (!m.running() && m.totalMs() == 0L) {
            return;
        }
        Font f = mc.font;
        long ms = m.totalMs();
        double hours = (double)ms / 3600000.0;
        ArrayList<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{"Status", m.status()});
        rows.add(new String[]{"Time", Hud.clock(ms)});
        rows.add(new String[]{"Spots in sight", "" + SpotScanner.SPOTS.size()});
        rows.add(new String[]{"Spots done", m.spotsDone + (String)(hours > 0.01 ? "  (" + (int)((double)m.spotsDone / hours) + "/h)" : "")});
        rows.add(new String[]{"Floor drops", m.floorDrops + "  (" + m.shards + " mudworm)"});
        rows.add(new String[]{"Etherwarps", "" + m.warps});
        if (Config.blatant) {
            rows.add(new String[]{"Mode", "BLATANT (silent aim)"});
        }
        String title = "Mudworm Macro";
        int labelW = 0;
        int valueW = 0;
        for (String[] r : rows) {
            labelW = Math.max(labelW, f.width(r[0]));
            valueW = Math.max(valueW, f.width(r[1]));
        }
        int pad = 6;
        int gap = 10;
        int lineH = 11;
        int headerH = 16;
        int w = Math.max(f.width(title), labelW + gap + valueW) + pad * 2;
        int h = headerH + rows.size() * lineH + pad;
        int x = 4;
        int y = 4;
        g.fill(x, y, x + w, y + h, -1341123560);
        g.fill(x, y, x + w, y + headerH, -803725272);
        g.fill(x, y, x + 2, y + h, m.running() ? -13566112 : -38037);
        g.text(f, title, x + pad, y + 4, -1, false);
        int ty = y + headerH + 3;
        for (String[] r : rows) {
            g.text(f, r[0], x + pad, ty, -6248272, false);
            g.text(f, r[1], x + w - pad - f.width(r[1]), ty, -1513240, false);
            ty += lineH;
        }
    }

    private static String clock(long ms) {
        long s = ms / 1000L;
        return String.format("%02d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L);
    }
}

