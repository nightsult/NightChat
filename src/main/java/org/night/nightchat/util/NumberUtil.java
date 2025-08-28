package org.night.nightchat.util;

import java.text.DecimalFormat;

public class NumberUtil {
    private static final DecimalFormat ONE_DEC = new DecimalFormat("#.#");

    public static String formatCompact(double value) {
        double abs = Math.abs(value);
        String suffix;
        double num;
        if (abs >= 1_000_000_000) { num = value / 1_000_000_000.0; suffix = "B"; }
        else if (abs >= 1_000_000) { num = value / 1_000_000.0; suffix = "M"; }
        else if (abs >= 1_000) { num = value / 1_000.0; suffix = "k"; }
        else { return stripTrailingZeros(value); }
        return ONE_DEC.format(num) + suffix;
    }

    public static String stripTrailingZeros(double value) {
        if (value == (long) value) return String.format("%d", (long) value);
        return ONE_DEC.format(value);
    }
}