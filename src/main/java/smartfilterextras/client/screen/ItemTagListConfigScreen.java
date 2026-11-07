package smartfilterextras.client.screen;

import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.filter.ItemTagListFilter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Config screen for {@link ItemTagListFilter}. Any item can be used for the reverse lookup. */
public class ItemTagListConfigScreen extends AbstractTagConfigScreen<Item, ItemTagListFilter> {
    private static final Texts TEXTS = new Texts(
            "smart_filter_extras.gui.item_lookup_hint",
            "smart_filter_extras.gui.item_lookup_tooltip",
            "smart_filter_extras.gui.item_lookup_clear",
            "smart_filter_extras.gui.item_lookup_no_items",
            "smart_filter_extras.gui.item_lookup_no_items",
            "smart_filter_extras.gui.item_tag_preview",
            "smart_filter_extras.gui.item_tag_preview_empty",
            "smart_filter_extras.gui.tag_count_items");

    public ItemTagListConfigScreen(ItemTagListFilter filter, AbstractFilterScreen parentScreen) {
        this(filter, parentScreen, currentListRows());
    }

    private ItemTagListConfigScreen(ItemTagListFilter filter, AbstractFilterScreen parentScreen, int listRows) {
        super(filter, parentScreen, filter.getTags(), listRows);
    }

    @Override
    protected ItemTagListConfigScreen withListRows(int rows) {
        return new ItemTagListConfigScreen(filter, parentScreen, rows);
    }

    @Override
    protected Registry<Item> registry() {
        return BuiltInRegistries.ITEM;
    }

    @Override
    protected ItemTagListFilter createFilter(List<TagKey<Item>> tags) {
        return new ItemTagListFilter(filter.getParent(), tags);
    }

    @Override
    @Nullable
    protected Set<TagKey<Item>> tagsOf(Item item) {
        return item == Items.AIR ? null : new ItemStack(item).getTags().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    protected Item itemOf(Item item) {
        return item;
    }

    @Override
    protected ItemStack lookupPlaceholder() {
        return new ItemStack(Items.IRON_INGOT);
    }

    @Override
    protected Texts texts() {
        return TEXTS;
    }
}
