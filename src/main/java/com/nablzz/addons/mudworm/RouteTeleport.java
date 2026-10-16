package com.nablzz.addons.mudworm;

import com.nablzz.addons.Notify;
import com.nablzz.addons.minerzombie.HumanAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Follows a route waypoint by teleporting: face it and spam the plain Aspect of the Void right-click
 * (Instant Transmission, no shift) for the last stretch (a few blocks up to the shift-etherwarp distance).
 * Longer hops and going up are the shift-etherwarp of the macro.
 */
final class RouteTeleport {
    enum Result {
        /** Not usable right now: walk to the waypoint instead. */
        WALK,
        /** Facing the waypoint / clicking: the macro should hold still this tick. */
        TELEPORT
    }

    volatile boolean enabled = true;
    volatile int delayMs = 250;

    private long next, offUntil;
    private Vec3 last;
    private int blocked;
    private boolean clicked;

    long offUntilMs() {
        return offUntil;
    }

    /** True once per right-click that was sent (for the teleport counter). */
    boolean takeClick() {
        boolean c = clicked;
        clicked = false;
        return c;
    }

    Result step(Minecraft mc, LocalPlayer p, long now, HumanAim aim, BlockPos goal, double hd, Learned learned) {
        if (!enabled || now < offUntil) return Result.WALK;
        double dy = goal.getY() + 1.0 - p.getY();
        if (hd < 5.0 && Math.abs(dy) < 4.0) return Result.WALK; // close: a teleport would overshoot, walk the rest
        if (learned.badTp(p.blockPosition(), goal)) return Result.WALK; // clicking toward this goal from here never worked

        Vec3 eye = p.getEyePosition();
        Vec3 to = goal.getCenter();
        double dx = to.x - eye.x, dyy = to.y - eye.y, dz = to.z - eye.z;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dyy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
        // the teleport stops at walls: only click when the way (feet and head height) is clear and the landing is not liquid
        double reach = Math.min(8.0, eye.distanceTo(to));
        Vec3 dir = to.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(reach));
        Vec3 feet = p.position().add(0, 0.3, 0);
        if (blocked(mc, p, eye, end) || blocked(mc, p, feet, feet.add(dir.scale(reach)))
                || !mc.level.getFluidState(BlockPos.containing(end)).isEmpty()) {
            return Result.WALK;
        }
        aim.setTarget(yaw, pitch, 900f, 1.3f);

        if (now >= next && aim.settled(p, 3f)) {
            if (last != null && p.position().distanceTo(last) < 1.0) {
                if (++blocked >= 3) { // the clicks do nothing: wall in the way, no mana, or no upgrade
                    blocked = 0;
                    last = null;
                    offUntil = now + 8000;
                    learned.addBadTp(p.blockPosition(), goal);
                    Notify.msg("Route teleport is not moving you (blocked or no mana) - walking for 8 s.");
                    return Result.WALK;
                }
            } else {
                blocked = 0;
            }
            last = p.position();
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            clicked = true;
            next = now + Math.max(120, delayMs);
        }
        return Result.TELEPORT;
    }

    private static boolean blocked(Minecraft mc, LocalPlayer p, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS;
    }
}
