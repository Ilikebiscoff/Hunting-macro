package com.nablzz.addons.fusion;

import com.nablzz.addons.Notify;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Drives the SkyBlock Fusion Box: "Fusion Box" -> click "Repeat Previous Fusion" -> "Confirm Fusion" ->
 * click "Fusion" -> back to "Fusion Box", until the Hunting Box runs low on shards.
 */
public class FusionMacro {
    private static final Pattern STILL_HAVE = Pattern.compile("(\\d[\\d,]*)x\\s");
    private static final long OPEN_GRACE_MS = 30_000;
    private static final long GAP_MS = 6_000;

    public int baseDelayMs = 450;
    public int minShards = 5;
    public int maxFusions = 0;

    private final Random rng = new Random();
    private boolean running;
    private long nextActionAt;
    private long deadline;
    public int fusions;
    public String state = "Stopped";

    public boolean isRunning() {
        return running;
    }

    public void start() {
        running = true;
        fusions = 0;
        nextActionAt = 0;
        deadline = System.currentTimeMillis() + OPEN_GRACE_MS;
        state = "Open the Fusion Box";
        Notify.msg("Fusion macro started. Open the Fusion Box (within 30 s).");
    }

    public void stop(String why) {
        if (running) Notify.msg("Fusion macro: " + why + " (" + fusions + " fusions)");
        running = false;
        state = "Stopped";
    }

    public void tick(Minecraft mc) {
        if (!running || mc.player == null || mc.gameMode == null) return;
        long now = System.currentTimeMillis();

        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            // The menu closes briefly between fusions; give up only if it stays closed.
            if (mc.screen == null && now > deadline) stop("Fusion Box is not open");
            return;
        }
        if (now < nextActionAt) return;

        String title = screen.getTitle().getString();
        Slot target;
        boolean confirm = false;

        if (title.contains("Confirm Fusion")) {
            target = findByName(screen, "Fusion", true);
            confirm = true;
            state = "Confirming";
        } else if (title.contains("Fusion Box")) {
            Slot repeat = findByName(screen, "Repeat Previous Fusion", false);
            if (repeat == null) {
                stop("no 'Repeat Previous Fusion' button, do one fusion by hand first");
                return;
            }
            if (maxFusions > 0 && fusions >= maxFusions) {
                stop("reached the fusion limit");
                return;
            }
            int low = lowestShardCount(mc, repeat.getItem());
            if (low >= 0 && low < minShards) {
                stop("out of shards (" + low + " left)");
                return;
            }
            target = repeat;
            state = "Repeating";
        } else {
            return;
        }

        if (target == null) {
            if (now > deadline) stop("could not find the button in '" + title + "'");
            return;
        }

        mc.gameMode.handleContainerInput(screen.getMenu().containerId, target.index, 0,
                ContainerInput.PICKUP, mc.player);
        if (confirm) fusions++;
        deadline = now + GAP_MS;
        nextActionAt = now + baseDelayMs + rng.nextInt(Math.max(1, baseDelayMs / 2));
    }

    /** Slot whose item name equals (exact) or contains (not exact) the text. */
    private static Slot findByName(AbstractContainerScreen<?> screen, String text, boolean exact) {
        for (Slot s : screen.getMenu().slots) {
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String n = st.getHoverName().getString().trim();
            if (exact ? n.equalsIgnoreCase(text) : n.toLowerCase().contains(text.toLowerCase())) return s;
        }
        return null;
    }

    /** Smallest "Nx Shard" count in the repeat button's lore, or -1 if none was found. */
    private static int lowestShardCount(Minecraft mc, ItemStack stack) {
        List<Component> tip = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
        int low = -1;
        for (Component line : tip) {
            Matcher m = STILL_HAVE.matcher(line.getString());
            while (m.find()) {
                int n = Integer.parseInt(m.group(1).replace(",", ""));
                if (low < 0 || n < low) low = n;
            }
        }
        return low;
    }
}
