package smartfilterextras.filter;

import dev.ftb.mods.ftbfiltersystem.api.filter.AbstractSmartFilter;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;

import java.util.Collection;
import java.util.List;

/**
 * Matches items in any of the given item tags: {@code smart_filter_extras:item_tag(c:ingots,c:gems)}.
 *
 * <p>Each tag matches exactly like FTB Filter System's own single-tag {@code item_tag} filter
 * ({@code stack.is(tag)}), so {@code /smart_filter_extras downgrade} can turn this into
 * {@code or(item_tag(a)item_tag(b))} without changing what matches.</p>
 *
 * <p>Tags don't have to exist when the filter is parsed; they are looked up when items are tested.</p>
 */
public final class ItemTagListFilter extends AbstractSmartFilter {
    public static final ResourceLocation ID = SmartFilterExtras.id("item_tag");
    public static final int MAX_TAGS = TagListArgument.MAX_TAGS;

    private final List<TagKey<Item>> tags;

    /**
     * FFS needs a valid default for the "Add..." menu. The config screen starts a newly added filter with nothing
     * ticked, so this default is never saved by accident.
     */
    public ItemTagListFilter(@Nullable SmartFilter.Compound parent) {
        this(parent, List.of(ItemTags.LOGS));
    }

    public ItemTagListFilter(@Nullable SmartFilter.Compound parent, Collection<TagKey<Item>> tags) {
        super(parent);
        this.tags = TagListArgument.copyNonEmpty(tags, "item");
    }

    /** Comma-separated tag IDs; at least one, at most {@link #MAX_TAGS}. */
    public static ItemTagListFilter fromString(SmartFilter.Compound parent, String str, HolderLookup.Provider registryAccess) {
        return new ItemTagListFilter(parent, TagListArgument.parse(Registries.ITEM, str, "item"));
    }

    public List<TagKey<Item>> getTags() {
        return tags;
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
        for (TagKey<Item> tag : tags) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getStringArg(HolderLookup.Provider registryAccess) {
        return TagListArgument.join(tags);
    }

    @Override
    public Component getDisplayArg(HolderLookup.Provider registryAccess) {
        return TagListArgument.summary(tags);
    }
}
