package com.nablzz.addons;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Chat line from the mod itself (not from one of the macros). */
public final class Notify {
    private Notify() {}

    public static void msg(String s) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§d[Nablzz] §f" + s));
    }
}
