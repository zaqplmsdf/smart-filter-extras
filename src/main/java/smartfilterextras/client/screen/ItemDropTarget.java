package smartfilterextras.client.screen;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * Lets recipe viewers (JEI) find the screen area and drop ingredients onto it, without the screen class
 * referencing any recipe viewer API.
 */
public interface ItemDropTarget {
    /** The whole panel, in screen coordinates. */
    Rect2i getPanelArea();

    /** Where dropped items are accepted, in screen coordinates. */
    Rect2i getDropArea();

    /** Whether this kind of item can be dropped here at all (used to decide whether to offer a drop target). */
    default boolean canAcceptDrop(ItemStack stack) {
        return !stack.isEmpty();
    }

    /** @return true if the drop was accepted */
    boolean acceptDrop(ItemStack stack);
}
