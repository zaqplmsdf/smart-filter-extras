package smartfilterextras.filter;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One entry of an {@link ItemListFilter}: an item, optionally with components that a stack must also have
 * ("a healing potion" rather than "any potion"). Components are matched as a subset: the stack may carry other
 * components too. Written in the same syntax as {@code /give}:
 * {@code minecraft:potion[minecraft:potion_contents={potion:"minecraft:healing"}]}.
 */
public record ItemEntry(Item item, DataComponentPatch components) {
    /**
     * Components that don't describe a variant: state that changes while the item is used (damage, repair cost,
     * a loaded crossbow, a book being written, debug stick state) and stored contents (shulker box and bundle
     * contents, block entity data, bees in a hive, unopened loot). Keeping them would make an entry useless (it
     * stops matching as soon as the item is used) and can make it huge: a full shulker box alone can be several
     * thousand characters, and FFS can't send filters longer than 32767.
     */
    private static final Set<DataComponentType<?>> NOT_VARIANT = Set.of(
            DataComponents.DAMAGE, DataComponents.REPAIR_COST, DataComponents.CHARGED_PROJECTILES,
            DataComponents.WRITABLE_BOOK_CONTENT, DataComponents.DEBUG_STICK_STATE,
            DataComponents.CONTAINER, DataComponents.BUNDLE_CONTENTS, DataComponents.BLOCK_ENTITY_DATA,
            DataComponents.BEES, DataComponents.CONTAINER_LOOT);

    public ItemEntry {
        Objects.requireNonNull(item);
        Objects.requireNonNull(components);
    }

    public static ItemEntry of(Item item) {
        return new ItemEntry(item, DataComponentPatch.EMPTY);
    }

    /**
     * The entry describing this stack's variant: its item plus its non-default components, minus the ones that
     * aren't part of a variant (see {@link #NOT_VARIANT}), removals and transient components. Dropping a component
     * only makes the entry match more, never less.
     */
    public static ItemEntry variantOf(ItemStack stack) {
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        for (Map.Entry<DataComponentType<?>, Optional<?>> e : stack.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = e.getKey();
            if (e.getValue().isPresent() && !type.isTransient() && !NOT_VARIANT.contains(type)) {
                set(builder, type, e.getValue().get());
            }
        }
        return new ItemEntry(stack.getItem(), builder.build());
    }

    /** The FFS component-filter argument for this entry's components (fuzzy = the stack may have more). */
    public String componentFilterArg(HolderLookup.Provider registryAccess) {
        net.minecraft.core.component.DataComponentMap.Builder map = net.minecraft.core.component.DataComponentMap.builder();
        for (Map.Entry<DataComponentType<?>, Optional<?>> e : components.entrySet()) {
            e.getValue().ifPresent(value -> setMap(map, e.getKey(), value));
        }
        Tag tag = net.minecraft.core.component.DataComponentMap.CODEC
                .encodeStart(registryAccess.createSerializationContext(NbtOps.INSTANCE), map.build()).getOrThrow();
        return "fuzzy:" + tag;
    }

    public boolean isPlain() {
        return components.isEmpty();
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(item)) {
            return false;
        }
        for (Map.Entry<DataComponentType<?>, Optional<?>> e : components.entrySet()) {
            Object actual = stack.get(e.getKey());
            if (e.getValue().isPresent() ? !e.getValue().get().equals(actual) : actual != null) {
                return false;
            }
        }
        return true;
    }

    public ItemStack toStack() {
        return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), 1, components);
    }

    /**
     * {@code minecraft:stone}, or {@code /give} syntax for entries with components, with {@code ( ) \ %}
     * percent-encoded (see {@link ItemIdList#encode(String)}) so the text is safe inside FFS filter strings.
     */
    public String serialize(HolderLookup.Provider registryAccess) {
        return isPlain()
                ? BuiltInRegistries.ITEM.getKey(item).toString()
                : ItemIdList.encode(new ItemInput(BuiltInRegistries.ITEM.wrapAsHolder(item), components).serialize(registryAccess));
    }

    /** Parses {@code id} or {@code id[components]} (percent-encoded or not); the item must exist. */
    public static ItemEntry parse(String text, HolderLookup.Provider registryAccess) throws CommandSyntaxException {
        StringReader reader = new StringReader(ItemIdList.decode(text));
        ItemParser.ItemResult result = new ItemParser(registryAccess).parse(reader);
        if (reader.canRead()) {
            throw new IllegalArgumentException("unexpected text after item: " + reader.getRemaining());
        }
        return new ItemEntry(result.item().value(), result.components());
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(DataComponentPatch.Builder builder, DataComponentType<T> type, Object value) {
        builder.set(type, (T) value);
    }

    @SuppressWarnings("unchecked")
    private static <T> void setMap(net.minecraft.core.component.DataComponentMap.Builder builder, DataComponentType<T> type, Object value) {
        builder.set(type, (T) value);
    }
}
