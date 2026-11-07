package smartfilterextras.gametest.client;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.SmartFilterExtras;
import smartfilterextras.client.screen.AbstractTagConfigScreen;
import smartfilterextras.client.screen.BlockTagConfigScreen;
import smartfilterextras.client.screen.ItemListConfigScreen;

import java.util.List;
import java.util.Optional;

/**
 * JEI part of the client smoke test, in its own class so the smoke test still loads without JEI. Uses JEI's own
 * runtime to look up the handlers our plugin registered, then performs a drop through the returned target.
 */
@JeiPlugin
public final class JeiCheck implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return SmartFilterExtras.id("smoke_test_runtime_probe");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    static void run(ItemListConfigScreen screen) {
        IJeiRuntime jei = runtime;
        ClientSmokeTest.check(jei != null, "JEI runtime available");
        ClientSmokeTest.check(jei.getScreenHelper().getGuiProperties(screen).isPresent(), "JEI knows the screen bounds (so it shows its ingredient list)");

        List<IGhostIngredientHandler<ItemListConfigScreen>> handlers = jei.getScreenHelper().getGhostIngredientHandlers(screen);
        ClientSmokeTest.check(!handlers.isEmpty(), "JEI has a drag-and-drop handler for the screen");
        Optional<ITypedIngredient<ItemStack>> ingredient = jei.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(Items.DIAMOND));
        ClientSmokeTest.check(ingredient.isPresent(), "JEI ingredient for diamond");
        List<IGhostIngredientHandler.Target<ItemStack>> targets = handlers.get(0).getTargetsTyped(screen, ingredient.get(), true);
        ClientSmokeTest.check(!targets.isEmpty(), "a drop target is offered");
        targets.get(0).accept(new ItemStack(Items.DIAMOND));
        handlers.get(0).onComplete();
        ClientSmokeTest.check(screen.getSelectedItems().contains(Items.DIAMOND), "dropped diamond was added to the selection");
    }

    static void runBlockTag(BlockTagConfigScreen screen) {
        runTagScreen(screen, new ItemStack(Items.STICK), new ItemStack(Items.OAK_PLANKS), Items.OAK_PLANKS);
    }

    /** No target for {@code refused} (if given); dropping {@code accepted} makes it the lookup item. */
    static <S extends AbstractTagConfigScreen<?, ?>> void runTagScreen(S screen, @Nullable ItemStack refused, ItemStack accepted, Item expectedLookup) {
        IJeiRuntime jei = runtime;
        ClientSmokeTest.check(jei != null, "JEI runtime available");
        ClientSmokeTest.check(jei.getScreenHelper().getGuiProperties(screen).isPresent(), "JEI knows the tag screen bounds");
        List<IGhostIngredientHandler<S>> handlers = jei.getScreenHelper().getGhostIngredientHandlers(screen);
        ClientSmokeTest.check(!handlers.isEmpty(), "JEI has a drag-and-drop handler for the tag screen");

        if (refused != null) {
            Optional<ITypedIngredient<ItemStack>> no = jei.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, refused);
            ClientSmokeTest.check(no.isPresent() && handlers.get(0).getTargetsTyped(screen, no.get(), true).isEmpty(),
                    "no drop target offered for " + refused.getHoverName().getString());
        }
        Optional<ITypedIngredient<ItemStack>> yes = jei.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, accepted);
        ClientSmokeTest.check(yes.isPresent(), "JEI ingredient for " + accepted.getHoverName().getString());
        List<IGhostIngredientHandler.Target<ItemStack>> targets = handlers.get(0).getTargetsTyped(screen, yes.get(), true);
        ClientSmokeTest.check(!targets.isEmpty(), "a drop target is offered for " + accepted.getHoverName().getString());
        targets.get(0).accept(accepted.copy());
        handlers.get(0).onComplete();
        ClientSmokeTest.check(screen.getLookupItem() == expectedLookup, "dropped item became the lookup item: " + screen.getLookupItem());
        screen.setLookup(null);
    }
}
