package smartfilterextras.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Restyles the search {@link EditBox} that FFS's base screen creates: a text field frame with a magnifier on the
 * left and a clear (x) button on the right while there is text. The edit box itself becomes borderless and sits
 * inside the frame.
 */
final class SearchField {
    static final int HEIGHT = 14;
    private static final ResourceLocation FRAME = ResourceLocation.withDefaultNamespace("widget/text_field");
    private static final ResourceLocation FRAME_FOCUSED = ResourceLocation.withDefaultNamespace("widget/text_field_highlighted");

    private final EditBox box;
    private final int x;
    private final int y;
    private final int width;

    SearchField(EditBox box, int x, int y, int width) {
        this.box = box;
        this.x = x;
        this.y = y;
        this.width = width;
        box.setBordered(false);
        box.setTextColor(0xFFFFFF);
        box.setRectangle(width - 15 - 12, 9, x + 15, y + 3);
    }

    EditBox box() {
        return box;
    }

    /** Sets the edit box hint, cut with an ellipsis to the text area: EditBox draws its hint unclipped. */
    void setHint(Font font, Component hint) {
        int room = box.getWidth();
        if (font.width(hint) > room) {
            String cut = font.plainSubstrByWidth(hint.getString(), room - font.width("…"));
            hint = Component.literal(cut + "…").withStyle(hint.getStyle());
        }
        box.setHint(hint);
    }

    /** Draw before the widgets (from renderBackground), so the edit box renders on top. */
    void renderFrame(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.blitSprite(box.isFocused() ? FRAME_FOCUSED : FRAME, x, y, width, HEIGHT);
        graphics.blitSprite(Ui.ICON_SEARCH, x + 2, y + 1, 12, 12);
        if (!box.getValue().isEmpty()) {
            boolean hover = overClear(mouseX, mouseY);
            if (hover) {
                graphics.setColor(1.0F, 0.55F, 0.55F, 1.0F);
            }
            graphics.blitSprite(Ui.ICON_CLEAR_SMALL, x + width - 10, y + 4, 7, 7);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** @return true if the click hit the clear button (and the text was cleared) */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (!box.getValue().isEmpty() && overClear(mouseX, mouseY)) {
            box.setValue("");
            box.setFocused(true);
            return true;
        }
        return false;
    }

    private boolean overClear(double mouseX, double mouseY) {
        return ScreenSupport.isInside(mouseX, mouseY, x + width - 12, y, 12, HEIGHT);
    }
}
