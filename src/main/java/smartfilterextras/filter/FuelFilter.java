package smartfilterextras.filter;

import dev.ftb.mods.ftbfiltersystem.api.NumericComparison;
import dev.ftb.mods.ftbfiltersystem.api.filter.AbstractComparisonFilter;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;

import java.util.Locale;

/**
 * Compares an item's furnace burn time in ticks: {@code smart_filter_extras:fuel(>=1600)}. One smelted item takes
 * 200 ticks, so coal (1600) smelts 8 items. {@code fuel(>0)} matches any fuel, {@code fuel(=0)} anything that
 * isn't one. Burn times come from NeoForge (the {@code furnace_fuels} data map plus item overrides).
 */
public final class FuelFilter extends AbstractComparisonFilter {
    public static final ResourceLocation ID = SmartFilterExtras.id("fuel");
    public static final int TICKS_PER_ITEM = 200;

    /** Default instance: any fuel. */
    public FuelFilter(@Nullable SmartFilter.Compound parent) {
        this(parent, new NumericComparison(NumericComparison.ComparisonOp.GT, 0, false));
    }

    public FuelFilter(@Nullable SmartFilter.Compound parent, NumericComparison comparison) {
        super(parent, comparison);
    }

    public static FuelFilter fromString(SmartFilter.Compound parent, String str, HolderLookup.Provider registryAccess) {
        return new FuelFilter(parent, NumericComparison.fromString(str.trim(), false));
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected int getValueToCompare(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING);
    }

    /** e.g. "≥1600 ticks (8 items)". */
    @Override
    public Component getDisplayArg(HolderLookup.Provider registryAccess) {
        int value = comparison.value();
        String items = value % TICKS_PER_ITEM == 0
                ? Integer.toString(value / TICKS_PER_ITEM)
                : String.format(Locale.ROOT, "%.1f", value / (double) TICKS_PER_ITEM);
        return Component.translatable("smart_filter_extras.fuel.summary", comparison.op().toString() + value, items);
    }
}
