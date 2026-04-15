package main.testmasterbot.util;

import java.util.Locale;

public final class TextUtils {
    private TextUtils() {
    }

    public static String shorten(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    public static String trimTelegramText(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 4000) {
            return text;
        }
        return text.substring(0, 3990) + "\n...";
    }

    public static String trimCaption(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 1000) {
            return text;
        }
        return text.substring(0, 990) + "\n...";
    }

    public static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    public static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    public static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.US, "%.3f", value);
    }

    public static String formatPercent(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
