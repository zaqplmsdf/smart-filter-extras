package smartfilterextras.filter;

import dev.ftb.mods.ftbfiltersystem.api.FilterException;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** The comma-separated tag list argument shared by the block tag and item tag filters. */
public final class TagListArgument {
    public static final int MAX_TAGS = 64;

    private TagListArgument() {
    }

    /**
     * Accepts {@code minecraft:logs}, {@code #minecraft:logs} and {@code logs} (default namespace), ignoring case
     * and surrounding whitespace.
     *
     * @return the tag ID, or {@code null} if the text isn't a valid resource location
     */
    @Nullable
    public static ResourceLocation parseTagId(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    /** Comma-separated tag IDs; at least one, at most {@link #MAX_TAGS}, duplicates dropped. */
    public static <T> List<TagKey<T>> parse(ResourceKey<? extends Registry<T>> registry, String str, String kind) {
        Set<TagKey<T>> tags = new LinkedHashSet<>();
        for (String part : str.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            ResourceLocation id = parseTagId(part);
            if (id == null) {
                throw new FilterException("invalid " + kind + " tag: " + part.trim());
            }
            tags.add(TagKey.create(registry, id));
        }
        if (tags.isEmpty()) {
            throw new FilterException(kind + " tag filter needs at least one tag");
        }
        if (tags.size() > MAX_TAGS) {
            throw new FilterException("too many " + kind + " tags (maximum " + MAX_TAGS + ")");
        }
        return List.copyOf(tags);
    }

    public static <T> String join(List<TagKey<T>> tags) {
        return tags.stream().map(tag -> tag.location().toString()).collect(Collectors.joining(","));
    }

    /** {@code tag(...)}, {@code item_tag(...)} or {@code block_tag(...)} calls, optionally namespaced. */
    private static final Pattern TAG_CALL = Pattern.compile("(?<![\\w:])(?:[a-z0-9_.-]+:)?(?:item_|block_)?tag\\(([^()]*)\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Pulls tag IDs out of pasted text, in order, without duplicates. Filter strings work: only the arguments of
     * {@code item_tag(...)} / {@code block_tag(...)} calls are used (FFS's own and this mod's). Otherwise the text
     * is a list separated by commas, semicolons, spaces or new lines, and only entries that look like tag IDs are
     * taken ({@code #logs} or {@code minecraft:logs}), so pasting ordinary words doesn't tick made-up tags.
     */
    public static List<ResourceLocation> extractTagIds(String text) {
        Matcher calls = TAG_CALL.matcher(text);
        StringBuilder arguments = new StringBuilder();
        while (calls.find()) {
            arguments.append(calls.group(1)).append(',');
        }
        boolean fromCalls = !arguments.isEmpty();
        String source = fromCalls ? arguments.toString() : text;
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (String token : source.split("[,;\\s]+")) {
            String t = token.trim();
            if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
                t = t.substring(1, t.length() - 1).trim();
            }
            if (t.isEmpty() || !fromCalls && !t.startsWith("#") && t.indexOf(':') < 0) {
                continue;
            }
            ResourceLocation id = parseTagId(t);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** "#minecraft:logs", or "#minecraft:logs +2 more" for several tags. */
    public static <T> Component summary(List<TagKey<T>> tags) {
        MutableComponent text = Component.literal("#" + tags.get(0).location());
        if (tags.size() > 1) {
            text.append(" ").append(Component.translatable("smart_filter_extras.items.summary.more", tags.size() - 1));
        }
        return text;
    }

    static <T> List<TagKey<T>> copyNonEmpty(java.util.Collection<TagKey<T>> tags, String kind) {
        if (tags.isEmpty()) {
            throw new IllegalArgumentException("a " + kind + " tag filter needs at least one tag");
        }
        return List.copyOf(new LinkedHashSet<>(tags));
    }
}
