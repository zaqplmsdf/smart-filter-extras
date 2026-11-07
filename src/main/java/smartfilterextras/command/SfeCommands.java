package smartfilterextras.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemAPI;
import dev.ftb.mods.ftbfiltersystem.api.FilterException;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import smartfilterextras.SmartFilterExtras;
import smartfilterextras.filter.ItemIdList;

/**
 * {@code /smart_filter_extras downgrade}: converts the Smart Filter in the player's main hand to built-in FTB Filter
 * System syntax where possible. Intended to be run before uninstalling this mod. Like FFS's own
 * {@code set_filter}, it only touches the caller's own held item, so no permission level is required.
 */
public final class SfeCommands {
    /** FFS stores the filter string in this data component (a {@code String}). Looked up by ID, not by FFS internals. */
    private static final ResourceLocation FILTER_COMPONENT_ID = ResourceLocation.fromNamespaceAndPath(FTBFilterSystemAPI.MOD_ID, "filter");

    private static final SimpleCommandExceptionType NOT_A_FILTER =
            new SimpleCommandExceptionType(Component.translatable("smart_filter_extras.command.not_a_filter"));
    private static final SimpleCommandExceptionType NO_COMPONENT =
            new SimpleCommandExceptionType(Component.translatable("smart_filter_extras.command.no_component"));
    private static final DynamicCommandExceptionType INVALID_FILTER =
            new DynamicCommandExceptionType(msg -> Component.translatable("smart_filter_extras.command.invalid_filter", msg));
    private static final Dynamic2CommandExceptionType TOO_LONG = new Dynamic2CommandExceptionType(
            (length, max) -> Component.translatable("smart_filter_extras.command.downgrade.too_long", length, max));

    private SfeCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(SmartFilterExtras.MOD_ID)
                .then(Commands.literal("downgrade")
                        .executes(ctx -> downgradeHeldFilter(ctx.getSource()))));
    }

    private static int downgradeHeldFilter(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!FTBFilterSystemAPI.api().isFilterItem(stack)) {
            throw NOT_A_FILTER.create();
        }
        DataComponentType<String> component = filterComponent();
        if (component == null) {
            throw NO_COMPONENT.create();
        }

        HolderLookup.Provider registryAccess = source.registryAccess();
        String original = stack.getOrDefault(component, "");
        FilterDowngrader.Result result;
        try {
            SmartFilter root = FTBFilterSystemAPI.api().parseFilter(original, registryAccess);
            result = FilterDowngrader.downgrade(root, registryAccess);
            if (result.convertedLists() > 0) {
                // The built-in syntax is longer. FFS syncs the filter to clients as a string of at most 32767
                // characters; a longer one can't be encoded when the item is sent to the client, which
                // disconnects the player, so refuse instead of writing it.
                if (result.filterString().length() > ItemIdList.MAX_FILTER_CHARS) {
                    throw TOO_LONG.create(result.filterString().length(), ItemIdList.MAX_FILTER_CHARS);
                }
                // Must still be a valid filter before we overwrite the player's item.
                FTBFilterSystemAPI.api().parseFilter(result.filterString(), registryAccess);
            }
        } catch (FilterException e) {
            throw INVALID_FILTER.create(e.getMessage());
        }

        if (result.convertedLists() == 0) {
            source.sendSuccess(() -> Component.translatable("smart_filter_extras.command.downgrade.nothing"), false);
        } else {
            stack.set(component, result.filterString());
            source.sendSuccess(() -> Component.translatable("smart_filter_extras.command.downgrade.done", result.convertedLists())
                    .withStyle(ChatFormatting.GREEN), false);
        }
        if (result.droppedMissingIds() > 0) {
            source.sendSuccess(() -> Component.translatable("smart_filter_extras.command.downgrade.dropped_missing", result.droppedMissingIds())
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (result.keptOtherFilters() > 0) {
            source.sendSuccess(() -> Component.translatable("smart_filter_extras.command.downgrade.kept_other_filters", result.keptOtherFilters())
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (result.unsupported() > 0) {
            source.sendSuccess(() -> Component.translatable("smart_filter_extras.command.downgrade.unsupported", result.unsupported())
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return result.convertedLists();
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<String> filterComponent() {
        return (DataComponentType<String>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(FILTER_COMPONENT_ID);
    }
}
