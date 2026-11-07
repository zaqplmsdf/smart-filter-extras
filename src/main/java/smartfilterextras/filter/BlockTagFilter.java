package smartfilterextras.filter;

import dev.ftb.mods.ftbfiltersystem.api.filter.AbstractSmartFilter;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;

import java.util.Collection;
import java.util.List;

/**
 * Matches items that place a block in any of the given <em>block</em> tags:
 * {@code smart_filter_extras:block_tag(minecraft:mineable/pickaxe,minecraft:logs)}.
 *
 * <p>Only {@link BlockItem}s can match. Note that some items that don't look like blocks are block items too:
 * seeds place crops (so wheat seeds match {@code minecraft:crops}) and redstone dust places redstone wire.</p>
 *
 * <p>Tags don't have to exist when the filter is parsed; they are looked up when items are tested, so a filter
 * keeps working across data pack reloads.</p>
 */
public final class BlockTagFilter extends AbstractSmartFilter {
    public static final ResourceLocation ID = SmartFilterExtras.id("block_tag");
    public static final int MAX_TAGS = TagListArgument.MAX_TAGS;

    private final List<TagKey<Block>> tags;

    public BlockTagFilter(@Nullable SmartFilter.Compound parent) {
        this(parent, List.of(BlockTags.LOGS));
    }

    public BlockTagFilter(@Nullable SmartFilter.Compound parent, TagKey<Block> tag) {
        this(parent, List.of(tag));
    }

    public BlockTagFilter(@Nullable SmartFilter.Compound parent, Collection<TagKey<Block>> tags) {
        super(parent);
        this.tags = TagListArgument.copyNonEmpty(tags, "block");
    }

    /** See {@link TagListArgument#parseTagId(String)}. */
    @Nullable
    public static ResourceLocation parseTagId(String text) {
        return TagListArgument.parseTagId(text);
    }

    /** Comma-separated tag IDs; at least one, at most {@link #MAX_TAGS}. */
    public static BlockTagFilter fromString(SmartFilter.Compound parent, String str, HolderLookup.Provider registryAccess) {
        return new BlockTagFilter(parent, TagListArgument.parse(Registries.BLOCK, str, "block"));
    }

    public List<TagKey<Block>> getTags() {
        return tags;
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public boolean test(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getStringArg(HolderLookup.Provider registryAccess) {
        return TagListArgument.join(tags);
    }

    /** "#minecraft:logs", or "#minecraft:logs +2" for several tags. */
    @Override
    public Component getDisplayArg(HolderLookup.Provider registryAccess) {
        return TagListArgument.summary(tags);
    }
}
