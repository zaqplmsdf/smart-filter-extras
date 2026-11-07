package smartfilterextras.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import smartfilterextras.filter.ItemEntry;

import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Where the item picker gets its entries from. In normal mode entries are distinct items (the filter then
 * matches every variant); in variant mode every distinct variant is listed (each potion, each enchanted book...).
 */
public final class ItemCatalog {
    public enum Source {
        CREATIVE("creative", new ItemStack(Items.COMPASS)),
        INVENTORY("inventory", new ItemStack(Items.CHEST));

        private final Component displayName;
        private final Component tooltip;
        private final ItemStack icon;

        Source(String key, ItemStack icon) {
            this.displayName = Component.translatable("smart_filter_extras.gui.source." + key);
            this.tooltip = Component.translatable("smart_filter_extras.gui.source." + key + ".tooltip");
            this.icon = icon;
        }

        public Component displayName() {
            return displayName;
        }

        public Component tooltip() {
            return tooltip;
        }

        public ItemStack icon() {
            return icon;
        }
    }

    // Cache of the creative stacks, rebuilt when the world (registry access), feature flags or op status change.
    private static WeakReference<RegistryAccess> cachedFor = new WeakReference<>(null);
    private static FeatureFlagSet cachedFlags;
    private static boolean cachedOp;
    private static List<ItemStack> cachedCreative = List.of();
    // Entry lists built from the creative stacks; cleared whenever those are rebuilt.
    @Nullable
    private static List<ItemEntry> cachedCreativeItems;
    @Nullable
    private static List<ItemEntry> cachedCreativeVariants;

    /**
     * Lower-case search texts per entry, so a search doesn't look up every display name again (that was ~2 us
     * per entry, ~100 ms per search in a 40,000-entry pack). Dropped when the language or the world changes.
     */
    private static final Map<ItemEntry, SearchKey> SEARCH_KEYS = new HashMap<>();
    private static String searchKeysLanguage = "";
    private static WeakReference<RegistryAccess> searchKeysFor = new WeakReference<>(null);

    private record SearchKey(String id, String namespace, String name) {
    }

    private ItemCatalog() {
    }

    /** Entries for the grid: one per item, or one per distinct variant when {@code variants} is on. */
    public static List<ItemEntry> entries(Source source, boolean variants, Minecraft minecraft) {
        if (source == Source.CREATIVE) {
            List<ItemStack> stacks = creativeStacks(minecraft);
            if (stacks != cachedCreative) {
                // No world: an uncached registry list; don't mix it up with the last world's cached entries.
                return toEntries(stacks, variants);
            }
            if (variants) {
                if (cachedCreativeVariants == null) {
                    cachedCreativeVariants = toEntries(stacks, true);
                }
                return cachedCreativeVariants;
            }
            if (cachedCreativeItems == null) {
                cachedCreativeItems = toEntries(stacks, false);
            }
            return cachedCreativeItems;
        }
        return toEntries(inventoryStacks(minecraft.player), variants);
    }

    private static List<ItemEntry> toEntries(List<ItemStack> stacks, boolean variants) {
        Set<ItemEntry> entries = new LinkedHashSet<>();
        for (ItemStack stack : stacks) {
            entries.add(variants ? ItemEntry.variantOf(stack) : ItemEntry.of(stack.getItem()));
        }
        return List.copyOf(entries);
    }

    /** Distinct items, in catalog order. */
    public static List<Item> items(Source source, Minecraft minecraft) {
        return entries(source, false, minecraft).stream().map(ItemEntry::item).toList();
    }

    private static List<ItemStack> creativeStacks(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).map(ItemStack::new).toList();
        }
        // Same parameters as the vanilla creative screen, so we don't fight over its cached tab contents.
        FeatureFlagSet flags = player.connection.enabledFeatures();
        boolean op = minecraft.options.operatorItemsTab().get() && player.canUseGameMasterBlocks();
        RegistryAccess registryAccess = level.registryAccess();
        if (cachedFor.get() != registryAccess || !flags.equals(cachedFlags) || op != cachedOp) {
            CreativeModeTabs.tryRebuildTabContents(flags, op, registryAccess);
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack stack : CreativeModeTabs.searchTab().getDisplayItems()) {
                if (!stack.isEmpty()) {
                    stacks.add(stack.copyWithCount(1));
                }
            }
            cachedCreative = List.copyOf(stacks);
            cachedCreativeItems = null;
            cachedCreativeVariants = null;
            cachedFor = new WeakReference<>(registryAccess);
            cachedFlags = flags;
            cachedOp = op;
        }
        return cachedCreative;
    }

    private static List<ItemStack> inventoryStacks(LocalPlayer player) {
        if (player == null) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        // Main inventory first, then the hotbar, armour and offhand.
        for (int i = 9; i < inventory.items.size(); i++) {
            addIfPresent(stacks, inventory.items.get(i));
        }
        for (int i = 0; i < Math.min(9, inventory.items.size()); i++) {
            addIfPresent(stacks, inventory.items.get(i));
        }
        inventory.armor.forEach(stack -> addIfPresent(stacks, stack));
        inventory.offhand.forEach(stack -> addIfPresent(stacks, stack));
        return stacks;
    }

    private static void addIfPresent(List<ItemStack> stacks, ItemStack stack) {
        if (!stack.isEmpty()) {
            stacks.add(stack.copyWithCount(1));
        }
    }

    /**
     * The entries matching the query (see {@link #matches(ItemStack, String)} for the rules), in order, as a new
     * mutable list. Search texts are cached per entry; {@code stacks} is only asked for entries not seen before.
     */
    public static List<ItemEntry> search(List<ItemEntry> entries, String query, Function<ItemEntry, ItemStack> stacks) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return new ArrayList<>(entries);
        }
        syncSearchKeys();
        boolean modSearch = q.startsWith("@");
        String mod = modSearch ? q.substring(1).trim() : "";
        // Pinyin only matters for queries with Latin letters; anything else is a plain substring search.
        boolean pinyin = !modSearch && SearchText.pinyinAvailable() && q.chars().anyMatch(c -> c >= 'a' && c <= 'z');
        List<ItemEntry> result = new ArrayList<>();
        for (ItemEntry entry : entries) {
            SearchKey key = SEARCH_KEYS.computeIfAbsent(entry, e -> searchKey(stacks.apply(e)));
            boolean hit = modSearch
                    ? key.namespace().startsWith(mod)
                    : key.id().contains(q) || (pinyin ? SearchText.contains(key.name(), q) : key.name().contains(q));
            if (hit) {
                result.add(entry);
            }
        }
        return result;
    }

    private static SearchKey searchKey(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new SearchKey(id.toString(), id.getNamespace(), stack.getHoverName().getString().toLowerCase(Locale.ROOT));
    }

    /**
     * Drops every cached name and entry list. Called after a resource reload: resource packs can rename items
     * without the language or the world changing.
     */
    public static void clearCaches() {
        SEARCH_KEYS.clear();
        cachedCreativeItems = null;
        cachedCreativeVariants = null;
    }

    private static void syncSearchKeys() {
        Minecraft minecraft = Minecraft.getInstance();
        String language = minecraft.options.languageCode;
        RegistryAccess registryAccess = minecraft.level == null ? null : minecraft.level.registryAccess();
        if (!language.equals(searchKeysLanguage) || searchKeysFor.get() != registryAccess) {
            SEARCH_KEYS.clear();
            searchKeysLanguage = language;
            searchKeysFor = new WeakReference<>(registryAccess);
        }
    }

    /**
     * Search rules: empty matches everything; {@code @mod} matches items whose namespace starts with "mod";
     * anything else matches a substring of the display name (pinyin too, with Just Enough Characters) or of the
     * registry ID. Case-insensitive.
     */
    public static boolean matches(ItemStack stack, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (q.startsWith("@")) {
            return id.getNamespace().startsWith(q.substring(1).trim());
        }
        return id.toString().contains(q) || SearchText.contains(stack.getHoverName().getString().toLowerCase(Locale.ROOT), q);
    }

    public static boolean matches(Item item, String query) {
        return matches(new ItemStack(item), query);
    }

    /** Whether pinyin search is active (Just Enough Characters installed and usable). */
    public static boolean pinyinSearch() {
        return SearchText.pinyinAvailable();
    }
}
