package smartfilterextras.filter;

import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemAPI;
import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemRegistry;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import dev.ftb.mods.ftbfiltersystem.registry.FilterRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import smartfilterextras.SmartFilterExtras;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Keeps filter strings written by earlier versions working. Quest books, item pipes and anything else that stores
 * a smart filter keep the text as it was saved, so a renamed filter ID makes those filters invalid (FFS then
 * matches nothing at all, and an FTB Quests item task can never be detected).
 *
 * <ul>
 *     <li>The 2.x test builds used the mod ID {@code smartfilterextras}: {@code smartfilterextras:items(...)},
 *     {@code item_tag}, {@code block_tag} and {@code fuel}.</li>
 *     <li>The original FTB Filter System Addons 1.0.0 had {@code ftbfiltersystemaddons:block_tag(tag)}, and
 *     replaced FFS's own item filter with one taking several items: {@code item(minecraft:stone,minecraft:dirt)}.</li>
 * </ul>
 *
 * <p>Old IDs parse into today's filters, which save under today's IDs, so a filter is upgraded the next time it is
 * edited and saved. A single-item {@code item(...)} is still FFS's own item filter (same parser, class, screen and
 * JEI support); only the comma-separated form, which FFS rejects, becomes an Item List.</p>
 *
 * <p>FFS builds its Add menu from every registered ID, so the old IDs would show up as duplicates; see
 * {@link #hideFromAddMenu()}.</p>
 */
public final class LegacyFilters {
    /** Mod ID of the 2.x test builds. */
    public static final String V2_NAMESPACE = "smartfilterextras";
    /** Mod ID of the original FTB Filter System Addons 1.0.0. */
    public static final String ADDONS_NAMESPACE = "ftbfiltersystemaddons";
    /** FFS's own item filter, which 1.0.0 replaced with a multi-item one. */
    public static final ResourceLocation ITEM_ID = ResourceLocation.fromNamespaceAndPath(FTBFilterSystemAPI.MOD_ID, "item");

    public static final List<ResourceLocation> OLD_IDS = List.of(
            ResourceLocation.fromNamespaceAndPath(V2_NAMESPACE, "items"),
            ResourceLocation.fromNamespaceAndPath(V2_NAMESPACE, "item_tag"),
            ResourceLocation.fromNamespaceAndPath(V2_NAMESPACE, "block_tag"),
            ResourceLocation.fromNamespaceAndPath(V2_NAMESPACE, "fuel"),
            ResourceLocation.fromNamespaceAndPath(ADDONS_NAMESPACE, "block_tag"));

    /** Default instances FFS created for the old IDs; they are taken out of the Add menu again. */
    private static final Set<SmartFilter> OLD_ID_DEFAULTS = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private LegacyFilters() {
    }

    /** Call from FFS's filter registration, after FFS has registered its own filters. */
    public static void register(FTBFilterSystemRegistry registry) {
        alias(registry, OLD_IDS.get(0), ItemListFilter::fromString, ItemListFilter::new);
        alias(registry, OLD_IDS.get(1), ItemTagListFilter::fromString, ItemTagListFilter::new);
        alias(registry, OLD_IDS.get(2), BlockTagFilter::fromString, BlockTagFilter::new);
        alias(registry, OLD_IDS.get(3), FuelFilter::fromString, FuelFilter::new);
        alias(registry, OLD_IDS.get(4), BlockTagFilter::fromString, BlockTagFilter::new);
        widenItemFilter(registry);
    }

    /**
     * Re-registers {@code ftbfiltersystem:item} with FFS's own parser and default for a single item, and an Item
     * List for a comma-separated list (which FFS's parser rejects). FFS's parser is taken from its registry rather
     * than called directly, so single items go through exactly the code FFS registered. If that isn't possible
     * (FFS registers after us, or its registry changed), the item filter is left alone and only the old
     * {@code item(a,b)} form stays unreadable.
     */
    private static void widenItemFilter(FTBFilterSystemRegistry registry) {
        FilterRegistry.FilterDetails<?> builtIn;
        try {
            builtIn = registry instanceof FilterRegistry ffs ? ffs.getDetails(ITEM_ID).orElse(null) : null;
        } catch (LinkageError e) {
            builtIn = null;
        }
        if (builtIn == null) {
            SmartFilterExtras.LOGGER.warn("FTB Filter System's item filter isn't registered yet; item(a,b) filters from FTB Filter System Addons 1.0.0 won't be read");
            return;
        }
        SmartFilter.Factory<?> single = builtIn.factory();
        registry.register(ITEM_ID, (parent, arg, registryAccess) -> itemFilter(single, parent, arg, registryAccess), widen(builtIn.defaultSupplier()));
    }

    static SmartFilter itemFilter(SmartFilter.Factory<?> single, SmartFilter.Compound parent, String arg, HolderLookup.Provider registryAccess) {
        return arg.indexOf(',') < 0 ? single.create(parent, arg, registryAccess) : ItemListFilter.fromString(parent, arg, registryAccess);
    }

    private static SmartFilter.DefaultFactory<SmartFilter> widen(SmartFilter.DefaultFactory<?> defaults) {
        return defaults::create;
    }

    private static <T extends SmartFilter> void alias(FTBFilterSystemRegistry registry, ResourceLocation oldId,
                                                      SmartFilter.Factory<T> factory, SmartFilter.DefaultFactory<T> defaults) {
        registry.register(oldId, factory, parent -> {
            T filter = defaults.create(parent);
            OLD_ID_DEFAULTS.add(filter);
            return filter;
        });
    }

    /**
     * Takes the old IDs out of FFS's Add menu. FFS creates one default instance per registered ID whenever its
     * registry is frozen (at client start, and again each time a world starts) and lists those in the menu; the
     * old IDs' defaults are instances of today's filters, so they would show as a second copy of each entry.
     *
     * @return how many entries were removed
     */
    public static int hideFromAddMenu() {
        synchronized (OLD_ID_DEFAULTS) {
            if (OLD_ID_DEFAULTS.isEmpty()) {
                return 0;
            }
            int removed = 0;
            try {
                var defaults = FTBFilterSystemAPI.api().getRegistry().defaultFilterInstances();
                int before = defaults.size();
                defaults.removeIf(OLD_ID_DEFAULTS::contains);
                removed = before - defaults.size();
            } catch (UnsupportedOperationException e) {
                SmartFilterExtras.LOGGER.warn("Can't hide the old filter IDs from FTB Filter System's Add menu; they show as duplicates", e);
            }
            OLD_ID_DEFAULTS.clear();
            return removed;
        }
    }
}
