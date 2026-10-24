package com.nablzz.addons.mudworm;

import com.nablzz.addons.mudworm.Chat;
import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.Etherwarp;
import com.nablzz.addons.mudworm.Macro;
import com.nablzz.addons.mudworm.SpotScanner;
import com.nablzz.addons.mudworm.path.Cell;
import com.nablzz.addons.mudworm.path.WorldCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class Dev {
    private Dev() {
    }

    static void run(Minecraft mc) {
        String text;
        try {
            text = Dev.build(mc);
        }
        catch (Throwable t) {
            StringBuilder sb = new StringBuilder("dev dump failed: " + String.valueOf(t) + "\n");
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append("  at ").append(e).append('\n');
            }
            text = sb.toString();
        }
        Path file = FabricLoader.getInstance().getConfigDir().resolve("nablzz_mudworm_dev.txt");
        try {
            Files.writeString(file, (CharSequence)text, new OpenOption[0]);
        }
        catch (IOException sb) {
            // empty catch block
        }
        try {
            mc.keyboardHandler.setClipboard(text);
        }
        catch (Throwable sb) {
            // empty catch block
        }
        Chat.msg("Dev info copied to the clipboard (paste it into the chat with Claude) and saved to " + String.valueOf(file) + ".");
        int shown = 0;
        for (String line : text.split("\n")) {
            if (!line.startsWith("== ") && !line.startsWith("stage:") && !line.startsWith("running:") && !line.startsWith("last plan:") && !line.startsWith("result:") && !line.startsWith("PROBLEM")) continue;
            Chat.msg("\u00a77" + (String)(line.length() > 230 ? line.substring(0, 230) + "..." : line));
            if (++shown > 14) break;
        }
    }

    static String build(Minecraft mc) {
        ItemStack st;
        int i;
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        LocalPlayer p = mc.player;
        ClientLevel lv = mc.level;
        Macro m = Macro.INSTANCE;
        ArrayList<String> problems = new ArrayList<String>();
        b.append("== Mudworm Macro dev dump ==\n");
        b.append("mod ").append(com.nablzz.addons.Updater.currentVersion()).append("   MC ").append(FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append("   fabric loader ").append(FabricLoader.getInstance().getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
        if (p == null || lv == null) {
            return b.append("not in a world\n").toString();
        }
        b.append("\n== Player ==\n");
        b.append(String.format("pos %.2f %.2f %.2f  block %s  yaw %.2f pitch %.2f\n", p.getX(), p.getY(), p.getZ(), p.blockPosition().toShortString(), Float.valueOf(p.getYRot()), Float.valueOf(p.getXRot())));
        b.append("onGround ").append(p.onGround()).append("  sneaking(player) ").append(p.isShiftKeyDown()).append("  sneakKeyDown ").append(mc.options.keyShift.isDown()).append("  screen ").append(mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()).append('\n');
        b.append("selected hotbar slot ").append(p.getInventory().getSelectedSlot()).append('\n');
        b.append("sidebar:\n");
        List<String> side = SpotScanner.sidebar(mc);
        if (side == null) {
            b.append("  (none)\n");
        } else {
            for (String l : side) {
                b.append("  ").append(l).append('\n');
            }
        }
        if (side == null) {
            problems.add("no sidebar: not in SkyBlock?");
        }
        b.append("\n== Hotbar ==\n");
        int aotv = Macro.aotvSlot(p);
        for (i = 0; i < 9; ++i) {
            st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            b.append(i).append(": ").append(st.getHoverName().getString()).append("  ether=").append(Etherwarp.hasEther(st)).append(i == aotv ? "  <- used for etherwarp" : "").append('\n');
        }
        if (aotv < 0) {
            problems.add("no Aspect of the Void / Aspect of the End with ethermerge==1 found in the hotbar by name+tag (see custom data below)");
            for (i = 0; i < 9; ++i) {
                String n;
                st = p.getInventory().getItem(i);
                String string = n = st.isEmpty() ? "" : st.getHoverName().getString().toLowerCase();
                if (!n.contains("aspect of the")) continue;
                CustomData cd = (CustomData)st.get(DataComponents.CUSTOM_DATA);
                String raw = cd == null ? "(no custom data)" : cd.copyTag().toString();
                b.append("custom data of slot ").append(i).append(": ").append((String)(raw.length() > 900 ? raw.substring(0, 900) + "..." : raw)).append('\n');
            }
        } else {
            ItemStack st2 = p.getInventory().getItem(aotv);
            CustomData cd = (CustomData)st2.get(DataComponents.CUSTOM_DATA);
            String raw = cd == null ? "(no custom data)" : cd.copyTag().toString();
            b.append("etherwarp range used: ").append(String.format("%.1f", Etherwarp.range(st2))).append("   custom data: ").append((String)(raw.length() > 500 ? raw.substring(0, 500) + "..." : raw)).append('\n');
        }
        b.append("\n== Spots ==\n");
        b.append("live spots: ").append(SpotScanner.SPOTS.size()).append("   remembered sites: ").append(SpotScanner.SITES.size()).append("  blacklisted: ").append(SpotScanner.BLACKLIST.size()).append('\n');
        ArrayList<BlockPos> spots = new ArrayList<BlockPos>(SpotScanner.SPOTS.keySet());
        spots.sort(Comparator.comparingDouble(s -> s.distToCenterSqr((Position)p.position())));
        for (int i2 = 0; i2 < Math.min(8, spots.size()); ++i2) {
            BlockPos s2 = (BlockPos)spots.get(i2);
            b.append("  ").append(s2.toShortString()).append(String.format("  %.1f blocks away  strings=%d  %s\n", Math.sqrt(s2.distToCenterSqr((Position)p.position())), SpotScanner.stringsOf(lv, s2), (Math.sqrt(s2.distToCenterSqr((Position)p.position())) > Config.maxTargetDistance || Math.abs((double)s2.getY() - p.getY()) > Config.maxTargetHeight ? "TOO FAR / OTHER LEVEL (ignored)  " : "") + (m.isExposed(s2) ? "clear view possible" : "WALLED IN (no landing with a clear view)")));
        }
        if (spots.isEmpty()) {
            problems.add("no spots known: the macro can only patrol. Stand near a spot and check /mudworm spots");
        }
        b.append("\n== Terrain snapshot ==\n");
        WorldCache cache = m.cache();
        cache.warm(mc, 1500L);
        b.append("columns cached: ").append(cache.columns()).append("   column under you: ").append(cache.has(p.blockPosition())).append('\n');
        if (!cache.has(p.blockPosition())) {
            problems.add("terrain snapshot has no column under you (macro waits on 'Scanning terrain')");
        }
        WorldCache.Reader rd = cache.reader();
        BlockPos fp = p.blockPosition();
        b.append("cells at feet-1 / feet / head: ").append(Dev.flags(rd.at(fp.getX(), fp.getY() - 1, fp.getZ()))).append(" | ").append(Dev.flags(rd.at(fp.getX(), fp.getY(), fp.getZ()))).append(" | ").append(Dev.flags(rd.at(fp.getX(), fp.getY() + 1, fp.getZ()))).append('\n');
        long[] here = rd.targets(p.getBlockX() >> 4, p.getBlockZ() >> 4);
        b.append("etherwarp landing blocks in your chunk: ").append((String)(here == null ? "chunk not cached" : "" + here.length)).append('\n');
        b.append("\n== Look ray right now ==\n");
        Cell.Live live = new Cell.Live((Level)lv);
        Vec3 eye = new Vec3(p.getX(), p.getY() + 1.27, p.getZ());
        double range = aotv >= 0 ? Etherwarp.range(p.getInventory().getItem(aotv)) : (double)Config.baseRange - Config.rangeMargin;
        BlockPos stop = Etherwarp.firstStop(live, eye, Etherwarp.dir(p.getYRot(), p.getXRot()), range, false);
        b.append("sneak eye ").append(String.format("%.2f %.2f %.2f", eye.x, eye.y, eye.z)).append("   ray stops at ").append(stop == null ? "nothing within " + (int)range + " blocks (or unloaded)" : stop.toShortString() + " (" + lv.getBlockState(stop).getBlock().getName().getString() + ", " + Dev.flags(live.at(stop.getX(), stop.getY(), stop.getZ())) + ")").append("   valid etherwarp target: ").append(stop != null && Etherwarp.validTarget(live, stop.getX(), stop.getY(), stop.getZ())).append('\n');
        b.append("\n== Macro ==\n");
        b.append(m.devState(mc, now));
        if (!m.running()) {
            problems.add("macro is not running (press K or /mudworm start)");
        }
        b.append("\n== Planner test (runs the planner now from your position) ==\n");
        b.append(m.devPlanTest(mc));
        b.append("\n== Settings ==\n");
        b.append("baseRange ").append(Config.baseRange).append("  rangeMargin ").append(Config.rangeMargin).append("  etherCost ").append(Config.etherCost).append("  strictRay ").append(Config.strictRay).append("  coneDeg ").append(Config.coneDeg).append("  reach ").append(Config.reach).append('\n');
        b.append("stuck: ").append(Config.unstuckWarp).append(" after ").append(Config.stuckSeconds).append(" s -> /").append(Config.stuckCommand).append("  cacheChunks ").append(Config.cacheChunks).append("  scanY ").append(Config.scanMinY).append("..").append(Config.scanMaxY).append('\n');
        b.append("patrol area x ").append(Config.areaMinX).append("..").append(Config.areaMaxX).append(" z ").append(Config.areaMinZ).append("..").append(Config.areaMaxZ).append(" step ").append(Config.patrolStep).append("  failsafe(foreign teleport) ").append(Config.stopOnForeignTeleport).append('\n');
        if (p.getY() < (double)Config.scanMinY || p.getY() > (double)Config.scanMaxY) {
            problems.add("your Y (" + (int)p.getY() + ") is outside the scanned range " + Config.scanMinY + ".." + Config.scanMaxY);
        }
        b.append("\n== Recent events (oldest first) ==\n");
        for (String l : Chat.recent()) {
            b.append(l).append('\n');
        }
        StringBuilder head = new StringBuilder();
        for (String string : problems) {
            head.append("PROBLEM: ").append(string).append('\n');
        }
        return String.valueOf(head) + b.toString();
    }

    private static String flags(short f) {
        if (f == 0) {
            return "unknown";
        }
        StringBuilder s = new StringBuilder();
        String[] names = new String[]{"LOADED", "AIR", "RAYPASS", "TARGET", "COLL", "LOW", "TALL", "FLUID", "HAZARD", "SLOW", "LEAF"};
        for (int i = 0; i < names.length; ++i) {
            if ((f & 1 << i) == 0) continue;
            s.append(names[i]).append(' ');
        }
        return s.toString().trim();
    }
}

