package com.nablzz.addons.arcade;

/** The play-money chips of the casino games. Not real money, and not connected to anything in SkyBlock. */
final class Chips {
    private Chips() {}

    private static final String KEY = "casino.chips";
    private static int balance = -1;

    static int balance() {
        if (balance < 0) balance = Math.max(0, Scores.getInt(KEY, 1000));
        return balance;
    }

    static void add(int d) {
        balance = Math.max(0, balance() + d);
        Scores.putInt(KEY, balance);
    }

    static boolean canBet(int bet) {
        return bet > 0 && balance() >= bet;
    }

    /** Free chips for a player who went broke. */
    static void refill() {
        balance = 500;
        Scores.putInt(KEY, balance);
    }
}
