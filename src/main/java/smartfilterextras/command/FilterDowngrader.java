package smartfilterextras.command;

import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemAPI;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import smartfilterextras.filter.BlockTagFilter;
import smartfilterextras.filter.FuelFilter;
import smartfilterextras.filter.ItemEntry;
import smartfilterextras.filter.ItemListFilter;
import smartfilterextras.filter.ItemTagListFilter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Rewrites a filter tree so that it no longer depends on this mod where FTB Filter System has an equivalent:
 * <ul>
 *     <li>{@code smart_filter_extras:items(a,b)} becomes {@code or(item(a)item(b))}, a single-entry list becomes
 *     {@code item(a)}, and an entry with components becomes {@code and(item(a)component(fuzzy:{...}))};</li>
 *     <li>{@code smart_filter_extras:item_tag(a,b)} becomes {@code or(item_tag(a)item_tag(b))}, a single tag
 *     {@code item_tag(a)} (FFS's item tag filter matches exactly the same items).</li>
 * </ul>
 *
 * <p>Block tag and fuel filters have no built-in equivalent and are left as they are. Missing item list entries
 * can't be expressed with the built-in item filter, so they are dropped (and counted).</p>
 *
 * <p>The input tree is never modified; it may be a shared instance from FFS's filter cache.</p>
 */
public final class FilterDowngrader {
    private FilterDowngrader() {
    }

    /**
     * @param convertedLists    Item List and Item Tags filters that were rewritten
     * @param droppedMissingIds unknown item IDs dropped from rewritten item lists
     * @param keptOtherFilters  Block Tag and Fuel Value filters, which still need this mod
     * @param unsupported       Item List / Item Tags filters that had to be left unchanged
     */
    public record Result(String filterString, int convertedLists, int droppedMissingIds, int keptOtherFilters, int unsupported) {
    }

    public static Result downgrade(SmartFilter root, HolderLookup.Provider registryAccess) {
        Counter counter = new Counter();
        String converted = convert(root, registryAccess, counter);
        return new Result(converted, counter.convertedLists, counter.droppedMissingIds, counter.keptOtherFilters, counter.unsupported);
    }

    private static String convert(SmartFilter filter, HolderLookup.Provider registryAccess, Counter counter) {
        if (filter instanceof ItemListFilter list) {
            if (!representable(list, registryAccess)) {
                counter.unsupported++;
                return filter.asString(registryAccess);
            }
            counter.convertedLists++;
            counter.droppedMissingIds += list.getMissingEntries().size();
            return anyOf(list.getEntries().stream().map(e -> entryFilter(e, registryAccess)).toList());
        }
        if (filter instanceof ItemTagListFilter tags) {
            counter.convertedLists++;
            return anyOf(tags.getTags().stream().map(tag -> "item_tag(" + tag.location() + ")").toList());
        }
        if (filter instanceof BlockTagFilter || filter instanceof FuelFilter) {
            counter.keptOtherFilters++;
            return filter.asString(registryAccess);
        }
        if (filter instanceof SmartFilter.Compound compound) {
            String original = filter.asString(registryAccess);
            // The root filter serializes as just its children; every other compound as "id(children)".
            String prefix = filter.getParent() == null ? "" : FTBFilterSystemAPI.modDefaultedString(filter.getId()) + "(";
            String suffix = filter.getParent() == null ? "" : ")";
            String childrenAsIs = compound.getChildren().stream()
                    .map(child -> child.asString(registryAccess))
                    .collect(Collectors.joining());
            if (!original.equals(prefix + childrenAsIs + suffix)) {
                // A compound filter (probably from another addon) with extra arguments: rebuilding it could lose
                // data, so leave the whole subtree unchanged.
                counter.unsupported += countOwnFilters(filter);
                return original;
            }
            return prefix + compound.getChildren().stream()
                    .map(child -> convert(child, registryAccess, counter))
                    .collect(Collectors.joining()) + suffix;
        }
        return filter.asString(registryAccess);
    }

    /** One filter as is, several wrapped in {@code or(...)}; an empty list becomes {@code or()} (matches nothing). */
    private static String anyOf(List<String> parts) {
        return parts.size() == 1 ? parts.get(0) : "or(" + String.join("", parts) + ")";
    }

    private static String entryFilter(ItemEntry entry, HolderLookup.Provider registryAccess) {
        String item = "item(" + BuiltInRegistries.ITEM.getKey(entry.item()) + ")";
        if (entry.isPlain()) {
            return item;
        }
        // FFS's component filter has the same "stack has at least these components" (fuzzy) semantics.
        return "and(" + item + "component(" + entry.componentFilterArg(registryAccess) + "))";
    }

    /** FFS's own component filter can't hold ( ) or \ reliably (its parser strips escapes at every nesting level). */
    private static boolean representable(ItemListFilter list, HolderLookup.Provider registryAccess) {
        for (ItemEntry entry : list.getEntries()) {
            if (!entry.isPlain()) {
                String arg = entry.componentFilterArg(registryAccess);
                if (arg.indexOf('(') >= 0 || arg.indexOf(')') >= 0 || arg.indexOf('\\') >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int countOwnFilters(SmartFilter filter) {
        if (filter instanceof ItemListFilter || filter instanceof ItemTagListFilter) {
            return 1;
        }
        if (filter instanceof SmartFilter.Compound compound) {
            return compound.getChildren().stream().mapToInt(FilterDowngrader::countOwnFilters).sum();
        }
        return 0;
    }

    private static final class Counter {
        int convertedLists;
        int droppedMissingIds;
        int keptOtherFilters;
        int unsupported;
    }
}
