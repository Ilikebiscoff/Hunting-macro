package com.nablzz.addons;

import net.minecraft.client.Minecraft;

/** The "Avoid list" module of the settings screen: one list for every macro. */
public final class AvoidModule implements Feature {
    @Override
    public String id() {
        return "avoid";
    }

    @Override
    public String name() {
        return "Avoid list";
    }

    @Override
    public boolean hasRunButton() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public String status() {
        return !AvoidPlayers.enabled ? "Off" : AvoidPlayers.LIST.list().size() + " players";
    }

    @Override
    public void init() {
        AvoidPlayers.init();
    }

    @Override
    public void start(Minecraft mc) {
    }

    @Override
    public void stop(Minecraft mc) {
    }

    @Override
    public void saveSettings() {
        Prefs.save();
    }

    @Override
    public void buildScreen(NablzzScreen.Builder ui) {
        ui.tab("Players", "P");
        ui.section("Avoid list (all macros)");
        ui.note("Players you never want to share a lobby with. Glowing Mushrooms leaves for a new lobby, Galatea Mudworm swaps lobby, Miner Zombie Hunting stops. Fusion Machine does not care about the lobby.");
        ui.toggle("Check the tab list", "While a macro runs, look for these names in the tab list every second.", () -> AvoidPlayers.enabled, v -> {
            AvoidPlayers.enabled = v;
            Prefs.save();
        });
        ui.info("Players", "Names on the list.",
                () -> AvoidPlayers.LIST.isEmpty() ? "empty" : String.join(", ", AvoidPlayers.LIST.list()), Ui.MUTED);
        ui.add("Add player", "Type a name and press Add.", "name", "Add", v -> {
            if (!v.isEmpty()) AvoidPlayers.LIST.add(v);
        });
        ui.add("Remove player", "Type a name and press Remove.", "name", "Remove", v -> {
            if (!v.isEmpty()) AvoidPlayers.LIST.remove(v);
        });
        ui.confirmButton("Clear the list", "Removes every name.", "Clear", AvoidPlayers.LIST::clear);
        ui.button("Check this lobby", "Looks for listed players in the tab list now.", "Check", false, () -> {
            String who = AvoidPlayers.LIST.scan(Minecraft.getInstance());
            ui.flash(who == null ? "Nobody from the list is in this lobby." : who + " is in this lobby!");
        });
    }
}
