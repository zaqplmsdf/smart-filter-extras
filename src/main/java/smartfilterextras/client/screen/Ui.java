package smartfilterextras.client.screen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;

/** Shared look for the config screens: vanilla-style slots and insets, clipped text, and a fading toast. */
final class Ui {
    static final int SLOT = 18;
    static final int TEXT = 0x404040;
    static final int TEXT_DIM = 0x6A6A6A;
    static final int GREEN = 0xFF3FB43F;
    static final int RED = 0xFFD04040;
    static final int ORANGE = 0xFFE0A030;
    static final int BLUE = 0xFF3F6FD0;

    static final ResourceLocation ICON_VIEW_ALL = SmartFilterExtras.id("icon/view_all");
    static final ResourceLocation ICON_VIEW_SELECTED = SmartFilterExtras.id("icon/view_selected");
    static final ResourceLocation ICON_VARIANTS = SmartFilterExtras.id("icon/variants");
    static final ResourceLocation ICON_COPY = SmartFilterExtras.id("icon/copy");
    static final ResourceLocation ICON_PASTE = SmartFilterExtras.id("icon/paste");
    static final ResourceLocation ICON_TRASH = SmartFilterExtras.id("icon/trash");
    static final ResourceLocation ICON_WARNING = SmartFilterExtras.id("icon/warning");
    static final ResourceLocation ICON_CHECK_SMALL = SmartFilterExtras.id("icon/check_small");
    static final ResourceLocation ICON_CLEAR_SMALL = SmartFilterExtras.id("icon/clear_small");
    static final ResourceLocation ICON_ARROW_LEFT = SmartFilterExtras.id("icon/arrow_left");
    static final ResourceLocation ICON_ARROW_RIGHT = SmartFilterExtras.id("icon/arrow_right");
    static final ResourceLocation ICON_SEARCH = ResourceLocation.withDefaultNamespace("icon/search");

    private Ui() {
    }

    /** A vanilla inventory slot (18x18). */
    static void slot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT, y + SLOT, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + SLOT, y + SLOT, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF8B8B8B);
    }

    /** A sunken area: dark top/left edge, light bottom/right edge, filled. */
    static void inset(GuiGraphics graphics, int x, int y, int width, int height, int fill) {
        graphics.fill(x, y, x + width, y + height, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + width, y + height, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    static void drawClipped(GuiGraphics graphics, Font font, Component text, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) {
            return;
        }
        if (font.width(text) <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        FormattedText cut = font.substrByWidth(text, maxWidth - font.width("…"));
        graphics.drawString(font, Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…"))), x, y, color, false);
    }

    /** A short message shown as a dark pill that fades out after a few seconds. */
    static final class Toast {
        private static final long SHOW_MILLIS = 2600L;
        private static final long FADE_MILLIS = 400L;
        /** Extra reading time for each line a long message wraps onto. */
        private static final long EXTRA_LINE_MILLIS = 1200L;

        @Nullable
        private Component text;
        private int accent;
        private long until;
        private boolean timed;

        void show(Component text, int accent) {
            this.text = text;
            this.accent = accent;
            this.until = Util.getMillis() + SHOW_MILLIS;
            this.timed = false;
        }

        @Nullable
        Component current() {
            return text != null && Util.getMillis() < until ? text : null;
        }

        /** Draws the toast centred on {@code centreX}, with its top at {@code y}. */
        void render(GuiGraphics graphics, Font font, int centreX, int y, int maxWidth) {
            Component shown = current();
            if (shown == null) {
                return;
            }
            if (!timed) {
                // The font is only at hand while drawing, so long messages get their extra time on the first frame.
                until += (pillLines(font, shown, maxWidth).size() - 1) * EXTRA_LINE_MILLIS;
                timed = true;
            }
            long left = until - Util.getMillis();
            pill(graphics, font, shown, centreX, y, maxWidth, accent, left < FADE_MILLIS ? left / (float) FADE_MILLIS : 1.0F);
        }
    }

    /** Most lines a pill wraps onto; the last one is cut with an ellipsis if the text is longer still. */
    private static final int PILL_MAX_LINES = 6;
    private static final int PILL_LINE_HEIGHT = 9;

    /** The pill's text split into lines no wider than {@code maxWidth} minus the padding. */
    static List<FormattedText> pillLines(Font font, Component text, int maxWidth) {
        int textWidth = maxWidth - 14;
        if (font.width(text) <= textWidth) {
            return List.of(text);
        }
        List<FormattedText> lines = new ArrayList<>(font.getSplitter().splitLines(text, textWidth, Style.EMPTY));
        if (lines.isEmpty()) {
            return List.of(text);
        }
        if (lines.size() > PILL_MAX_LINES) {
            FormattedText last = font.substrByWidth(lines.get(PILL_MAX_LINES - 1), textWidth - font.width("…"));
            lines = new ArrayList<>(lines.subList(0, PILL_MAX_LINES - 1));
            lines.add(FormattedText.composite(last, FormattedText.of("…")));
        }
        return lines;
    }

    /**
     * A dark rounded-looking label with a coloured accent bar, drawn above everything else. Text wider than
     * {@code maxWidth} wraps, and the pill grows upwards: its bottom stays where a one-line pill at {@code y} ends.
     */
    static void pill(GuiGraphics graphics, Font font, Component text, int centreX, int y, int maxWidth, int accent, float alpha) {
        List<FormattedText> lines = pillLines(font, text, maxWidth);
        int textWidth = 0;
        for (FormattedText line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int width = Math.min(maxWidth, textWidth + 14);
        int height = 14 + (lines.size() - 1) * PILL_LINE_HEIGHT;
        int x = centreX - width / 2;
        int top = y - (lines.size() - 1) * PILL_LINE_HEIGHT;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        int background = FastColor.ARGB32.color((int) (alpha * 0xE6), 0x1C, 0x1C, 0x1C);
        graphics.fill(x + 1, top, x + width - 1, top + height, background);
        graphics.fill(x, top + 1, x + width, top + height - 1, background);
        graphics.fill(x + 1, top + 2, x + 3, top + height - 2, FastColor.ARGB32.color((int) (alpha * 255),
                FastColor.ARGB32.red(accent), FastColor.ARGB32.green(accent), FastColor.ARGB32.blue(accent)));
        if (alpha > 0.1F) {
            int color = FastColor.ARGB32.color((int) (alpha * 255), 0xF0, 0xF0, 0xF0);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawString(font, Language.getInstance().getVisualOrder(lines.get(i)), x + 7, top + 3 + i * PILL_LINE_HEIGHT, color, false);
            }
        }
        graphics.pose().popPose();
    }
}
