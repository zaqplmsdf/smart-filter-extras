package smartfilterextras.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Keyboard shortcuts shared by the config screens.
 *
 * <ul>
 *     <li>Enter saves (like the Done button), unless a button was focused with Tab, in which case Enter presses
 *     that button as usual.</li>
 *     <li>Typing anywhere goes to the search field.</li>
 * </ul>
 *
 * <p>There is deliberately no Ctrl+F: JEI and EMI both use it to focus their own search field and JEI takes it
 * before the screen sees it, so it would never reach us.</p>
 *
 * <p>Modifiers are read from the key event rather than the live keyboard state, so the dev-only smoke test can
 * send shortcuts too.</p>
 */
final class Keys {
    private Keys() {
    }

    static boolean isEnter(int keyCode) {
        return keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER;
    }

    /** Ctrl (Cmd on macOS) + {@code key}, without Shift or Alt. */
    static boolean isCtrl(int keyCode, int modifiers, int key) {
        int ctrl = Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL;
        return keyCode == key && (modifiers & ctrl) != 0 && (modifiers & (GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT)) == 0;
    }

    /** Enter means "save" unless the player tabbed to a button (then the button handles it). */
    static boolean enterSaves(Screen screen) {
        return !(screen.getFocused() instanceof AbstractButton && Minecraft.getInstance().getLastInputType().isKeyboard());
    }

    /**
     * Backspace while something else has focus (e.g. after clicking a button) still edits the search text, the
     * same way typing does.
     *
     * @return true if the key was handled
     */
    static boolean backspaceToSearch(Screen screen, EditBox search, int keyCode, int scanCode, int modifiers) {
        if (keyCode != InputConstants.KEY_BACKSPACE || screen.getFocused() == search || !search.isVisible() || search.getValue().isEmpty()) {
            return false;
        }
        screen.setFocused(search);
        search.moveCursorToEnd(false);
        return search.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Sends a typed character to the search field if something else has focus. Leading spaces are ignored so a
     * stray space doesn't start a search.
     *
     * @return true if the character was handled
     */
    static boolean typeToSearch(Screen screen, EditBox search, char character, int modifiers) {
        if (screen.getFocused() == search || !search.isVisible() || Character.isISOControl(character)) {
            return false;
        }
        if (Character.isWhitespace(character) && search.getValue().isEmpty()) {
            return false;
        }
        screen.setFocused(search);
        search.moveCursorToEnd(false);
        return search.charTyped(character, modifiers);
    }
}
