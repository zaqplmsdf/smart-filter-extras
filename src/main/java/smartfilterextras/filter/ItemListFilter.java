package smartfilterextras.filter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbfiltersystem.api.FilterException;
import dev.ftb.mods.ftbfiltersystem.api.filter.AbstractSmartFilter;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Matches any of a list of entries: {@code smart_filter_extras:items(minecraft:stone,minecraft:potion[...])}. A plain
 * entry matches every stack of that item; an entry with components (see {@link ItemEntry}) only matches stacks
 * that also carry those components.
 *
 * <p>Entries that can't be resolved in the current game (for example after a mod was removed) are kept in the
 * serialized form, so they survive an edit, but never match anything. One missing item therefore doesn't
 * invalidate the whole filter.</p>
 */
public final class ItemListFilter extends AbstractSmartFilter {
    public static final ResourceLocation ID = SmartFilterExtras.id("items");
    public static final int MAX_ENTRIES = ItemIdList.MAX_ENTRIES;

    /** Every entry in serialized order: resolved entries and missing ones. */
    private final List<String> serialized;
    private final List<ItemEntry> entries;
    private final List<String> missing;
    private final Set<Item> plainItems = new HashSet<>();
    private final List<ItemEntry> variants = new ArrayList<>();

    /** Default instance (used by the "Add..." menu): an empty list, which the config screen won't let you save. */
    public ItemListFilter(@Nullable SmartFilter.Compound parent) {
        this(parent, List.of(), List.of(), List.of());
    }

    private ItemListFilter(@Nullable SmartFilter.Compound parent, List<String> serialized, List<ItemEntry> entries, List<String> missing) {
        super(parent);
        this.serialized = List.copyOf(serialized);
        this.entries = List.copyOf(entries);
        this.missing = List.copyOf(missing);
        for (ItemEntry entry : entries) {
            if (entry.isPlain()) {
                plainItems.add(entry.item());
            } else {
                variants.add(entry);
            }
        }
    }

    /** A list of plain items, e.g. for tests and code; no registry access needed. */
    public static ItemListFilter ofItems(@Nullable SmartFilter.Compound parent, Collection<Item> items) {
        List<ItemEntry> entries = new LinkedHashSet<>(items).stream().map(ItemEntry::of).toList();
        return new ItemListFilter(parent, entries.stream().map(e -> e.serialize(null)).toList(), entries, List.of());
    }

    /**
     * Builds a filter from resolved entries, keeping previously missing entries at the end.
     *
     * @throws IllegalArgumentException if there would be more than {@link #MAX_ENTRIES} entries
     */
    public static ItemListFilter of(@Nullable SmartFilter.Compound parent, Collection<ItemEntry> entries, Collection<String> keepMissing,
                                    HolderLookup.Provider registryAccess) {
        Set<String> serialized = new LinkedHashSet<>();
        List<ItemEntry> resolved = new ArrayList<>();
        for (ItemEntry entry : new LinkedHashSet<>(entries)) {
            if (serialized.add(entry.serialize(registryAccess))) {
                resolved.add(entry);
            }
        }
        List<String> missing = keepMissing.stream().filter(m -> !serialized.contains(m)).distinct().toList();
        serialized.addAll(missing);
        if (serialized.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many items: " + serialized.size() + " > " + MAX_ENTRIES);
        }
        return new ItemListFilter(parent, new ArrayList<>(serialized), resolved, missing);
    }

    public static ItemListFilter fromString(SmartFilter.Compound parent, String str, HolderLookup.Provider registryAccess) {
        HolderLookup.RegistryLookup<Item> lookup = registryAccess.lookup(Registries.ITEM)
                .orElseThrow(() -> new FilterException("no item registry available"));
        List<String> serialized = new ArrayList<>();
        List<ItemEntry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String raw : ItemIdList.parseEntries(str)) {
            Optional<ItemEntry> entry = resolve(raw, lookup, registryAccess);
            if (entry.isPresent()) {
                String canonical = entry.get().serialize(registryAccess);
                if (!serialized.contains(canonical)) {
                    serialized.add(canonical);
                    entries.add(entry.get());
                }
            } else if (!serialized.contains(raw)) {
                serialized.add(raw);
                missing.add(raw);
            }
        }
        return new ItemListFilter(parent, serialized, entries, missing);
    }

    private static Optional<ItemEntry> resolve(String raw, HolderLookup.RegistryLookup<Item> lookup, HolderLookup.Provider registryAccess) {
        if (raw.indexOf('[') < 0) {
            return lookup.get(ResourceKey.create(Registries.ITEM, ResourceLocation.parse(raw))).map(Holder.Reference::value).map(ItemEntry::of);
        }
        try {
            return Optional.of(ItemEntry.parse(raw, registryAccess));
        } catch (CommandSyntaxException | RuntimeException e) {
            // Unknown item or component (e.g. from a removed mod): keep the text, match nothing.
            return Optional.empty();
        }
    }

    /** Entries that exist in the current game, in list order. */
    public List<ItemEntry> getEntries() {
        return entries;
    }

    /** The items of all entries (plain and with components), in list order, without duplicates. */
    public List<Item> getItems() {
        return entries.stream().map(ItemEntry::item).distinct().toList();
    }

    /** Entries from the serialized list that can't be resolved in the current game, as written. */
    public List<String> getMissingEntries() {
        return missing;
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public boolean test(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (plainItems.contains(stack.getItem())) {
            return true;
        }
        for (ItemEntry variant : variants) {
            if (variant.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Entries never contain ( ) or \ (see {@link ItemEntry#serialize}), so no escaping is needed. */
    @Override
    public String getStringArg(HolderLookup.Provider registryAccess) {
        return ItemIdList.join(serialized);
    }

    /** Short summary for the filter tree and {@code /ftbfiltersystem} output, e.g. "Stone, Potion of Healing +3". */
    @Override
    public Component getDisplayArg(HolderLookup.Provider registryAccess) {
        MutableComponent text;
        if (entries.isEmpty()) {
            text = Component.translatable("smart_filter_extras.items.summary.empty");
        } else {
            text = Component.empty().append(entries.get(0).toStack().getHoverName());
            if (entries.size() > 1) {
                text.append(", ").append(entries.get(1).toStack().getHoverName());
            }
            if (entries.size() > 2) {
                text.append(" ").append(Component.translatable("smart_filter_extras.items.summary.more", entries.size() - 2));
            }
        }
        if (!missing.isEmpty()) {
            text.append(" ").append(Component.translatable("smart_filter_extras.items.summary.missing", missing.size())
                    .withStyle(ChatFormatting.RED));
        }
        return text;
    }
}
