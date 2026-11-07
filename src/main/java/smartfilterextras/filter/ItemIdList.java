package smartfilterextras.filter;

import dev.ftb.mods.ftbfiltersystem.api.FilterException;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text handling for {@link ItemListFilter} arguments and for pasted text. Kept free of registry access so it can
 * be tested on its own; entries with components ({@code id[...]}) are split out whole and parsed by the caller.
 */
public final class ItemIdList {
    /**
     * Upper bound on entries in one list. For plain IDs this keeps a list well below FFS's 32767-character limit for
     * a whole filter; entries with components can be much longer, so the config screen also checks the length.
     */
    public static final int MAX_ENTRIES = 256;

    /** FFS sends and stores a whole filter as one string of at most this many characters. */
    public static final int MAX_FILTER_CHARS = 32767;

    /** {@code item(...)} or {@code items(...)} calls, optionally namespaced, e.g. {@code smart_filter_extras:items(a,b)}. */
    private static final Pattern ITEM_CALL = Pattern.compile("(?<![\\w:])(?:[a-z0-9_.-]+:)?items?\\(([^()]*)\\)", Pattern.CASE_INSENSITIVE);

    private ItemIdList() {
    }

    /**
     * Parses a filter argument such as {@code "minecraft:stone, dirt, minecraft:potion[...]"} into entries: trimmed,
     * empty entries skipped, default namespace {@code minecraft}, duplicates dropped (first position wins). Plain
     * IDs are normalised; the component part of {@code id[...]} entries is kept as written.
     *
     * @throws FilterException if an ID is not a valid resource location, or there are too many entries
     */
    public static List<String> parseEntries(String str) {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : split(str, false)) {
            int bracket = entry.indexOf('[');
            String idText = (bracket < 0 ? entry : entry.substring(0, bracket)).trim();
            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null) {
                throw new FilterException("invalid item ID: " + idText);
            }
            entries.add(bracket < 0 ? id.toString() : id + entry.substring(bracket).trim());
            if (entries.size() > MAX_ENTRIES) {
                throw new FilterException("too many items in one list (maximum " + MAX_ENTRIES + ")");
            }
        }
        return new ArrayList<>(entries);
    }

    public static String join(List<String> entries) {
        return String.join(",", entries);
    }

    /**
     * Percent-encodes the characters FFS's filter parser treats specially ({@code ( ) \}) plus {@code %} itself.
     * FFS removes one level of backslash escapes at every level of filter nesting, so backslash escaping can't
     * survive {@code and(...)} / {@code or(...)}; percent-encoding means nothing to FFS and survives any depth.
     */
    public static String encode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '%' -> out.append("%25");
                case '(' -> out.append("%28");
                case ')' -> out.append("%29");
                case '\\' -> out.append("%5C");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Reverses {@link #encode(String)}; anything that isn't one of its four sequences is left as it is. */
    public static String decode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length()) {
                String code = text.substring(i + 1, i + 3).toUpperCase(Locale.ROOT);
                String decoded = switch (code) {
                    case "25" -> "%";
                    case "28" -> "(";
                    case "29" -> ")";
                    case "5C" -> "\\";
                    default -> null;
                };
                if (decoded != null) {
                    out.append(decoded);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Pulls item IDs out of free text, for pasting: a plain list separated by commas, semicolons, spaces or new
     * lines, or a filter string, in which case only the arguments of {@code item(...)} / {@code items(...)} are
     * used. IDs are lower-cased; tags ({@code #...}), entries with components and anything that isn't a valid ID
     * are skipped.
     */
    public static List<ResourceLocation> extractIds(String text) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (String entry : extractEntries(text)) {
            if (entry.indexOf('[') < 0) {
                ids.add(ResourceLocation.parse(entry));
            }
        }
        return ids;
    }

    /**
     * Like {@link #extractIds(String)}, but also keeps entries with components ({@code id[...]}) whole, as written
     * apart from the lower-cased ID. Order is kept and duplicates are dropped.
     */
    public static List<String> extractEntries(String text) {
        String source = text;
        Matcher calls = ITEM_CALL.matcher(text);
        StringBuilder arguments = new StringBuilder();
        while (calls.find()) {
            arguments.append(calls.group(1)).append(',');
        }
        if (!arguments.isEmpty()) {
            source = arguments.toString();
        }
        Set<String> entries = new LinkedHashSet<>();
        for (String token : split(source, true)) {
            String t = token;
            if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
                t = t.substring(1, t.length() - 1).trim();
            }
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int bracket = t.indexOf('[');
            String idText = (bracket < 0 ? t : t.substring(0, bracket)).toLowerCase(Locale.ROOT);
            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null || bracket >= 0 && !t.endsWith("]")) {
                continue;
            }
            entries.add(bracket < 0 ? id.toString() : id + t.substring(bracket));
        }
        return new ArrayList<>(entries);
    }

    /**
     * Splits on commas outside brackets, braces and quoted strings (quotes only count inside brackets/braces, so a
     * quoted plain ID stays one token). With {@code loose}, semicolons and whitespace also separate entries.
     */
    static List<String> split(String str, boolean loose) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < str.length()) {
                    current.append(str.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '"' || c == '\'') && depth > 0) {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
            boolean separator = c == ',' || loose && (c == ';' || Character.isWhitespace(c));
            if (separator && depth == 0) {
                addTrimmed(out, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addTrimmed(out, current);
        return out;
    }

    private static void addTrimmed(List<String> out, StringBuilder entry) {
        String s = entry.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }
}
