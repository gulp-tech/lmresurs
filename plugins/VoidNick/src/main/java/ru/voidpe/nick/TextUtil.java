package ru.voidpe.nick;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Цвета: &a, &#RRGGBB и &x&F&F&0&0&0&0. Ничего не выдумываем — только то, что понимает Bukkit. */
final class TextUtil {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private TextUtil() {
    }

    static String color(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char digit : hex.toCharArray()) {
                replacement.append('&').append(digit);
            }
            matcher.appendReplacement(out, replacement.toString());
        }
        matcher.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    static String plain(String text) {
        return ChatColor.stripColor(color(text));
    }
}
