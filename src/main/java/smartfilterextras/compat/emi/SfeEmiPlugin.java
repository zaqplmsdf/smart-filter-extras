package smartfilterextras.compat.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import smartfilterextras.SmartFilterExtras;
import smartfilterextras.client.screen.BlockTagConfigScreen;
import smartfilterextras.client.screen.ItemDropTarget;
import smartfilterextras.client.screen.ItemListConfigScreen;
import smartfilterextras.client.screen.ItemTagListConfigScreen;

/**
 * Optional EMI integration, found and loaded by EMI only when EMI is installed. Tells EMI where our config screens
 * are and accepts stacks dragged from EMI: into the item list (adds the item) or onto a tag screen (looks up the
 * item's tags).
 *
 * <p>Limitation, checked against EMI 1.1.24 (the latest for 1.21.1): EMI added a bounds API for non-container
 * screens in 1.1.23, but its NeoForge build still only draws its sidebars, and only handles mouse and keyboard
 * input, on container screens. FTB Filter System's config screens aren't container screens, so EMI doesn't appear
 * on them yet. This registration is what EMI asks for, and takes effect if EMI starts drawing on such screens.
 * With EMI older than 1.1.23 only the drag-and-drop handlers are registered.</p>
 */
@EmiEntrypoint
public final class SfeEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        boolean bounds = register(registry, ItemListConfigScreen.class);
        register(registry, ItemTagListConfigScreen.class);
        register(registry, BlockTagConfigScreen.class);
        if (!bounds) {
            SmartFilterExtras.LOGGER.info("EMI is older than 1.1.23 (no screen bounds API): EMI's sidebars won't show on Smart Filter Extras screens");
        }
    }

    /** @return false if this EMI version can't be told where the screen is */
    private static <T extends Screen & ItemDropTarget> boolean register(EmiRegistry registry, Class<T> screenClass) {
        registry.addDragDropHandler(screenClass, new DropHandler<>());
        try {
            ScreenBounds.register(registry, screenClass);
            return true;
        } catch (LinkageError e) {
            return false;
        }
    }

    /** Separate class, so a missing {@code EmiScreenBoundsProvider} (EMI < 1.1.23) only fails this call. */
    private static final class ScreenBounds {
        static <T extends Screen & ItemDropTarget> void register(EmiRegistry registry, Class<T> screenClass) {
            registry.addScreenBoundsProvider(screenClass, screen -> {
                if (screen.width == 0 || screen.height == 0) {
                    return Bounds.EMPTY;
                }
                Rect2i panel = screen.getPanelArea();
                return new Bounds(panel.getX(), panel.getY(), panel.getWidth(), panel.getHeight());
            });
        }
    }

    private static final class DropHandler<T extends Screen & ItemDropTarget> implements EmiDragDropHandler<T> {
        @Override
        public boolean dropStack(T screen, EmiIngredient ingredient, int x, int y) {
            ItemStack stack = firstItem(ingredient);
            if (stack.isEmpty() || !screen.getDropArea().contains(x, y)) {
                return false;
            }
            // A stack the screen can't use (e.g. a non-block on the block tag screen) is still consumed: the
            // screen then says why it was refused.
            screen.acceptDrop(stack);
            return true;
        }

        /** While dragging: the drop area in green if the stack can be dropped there, red if not. */
        @Override
        public void render(T screen, EmiIngredient dragged, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            ItemStack stack = firstItem(dragged);
            if (stack.isEmpty()) {
                return;
            }
            Rect2i area = screen.getDropArea();
            graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + area.getHeight(),
                    screen.canAcceptDrop(stack) ? 0x6022BB33 : 0x60BB2222);
        }
    }

    private static ItemStack firstItem(EmiIngredient ingredient) {
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            ItemStack stack = emiStack.getItemStack();
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
