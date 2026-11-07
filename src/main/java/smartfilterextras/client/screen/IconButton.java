package smartfilterextras.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A square button with the standard button background and an icon (a GUI sprite or an item) instead of text.
 * The message is still set (for narration), it just isn't drawn. A "pressed" state can be shown for toggles and
 * tabs: a darker face with a coloured bar along the bottom.
 */
public class IconButton extends Button {
    private final Supplier<ResourceLocation> sprite;
    private final ItemStack item;
    private final int iconWidth;
    private final int iconHeight;
    private BooleanSupplier pressed = () -> false;

    public IconButton(int x, int y, int size, Supplier<ResourceLocation> sprite, int iconWidth, int iconHeight,
                      Component message, OnPress onPress) {
        this(x, y, size, size, sprite, iconWidth, iconHeight, message, onPress);
    }

    public IconButton(int x, int y, int width, int height, Supplier<ResourceLocation> sprite, int iconWidth, int iconHeight,
                      Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.sprite = sprite;
        this.item = ItemStack.EMPTY;
        this.iconWidth = iconWidth;
        this.iconHeight = iconHeight;
    }

    /** An item-icon button (the item is drawn at 12x12). */
    public IconButton(int x, int y, int size, ItemStack item, Component message, OnPress onPress) {
        super(x, y, size, size, message, onPress, DEFAULT_NARRATION);
        this.sprite = () -> null;
        this.item = item;
        this.iconWidth = 12;
        this.iconHeight = 12;
    }

    public IconButton withTooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        return this;
    }

    public IconButton pressedWhen(BooleanSupplier pressed) {
        this.pressed = pressed;
        return this;
    }

    public boolean isPressed() {
        return pressed.getAsBoolean();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean down = pressed.getAsBoolean();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blitSprite(SPRITES.get(active, isHoveredOrFocused()), getX(), getY(), getWidth(), getHeight());
        if (down) {
            graphics.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0x50000000);
            graphics.fill(getX() + 2, getBottom() - 3, getRight() - 2, getBottom() - 1, Ui.GREEN);
        }
        if (!active) {
            graphics.setColor(0.6F, 0.6F, 0.6F, alpha);
        }
        int iconX = getX() + (getWidth() - iconWidth) / 2;
        int iconY = getY() + (getHeight() - iconHeight) / 2 - (down ? 1 : 0);
        if (!item.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(iconX, iconY, 0);
            graphics.pose().scale(0.75F, 0.75F, 1.0F);
            graphics.renderItem(item, 0, 0);
            graphics.pose().popPose();
        } else {
            ResourceLocation icon = sprite.get();
            if (icon != null) {
                graphics.blitSprite(icon, iconX, iconY, iconWidth, iconHeight);
            }
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
