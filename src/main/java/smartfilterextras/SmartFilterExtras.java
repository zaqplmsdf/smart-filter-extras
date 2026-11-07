package smartfilterextras;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemRegistry;
import dev.ftb.mods.ftbfiltersystem.api.event.FilterRegistrationEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import smartfilterextras.command.SfeCommands;
import smartfilterextras.filter.BlockTagFilter;
import smartfilterextras.filter.FuelFilter;
import smartfilterextras.filter.ItemListFilter;
import smartfilterextras.filter.ItemTagListFilter;
import smartfilterextras.filter.LegacyFilters;
import smartfilterextras.network.PresencePayload;

/**
 * Common entry point.
 *
 * <p>All filters are registered under this mod's own namespace. Single-item {@code ftbfiltersystem:item}
 * filters stay FFS's own filter (and keep their JEI support); only the comma-separated form the original addon
 * wrote is read as an Item List (see {@link LegacyFilters}).</p>
 */
@Mod(SmartFilterExtras.MOD_ID)
public final class SmartFilterExtras {
    public static final String MOD_ID = "smart_filter_extras";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SmartFilterExtras(IEventBus modBus) {
        // FFS fires this during common setup, after every mod has been constructed.
        FilterRegistrationEvent.REGISTER.register(SmartFilterExtras::registerFilters);

        modBus.addListener(PresencePayload::register);
        NeoForge.EVENT_BUS.addListener(SfeCommands::register);
    }

    private static void registerFilters(FTBFilterSystemRegistry registry) {
        registry.register(ItemListFilter.ID, ItemListFilter::fromString, ItemListFilter::new);
        registry.register(ItemTagListFilter.ID, ItemTagListFilter::fromString, ItemTagListFilter::new);
        registry.register(BlockTagFilter.ID, BlockTagFilter::fromString, BlockTagFilter::new);
        registry.register(FuelFilter.ID, FuelFilter::fromString, FuelFilter::new);
        // Filters saved by 2.x and by the original addon (quest books keep them as written).
        LegacyFilters.register(registry);
        LOGGER.debug("Registered {}, {}, {} and {}, plus {} old IDs", ItemListFilter.ID, ItemTagListFilter.ID, BlockTagFilter.ID,
                FuelFilter.ID, LegacyFilters.OLD_IDS.size());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
