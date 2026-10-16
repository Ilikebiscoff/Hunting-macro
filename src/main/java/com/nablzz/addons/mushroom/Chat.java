package com.nablzz.addons.mushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class Chat {
    private Chat() {}

    public static void msg(String s) {
        com.nablzz.addons.Report.event("[Glowing] " + s);
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§a[Glowing] §f" + s));
    }
}
