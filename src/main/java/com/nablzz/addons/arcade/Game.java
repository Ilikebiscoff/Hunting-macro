package com.nablzz.addons.arcade;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** One mini game. All coordinates are relative to the play field (0,0 = its top left corner). */
interface Game {
    String name();

    String blurb();

    /** Tells the game how big the field is (called every frame before {@link #update}). */
    void size(int w, int h);

    void reset();

    void update(float dt);

    void render(GuiGraphicsExtractor g, int x, int y, int mx, int my);

    boolean click(double mx, double my, int button);

    boolean key(int key);

    /** A key was let go (games that move while a key is held). */
    default void keyUp(int key) {
    }

    /** What {@link #score()} is called in the header ("Score", "Chips"). */
    default String scoreName() {
        return "Score";
    }

    /** Score of the current round (the best one is kept between sessions). */
    int score();

    /** One line under the field: controls or the state. */
    String status();
}
