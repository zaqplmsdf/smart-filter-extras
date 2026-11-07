package smartfilterextras.client.screen;

import net.minecraft.client.Minecraft;

/**
 * Clipboard access that copes with Windows refusing access while another program (e.g. a clipboard manager) has
 * the clipboard open. GLFW fails silently then, so writes are read back and retried, and callers can tell the
 * player when it still didn't work instead of claiming success.
 */
final class ClipboardSupport {
    private static final int ATTEMPTS = 4;

    private ClipboardSupport() {
    }

    /** @return true if the clipboard now holds {@code text} */
    static boolean write(Minecraft minecraft, String text) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (attempt > 0) {
                pause();
            }
            minecraft.keyboardHandler.setClipboard(text);
            if (text.equals(minecraft.keyboardHandler.getClipboard())) {
                return true;
            }
        }
        return false;
    }

    /** Locks by other programs are brief, so an empty result is retried a few times before it's believed. */
    static String read(Minecraft minecraft) {
        String text = "";
        for (int attempt = 0; attempt < ATTEMPTS && text.isEmpty(); attempt++) {
            if (attempt > 0) {
                pause();
            }
            text = minecraft.keyboardHandler.getClipboard();
        }
        return text;
    }

    private static void pause() {
        try {
            Thread.sleep(15L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
