package smartfilterextras.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asks before Cancel or Esc throws away edits. "Keep editing" (and Esc on the question) goes back to the screen
 * exactly as it was; only "Discard" closes it without saving.
 */
final class UnsavedChanges {
    private UnsavedChanges() {
    }

    static void confirmDiscard(Screen screen, Runnable discard) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(discardIt -> {
            if (discardIt) {
                discard.run();
            } else {
                minecraft.setScreen(screen);
                // The window may have been resized meanwhile; our screens reopen at a fitting size if so.
                screen.resize(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
            }
        },
                Component.translatable("smart_filter_extras.gui.unsaved.title"),
                Component.translatable("smart_filter_extras.gui.unsaved.message"),
                Component.translatable("smart_filter_extras.gui.unsaved.discard"),
                Component.translatable("smart_filter_extras.gui.unsaved.keep")));
    }
}
