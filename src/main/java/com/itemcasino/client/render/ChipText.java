package com.itemcasino.client.render;

import com.itemcasino.core.chips.Chips;
import net.minecraft.client.resources.language.I18n;

/** Chip amounts as a player reads them: "1 234,5" in French, "1,234.5" in English. */
public final class ChipText {

    private ChipText() {}

    public static String format(long cents) {
        String plain = Chips.format(cents);
        String whole = plain;
        String fraction = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) {
            whole = plain.substring(0, dot);
            fraction = plain.substring(dot + 1);
        }
        String group = I18n.get("itemcasino.thousands_separator");
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) grouped.append(group);
            grouped.append(whole.charAt(i));
        }
        return fraction.isEmpty() ? grouped.toString()
                : grouped + I18n.get("itemcasino.decimal_separator") + fraction;
    }
}
