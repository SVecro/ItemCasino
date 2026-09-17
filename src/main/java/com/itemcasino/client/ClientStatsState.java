package com.itemcasino.client;

import com.itemcasino.network.s2c.S2CStats;

import javax.annotation.Nullable;

/** The last stats the server sent. Display only; cleared on disconnect with the rest. */
public final class ClientStatsState {

    @Nullable private static S2CStats latest;

    private ClientStatsState() {}

    public static void accept(S2CStats stats) { latest = stats; }

    @Nullable public static S2CStats latest() { return latest; }

    public static void reset() { latest = null; }
}
