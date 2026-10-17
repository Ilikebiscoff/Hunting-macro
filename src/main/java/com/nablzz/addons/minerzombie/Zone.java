package com.nablzz.addons.minerzombie;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads the SkyBlock sidebar to tell which area we are in. */
public final class Zone {
    private Zone() {}

    /** Sidebar lines as plain text, or null if there is no sidebar. */
    public static List<String> sidebar(Minecraft mc) {
        if (mc.level == null) return null;
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<String> out = new ArrayList<>();
        for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
            if (e.isHidden()) continue;
            PlayerTeam team = sb.getPlayersTeam(e.owner());
            String line = team == null ? e.ownerName().getString()
                    : PlayerTeam.formatNameForTeam(team, e.ownerName()).getString();
            out.add(line.replaceAll("§.", "").trim());
        }
        return out;
    }

    public static String title(Minecraft mc) {
        if (mc.level == null) return "";
        Objective obj = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return obj == null ? "" : obj.getDisplayName().getString().replaceAll("§.", "");
    }

    /** TRUE = a sidebar line contains {@code text}, FALSE = SkyBlock sidebar without it, null = can't tell. */
    public static Boolean contains(Minecraft mc, String text) {
        if (!title(mc).toUpperCase(Locale.ROOT).contains("SKYBLOCK")) return null;
        List<String> lines = sidebar(mc);
        if (lines == null || lines.isEmpty()) return null;
        String z = text.toLowerCase(Locale.ROOT);
        for (String l : lines) if (l.toLowerCase(Locale.ROOT).contains(z)) return true;
        return false;
    }
}
