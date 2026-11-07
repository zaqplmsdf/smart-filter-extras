package smartfilterextras.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import smartfilterextras.SmartFilterExtras;

/**
 * FTB Quests item tasks that use a smart filter as their item. These only do something when FTB Quests and
 * FTB XMod Compat are loaded ({@code gradlew -Pquests=true runGameTestServer}); otherwise they pass at once.
 * Everything that touches FTB Quests lives in {@link QuestChecks}, so this class loads without it.
 */
@GameTestHolder(SmartFilterExtras.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestGameTests {
    private QuestGameTests() {
    }

    private static boolean questsLoaded() {
        return ModList.get().isLoaded("ftbquests") && ModList.get().isLoaded("ftbxmodcompat");
    }

    @GameTest(template = "empty")
    public static void questItemTasksMatchThroughEveryFilter(GameTestHelper helper) {
        if (questsLoaded()) {
            QuestChecks.itemTasksMatch(helper);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void questItemTaskDetectsTheInventory(GameTestHelper helper) {
        if (questsLoaded()) {
            QuestChecks.detectCountsMatchingStacks(helper);
        }
        helper.succeed();
    }
}
