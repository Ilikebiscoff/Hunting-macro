package com.nablzz.addons.hunting;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class Chat {
    private Chat() {}

    public static void msg(String s) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§6[Hunt] §f" + s));
    }
}
