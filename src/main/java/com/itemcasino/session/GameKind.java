package com.itemcasino.session;

import com.itemcasino.registry.CasinoMenus;
import net.minecraft.world.inventory.MenuType;

import java.util.function.Function;

/** The three games, so a pocket device can carry any of them without three of everything. */
public enum GameKind {
    UPGRADER("container.itemcasino.pocket_upgrader", UpgraderSession::new),
    DICE("container.itemcasino.pocket_dice", DiceSession::new),
    BLACKJACK("container.itemcasino.pocket_blackjack", BlackjackSession::new);

    private final String titleKey;
    private final Function<SessionHost, CasinoSession> factory;

    GameKind(String titleKey, Function<SessionHost, CasinoSession> factory) {
        this.titleKey = titleKey;
        this.factory = factory;
    }

    public String titleKey() { return titleKey; }

    public CasinoSession newSession(SessionHost host) { return factory.apply(host); }

    public MenuType<?> menuType() {
        return switch (this) {
            case UPGRADER -> CasinoMenus.UPGRADER.get();
            case DICE -> CasinoMenus.DICE.get();
            case BLACKJACK -> CasinoMenus.BLACKJACK_TABLE.get();
        };
    }

    public static GameKind byId(int id) {
        GameKind[] values = values();
        return (id < 0 || id >= values.length) ? UPGRADER : values[id];
    }
}
