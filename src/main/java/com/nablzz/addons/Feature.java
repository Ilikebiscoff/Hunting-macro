package com.nablzz.addons;

import net.minecraft.client.Minecraft;

/**
 * One macro / tool of Nablzz Addons. To add a new one: implement this interface, then register it in
 * {@link NablzzAddons#onInitializeClient()} with {@code Features.register(new MyFeature())}. It then gets its own
 * module in the settings screen (tabs from {@link #buildScreen}), a start/stop button, a dot in the header and
 * is stopped automatically when another feature starts.
 */
public interface Feature {
    /** Short stable id (used for opening the screen on this module). */
    String id();

    /** Name shown in the settings screen. */
    String name();

    /** Register events, commands and HUD elements (called once at game start). */
    void init();

    /** Whether it is running (or waiting to run, e.g. for a fail-safe). */
    boolean isRunning();

    /** One short line for the header pill ("Hunting", "Stopped" ...). */
    String status();

    /** Starts the feature. Print the reason in chat if it cannot start. */
    void start(Minecraft mc);

    void stop(Minecraft mc);

    /** Whether the settings screen shows a Start/Stop button for it. */
    default boolean hasRunButton() {
        return true;
    }

    /** Add the tabs of this feature to the settings screen. */
    void buildScreen(NablzzScreen.Builder ui);

    /** Lines for the report (/nablzz report): the state and settings that explain what this feature is doing. */
    default void report(java.util.function.Consumer<String> out) {
    }

    /** Write the feature's settings to disk (called when the screen closes or a setting changed). */
    void saveSettings();
}
