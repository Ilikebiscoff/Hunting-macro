package com.nablzz.addons.mudworm;

import com.nablzz.addons.Notify;
import com.nablzz.addons.minerzombie.HumanAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Follows a route waypoint by teleporting: face it and spam the plain Aspect of the Void right-click
 * (Instant Transmission, no shift). Going up is left to the shift-etherwarp of the macro.
 */
final class RouteTeleport {
    enum Result {
        /** Not usable right now: walk to the waypoint instead. */
        WALK,
        /** The waypoint is well above us: the macro should etherwarp up. */
        UP,
        /** Facing the waypoint / clicking: the macro should hold still this tick. */
        TELEPORT
    }

    volatile boolean enabled = true;
    volatile int delayMs = 250;

    private long next, offUntil;
    private Vec3 last;
    private int blocked;
    private boolean clicked;

    /** True once per right-click that was sent (for the teleport counter). */
    boolean takeClick() {
        boolean c = clicked;
        clicked = false;
        return c;
    }

    Result step(Minecraft mc, LocalPlayer p, long now, HumanAim aim, BlockPos goal, double hd) {
        if (!enabled || now < offUntil) return Result.WALK;
        double dy = goal.getY() + 1.0 - p.getY();
        if (dy > 3.0) return Result.UP;
        if (hd < 10.0 && Math.abs(dy) < 4.0) return Result.WALK; // close: a teleport would overshoot, walk the rest

        Vec3 eye = p.getEyePosition();
        Vec3 to = goal.getCenter();
        double dx = to.x - eye.x, dyy = to.y - eye.y, dz = to.z - eye.z;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dyy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
        aim.setTarget(yaw, pitch, 900f, 1.3f);

        if (now >= next && aim.settled(p, 3f)) {
            if (last != null && p.position().distanceTo(last) < 1.0) {
                if (++blocked >= 3) { // the clicks do nothing: wall in the way, no mana, or no upgrade
                    blocked = 0;
                    last = null;
                    offUntil = now + 8000;
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
}
