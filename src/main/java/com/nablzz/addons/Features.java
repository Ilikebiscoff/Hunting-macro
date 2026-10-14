package com.nablzz.addons;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The list of registered features, in the order they show up in the settings screen. */
public final class Features {
    private Features() {}

    private static final List<Feature> ALL = new ArrayList<>();

    public static void register(Feature f) {
        ALL.add(f);
        f.init();
    }

    public static List<Feature> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static Feature byId(String id) {
        for (Feature f : ALL) if (f.id().equals(id)) return f;
        return null;
    }

    /** Index of a feature in the list, or -1. */
    public static int indexOf(String id) {
        for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).id().equals(id)) return i;
        return -1;
    }

    /** Called by a feature just before it starts: every other running feature is stopped (only one at a time). */
    public static void starting(String id, Minecraft mc) {
        for (Feature f : ALL) if (!f.id().equals(id) && f.isRunning()) f.stop(mc);
    }

    public static void stopAll(Minecraft mc) {
        for (Feature f : ALL) f.stop(mc);
    }

    public static void saveAll() {
        for (Feature f : ALL) f.saveSettings();
    }
}
