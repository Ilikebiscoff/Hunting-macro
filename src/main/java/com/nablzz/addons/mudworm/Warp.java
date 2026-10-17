package com.nablzz.addons.mudworm;

import com.nablzz.addons.minerzombie.HumanAim;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Aspect of the Void shift + right-click teleport. The landing blocks come from a fan of rays cast from the eyes, so
 * every candidate is a block with an unobstructed line to the eyes by construction (no teleport through walls). The
 * aim point is the point the ray actually hit, a candidate must survive a small camera sway, and the crosshair is
 * checked against the block again right before the click.
 */
final class Warp {
    enum Result { BUSY, DONE, FAILED }

    /** A block to land on and the exact point to look at. */
    record Pick(BlockPos block, Vec3 aim) {}

    private int step;
    private Pick pick;
    private long at;
    private int swapTicks;
    private Vec3 from;
    private boolean refused;
    private String why = "";
    private final Set<BlockPos> banned = new HashSet<>();

    boolean active() {
        return step != 0;
    }

    Pick current() {
        return pick;
    }

    String why() {
        return why;
    }

    void forget() {
        banned.clear();
        step = 0;
    }

    void ban(BlockPos b) {
        banned.add(b);
    }

    boolean isBanned(BlockPos b) {
        return banned.contains(b);
    }

    /** The server said "There are blocks in the way": this teleport is dead. */
    void serverRefused() {
        if (step != 0) refused = true;
    }

    void begin(Pick p, int slot, LocalPlayer pl, long now) {
        pick = p;
        step = 1;
        at = now;
        swapTicks = 4;
        refused = false;
        why = "";
        if (pl.getInventory().getSelectedSlot() != slot) pl.getInventory().setSelectedSlot(slot);
    }

    void abort(Minecraft mc) {
        step = 0;
        sneak(mc, false);
    }

    // ---- looking around ------------------------------------------------------------------------------------

    /** A block you can land on: a solid, hazard-free floor with two free blocks above it and no fluid. */
    static boolean landable(Nav4World w, BlockPos b) {
        int f = w.flags(b.getX(), b.getY(), b.getZ());
        if ((f & (Nav4.SOLID | Nav4.LOW)) == 0 || (f & (Nav4.HAZARD | Nav4.LAVA | Nav4.UNKNOWN | Nav4.TALLX)) != 0) return false;
        int mask = Nav4.SOLID | Nav4.LOW | Nav4.HAZARD | Nav4.LAVA | Nav4.WATER | Nav4.UNKNOWN;
        return (w.flags(b.getX(), b.getY() + 1, b.getZ()) & mask) == 0 && (w.flags(b.getX(), b.getY() + 2, b.getZ()) & mask) == 0;
    }

    private static Vec3 dirOf(double yaw, double pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double cp = Math.cos(pr);
        return new Vec3(-Math.sin(yr) * cp, -Math.sin(pr), Math.cos(yr) * cp);
    }

    private static BlockPos firstBlock(Minecraft mc, LocalPlayer p, Vec3 eye, Vec3 dir, double reach) {
        BlockHitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.BLOCK ? h.getBlockPos() : null;
    }

    /**
     * Looks in every direction. Returns the landing blocks that can be seen and hit cleanly (also when the camera
     * sways by about half a degree), each with the point to aim at.
     */
    static List<Pick> scan(Minecraft mc, LocalPlayer p, int range, double minHop, Predicate<BlockPos> banned) {
        Vec3 eye = p.getEyePosition();
        Nav4World w = new Nav4World(mc.level);
        Map<BlockPos, Vec3> hits = new HashMap<>();
        for (int pitch = -85; pitch <= 70; pitch += 4) {
            double cp = Math.cos(Math.toRadians(pitch));
            int yawStep = Math.max(3, (int) Math.round(4.0 / Math.max(0.2, cp)));
            for (int yaw = 0; yaw < 360; yaw += yawStep) {
                Vec3 dir = dirOf(yaw, pitch);
                BlockHitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (h.getType() != HitResult.Type.BLOCK) continue;
                BlockPos b = h.getBlockPos();
                if (banned.test(b) || !landable(w, b) || eye.distanceTo(b.getCenter()) < minHop) continue;
                Vec3 loc = h.getLocation(), mid = b.getCenter();
                Vec3 old = hits.get(b);
                if (old == null || loc.distanceToSqr(mid) < old.distanceToSqr(mid)) hits.put(b, loc); // the hit nearest the block's middle
            }
        }
        List<Pick> out = new ArrayList<>();
        for (var e : hits.entrySet()) {
            Vec3 d = e.getValue().subtract(eye);
            double yaw = Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0;
            double pitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            int ok = 0;
            double[][] sway = {{0.6, 0}, {-0.6, 0}, {0, 0.6}, {0, -0.6}};
            for (double[] s : sway) {
                if (e.getKey().equals(firstBlock(mc, p, eye, dirOf(yaw + s[0], pitch + s[1]), range + 2.0))) ok++;
            }
            if (ok >= 3) out.add(new Pick(e.getKey(), e.getValue()));
        }
        return out;
    }

    // ---- the click -----------------------------------------------------------------------------------------

    /** Whether the crosshair is on the landing block (a clip at teleport range; mc.hitResult only reaches ~4.5 blocks). */
    private boolean crosshairOn(Minecraft mc, LocalPlayer p, int range) {
        return pick.block().equals(firstBlock(mc, p, p.getEyePosition(), p.getLookAngle(), range + 2.0));
    }

    Result tick(Minecraft mc, LocalPlayer p, long now, HumanAim aim, int range, boolean debug) {
        if (step == 0) return Result.FAILED;
        if (refused) return fail(mc, "the server refused it (blocks in the way)", true);
        Vec3 target = pick.aim();
        switch (step) {
            case 1 -> { // look at the block
                float[] a = angles(p.getEyePosition(), target);
                aim.setTarget(a[0], a[1], 900f, 1.3f);
                if (swapTicks > 0) swapTicks--;
                sneak(mc, true);
                boolean onIt = crosshairOn(mc, p, range);
                if (swapTicks == 0 && aim.settled(p, 2f) && onIt) {
                    step = 2;
                    at = now;
                } else if (now - at > 2500) {
                    return fail(mc, onIt ? "the camera never settled on the block" : "the crosshair is not on the block (something in the way)", !onIt);
                }
            }
            case 2 -> { // shift registers after a couple of ticks, then the click; the crosshair is checked again first
                sneak(mc, true);
                float[] a = angles(p.getEyePosition(), target);
                aim.setTarget(a[0], a[1], 900f, 1.3f);
                if (now - at > 1200 && !p.isShiftKeyDown()) return fail(mc, "the player never crouched (shift not registering)", false);
                if (now - at > 2500) return fail(mc, "the crosshair kept drifting off the block", true);
                if (now - at >= 130 && p.isShiftKeyDown() && crosshairOn(mc, p, range)) {
                    from = p.position();
                    if (debug) com.nablzz.addons.Notify.msg(String.format(Locale.ROOT, "Teleport: shift + right-click toward %d %d %d (%.0f blocks)",
                            pick.block().getX(), pick.block().getY(), pick.block().getZ(), p.getEyePosition().distanceTo(pick.block().getCenter())));
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    step = 3;
                    at = now;
                }
            }
            default -> { // wait for the teleport
                if (p.position().distanceTo(from) > 3.0) {
                    step = 0;
                    sneak(mc, false);
                    return Result.DONE;
                }
                if (now - at > 900) return fail(mc, "shift + right-click was sent but you did not move (Ether Transmission upgrade missing, or blocked)", true);
            }
        }
        return Result.BUSY;
    }

    private Result fail(Minecraft mc, String reason, boolean blame) {
        why = reason;
        if (blame && pick != null) banned.add(pick.block());
        step = 0;
        sneak(mc, false);
        return Result.FAILED;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
        return new float[]{yaw, pitch};
    }

    /**
     * Sneak on/off. A plain key is just set; with "toggle sneak" on, the key flips its state each time it is pressed,
     * so press it only when the state is not what we want.
     */
    static void sneak(Minecraft mc, boolean want) {
        KeyMapping k = mc.options.keyShift;
        k.setDown(want);
        if (k.isDown() != want) k.setDown(true);
    }
}
