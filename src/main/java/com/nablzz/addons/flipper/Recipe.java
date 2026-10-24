package com.nablzz.addons.flipper;

import java.util.Map;

/** One crafting recipe: {@code in} (item id to total amount over the 3x3 grid) makes {@code outCount} of {@code out}. */
public record Recipe(String out, int outCount, Map<String, Integer> in, String note) {}
