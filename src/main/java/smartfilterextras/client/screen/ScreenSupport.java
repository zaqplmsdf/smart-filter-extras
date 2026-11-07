package smartfilterextras.client.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

final class ScreenSupport {
    private ScreenSupport() {
    }

    /**
     * Finds the "Done" button that {@code AbstractFilterConfigScreen} creates, so it can be disabled while the
     * current state can't be saved. FFS doesn't expose it; if it can't be found, saving an invalid state is
     * still prevented because {@code makeNewFilter()} returns null.
     */
    @Nullable
    static Button findDoneButton(Screen screen) {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof Button button
                    && button.getMessage().getContents() instanceof TranslatableContents contents
                    && "gui.done".equals(contents.getKey())) {
                return button;
            }
        }
        return null;
    }

    static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
