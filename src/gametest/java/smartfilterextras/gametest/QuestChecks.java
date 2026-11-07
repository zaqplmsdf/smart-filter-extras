package smartfilterextras.gametest;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemAPI;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.util.PlayerInventorySummary;
import io.netty.buffer.Unpooled;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.connection.ConnectionType;
import smartfilterextras.SmartFilterExtras;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The FTB Quests side of {@link QuestGameTests}; only loaded when FTB Quests is present. */
final class QuestChecks {
    private QuestChecks() {
    }

    private record Case(String filter, List<ItemStack> matching, List<ItemStack> notMatching) {
    }

    static void itemTasksMatch(GameTestHelper helper) {
        ServerQuestFile file = questFile(helper);
        RegistryAccess ra = helper.getLevel().registryAccess();
        ItemStack healing = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        List<Case> cases = List.of(
                new Case("smart_filter_extras:items(minecraft:stone,minecraft:dirt)",
                        stacks(Items.STONE, Items.DIRT), stacks(Items.COBBLESTONE)),
                new Case("smart_filter_extras:items(minecraft:dirt)", stacks(Items.DIRT), stacks(Items.STONE)),
                new Case("or(smart_filter_extras:items(minecraft:stone)item(minecraft:dirt))",
                        stacks(Items.STONE, Items.DIRT), stacks(Items.COBBLESTONE)),
                new Case("smart_filter_extras:items(minecraft:potion[minecraft:potion_contents={potion:\"minecraft:healing\"}])",
                        List.of(healing), List.of(water)),
                new Case("smart_filter_extras:item_tag(c:ingots,minecraft:logs)",
                        stacks(Items.IRON_INGOT, Items.OAK_LOG), stacks(Items.STONE)),
                new Case("smart_filter_extras:block_tag(minecraft:logs)", stacks(Items.OAK_LOG), stacks(Items.OAK_PLANKS)),
                new Case("smart_filter_extras:fuel(>=1600)", stacks(Items.COAL), stacks(Items.STICK)),
                // Written by the 2.x test builds, before the mod ID changed.
                new Case("smartfilterextras:items(minecraft:stone,minecraft:dirt)",
                        stacks(Items.STONE, Items.DIRT), stacks(Items.COBBLESTONE)),
                new Case("smartfilterextras:item_tag(c:ingots)", stacks(Items.IRON_INGOT), stacks(Items.STONE)),
                new Case("smartfilterextras:block_tag(minecraft:logs)", stacks(Items.OAK_LOG), stacks(Items.OAK_PLANKS)),
                new Case("smartfilterextras:fuel(>=1600)", stacks(Items.COAL), stacks(Items.STICK)),
                new Case("or(smartfilterextras:items(minecraft:stone)item(minecraft:dirt))",
                        stacks(Items.STONE, Items.DIRT), stacks(Items.COBBLESTONE)));

        helper.assertTrue(ItemMatchingSystem.INSTANCE.adapters().stream().anyMatch(a -> a.getName().equals("FTB Filter System")),
                "FTB XMod Compat registered its FTB Filter System adapter with FTB Quests");
        List<String> failures = new ArrayList<>();
        for (Case c : cases) {
            ItemTask task = newTask(file, filterStack(c.filter()), 1);
            ItemTask saved = snbtRoundTrip(task, ra);
            ItemTask synced = netRoundTrip(task, ra);
            for (ItemTask t : List.of(task, saved, synced)) {
                String which = t == task ? "new" : t == saved ? "after save/load" : "after sync";
                for (ItemStack stack : c.matching()) {
                    if (!t.test(stack)) {
                        failures.add(c.filter() + " (" + which + ") should match " + describe(stack));
                    }
                }
                for (ItemStack stack : c.notMatching()) {
                    if (t.test(stack)) {
                        failures.add(c.filter() + " (" + which + ") should not match " + describe(stack));
                    }
                }
            }
        }
        failures.forEach(f -> SmartFilterExtras.LOGGER.warn("[quest test] {}", f));
        helper.assertTrue(failures.isEmpty(), failures.size() + " quest task check(s) failed: " + String.join("; ", failures));
    }

    /** The "detect" path: a non-consuming task counts the matching stacks in the player's inventory. */
    static void detectCountsMatchingStacks(GameTestHelper helper) {
        ServerQuestFile file = questFile(helper);
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.fromString("5f1c7e2a-0000-4000-8000-000000000010"), "sfe_quest_test"));
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 3));
        player.getInventory().setItem(5, new ItemStack(Items.STONE, 2));
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 7));

        List<String> failures = new ArrayList<>();
        for (String filter : List.of("smart_filter_extras:items(minecraft:stone,minecraft:dirt)",
                "smartfilterextras:items(minecraft:stone,minecraft:dirt)")) {
            // More than the player has, so the task stays open and only progress is checked.
            ItemTask task = newTask(file, filterStack(filter), 10);
            TeamData data = new TeamData(UUID.randomUUID(), file);
            PlayerInventorySummary.build(player);
            task.submitTask(data, player, ItemStack.EMPTY);
            long progress = data.getProgress(task);
            if (progress != 5) {
                failures.add(filter + ": progress " + progress + ", expected 5 (3 dirt + 2 stone, not the cobblestone)");
            }
        }
        player.getInventory().clearContent();
        failures.forEach(f -> SmartFilterExtras.LOGGER.warn("[quest test] {}", f));
        helper.assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private static ServerQuestFile questFile(GameTestHelper helper) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        helper.assertTrue(file != null, "FTB Quests has a server quest file");
        return file;
    }

    private static ItemTask newTask(ServerQuestFile file, ItemStack filter, int count) {
        Chapter chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup());
        Quest quest = new Quest(file.newID(), chapter);
        return new ItemTask(file.newID(), quest).setStackAndCount(filter, count);
    }

    /** Written to SNBT text and read back, as when the quest book is saved and the server restarts. */
    private static ItemTask snbtRoundTrip(ItemTask task, HolderLookup.Provider ra) {
        CompoundTag nbt = new SNBTCompoundTag();
        task.writeData(nbt, ra);
        SNBTCompoundTag read = SNBT.readLines(SNBT.writeLines(nbt));
        ItemTask copy = new ItemTask(task.id, task.getQuest());
        copy.readData(read, ra);
        return copy;
    }

    /** Sent over the network, as when the quest book is synced to a client and edited there. */
    private static ItemTask netRoundTrip(ItemTask task, RegistryAccess ra) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), ra, ConnectionType.NEOFORGE);
        try {
            task.writeNetData(buf);
            ItemTask copy = new ItemTask(task.id, task.getQuest());
            copy.readNetData(buf);
            return copy;
        } finally {
            buf.release();
        }
    }

    private static ItemStack filterStack(String filter) {
        ItemStack stack = new ItemStack(FTBFilterSystemAPI.api().filterItem());
        stack.set(filterComponent(), filter);
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<String> filterComponent() {
        return (DataComponentType<String>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath(FTBFilterSystemAPI.MOD_ID, "filter"));
    }

    private static List<ItemStack> stacks(Item... items) {
        List<ItemStack> out = new ArrayList<>();
        for (Item item : items) {
            out.add(new ItemStack(item));
        }
        return out;
    }

    private static String describe(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.has(DataComponents.POTION_CONTENTS) ? id + " " + stack.get(DataComponents.POTION_CONTENTS) : id;
    }
}
