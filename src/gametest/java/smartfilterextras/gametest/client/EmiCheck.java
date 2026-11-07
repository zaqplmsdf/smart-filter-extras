package smartfilterextras.gametest.client;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.client.screen.AbstractTagConfigScreen;
import smartfilterextras.client.screen.ItemDropTarget;
import smartfilterextras.client.screen.ItemListConfigScreen;

import java.lang.reflect.Method;

/**
 * EMI part of the client smoke test, in its own class so the smoke test still loads without EMI. Goes through
 * EMI's own (internal) registries, found by reflection, so it checks what EMI will actually do with our screens:
 * whether it shows its sidebars there, and what happens when a stack is dropped.
 */
final class EmiCheck {
    private EmiCheck() {
    }

    static boolean loaded() {
        return (boolean) invoke("dev.emi.emi.runtime.EmiReloadManager", "isLoaded", new Class<?>[0]);
    }

    static void runItemList(ItemListConfigScreen screen) {
        checkBounds(screen);
        Rect2i drop = screen.getDropArea();
        boolean consumed = drop(screen, new ItemStack(Items.DIAMOND), drop.getX() + 3, drop.getY() + 3);
        ClientSmokeTest.check(consumed && screen.getSelectedItems().contains(Items.DIAMOND), "dropped diamond was added: " + screen.getSelectedItems());
        boolean outside = drop(screen, new ItemStack(Items.EMERALD), screen.getPanelArea().getX() - 20, drop.getY() + 3);
        ClientSmokeTest.check(!outside && !screen.getSelectedItems().contains(Items.EMERALD), "a drop outside the panel is ignored");
    }

    static void runTagScreen(AbstractTagConfigScreen<?, ?> screen, @Nullable ItemStack refused, ItemStack accepted, Item expectedLookup) {
        checkBounds(screen);
        Rect2i drop = screen.getDropArea();
        int x = drop.getX() + drop.getWidth() / 2;
        int y = drop.getY() + drop.getHeight() / 2;
        if (refused != null) {
            ClientSmokeTest.check(drop(screen, refused, x, y) && screen.getLookupItem() == null, "refused drop consumed, lookup unchanged");
            Component status = screen.getStatusMessage();
            ClientSmokeTest.check(status != null && status.getContents() instanceof TranslatableContents, "the screen says why: " + status);
        }
        ClientSmokeTest.check(drop(screen, accepted, x, y), "drop consumed");
        ClientSmokeTest.check(screen.getLookupItem() == expectedLookup, "dropped item became the lookup item: " + screen.getLookupItem());
        screen.setLookup(null);
    }

    /**
     * A non-container screen needs a bounds provider before EMI treats it as a screen it can work with. (EMI 1.1.24
     * on NeoForge still only draws its sidebars on container screens; this checks our side of the contract.)
     */
    private static <T extends Screen & ItemDropTarget> void checkBounds(T screen) {
        Object base = invoke("dev.emi.emi.screen.EmiScreenBase", "of", new Class<?>[]{Screen.class}, screen);
        boolean empty = (boolean) call(base, "isEmpty");
        ClientSmokeTest.check(!empty, "EMI's screen registry knows the screen");
        Bounds bounds = (Bounds) call(base, "bounds");
        Rect2i panel = screen.getPanelArea();
        ClientSmokeTest.check(bounds.x() == panel.getX() && bounds.y() == panel.getY() && bounds.width() == panel.getWidth()
                && bounds.height() == panel.getHeight(), "EMI uses the panel bounds: " + bounds);
    }

    private static boolean drop(Screen screen, ItemStack stack, int x, int y) {
        return (boolean) invoke("dev.emi.emi.registry.EmiDragDropHandlers", "dropStack",
                new Class<?>[]{Screen.class, EmiIngredient.class, int.class, int.class}, screen, EmiStack.of(stack), x, y);
    }

    private static Object invoke(String className, String method, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(className).getMethod(method, types);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("EMI internals changed? " + className + "." + method + ": " + e);
        }
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("EMI internals changed? " + target.getClass().getName() + "." + method + ": " + e);
        }
    }
}
