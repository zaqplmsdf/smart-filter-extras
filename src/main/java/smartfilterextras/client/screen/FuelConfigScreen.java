package smartfilterextras.client.screen;

import dev.ftb.mods.ftbfiltersystem.api.NumericComparison;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterConfigScreen;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.filter.FuelFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Config screen for {@link FuelFilter}: an operator and a burn time, one-click presets, and a live preview of
 * which fuels currently match (sorted by burn time, with their burn times in the tooltip).
 */
public class FuelConfigScreen extends AbstractFilterConfigScreen<FuelFilter> {
    private static final int PANEL_WIDTH = 200;
    private static final int CONTENT_HEIGHT = 102;
    private static final int ROW_Y = 18;
    private static final int PRESET_Y = 40;
    private static final int PREVIEW_LABEL_Y = 62;
    private static final int PREVIEW_Y = 72;
    private static final int PREVIEW_SLOTS = 10;
    private static final int MAX_TICKS = 1_000_000;

    private record Fuel(Item item, int ticks) {
    }

    private final List<Fuel> fuels = new ArrayList<>();
    private boolean savedAndClosing;
    private CycleButton<NumericComparison.ComparisonOp> opButton;
    private EditBox valueBox;
    @Nullable
    private Button doneButton;
    private List<Fuel> matching = List.of();

    public FuelConfigScreen(FuelFilter filter, AbstractFilterScreen parentScreen) {
        super(filter, parentScreen, PANEL_WIDTH, CONTENT_HEIGHT);
    }

    @Override
    protected void init() {
        NumericComparison.ComparisonOp op = opButton == null ? filter.getComparison().op() : opButton.getValue();
        String value = valueBox == null ? Integer.toString(filter.getComparison().value()) : valueBox.getValue();
        super.init();
        if (fuels.isEmpty()) {
            for (Item item : ItemCatalog.items(ItemCatalog.Source.CREATIVE, minecraft)) {
                int ticks = new ItemStack(item).getBurnTime(RecipeType.SMELTING);
                if (ticks > 0) {
                    fuels.add(new Fuel(item, ticks));
                }
            }
            fuels.sort(Comparator.comparingInt(Fuel::ticks).reversed().thenComparing(f -> f.item().getDescriptionId()));
        }

        opButton = addRenderableWidget(CycleButton.<NumericComparison.ComparisonOp>builder(o -> Component.literal(o.toString()))
                .withValues(NumericComparison.ComparisonOp.GE, NumericComparison.ComparisonOp.GT, NumericComparison.ComparisonOp.EQ,
                        NumericComparison.ComparisonOp.NE, NumericComparison.ComparisonOp.LE, NumericComparison.ComparisonOp.LT)
                .displayOnlyValue()
                .withInitialValue(op)
                .withTooltip(o -> Tooltip.create(Component.translatable("smart_filter_extras.gui.fuel_op_tooltip")))
                .create(leftPos + 8, topPos + ROW_Y, 24, 16, Component.translatable("smart_filter_extras.gui.fuel_op"),
                        (button, v) -> updateMatching()));
        valueBox = addRenderableWidget(new EditBox(font, leftPos + 36, topPos + ROW_Y + 1, 52, 14, Component.translatable("smart_filter_extras.gui.fuel_value")));
        valueBox.setMaxLength(7);
        valueBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        valueBox.setValue(value);
        valueBox.setResponder(s -> updateMatching());
        valueBox.setTooltip(Tooltip.create(Component.translatable("smart_filter_extras.gui.fuel_value_tooltip")));
        setInitialFocus(valueBox);

        // Presets: text buttons for the special cases, item icons for common fuels.
        int x = leftPos + 8;
        x = addTextPreset(x, "smart_filter_extras.gui.fuel_preset.any", NumericComparison.ComparisonOp.GT, 0);
        x = addItemPreset(x, Items.COAL);
        x = addItemPreset(x, Items.BLAZE_ROD);
        x = addItemPreset(x, Items.COAL_BLOCK);
        x = addItemPreset(x, Items.LAVA_BUCKET);
        addTextPreset(x, "smart_filter_extras.gui.fuel_preset.none", NumericComparison.ComparisonOp.EQ, 0);

        doneButton = ScreenSupport.findDoneButton(this);
        updateMatching();
    }

    private int addTextPreset(int x, String key, NumericComparison.ComparisonOp op, int value) {
        Component text = Component.translatable(key);
        int width = font.width(text) + 10;
        addRenderableWidget(Button.builder(text, b -> apply(op, value))
                .bounds(x, topPos + PRESET_Y, width, 16)
                .tooltip(Tooltip.create(Component.translatable(key + ".tooltip")))
                .build());
        return x + width + 2;
    }

    private int addItemPreset(int x, Item item) {
        int ticks = new ItemStack(item).getBurnTime(RecipeType.SMELTING);
        addRenderableWidget(new IconButton(x, topPos + PRESET_Y, 16, new ItemStack(item), item.getDescription(),
                b -> apply(NumericComparison.ComparisonOp.GE, ticks))
                .withTooltip(Component.translatable("smart_filter_extras.gui.fuel_preset.item", item.getDescription(), ticks)));
        return x + 18;
    }

    private void apply(NumericComparison.ComparisonOp op, int value) {
        opButton.setValue(op);
        valueBox.setValue(Integer.toString(value));
        updateMatching();
    }

    @Nullable
    private NumericComparison comparison() {
        String s = valueBox.getValue();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new NumericComparison(opButton.getValue(), Mth.clamp(Integer.parseInt(s), 0, MAX_TICKS), false);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateMatching() {
        NumericComparison comparison = comparison();
        matching = comparison == null ? List.of() : fuels.stream().filter(f -> comparison.test(f.ticks())).toList();
        if (doneButton != null) {
            doneButton.active = comparison != null;
        }
    }

    @Override
    @Nullable
    protected FuelFilter makeNewFilter() {
        NumericComparison comparison = comparison();
        if (comparison == null) {
            return null;
        }
        // FFS's applyChanges() calls this, saves, then closes the screen: that close must not ask about losing edits.
        savedAndClosing = true;
        return new FuelFilter(filter.getParent(), comparison);
    }

    /**
     * Cancel and Esc ask first if the comparison was changed. A newly added filter that still has its default
     * comparison has nothing to lose, so it closes (and is removed) without asking.
     */
    @Override
    public void onClose() {
        if (savedAndClosing || !hasUnsavedChanges()) {
            super.onClose();
        } else {
            UnsavedChanges.confirmDiscard(this, super::onClose);
        }
    }

    public boolean hasUnsavedChanges() {
        return !Objects.equals(comparison(), filter.getComparison());
    }

    /** Enter saves (as FFS's own comparison screens do), unless a button was focused with Tab. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Keys.isEnter(keyCode) && Keys.enterSaves(this)) {
            if (comparison() != null) {
                applyChanges();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && valueBox.isMouseOver(mouseX, mouseY)) {
            // Wheel: +-100 ticks (half an item); with Shift: +-1.
            int step = (Screen.hasShiftDown() ? 1 : 100) * (scrollY > 0 ? 1 : -1);
            NumericComparison current = comparison();
            int value = current == null ? 0 : current.value();
            valueBox.setValue(Integer.toString(Mth.clamp(value + step, 0, MAX_TICKS)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        NumericComparison comparison = comparison();
        int textX = valueBox.getRight() + 6;
        if (comparison != null) {
            Component meaning = Component.translatable("smart_filter_extras.gui.fuel_meaning", itemsText(comparison.value()));
            Ui.drawClipped(graphics, font, meaning, textX, topPos + ROW_Y + 4, leftPos + guiWidth - 8 - textX, Ui.TEXT_DIM);
        }

        Component label;
        if (comparison == null) {
            label = Component.translatable("smart_filter_extras.gui.fuel_invalid");
        } else if (comparison.test(0) && matching.size() < fuels.size()) {
            label = Component.translatable("smart_filter_extras.gui.fuel_matches_non_fuel", matching.size());
        } else {
            label = Component.translatable("smart_filter_extras.gui.fuel_matches", matching.size());
        }
        Ui.drawClipped(graphics, font, label, leftPos + 8, topPos + PREVIEW_LABEL_Y, guiWidth - 16, Ui.TEXT);

        Fuel hovered = null;
        for (int i = 0; i < PREVIEW_SLOTS; i++) {
            int x = leftPos + 8 + i * Ui.SLOT;
            int y = topPos + PREVIEW_Y;
            Ui.slot(graphics, x, y);
            if (i < matching.size()) {
                graphics.renderItem(new ItemStack(matching.get(i).item()), x + 1, y + 1);
                if (ScreenSupport.isInside(mouseX, mouseY, x, y, Ui.SLOT, Ui.SLOT)) {
                    hovered = matching.get(i);
                }
            }
        }
        if (hovered != null) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, new ItemStack(hovered.item())));
            lines.add(Component.translatable("smart_filter_extras.gui.fuel_ticks", hovered.ticks(), itemsText(hovered.ticks()))
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private static String itemsText(int ticks) {
        return ticks % FuelFilter.TICKS_PER_ITEM == 0
                ? Integer.toString(ticks / FuelFilter.TICKS_PER_ITEM)
                : String.format(Locale.ROOT, "%.1f", ticks / (double) FuelFilter.TICKS_PER_ITEM);
    }

    // ---------------------------------------------------------------- used by the dev-only client smoke test

    public int getMatchingCount() {
        return matching.size();
    }

    public EditBox getValueBox() {
        return valueBox;
    }

    public NumericComparison.ComparisonOp getOperator() {
        return opButton.getValue();
    }
}
