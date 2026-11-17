package fun.rederaven.cleaner.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side chat formatting: & legacy codes, &#RRGGBB and
 * <gradient:#RRGGBB:#RRGGBB>texto</gradient>.
 * No client resources, protocol mod, Adventure dependency or MiniMessage parser required.
 */
public final class ColoredMessage {
    private ColoredMessage() {}
    private static final Pattern GRADIENT = Pattern.compile(
        "(?is)^<gradient:(#[0-9a-f]{6}):(#[0-9a-f]{6})>");
    private static final String HEX = "0123456789abcdef";
    private static final int[] LEGACY_RGB = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    public static Component render(String template, Map<String, ?> values) {
        String content = template == null ? "" : template;
        if (values != null) {
            for (var entry : values.entrySet()) {
                content = content.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }
        return parse(content);
    }

    public static Component parse(String input) {
        MutableComponent root = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < input.length();) {
            char ch = input.charAt(i);
            if (ch == '&' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                if (code == '#' && i + 8 <= input.length() && isHex(input.substring(i + 2, i + 8))) {
                    flush(root, text, style);
                    style = style.withColor(TextColor.fromRgb(Integer.parseInt(input.substring(i + 2, i + 8), 16)));
                    i += 8;
                    continue;
                }
                int idx = HEX.indexOf(code);
                if (idx >= 0) {
                    flush(root, text, style);
                    // As in vanilla formatting codes, a new named color resets prior decorations.
                    style = Style.EMPTY.withColor(TextColor.fromRgb(LEGACY_RGB[idx]));
                    i += 2;
                    continue;
                }
                if ("lmnokr".indexOf(code) >= 0) {
                    flush(root, text, style);
                    style = switch (code) {
                        case 'l' -> style.withBold(true);
                        case 'm' -> style.withStrikethrough(true);
                        case 'n' -> style.withUnderlined(true);
                        case 'o' -> style.withItalic(true);
                        case 'k' -> style.withObfuscated(true);
                        default -> Style.EMPTY;
                    };
                    i += 2;
                    continue;
                }
                if (code == '&') { text.append('&'); i += 2; continue; }
            }
            if (ch == '<' && input.regionMatches(true, i, "<gradient:", 0, 10)) {
                Matcher m = GRADIENT.matcher(input.substring(i));
                if (m.find()) {
                    int contentStart = i + m.end();
                    int end = input.toLowerCase(java.util.Locale.ROOT).indexOf("</gradient>", contentStart);
                    if (end >= 0) {
                        flush(root, text, style);
                        applyGradient(root, input.substring(contentStart, end),
                            Integer.parseInt(m.group(1).substring(1), 16),
                            Integer.parseInt(m.group(2).substring(1), 16), style);
                        i = end + "</gradient>".length();
                        continue;
                    }
                }
            }
            text.append(ch);
            i++;
        }
        flush(root, text, style);
        return root;
    }

    private static void flush(MutableComponent parent, StringBuilder buffer, Style style) {
        if (!buffer.isEmpty()) {
            parent.append(Component.literal(buffer.toString()).setStyle(style));
            buffer.setLength(0);
        }
    }

    private static boolean isHex(String value) {
        if (value.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            if (HEX.indexOf(Character.toLowerCase(value.charAt(i))) < 0) return false;
        }
        return true;
    }

    private static void applyGradient(MutableComponent root, String content, int from, int to, Style inherited) {
        int len = content.codePointCount(0, content.length());
        int offset = 0;
        for (int i = 0; i < len; i++) {
            int codepoint = content.codePointAt(offset);
            int rgb = mix(from, to, len == 1 ? 0.0 : i / (double) (len - 1));
            root.append(Component.literal(new String(Character.toChars(codepoint)))
                .setStyle(inherited.withColor(TextColor.fromRgb(rgb))));
            offset += Character.charCount(codepoint);
        }
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
