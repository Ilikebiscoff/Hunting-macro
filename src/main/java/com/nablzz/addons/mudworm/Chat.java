package com.nablzz.addons.mudworm;

import com.nablzz.addons.mudworm.Config;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class Chat {
    private static final int KEEP = 80;
    private static final ArrayDeque<String> LOG = new ArrayDeque();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Chat() {
    }

    public static void msg(String s) {
        Chat.log(s);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage((Component)Component.literal((String)("\u00a72[Mudworm] \u00a7f" + s)));
        }
    }

    public static void debug(String s) {
        Chat.log(s);
        if (!Config.debug) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage((Component)Component.literal((String)("\u00a72[Mudworm] \u00a77" + s)));
        }
    }

    public static synchronized void log(String s) {
        com.nablzz.addons.Report.event("[Mudworm] " + s);
        if (LOG.size() >= 80) {
            LOG.removeFirst();
        }
        LOG.addLast(LocalTime.now().format(TIME) + "  " + s.replaceAll("\u00a7.", ""));
    }

    public static synchronized List<String> recent() {
        return new ArrayList<String>(LOG);
    }
}

