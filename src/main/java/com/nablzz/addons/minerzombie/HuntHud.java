package com.nablzz.addons.minerzombie;

import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Ui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small status panel on the game screen (corner picked in the settings). */
public final class HuntHud {
    private HuntHud() {}

    private record Row(String label, String value, int color) {}

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta, HuntController c) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.hud || mc.player == null || mc.options.hideGui || mc.screen instanceof NablzzScreen) return;
        boolean run = c.isRunning();
        if (!run && Session.runMs == 0 && c.holesPlaced == 0) return; // nothing to show yet
        Ui.alpha = 1f;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Status", c.phaseLabel(), run ? Ui.GOOD : Ui.BAD));
        rows.add(new Row("Time", Session.clock(), Ui.TEXT));
        rows.add(new Row("Kills", "" + c.killCount(), Ui.TEXT));
        rows.add(new Row("Black holes", "" + c.holesPlaced, Ui.TEXT));
        rows.add(new Row("Shards", c.shards + "  (" + String.format(Locale.ROOT, "%.0f", Session.perHour(c.shards)) + "/h)", Ui.accent));
        rows.add(new Row("Zombies", "" + c.zombiesInView(), Ui.TEXT));
        if (!c.inZoneNow()) rows.add(new Row("Zone", "outside", Ui.WARN));
        if (c.lavaIncidents() > 0) rows.add(new Row("Lava", c.lavaIncidents() + "/" + c.lavaLimit, Ui.WARN));

        String title = "HUNTING MACRO";
        int pad = 7, gap = 14, lineH = 11, headerH = 17;
        int labelW = 0, valueW = 0;
        for (Row r : rows) {
            labelW = Math.max(labelW, Ui.tw(r.label()));
            valueW = Math.max(valueW, Ui.tw(r.value()));
        }
        int w = Math.max(Ui.tw(title) + pad * 2 + 14, labelW + gap + valueW + pad * 2);
        int h = headerH + rows.size() * lineH + pad;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = (Config.hudCorner & 1) == 0 ? 6 : sw - w - 6;
        int y = (Config.hudCorner & 2) == 0 ? 6 : sh - h - 6;

        Ui.shadow(g, x, y, w, h, 4);
        Ui.rrb(g, x, y, w, h, 4, 0xE6101218, 0xFF252A35);
        Ui.hgrad(g, x + 4, y + 1, w - 8, 1, Ui.withAlpha(Ui.accent, 0), Ui.accent);
        int dot = run ? Ui.GOOD : Ui.BAD;
        Ui.rr(g, x + pad, y + 6, 5, 5, 2, dot);
        Ui.bold(g, title, x + pad + 9, y + 4, Ui.TEXT);
        int ty = y + headerH;
        for (Row r : rows) {
            Ui.text(g, r.label(), x + pad, ty, Ui.MUTED);
            Ui.textR(g, r.value(), x + w - pad, ty, r.color());
            ty += lineH;
        }
    }
}
