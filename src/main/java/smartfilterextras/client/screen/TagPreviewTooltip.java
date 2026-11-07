package smartfilterextras.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Tooltip image for a tag row: a grid of the first items in the tag, plus "+N more" when there are more.
 * The client-side renderer is registered in {@code SmartFilterExtrasClient}.
 */
public record TagPreviewTooltip(List<ItemStack> stacks, int more) implements TooltipComponent {
    static final int COLUMNS = 9;
    static final int CELL = 18;

    public static final class Renderer implements ClientTooltipComponent {
        private final TagPreviewTooltip tooltip;

        public Renderer(TagPreviewTooltip tooltip) {
            this.tooltip = tooltip;
        }

        private int rows() {
            return (tooltip.stacks().size() + COLUMNS - 1) / COLUMNS;
        }

        @Override
        public int getHeight() {
            return rows() * CELL + (tooltip.more() > 0 ? 10 : 0) + 2;
        }

        @Override
        public int getWidth(Font font) {
            int grid = Math.min(COLUMNS, tooltip.stacks().size()) * CELL;
            return tooltip.more() > 0 ? Math.max(grid, font.width(moreText())) : grid;
        }

        private Component moreText() {
            return Component.translatable("smart_filter_extras.items.summary.more", tooltip.more());
        }

        @Override
        public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
            List<ItemStack> stacks = tooltip.stacks();
            for (int i = 0; i < stacks.size(); i++) {
                int cx = x + (i % COLUMNS) * CELL;
                int cy = y + (i / COLUMNS) * CELL;
                graphics.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0x40000000);
                graphics.renderItem(stacks.get(i), cx + 1, cy + 1);
            }
            if (tooltip.more() > 0) {
                graphics.drawString(font, moreText(), x, y + rows() * CELL + 1, 0xFFA0A0A0);
            }
        }
    }
}
