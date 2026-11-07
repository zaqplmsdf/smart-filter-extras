package smartfilterextras.client.screen;

import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.filter.BlockTagFilter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Config screen for {@link BlockTagFilter}. The reverse lookup only takes block items and lists the tags of the
 * block they place.
 */
public class BlockTagConfigScreen extends AbstractTagConfigScreen<Block, BlockTagFilter> {
    private static final Texts TEXTS = new Texts(
            "smart_filter_extras.gui.lookup_hint",
            "smart_filter_extras.gui.lookup_tooltip",
            "smart_filter_extras.gui.lookup_clear",
            "smart_filter_extras.gui.lookup_no_blocks",
            "smart_filter_extras.gui.lookup_not_block",
            "smart_filter_extras.gui.tag_preview",
            "smart_filter_extras.gui.tag_preview_empty",
            "smart_filter_extras.gui.tag_count_blocks");

    public BlockTagConfigScreen(BlockTagFilter filter, AbstractFilterScreen parentScreen) {
        this(filter, parentScreen, currentListRows());
    }

    private BlockTagConfigScreen(BlockTagFilter filter, AbstractFilterScreen parentScreen, int listRows) {
        super(filter, parentScreen, filter.getTags(), listRows);
    }

    @Override
    protected BlockTagConfigScreen withListRows(int rows) {
        return new BlockTagConfigScreen(filter, parentScreen, rows);
    }

    @Override
    protected Registry<Block> registry() {
        return BuiltInRegistries.BLOCK;
    }

    @Override
    protected BlockTagFilter createFilter(List<TagKey<Block>> tags) {
        return new BlockTagFilter(filter.getParent(), tags);
    }

    @Override
    @Nullable
    protected Set<TagKey<Block>> tagsOf(Item item) {
        return item instanceof BlockItem blockItem
                ? blockItem.getBlock().defaultBlockState().getTags().collect(Collectors.toCollection(LinkedHashSet::new))
                : null;
    }

    @Override
    protected Item itemOf(Block block) {
        return block.asItem();
    }

    @Override
    protected ItemStack lookupPlaceholder() {
        return new ItemStack(Items.GRASS_BLOCK);
    }

    @Override
    protected Texts texts() {
        return TEXTS;
    }
}
