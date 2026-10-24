package ru.voidpe.voidtab;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цвета: &amp;-коды, &#RRGGBB и градиенты вида
 * &lt;gradient:#FF7A00:#FFE259&gt;VoidPE&lt;/gradient&gt;.
 * На выходе — строка с §-кодами (Geyser сам переведёт их для Bedrock).
 */
public final class TextUtil {

    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern GRADIENT =
            Pattern.compile("<gradient:#([0-9A-Fa-f]{6}):#([0-9A-Fa-f]{6})>(.*?)</gradient>", Pattern.DOTALL);

    private TextUtil() {
    }

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        String out = applyGradients(text);
        Matcher m = HEX.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                rep.append('§').append(c);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }

    private static String applyGradients(String text) {
        Matcher m = GRADIENT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int[] from = parse(m.group(1));
            int[] to = parse(m.group(2));
            String body = m.group(3);
            // Жирность: &l перед <gradient> или в начале его текста. Цветовые коды
            // сбрасывают форматирование, поэтому §l подмешиваем после каждого цвета.
            boolean bold = false;
            if (body.startsWith("&l") || body.startsWith("§l")) {
                bold = true;
                body = body.substring(2);
            } else if (sb.toString().endsWith("&l") || sb.toString().endsWith("§l")) {
                bold = true;
                sb.setLength(sb.length() - 2);
            }
            StringBuilder grad = new StringBuilder();
            int len = Math.max(1, body.length());
            for (int i = 0; i < body.length(); i++) {
                double t = len == 1 ? 0 : (double) i / (len - 1);
                int r = (int) Math.round(from[0] + (to[0] - from[0]) * t);
                int g = (int) Math.round(from[1] + (to[1] - from[1]) * t);
                int b = (int) Math.round(from[2] + (to[2] - from[2]) * t);
                grad.append(String.format("&#%02X%02X%02X", r, g, b));
                if (bold) {
                    grad.append("§l");
                }
                grad.append(body.charAt(i));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(grad.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int[] parse(String hex) {
        return new int[]{
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    /** Убрать цветовые коды (для подсчёта видимой длины и т.п.). */
    public static String strip(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orx]", "");
    }
}
