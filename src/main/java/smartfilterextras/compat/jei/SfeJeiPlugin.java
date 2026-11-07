package smartfilterextras.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.handlers.IScreenHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;
import smartfilterextras.client.screen.BlockTagConfigScreen;
import smartfilterextras.client.screen.ItemDropTarget;
import smartfilterextras.client.screen.ItemListConfigScreen;
import smartfilterextras.client.screen.ItemTagListConfigScreen;

import java.util.List;

/**
 * Optional JEI integration, loaded by JEI only when JEI is installed. JEI shows its ingredient list next to our
 * config screens, and items can be dragged from it: into the item list (adds the item) or onto a tag screen
 * (looks up the item's tags).
 */
@JeiPlugin
public final class SfeJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = SmartFilterExtras.id("jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        register(registration, ItemListConfigScreen.class);
        register(registration, ItemTagListConfigScreen.class);
        register(registration, BlockTagConfigScreen.class);
    }

    private static <T extends Screen & ItemDropTarget> void register(IGuiHandlerRegistration registration, Class<T> screenClass) {
        registration.addGuiScreenHandler(screenClass, new ScreenHandler<>());
        registration.addGhostIngredientHandler(screenClass, new GhostHandler<>());
    }

    private static final class ScreenHandler<T extends Screen & ItemDropTarget> implements IScreenHandler<T> {
        @Override
        @Nullable
        public IGuiProperties apply(T screen) {
            if (screen.width == 0 || screen.height == 0) {
                return null;
            }
            Rect2i panel = screen.getPanelArea();
            return new Properties(screen.getClass(), panel.getX(), panel.getY(), panel.getWidth(), panel.getHeight(), screen.width, screen.height);
        }
    }

    private record Properties(Class<? extends Screen> screenClass, int guiLeft, int guiTop, int guiXSize, int guiYSize,
                              int screenWidth, int screenHeight) implements IGuiProperties {
    }

    private static final class GhostHandler<T extends Screen & ItemDropTarget> implements IGhostIngredientHandler<T> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(T screen, ITypedIngredient<I> ingredient, boolean doStart) {
            ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY);
            if (!screen.canAcceptDrop(stack)) {
                return List.of();
            }
            return List.of(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return screen.getDropArea();
                }

                @Override
                public void accept(I value) {
                    if (value instanceof ItemStack dropped) {
                        screen.acceptDrop(dropped);
                    }
                }
            });
        }

        @Override
        public void onComplete() {
        }
    }
}
