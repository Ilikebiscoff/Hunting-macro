package com.nablzz.addons.mudworm;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public class HumanAim {
    private final Random rand = new Random();
    private final float[] ph = new float[6];
    private float tYaw;
    private float tPitch;
    private float offYaw;
    private float offPitch;
    private float velYaw;
    private float velPitch;
    private float pxYaw;
    private float pxPitch;
    private float omega = 11.0f;
    private float zeta = 0.9f;
    private float maxSpeed = 1000.0f;
    private float stiffness = 1.0f;
    private double time;
    private double holdUntil;
    private long lastNs;
    private boolean exact;

    public HumanAim() {
        for (int i = 0; i < this.ph.length; ++i) {
            this.ph[i] = this.rand.nextFloat() * 6.2832f;
        }
        this.retarget();
    }

    public void reset() {
        this.velPitch = 0.0f;
        this.velYaw = 0.0f;
        this.pxPitch = 0.0f;
        this.pxYaw = 0.0f;
        this.lastNs = 0L;
    }

    public void retarget() {
        this.offYaw = (float)this.rand.nextGaussian() * 0.35f;
        this.offPitch = (float)this.rand.nextGaussian() * 0.25f;
        this.omega = 9.0f + this.rand.nextFloat() * 6.0f;
        this.zeta = 0.78f + this.rand.nextFloat() * 0.22f;
        this.holdUntil = this.time + 0.09 + (double)this.rand.nextFloat() * 0.13;
    }

    public void retargetFast() {
        this.offYaw = (float)this.rand.nextGaussian() * 0.12f;
        this.offPitch = (float)this.rand.nextGaussian() * 0.1f;
        this.omega = 24.0f + this.rand.nextFloat() * 6.0f;
        this.zeta = 0.9f;
        this.holdUntil = this.time + 0.02;
    }

    public void precise() {
        this.offPitch = 0.0f;
        this.offYaw = 0.0f;
        this.omega = 34.0f;
        this.zeta = 1.0f;
        this.holdUntil = this.time + 0.03;
        this.exact = true;
    }

    public void loose() {
        this.exact = false;
    }

    public void setTarget(float yaw, float pitch) {
        this.setTarget(yaw, pitch, 1000.0f, 1.0f);
    }

    public void setTarget(float yaw, float pitch, float maxSpeed, float stiffness) {
        this.tYaw = yaw;
        this.tPitch = pitch;
        this.maxSpeed = maxSpeed;
        this.stiffness = stiffness;
    }

    public boolean settled(LocalPlayer p, float tol) {
        float ey = Mth.wrapDegrees((float)(this.tYaw - p.getYRot()));
        float ep = this.tPitch - p.getXRot();
        return this.time >= this.holdUntil && Math.hypot(ey, ep) <= (double)tol && Math.hypot(this.velYaw, this.velPitch) < 40.0;
    }

    public void frame(Minecraft mc, LocalPlayer p) {
        long now = System.nanoTime();
        if (this.lastNs == 0L) {
            this.lastNs = now;
        }
        float dt = Mth.clamp((float)((float)(now - this.lastNs) / 1.0E9f), (float)0.001f, (float)0.033f);
        this.lastNs = now;
        this.time += (double)dt;
        float drift = this.exact ? 0.0f : 1.0f;
        float wantYaw = this.tYaw + this.offYaw + drift * (0.35f * (float)Math.sin(this.time * 0.9 + (double)this.ph[0]) + 0.15f * (float)Math.sin(this.time * 2.3 + (double)this.ph[1]));
        float wantPitch = this.tPitch + this.offPitch + drift * (0.22f * (float)Math.sin(this.time * 0.7 + (double)this.ph[2]) + 0.1f * (float)Math.sin(this.time * 1.9 + (double)this.ph[3]));
        float errYaw = Mth.wrapDegrees((float)(wantYaw - p.getYRot()));
        float errPitch = wantPitch - p.getXRot();
        if (this.time < this.holdUntil) {
            float decay = (float)Math.exp(-12.0f * dt);
            this.velYaw *= decay;
            this.velPitch *= decay;
        } else {
            float max;
            float w = this.omega * this.stiffness;
            float zeta = this.maxSpeed < 500.0f ? Math.max(this.zeta, 1.15f) : this.zeta;
            float wy = w;
            float wp = w * 0.85f;
            this.velYaw += (wy * wy * errYaw - 2.0f * zeta * wy * this.velYaw) * dt;
            this.velPitch += (wp * wp * errPitch - 2.0f * zeta * wp * this.velPitch) * dt;
            float sp = (float)Math.hypot(this.velYaw, this.velPitch);
            if (sp > (max = this.maxSpeed)) {
                this.velYaw *= max / sp;
                this.velPitch *= max / sp;
            }
        }
        float dYaw = this.velYaw * dt + drift * 0.015f * (float)Math.sin(this.time * 47.0 + (double)this.ph[4]);
        float dPitch = this.velPitch * dt + drift * 0.01f * (float)Math.sin(this.time * 53.0 + (double)this.ph[5]);
        double sens = (Double)mc.options.sensitivity().get();
        double f = sens * 0.6 + 0.2;
        float e = (float)(f * f * f * 8.0);
        float step = e * 0.15f;
        float ty = dYaw / step + this.pxYaw;
        float tp = dPitch / step + this.pxPitch;
        int wy = Math.round(ty);
        int wp = Math.round(tp);
        this.pxYaw = ty - (float)wy;
        this.pxPitch = tp - (float)wp;
        if (wy != 0 || wp != 0) {
            p.turn((double)((float)wy * e), (double)((float)wp * e));
        }
    }
}

