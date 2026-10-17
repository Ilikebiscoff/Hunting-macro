package com.nablzz.addons.mudworm;

import com.nablzz.addons.NablzzScreen;
import com.nablzz.addons.Ui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Profit panel in the top right corner. */
final class MudwormHud {
    private MudwormHud() {}

    private record Row(String label, String value, int color) {}

    static void render(GuiGraphicsExtractor g, DeltaTracker delta, MudwormMacro m) {
        Minecraft mc = Minecraft.getInstance();
        if (!m.hudOn() || mc.player == null || mc.options.hideGui || mc.screen instanceof NablzzScreen) return;
        if (!m.isRunning() && m.runMs() == 0 && MudwormProfit.items() == 0) return;
        Ui.alpha = 1f;
        double coins = MudwormProfit.coins();
        double perHour = m.runMs() < 20_000 ? 0 : coins / (m.runMs() / 3_600_000.0);
        boolean priced = MudwormProfit.pricesLive();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Status", m.status(), m.isRunning() ? Ui.GOOD : Ui.BAD));
        rows.add(new Row("Time", m.clock(), Ui.TEXT));
        rows.add(new Row("Spots", "" + m.spotsDone(), Ui.TEXT));
        rows.add(new Row("Profit", priced ? MudwormProfit.fmt(coins) + " coins" : "prices loading...", Ui.WARN));
        rows.add(new Row("Per hour", priced ? MudwormProfit.fmt(perHour) + "/h" : "-", Ui.WARN));
        int shown = 0;
        for (var l : MudwormProfit.lines()) {
            if (shown++ >= 3) break;
            rows.add(new Row(Ui.fit(l.name(), 90), MudwormProfit.fmt(l.count()), Ui.accent));
        }

        String title = "GALATEA MUDWORM";
        int pad = 7, gap = 14, lineH = 11, headerH = 17;
        int labelW = 0, valueW = 0;
        for (Row r : rows) {
            labelW = Math.max(labelW, Ui.tw(r.label()));
            valueW = Math.max(valueW, Ui.tw(r.value()));
        }
        int w = Math.max(Ui.tw(title) + pad * 2 + 14, labelW + gap + valueW + pad * 2);
        int h = headerH + rows.size() * lineH + pad;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6, y = 6;

        Ui.shadow(g, x, y, w, h, 4);
        Ui.rrb(g, x, y, w, h, 4, 0xE6101218, 0xFF252A35);
        Ui.hgrad(g, x + 4, y + 1, w - 8, 1, Ui.withAlpha(Ui.accent, 0), Ui.accent);
        Ui.rr(g, x + pad, y + 6, 5, 5, 2, m.isRunning() ? Ui.GOOD : Ui.BAD);
        Ui.bold(g, title, x + pad + 9, y + 4, Ui.TEXT);
        int ty = y + headerH;
        for (Row r : rows) {
            Ui.text(g, r.label(), x + pad, ty, Ui.MUTED);
            Ui.textR(g, r.value(), x + w - pad, ty, r.color());
            ty += lineH;
        }
    }
}
