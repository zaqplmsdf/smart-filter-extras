package smartfilterextras.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemAPI;
import dev.ftb.mods.ftbfiltersystem.api.FilterException;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import smartfilterextras.SmartFilterExtras;
import smartfilterextras.client.screen.ClickTracker;
import smartfilterextras.client.screen.SelectionModel;
import smartfilterextras.command.FilterDowngrader;
import smartfilterextras.filter.BlockTagFilter;
import smartfilterextras.filter.FuelFilter;
import smartfilterextras.filter.ItemEntry;
import smartfilterextras.filter.ItemIdList;
import smartfilterextras.filter.ItemListFilter;
import smartfilterextras.filter.ItemTagListFilter;
import smartfilterextras.filter.LegacyFilters;
import smartfilterextras.filter.TagListArgument;
import smartfilterextras.network.PresencePayload;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Server-side behaviour tests. Run with {@code gradlew runGameTestServer}; the run fails if any test fails.
 * The selection-model and click-tracker tests are pure logic, but live here so there is one test harness.
 */
@GameTestHolder(SmartFilterExtras.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SfeGameTests {
    private static final String TEMPLATE = "empty";

    private SfeGameTests() {
    }

    // ---------------------------------------------------------------- Item List filter

    @GameTest(template = TEMPLATE)
    public static void itemListParsesNormalisesAndMatches(GameTestHelper helper) {
        ItemListFilter filter = single(helper, "smart_filter_extras:items( minecraft:stone, dirt ,,minecraft:stone)", ItemListFilter.class);
        helper.assertValueEqual(filter.getItems(), List.of(Items.STONE, Items.DIRT), "resolved items");
        helper.assertValueEqual(filter.asString(ra(helper)), "smart_filter_extras:items(minecraft:stone,minecraft:dirt)", "serialized form");
        helper.assertTrue(filter.test(new ItemStack(Items.STONE)), "stone matches");
        helper.assertTrue(filter.test(new ItemStack(Items.DIRT, 64)), "dirt matches");
        helper.assertTrue(!filter.test(new ItemStack(Items.COBBLESTONE)), "cobblestone doesn't match");
        helper.assertTrue(!filter.test(ItemStack.EMPTY), "empty stack doesn't match");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void itemListKeepsMissingIdsAndStillMatchesTheRest(GameTestHelper helper) {
        ItemListFilter filter = single(helper, "smart_filter_extras:items(minecraft:dirt,nosuchmod:widget)", ItemListFilter.class);
        helper.assertValueEqual(filter.getItems(), List.of(Items.DIRT), "resolved items");
        helper.assertValueEqual(filter.getMissingEntries(), List.of("nosuchmod:widget"), "missing entries");
        helper.assertTrue(filter.test(new ItemStack(Items.DIRT)), "dirt still matches");
        helper.assertValueEqual(filter.asString(ra(helper)), "smart_filter_extras:items(minecraft:dirt,nosuchmod:widget)", "missing ID survives re-serialization");

        // Rebuilding from the GUI's point of view keeps the missing ID at the end.
        ItemListFilter rebuilt = ItemListFilter.of(null, List.of(ItemEntry.of(Items.OAK_LOG), ItemEntry.of(Items.DIRT)), filter.getMissingEntries(), ra(helper));
        helper.assertValueEqual(rebuilt.getStringArg(ra(helper)), "minecraft:oak_log,minecraft:dirt,nosuchmod:widget", "rebuilt list");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void itemListRejectsInvalidSyntaxAndOverlongLists(GameTestHelper helper) {
        assertParseFails(helper, "smart_filter_extras:items(Minecraft:Stone)", "upper case is not a valid ID");
        String tooMany = IntStream.rangeClosed(0, ItemIdList.MAX_ENTRIES).mapToObj(i -> "x:item_" + i).collect(Collectors.joining(","));
        assertParseFails(helper, "smart_filter_extras:items(" + tooMany + ")", "more than " + ItemIdList.MAX_ENTRIES + " entries");
        String exactlyMax = IntStream.range(0, ItemIdList.MAX_ENTRIES).mapToObj(i -> "x:item_" + i).collect(Collectors.joining(","));
        ItemListFilter max = single(helper, "smart_filter_extras:items(" + exactlyMax + ")", ItemListFilter.class);
        helper.assertValueEqual(max.getMissingEntries().size(), ItemIdList.MAX_ENTRIES, "exactly the maximum is allowed");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void emptyItemListParsesAndMatchesNothing(GameTestHelper helper) {
        ItemListFilter filter = single(helper, "smart_filter_extras:items()", ItemListFilter.class);
        helper.assertTrue(filter.getItems().isEmpty() && filter.getMissingEntries().isEmpty(), "empty list");
        helper.assertTrue(!filter.test(new ItemStack(Items.STONE)), "matches nothing");
        ItemListFilter defaultInstance = new ItemListFilter(null);
        helper.assertTrue(defaultInstance.getItems().isEmpty(), "the Add-menu default selects nothing");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void itemListSummaryNamesItemsAndCountsTheRest(GameTestHelper helper) {
        ItemListFilter filter = single(helper, "smart_filter_extras:items(minecraft:stone,minecraft:dirt,minecraft:oak_log,minecraft:sand,nosuchmod:x)", ItemListFilter.class);
        Component summary = filter.getDisplayArg(ra(helper));
        String text = summary.getString();
        helper.assertTrue(text.contains(Items.STONE.getDescription().getString()), "names the first item: " + text);
        helper.assertTrue(text.contains(Items.DIRT.getDescription().getString()), "names the second item: " + text);
        helper.assertTrue(containsTranslation(summary, "smart_filter_extras.items.summary.more", 2), "counts the other 2 items");
        helper.assertTrue(containsTranslation(summary, "smart_filter_extras.items.summary.missing", 1), "flags the missing ID");
        helper.succeed();
    }

    // ---------------------------------------------------------------- Block Tag filter

    @GameTest(template = TEMPLATE)
    public static void blockTagAcceptsHashPrefixAndMatchesBlockItems(GameTestHelper helper) {
        BlockTagFilter logs = single(helper, "smart_filter_extras:block_tag(#minecraft:logs)", BlockTagFilter.class);
        helper.assertValueEqual(logs.asString(ra(helper)), "smart_filter_extras:block_tag(minecraft:logs)", "'#' is dropped on save");
        helper.assertTrue(logs.test(new ItemStack(Items.OAK_LOG)), "oak log is in #logs");
        helper.assertTrue(!logs.test(new ItemStack(Items.OAK_PLANKS)), "planks are not logs");
        helper.assertTrue(!logs.test(new ItemStack(Items.STICK)), "stick is not a block item");
        helper.assertTrue(!logs.test(ItemStack.EMPTY), "empty stack");

        BlockTagFilter pickaxe = single(helper, "smart_filter_extras:block_tag(minecraft:mineable/pickaxe)", BlockTagFilter.class);
        helper.assertTrue(pickaxe.test(new ItemStack(Items.STONE)), "stone is mineable with a pickaxe");
        helper.assertTrue(!pickaxe.test(new ItemStack(Items.DIRT)), "dirt is not");

        // Documented behaviour: seeds are block items that place crops.
        BlockTagFilter crops = single(helper, "smart_filter_extras:block_tag(minecraft:crops)", BlockTagFilter.class);
        helper.assertTrue(crops.test(new ItemStack(Items.WHEAT_SEEDS)), "wheat seeds place a crop");

        BlockTagFilter several = single(helper, "smart_filter_extras:block_tag(#minecraft:logs, minecraft:planks,minecraft:logs)", BlockTagFilter.class);
        helper.assertValueEqual(several.asString(ra(helper)), "smart_filter_extras:block_tag(minecraft:logs,minecraft:planks)", "several tags, duplicates dropped");
        helper.assertTrue(several.test(new ItemStack(Items.OAK_LOG)) && several.test(new ItemStack(Items.OAK_PLANKS)), "matches either tag");
        helper.assertTrue(!several.test(new ItemStack(Items.STONE)), "but not stone");
        helper.assertTrue(containsTranslation(several.getDisplayArg(ra(helper)), "smart_filter_extras.items.summary.more", 1), "summary counts the other tag");

        BlockTagFilter unknown = single(helper, "smart_filter_extras:block_tag(nosuchmod:nothing)", BlockTagFilter.class);
        helper.assertTrue(!unknown.test(new ItemStack(Items.STONE)), "an unknown tag matches nothing");
        assertParseFails(helper, "smart_filter_extras:block_tag(#)", "empty tag");
        helper.succeed();
    }

    // ---------------------------------------------------------------- Item Tags filter

    @GameTest(template = TEMPLATE)
    public static void itemTagListParsesMatchesAndDowngrades(GameTestHelper helper) {
        ItemTagListFilter filter = single(helper, "smart_filter_extras:item_tag(#c:ingots, minecraft:logs,c:ingots)", ItemTagListFilter.class);
        helper.assertValueEqual(filter.asString(ra(helper)), "smart_filter_extras:item_tag(c:ingots,minecraft:logs)", "'#' dropped, duplicates dropped");
        helper.assertTrue(filter.test(new ItemStack(Items.IRON_INGOT)), "iron ingot is in #c:ingots");
        helper.assertTrue(filter.test(new ItemStack(Items.OAK_LOG)), "oak log is in #minecraft:logs");
        helper.assertTrue(!filter.test(new ItemStack(Items.STONE)), "stone is in neither");
        helper.assertTrue(!filter.test(ItemStack.EMPTY), "empty stack");
        helper.assertTrue(containsTranslation(filter.getDisplayArg(ra(helper)), "smart_filter_extras.items.summary.more", 1), "summary counts the other tag");

        ItemTagListFilter defaultInstance = new ItemTagListFilter(null);
        helper.assertTrue(!defaultInstance.getTags().isEmpty(), "the Add-menu default is a valid filter");
        ItemTagListFilter unknown = single(helper, "smart_filter_extras:item_tag(nosuchmod:nothing)", ItemTagListFilter.class);
        helper.assertTrue(!unknown.test(new ItemStack(Items.IRON_INGOT)), "an unknown tag matches nothing");
        assertParseFails(helper, "smart_filter_extras:item_tag(#)", "empty tag");
        assertParseFails(helper, "smart_filter_extras:item_tag()", "no tags");
        String tooMany = IntStream.rangeClosed(0, ItemTagListFilter.MAX_TAGS).mapToObj(i -> "x:tag_" + i).collect(Collectors.joining(","));
        assertParseFails(helper, "smart_filter_extras:item_tag(" + tooMany + ")", "more than " + ItemTagListFilter.MAX_TAGS + " tags");

        // Downgrade: FFS's own item_tag filter matches exactly the same items, one tag each.
        SmartFilter root = parse(helper, "and(not(smart_filter_extras:item_tag(c:ingots,minecraft:logs))smart_filter_extras:item_tag(c:gems))");
        FilterDowngrader.Result result = FilterDowngrader.downgrade(root, ra(helper));
        helper.assertValueEqual(result.filterString(), "and(not(or(item_tag(c:ingots)item_tag(minecraft:logs)))item_tag(c:gems))", "converted string");
        helper.assertValueEqual(result.convertedLists(), 2, "both item tag filters converted");
        SmartFilter converted = parse(helper, result.filterString());
        for (ItemStack stack : List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND),
                new ItemStack(Items.EMERALD), new ItemStack(Items.STONE))) {
            helper.assertValueEqual(converted.test(stack), root.test(stack), "same result for " + stack);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- variants (entries with components)

    @GameTest(template = TEMPLATE)
    public static void variantEntriesMatchOnlyThatVariant(GameTestHelper helper) {
        ItemStack healing = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        ItemStack regeneration = PotionContents.createItemStack(Items.POTION, Potions.REGENERATION);
        ItemEntry healingEntry = ItemEntry.variantOf(healing);
        helper.assertTrue(!healingEntry.isPlain(), "a healing potion is a variant");

        ItemListFilter list = ItemListFilter.of(null, List.of(healingEntry, ItemEntry.of(Items.STONE)), List.of(), ra(helper));
        String text = list.asString(ra(helper));
        helper.assertTrue(text.startsWith("smart_filter_extras:items(minecraft:potion[") && text.contains("healing"), "serialized: " + text);

        ItemListFilter parsed = single(helper, text, ItemListFilter.class);
        helper.assertValueEqual(parsed.getEntries(), List.of(healingEntry, ItemEntry.of(Items.STONE)), "round trip");
        helper.assertTrue(parsed.test(healing.copy()), "healing potion matches");
        ItemStack namedHealing = healing.copy();
        namedHealing.set(DataComponents.CUSTOM_NAME, Component.literal("Red stuff"));
        helper.assertTrue(parsed.test(namedHealing), "extra components don't matter");
        helper.assertTrue(!parsed.test(regeneration), "regeneration potion doesn't match");
        helper.assertTrue(!parsed.test(new ItemStack(Items.POTION)), "water bottle doesn't match");
        helper.assertTrue(parsed.test(new ItemStack(Items.STONE)), "plain entries still match");
        helper.assertTrue(parsed.getDisplayArg(ra(helper)).getString().contains(healing.getHoverName().getString()), "summary names the potion");

        ItemEntry damaged = ItemEntry.variantOf(Util.make(new ItemStack(Items.DIAMOND_PICKAXE), s -> s.setDamageValue(100)));
        helper.assertTrue(damaged.isPlain(), "damage is ignored, so a worn pickaxe is just 'diamond pickaxe'");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void variantsLeaveOutUsageStateAndStoredContents(GameTestHelper helper) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.STONE))));
        helper.assertTrue(ItemEntry.variantOf(shulker).isPlain(), "a filled shulker box is just 'shulker box' (contents aren't a variant)");

        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.DIRT))));
        helper.assertTrue(ItemEntry.variantOf(bundle).isPlain(), "bundle contents aren't a variant");

        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        helper.assertTrue(ItemEntry.variantOf(crossbow).isPlain(), "a loaded crossbow is just 'crossbow'");

        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        named.setDamageValue(50);
        ItemEntry entry = ItemEntry.variantOf(named);
        helper.assertTrue(!entry.isPlain() && entry.components().get(DataComponents.CUSTOM_NAME) != null
                && entry.components().get(DataComponents.DAMAGE) == null, "name kept, damage dropped: " + entry.components());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void downgradeRefusesResultsFfsCannotStore(GameTestHelper helper) {
        // 225 renamed stones: fits FFS's 32767-character limit as an item list, but not once each entry is
        // spelled out as and(item(...)component(...)).
        List<ItemEntry> entries = new ArrayList<>();
        for (int i = 0; i < 225; i++) {
            String name = "Name" + i + "a".repeat(90);
            entries.add(new ItemEntry(Items.STONE, DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal(name)).build()));
        }
        String original = ItemListFilter.of(null, entries, List.of(), ra(helper)).asString(ra(helper));
        String converted = FilterDowngrader.downgrade(parse(helper, original), ra(helper)).filterString();
        helper.assertTrue(original.length() <= ItemIdList.MAX_FILTER_CHARS && converted.length() > ItemIdList.MAX_FILTER_CHARS,
                "test setup: original " + original.length() + " fits, converted " + converted.length() + " doesn't");

        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.fromString("5f1c7e2a-0000-4000-8000-000000000002"), "sfe_test_long"));
        DataComponentType<String> component = filterComponent();
        ItemStack filterStack = new ItemStack(FTBFilterSystemAPI.api().filterItem());
        filterStack.set(component, original);
        player.setItemInHand(InteractionHand.MAIN_HAND, filterStack);
        try {
            helper.getLevel().getServer().getCommands().getDispatcher().execute("smart_filter_extras downgrade", player.createCommandSourceStack());
            helper.fail("expected the command to refuse a result longer than " + ItemIdList.MAX_FILTER_CHARS);
        } catch (CommandSyntaxException expected) {
            helper.assertTrue(expected.getRawMessage() instanceof Component c && c.getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.command.downgrade.too_long"), "error message: " + expected.getMessage());
        }
        helper.assertValueEqual(player.getMainHandItem().get(component), original, "held filter left as it was");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void variantTextWithParenthesesSurvivesTheFilterParser(GameTestHelper helper) {
        ItemStack named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny (rare), \\ really"));
        ItemEntry entry = ItemEntry.variantOf(named);
        ItemListFilter list = ItemListFilter.of(null, List.of(entry, ItemEntry.of(Items.DIRT)), List.of(), ra(helper));
        String text = list.asString(ra(helper));
        String argument = text.substring(text.indexOf('(') + 1, text.length() - 1);
        helper.assertTrue(argument.indexOf('(') < 0 && argument.indexOf(')') < 0 && argument.indexOf('\\') < 0,
                "no characters FFS treats specially inside the argument: " + argument);
        ItemListFilter parsed = single(helper, "or(and(" + text + "item(minecraft:diamond)))", SmartFilter.Compound.class)
                .getChildren().get(0) instanceof SmartFilter.Compound and
                ? (and.getChildren().get(0) instanceof ItemListFilter l ? l : null) : null;
        ItemListFilter unused = single(helper, "and(" + text + "item(minecraft:diamond))", SmartFilter.Compound.class)
                .getChildren().get(0) instanceof ItemListFilter l ? l : null;
        helper.assertTrue(parsed != null && unused != null, "parsed inside or(and(...)): " + text);
        helper.assertValueEqual(parsed.getEntries(), List.of(entry, ItemEntry.of(Items.DIRT)), "round trip with ( ) , and \\ in a name");
        helper.assertTrue(parsed.test(named) && !parsed.test(new ItemStack(Items.DIAMOND)), "matches the named diamond only");

        ItemListFilter withMissing = single(helper, "smart_filter_extras:items(minecraft:dirt,nosuchmod:thing[nosuchmod:data=1])", ItemListFilter.class);
        helper.assertValueEqual(withMissing.getMissingEntries(), List.of("nosuchmod:thing[nosuchmod:data=1]"), "unknown variant kept as written");
        helper.assertTrue(withMissing.test(new ItemStack(Items.DIRT)), "the rest still matches");

        helper.assertValueEqual(ItemIdList.decode(ItemIdList.encode("a(b)c\\d%e 100%")), "a(b)c\\d%e 100%", "encode/decode round trip");
        helper.assertValueEqual(ItemIdList.decode("50% off %ZZ"), "50% off %ZZ", "stray percent signs are left alone");

        // The named diamond can't be expressed with FFS's own component filter, so downgrade leaves the list alone.
        FilterDowngrader.Result result = FilterDowngrader.downgrade(parse(helper, text), ra(helper));
        helper.assertValueEqual(result.unsupported(), 1, "list left unchanged");
        helper.assertValueEqual(result.filterString(), text, "unchanged text");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void downgradeKeepsVariantsExact(GameTestHelper helper) {
        ItemStack healing = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        ItemListFilter list = ItemListFilter.of(null, List.of(ItemEntry.variantOf(healing), ItemEntry.of(Items.STONE)), List.of(), ra(helper));
        SmartFilter root = parse(helper, list.asString(ra(helper)));
        FilterDowngrader.Result result = FilterDowngrader.downgrade(root, ra(helper));
        helper.assertTrue(result.filterString().startsWith("or(and(item(minecraft:potion)component(fuzzy:"), "converted: " + result.filterString());
        SmartFilter converted = parse(helper, result.filterString());
        helper.assertTrue(!result.filterString().contains(SmartFilterExtras.MOD_ID), "no longer needs this mod");
        for (ItemStack stack : List.of(healing, PotionContents.createItemStack(Items.POTION, Potions.REGENERATION), new ItemStack(Items.POTION), new ItemStack(Items.STONE))) {
            helper.assertValueEqual(converted.test(stack), root.test(stack), "same result for " + stack.getHoverName().getString());
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- Fuel filter

    @GameTest(template = TEMPLATE)
    public static void fuelFilterComparesBurnTime(GameTestHelper helper) {
        FuelFilter coalOrBetter = single(helper, "smart_filter_extras:fuel(>=1600)", FuelFilter.class);
        helper.assertValueEqual(coalOrBetter.asString(ra(helper)), "smart_filter_extras:fuel(>=1600)", "round trip");
        helper.assertTrue(coalOrBetter.test(new ItemStack(Items.COAL)), "coal burns 1600 ticks");
        helper.assertTrue(coalOrBetter.test(new ItemStack(Items.LAVA_BUCKET)), "lava bucket burns longer");
        helper.assertTrue(!coalOrBetter.test(new ItemStack(Items.STICK)), "a stick burns 100 ticks");
        helper.assertTrue(!coalOrBetter.test(ItemStack.EMPTY), "empty stack");

        FuelFilter anyFuel = new FuelFilter(null);
        helper.assertValueEqual(anyFuel.getStringArg(ra(helper)), ">0", "default is 'any fuel'");
        helper.assertTrue(anyFuel.test(new ItemStack(Items.STICK)) && !anyFuel.test(new ItemStack(Items.STONE)), "any fuel");

        FuelFilter notFuel = single(helper, "smart_filter_extras:fuel(=0)", FuelFilter.class);
        helper.assertTrue(notFuel.test(new ItemStack(Items.STONE)) && !notFuel.test(new ItemStack(Items.OAK_PLANKS)), "not fuel");

        helper.assertTrue(containsTranslation(coalOrBetter.getDisplayArg(ra(helper)), "smart_filter_extras.fuel.summary", ">=1600"), "summary");
        assertParseFails(helper, "smart_filter_extras:fuel(>50%)", "percentages make no sense for burn time");
        assertParseFails(helper, "smart_filter_extras:fuel(lots)", "not a comparison");
        helper.succeed();
    }

    // ---------------------------------------------------------------- pasting IDs

    @GameTest(template = TEMPLATE)
    public static void pastedTextYieldsItemIds(GameTestHelper helper) {
        helper.assertValueEqual(ItemIdList.extractIds("minecraft:stone, Dirt\nminecraft:oak_log;  ;minecraft:stone"),
                ids("minecraft:stone", "minecraft:dirt", "minecraft:oak_log"), "plain list with mixed separators and case");
        helper.assertValueEqual(ItemIdList.extractIds("smart_filter_extras:items(minecraft:stone,minecraft:dirt)"),
                ids("minecraft:stone", "minecraft:dirt"), "an Item List filter string");
        helper.assertValueEqual(ItemIdList.extractIds("or(item(minecraft:stone)item(minecraft:dirt))item_tag(minecraft:logs)"),
                ids("minecraft:stone", "minecraft:dirt"), "built-in item filters; item_tag is ignored");
        helper.assertValueEqual(ItemIdList.extractIds("\"minecraft:stone\" #minecraft:logs not:valid:id !!"),
                ids("minecraft:stone"), "quotes stripped, tags and junk skipped");
        helper.assertTrue(ItemIdList.extractIds("").isEmpty(), "empty clipboard");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void pastedTextYieldsTagIds(GameTestHelper helper) {
        helper.assertValueEqual(TagListArgument.extractTagIds("#c:ingots, c:gems\nminecraft:logs;#c:ingots"),
                ids("c:ingots", "c:gems", "minecraft:logs"), "plain list with '#' or a namespace, duplicates dropped");
        helper.assertValueEqual(TagListArgument.extractTagIds("smart_filter_extras:item_tag(c:ingots,minecraft:logs)"),
                ids("c:ingots", "minecraft:logs"), "this mod's filter string");
        helper.assertValueEqual(TagListArgument.extractTagIds("and(or(item_tag(c:ingots)ftbfiltersystem:item_tag(c:gems))items(minecraft:stone))"),
                ids("c:ingots", "c:gems"), "FFS's item_tag calls inside compounds; item lists ignored");
        helper.assertValueEqual(TagListArgument.extractTagIds("block_tag(#logs, planks)"),
                ids("minecraft:logs", "minecraft:planks"), "inside a tag call, bare IDs get the default namespace");
        helper.assertValueEqual(TagListArgument.extractTagIds("'#c:ingots' \"c:gems\""), ids("c:ingots", "c:gems"), "quotes stripped");
        helper.assertTrue(TagListArgument.extractTagIds("hello world logs").isEmpty(), "ordinary words aren't taken for tags");
        helper.assertTrue(TagListArgument.extractTagIds("").isEmpty(), "empty clipboard");
        helper.succeed();
    }

    // ---------------------------------------------------------------- translations

    private static final List<String> LANGUAGES = List.of("zh_cn", "zh_tw", "ja_jp", "ko_kr", "ru_ru", "de_de", "fr_fr", "es_es", "pt_br");

    /** Every language has exactly the English keys, with the same number of %s placeholders in each string. */
    @GameTest(template = TEMPLATE)
    public static void translationsHaveTheSameKeysAndPlaceholders(GameTestHelper helper) {
        Map<String, String> english = language("en_us");
        helper.assertTrue(english.size() > 100, "English file loaded: " + english.size() + " keys");
        for (String code : LANGUAGES) {
            Map<String, String> other = language(code);
            helper.assertValueEqual(other.keySet(), english.keySet(), code + " has the same keys as en_us");
            for (Map.Entry<String, String> entry : english.entrySet()) {
                helper.assertValueEqual(placeholders(other.get(entry.getKey())), placeholders(entry.getValue()),
                        code + " " + entry.getKey() + ": %s placeholders");
            }
        }
        helper.succeed();
    }

    private static Map<String, String> language(String code) {
        String path = "/assets/" + SmartFilterExtras.MOD_ID + "/lang/" + code + ".json";
        try (java.io.InputStream in = SmartFilterExtras.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("language file missing: " + path);
            }
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> strings = new java.util.TreeMap<>();
            json.entrySet().forEach(e -> strings.put(e.getKey(), e.getValue().getAsString()));
            return strings;
        } catch (java.io.IOException e) {
            throw new AssertionError("can't read " + path + ": " + e);
        }
    }

    private static int placeholders(String text) {
        int count = 0;
        for (int i = text.indexOf("%s"); i >= 0; i = text.indexOf("%s", i + 2)) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- integration with FFS

    @GameTest(template = TEMPLATE)
    public static void filtersWorkInsideCompoundFilters(GameTestHelper helper) {
        SmartFilter root = parse(helper, "and(smart_filter_extras:items(minecraft:oak_log,minecraft:stone)smart_filter_extras:block_tag(minecraft:logs))");
        helper.assertTrue(root.test(new ItemStack(Items.OAK_LOG)), "oak log is in both");
        helper.assertTrue(!root.test(new ItemStack(Items.STONE)), "stone is not a log");
        SmartFilter not = parse(helper, "not(smart_filter_extras:items(minecraft:stone))");
        helper.assertTrue(!not.test(new ItemStack(Items.STONE)) && not.test(new ItemStack(Items.DIRT)), "not(...)");
        helper.assertTrue(FTBFilterSystemAPI.api().getRegistry().allFilterKeys()
                .containsAll(Set.of(ItemListFilter.ID, ItemTagListFilter.ID, BlockTagFilter.ID, FuelFilter.ID)), "all filters registered");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void builtInItemFilterStaysForSingleItems(GameTestHelper helper) {
        SmartFilter item = single(helper, "item(minecraft:stone)", SmartFilter.class);
        helper.assertValueEqual(item.getClass().getName(), "dev.ftb.mods.ftbfiltersystem.filter.ItemFilter", "FFS's own item filter class");
        helper.assertValueEqual(item.asString(ra(helper)), "item(minecraft:stone)", "round trip");
        SmartFilter namespaced = single(helper, "ftbfiltersystem:item(minecraft:dirt)", SmartFilter.class);
        helper.assertValueEqual(namespaced.getClass().getName(), "dev.ftb.mods.ftbfiltersystem.filter.ItemFilter", "with its namespace too");
        assertParseFails(helper, "item(nosuchmod:widget)", "an unknown single item is still an error, as in FFS");
        assertParseFails(helper, "item()", "an empty item filter is still an error, as in FFS");
        helper.succeed();
    }

    // ---------------------------------------------------------------- filters saved by older versions

    @GameTest(template = TEMPLATE)
    public static void filtersFromOlderVersionsStillParse(GameTestHelper helper) {
        // The original FTB Filter System Addons 1.0.0 replaced FFS's item filter with one taking several items.
        ItemListFilter multi = single(helper, "item(minecraft:stone, minecraft:dirt)", ItemListFilter.class);
        helper.assertValueEqual(multi.getItems(), List.of(Items.STONE, Items.DIRT), "several items become an Item List");
        helper.assertValueEqual(multi.asString(ra(helper)), "smart_filter_extras:items(minecraft:stone,minecraft:dirt)", "and save as one");
        helper.assertTrue(multi.test(new ItemStack(Items.DIRT)) && !multi.test(new ItemStack(Items.COBBLESTONE)), "matches either item");
        single(helper, "ftbfiltersystem:item(minecraft:stone,minecraft:dirt)", ItemListFilter.class);
        BlockTagFilter addonsTag = single(helper, "ftbfiltersystemaddons:block_tag(minecraft:logs)", BlockTagFilter.class);
        helper.assertValueEqual(addonsTag.asString(ra(helper)), "smart_filter_extras:block_tag(minecraft:logs)", "addon block tag");
        helper.assertTrue(addonsTag.test(new ItemStack(Items.OAK_LOG)), "addon block tag matches");

        // The 2.x test builds, before the mod ID became smart_filter_extras.
        ItemListFilter v2Items = single(helper, "smartfilterextras:items(minecraft:stone,minecraft:potion[minecraft:potion_contents={potion:\"minecraft:healing\"}])", ItemListFilter.class);
        helper.assertTrue(v2Items.getStringArg(ra(helper)).startsWith("minecraft:stone,minecraft:potion["), "2.x item list: " + v2Items.getStringArg(ra(helper)));
        helper.assertTrue(v2Items.asString(ra(helper)).startsWith("smart_filter_extras:items("), "saves under the new ID");
        helper.assertTrue(v2Items.test(PotionContents.createItemStack(Items.POTION, Potions.HEALING))
                && !v2Items.test(PotionContents.createItemStack(Items.POTION, Potions.WATER)), "variant entry still exact");
        helper.assertValueEqual(single(helper, "smartfilterextras:item_tag(c:ingots)", ItemTagListFilter.class).asString(ra(helper)),
                "smart_filter_extras:item_tag(c:ingots)", "2.x item tags");
        helper.assertValueEqual(single(helper, "smartfilterextras:block_tag(minecraft:logs,minecraft:planks)", BlockTagFilter.class).asString(ra(helper)),
                "smart_filter_extras:block_tag(minecraft:logs,minecraft:planks)", "2.x block tags");
        helper.assertValueEqual(single(helper, "smartfilterextras:fuel(>=1600)", FuelFilter.class).asString(ra(helper)),
                "smart_filter_extras:fuel(>=1600)", "2.x fuel");

        SmartFilter nested = parse(helper, "or(not(smartfilterextras:items(minecraft:stone))item(minecraft:dirt,minecraft:sand))");
        helper.assertValueEqual(nested.asString(ra(helper)), "or(not(smart_filter_extras:items(minecraft:stone))smart_filter_extras:items(minecraft:dirt,minecraft:sand))",
                "old IDs inside compound filters, rewritten when saved");
        helper.assertTrue(nested.test(new ItemStack(Items.SAND)) && nested.test(new ItemStack(Items.OAK_LOG)) && !nested.test(new ItemStack(Items.STONE)), "nested result");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void oldFilterIdsStayOutOfTheAddMenu(GameTestHelper helper) {
        // FFS froze its registry when this server started, creating a default for every ID, old ones included:
        // an Item List for smart_filter_extras:items and another for smartfilterextras:items. Nothing on a
        // dedicated server opens a filter screen, so they are both still there.
        var defaults = FTBFilterSystemAPI.api().getRegistry().defaultFilterInstances();
        long itemListsBefore = defaults.stream().filter(f -> f.getId().equals(ItemListFilter.ID)).count();
        helper.assertValueEqual(itemListsBefore, 2L, "Item List defaults before hiding (new ID and 2.x ID)");
        helper.assertValueEqual(LegacyFilters.hideFromAddMenu(), LegacyFilters.OLD_IDS.size(), "one default hidden per old ID");
        Map<String, Long> perId = defaults.stream().collect(Collectors.groupingBy(f -> f.getId().toString(), Collectors.counting()));
        for (ResourceLocation id : List.of(ItemListFilter.ID, ItemTagListFilter.ID, BlockTagFilter.ID, FuelFilter.ID,
                ResourceLocation.fromNamespaceAndPath(FTBFilterSystemAPI.MOD_ID, "item"))) {
            helper.assertValueEqual(perId.getOrDefault(id.toString(), 0L), 1L, "Add menu entries for " + id);
        }
        helper.assertValueEqual(defaults.size(), perId.size(), "every Add menu entry is a different filter: " + perId);
        helper.assertTrue(FTBFilterSystemAPI.api().getRegistry().allFilterKeys().containsAll(LegacyFilters.OLD_IDS), "old IDs still parse");
        helper.succeed();
    }

    // ---------------------------------------------------------------- downgrade

    @GameTest(template = TEMPLATE)
    public static void downgradeRewritesItemListsToBuiltInFilters(GameTestHelper helper) {
        SmartFilter root = parse(helper, "and(smart_filter_extras:items(minecraft:stone,minecraft:dirt,nosuchmod:x)"
                + "not(smart_filter_extras:items(minecraft:oak_log))smart_filter_extras:block_tag(minecraft:logs)durability(>10)"
                + "smart_filter_extras:fuel(>0))");
        String before = root.asString(ra(helper));
        FilterDowngrader.Result result = FilterDowngrader.downgrade(root, ra(helper));
        helper.assertValueEqual(result.filterString(),
                "and(or(item(minecraft:stone)item(minecraft:dirt))not(item(minecraft:oak_log))smart_filter_extras:block_tag(minecraft:logs)durability(>10)"
                        + "smart_filter_extras:fuel(>0))",
                "converted string");
        helper.assertValueEqual(result.convertedLists(), 2, "converted lists");
        helper.assertValueEqual(result.droppedMissingIds(), 1, "dropped missing IDs");
        helper.assertValueEqual(result.keptOtherFilters(), 2, "kept block tag and fuel filters");
        helper.assertValueEqual(result.unsupported(), 0, "unsupported");
        helper.assertValueEqual(root.asString(ra(helper)), before, "input tree untouched");

        SmartFilter converted = parse(helper, result.filterString());
        for (ItemStack stack : List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT), new ItemStack(Items.OAK_LOG), new ItemStack(Items.BIRCH_LOG))) {
            helper.assertValueEqual(converted.test(stack), root.test(stack), "same result for " + stack);
        }

        FilterDowngrader.Result empty = FilterDowngrader.downgrade(parse(helper, "smart_filter_extras:items()"), ra(helper));
        helper.assertValueEqual(empty.filterString(), "or()", "empty list becomes an empty or()");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void downgradeCommandRewritesTheHeldFilter(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.fromString("5f1c7e2a-0000-4000-8000-000000000001"), "sfe_test"));
        DataComponentType<String> component = filterComponent();
        ItemStack filterStack = new ItemStack(FTBFilterSystemAPI.api().filterItem());
        filterStack.set(component, "smart_filter_extras:items(minecraft:stone,minecraft:dirt)");
        player.setItemInHand(InteractionHand.MAIN_HAND, filterStack);

        CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        try {
            int result = dispatcher.execute("smart_filter_extras downgrade", player.createCommandSourceStack());
            helper.assertValueEqual(result, 1, "command result (number of converted lists)");
        } catch (CommandSyntaxException e) {
            helper.fail("downgrade failed: " + e.getMessage());
        }
        helper.assertValueEqual(player.getMainHandItem().get(component), "or(item(minecraft:stone)item(minecraft:dirt))", "held filter rewritten");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        try {
            dispatcher.execute("smart_filter_extras downgrade", player.createCommandSourceStack());
            helper.fail("expected the command to fail without a filter in hand");
        } catch (CommandSyntaxException expected) {
            helper.assertTrue(expected.getRawMessage() instanceof Component c && c.getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.command.not_a_filter"), "error message: " + expected.getMessage());
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- networking

    @GameTest(template = TEMPLATE)
    public static void presencePayloadMakesTheModRequiredOnBothSides(GameTestHelper helper) {
        PayloadRegistration<?> registration = playRegistration(PresencePayload.TYPE.id());
        helper.assertTrue(registration != null, "presence payload is registered");
        helper.assertTrue(!registration.optional(), "presence payload is not optional");
        helper.assertValueEqual(registration.version(), PresencePayload.PROTOCOL_VERSION, "version");

        NegotiableNetworkComponent ours = new NegotiableNetworkComponent(registration);
        helper.assertTrue(!NetworkComponentNegotiator.negotiate(List.of(ours), List.of()).success(), "client without the mod is refused");
        helper.assertTrue(!NetworkComponentNegotiator.negotiate(List.of(), List.of(ours)).success(), "server without the mod is refused");
        helper.assertTrue(NetworkComponentNegotiator.negotiate(List.of(ours), List.of(ours)).success(), "both sides with the mod connect");
        helper.assertValueEqual(PresencePayload.PROTOCOL_VERSION, "4", "2.3.0 added the item tag filter, so the protocol is 4");
        NegotiableNetworkComponent otherVersion = new NegotiableNetworkComponent(ours.id(), "3", ours.flow(), false);
        helper.assertTrue(!NetworkComponentNegotiator.negotiate(List.of(ours), List.of(otherVersion)).success(), "protocol version mismatch is refused");
        helper.succeed();
    }

    // ---------------------------------------------------------------- GUI logic (pure)

    @GameTest(template = TEMPLATE)
    public static void selectionModelRules(GameTestHelper helper) {
        SelectionModel<String> model = new SelectionModel<>(3);
        helper.assertValueEqual(model.add("a"), SelectionModel.Change.ADDED, "add a");
        helper.assertValueEqual(model.add("a"), SelectionModel.Change.ALREADY_PRESENT, "add a again");
        helper.assertValueEqual(model.toggle("b"), SelectionModel.Change.ADDED, "toggle b on");
        helper.assertValueEqual(model.toggle("b"), SelectionModel.Change.REMOVED, "toggle b off");
        model.add("b");
        model.add("c");
        helper.assertValueEqual(model.add("d"), SelectionModel.Change.FULL, "capacity");
        helper.assertValueEqual(model.remove("a"), SelectionModel.Change.REMOVED, "the last-but-two can be removed");
        model.clear();
        helper.assertTrue(model.isEmpty(), "can become empty");

        SelectionModel<Integer> paged = new SelectionModel<>(100);
        paged.addAll(IntStream.range(0, 20).boxed().toList());
        helper.assertValueEqual(paged.pageCount(9), 3, "20 entries = 3 pages of 9");
        helper.assertValueEqual(paged.page(2, 9), List.of(18, 19), "last page");
        helper.assertValueEqual(paged.clampPage(5, 9), 2, "page clamped");
        helper.assertValueEqual(new SelectionModel<Integer>(5).pageCount(9), 1, "an empty selection still has one page");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void clickTrackerRules(GameTestHelper helper) {
        ClickTracker tracker = new ClickTracker(250);
        helper.assertTrue(!tracker.click("a", 1000), "first click");
        helper.assertTrue(tracker.click("a", 1100), "second click within 250 ms");
        helper.assertTrue(!tracker.click("a", 1150), "a third quick click starts over");
        helper.assertTrue(!tracker.click("b", 1200), "different key");
        helper.assertTrue(!tracker.click("b", 1500), "too slow");
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    private static List<ResourceLocation> ids(String... ids) {
        return java.util.Arrays.stream(ids).map(ResourceLocation::parse).toList();
    }

    private static HolderLookup.Provider ra(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    private static SmartFilter parse(GameTestHelper helper, String str) {
        return FTBFilterSystemAPI.api().parseFilter(str, ra(helper));
    }

    private static <T extends SmartFilter> T single(GameTestHelper helper, String str, Class<T> type) {
        SmartFilter root = parse(helper, str);
        if (!(root instanceof SmartFilter.Compound compound) || compound.getChildren().size() != 1) {
            throw new AssertionError("expected exactly one filter in " + str);
        }
        SmartFilter child = compound.getChildren().get(0);
        if (!type.isInstance(child)) {
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + child.getClass().getName());
        }
        return type.cast(child);
    }

    private static void assertParseFails(GameTestHelper helper, String str, String why) {
        try {
            parse(helper, str);
        } catch (FilterException expected) {
            return;
        }
        helper.fail("expected a parse failure (" + why + "): " + str);
    }

    private static boolean containsTranslation(Component component, String key, Object firstArg) {
        List<Component> all = new ArrayList<>();
        collect(component, all);
        return all.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents tc
                && tc.getKey().equals(key) && tc.getArgs().length > 0 && String.valueOf(tc.getArgs()[0]).equals(String.valueOf(firstArg)));
    }

    private static void collect(Component component, List<Component> out) {
        out.add(component);
        component.getSiblings().forEach(sibling -> collect(sibling, out));
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType<String> filterComponent() {
        return (DataComponentType<String>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath(FTBFilterSystemAPI.MOD_ID, "filter"));
    }

    @SuppressWarnings("unchecked")
    private static PayloadRegistration<?> playRegistration(ResourceLocation id) {
        try {
            Field field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> all =
                    (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null);
            return all.getOrDefault(ConnectionProtocol.PLAY, Map.of()).get(id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("can't read NeoForge payload registrations", e);
        }
    }
}
