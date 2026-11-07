package smartfilterextras.client;

import dev.ftb.mods.ftbfiltersystem.api.client.FTBFilterSystemClientAPI;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import dev.ftb.mods.ftbfiltersystem.api.event.client.ClientFilterRegistrationEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import smartfilterextras.SmartFilterExtras;
import smartfilterextras.client.screen.BlockTagConfigScreen;
import smartfilterextras.client.screen.FuelConfigScreen;
import smartfilterextras.client.screen.ItemCatalog;
import smartfilterextras.client.screen.ItemListConfigScreen;
import smartfilterextras.client.screen.ItemTagListConfigScreen;
import smartfilterextras.client.screen.TagPreviewTooltip;
import smartfilterextras.filter.BlockTagFilter;
import smartfilterextras.filter.FuelFilter;
import smartfilterextras.filter.ItemListFilter;
import smartfilterextras.filter.ItemTagListFilter;
import smartfilterextras.filter.LegacyFilters;

/** Client-only entry point; NeoForge never loads this class on a dedicated server. */
@Mod(value = SmartFilterExtras.MOD_ID, dist = Dist.CLIENT)
public final class SmartFilterExtrasClient {
    public SmartFilterExtrasClient(IEventBus modBus) {
        ClientFilterRegistrationEvent.REGISTER.register(SmartFilterExtrasClient::registerScreens);
        modBus.addListener(SmartFilterExtrasClient::registerTooltips);
        modBus.addListener(SmartFilterExtrasClient::registerReloadListeners);
        NeoForge.EVENT_BUS.addListener(SmartFilterExtrasClient::beforeFilterScreenInit);
    }

    /**
     * FFS rebuilds its Add menu entries whenever a world starts, and the editor builds the menu in init(); taking
     * the old filter IDs out just before that keeps each filter listed once.
     */
    private static void beforeFilterScreenInit(ScreenEvent.Init.Pre event) {
        if (event.getScreen() instanceof AbstractFilterScreen) {
            LegacyFilters.hideFromAddMenu();
        }
    }

    /** Item names can change with resource packs, so the search caches are dropped on every resource reload. */
    private static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> ItemCatalog.clearCaches());
    }

    private static void registerScreens(FTBFilterSystemClientAPI api) {
        api.registerFilterScreenFactory(ItemListFilter.ID, ItemListConfigScreen::new);
        api.registerFilterScreenFactory(ItemTagListFilter.ID, ItemTagListConfigScreen::new);
        api.registerFilterScreenFactory(BlockTagFilter.ID, BlockTagConfigScreen::new);
        api.registerFilterScreenFactory(FuelFilter.ID, FuelConfigScreen::new);
    }

    private static void registerTooltips(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(TagPreviewTooltip.class, TagPreviewTooltip.Renderer::new);
    }
}
