package smartfilterextras.gametest.client;

import dev.ftb.mods.ftbfiltersystem.api.FTBFilterSystemAPI;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterConfigScreen;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import smartfilterextras.SmartFilterExtras;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.tags.BlockTags;
import org.lwjgl.glfw.GLFW;
import smartfilterextras.client.screen.AbstractTagConfigScreen;
import smartfilterextras.client.screen.BlockTagConfigScreen;
import smartfilterextras.client.screen.FuelConfigScreen;
import smartfilterextras.client.screen.ItemCatalog;
import smartfilterextras.client.screen.ItemListConfigScreen;
import smartfilterextras.client.screen.ItemTagListConfigScreen;
import smartfilterextras.filter.ItemEntry;
import smartfilterextras.filter.BlockTagFilter;
import smartfilterextras.filter.FuelFilter;
import smartfilterextras.filter.ItemListFilter;
import smartfilterextras.filter.ItemTagListFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Dev-only automated client check (never shipped: it lives in the gametest source set). Enabled with
 * {@code -Dsmart_filter_extras.clientSmoke=true} (the {@code runClientSmoke} task). It creates a superflat world,
 * opens the config screens with a recording parent screen, drives them with mouse events through the screens' own
 * input handlers, takes screenshots, writes {@code smoke-result.json} into the game directory and quits.
 */
@EventBusSubscriber(modid = SmartFilterExtras.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("smart_filter_extras.clientSmoke");
    private static final int TIMEOUT_TICKS = 20 * 240;

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static final List<String> RESULTS = new ArrayList<>();
    private static int ticks;
    private static int waitTicks;
    private static boolean worldRequested;
    private static boolean scenarioQueued;
    private static boolean finished;
    private static int failures;

    // State shared between the steps of one scenario.
    private static RecordingParent parent;
    private static Screen screen;

    private ClientSmokeTest() {
    }

    private record Step(String name, int delayTicks, Runnable action) {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            record("timeout", false, "smoke test did not finish within " + TIMEOUT_TICKS + " ticks; steps left: " + STEPS.size());
            finish(mc);
            return;
        }
        if (mc.level == null) {
            if (!worldRequested && ticks > 60) {
                worldRequested = true;
                createWorld(mc);
            }
            return;
        }
        if (!scenarioQueued) {
            if (mc.player == null || mc.screen != null) {
                return;
            }
            scenarioQueued = true;
            queueScenarios();
            waitTicks = 40;  // let chunks and JEI settle
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        Step step = STEPS.poll();
        if (step == null) {
            finish(mc);
            return;
        }
        try {
            step.action().run();
            if (step.name() != null) {
                record(step.name(), true, "");
            }
        } catch (Throwable t) {
            record(step.name() == null ? "step" : step.name(), false, t.toString());
            SmartFilterExtras.LOGGER.error("Client smoke step failed: {}", step.name(), t);
        }
        Step next = STEPS.peek();
        waitTicks = next == null ? 5 : next.delayTicks();
    }

    private static void createWorld(Minecraft mc) {
        String name = "sfe-smoke-" + System.currentTimeMillis();
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(0L, false, false),
                registryAccess -> registryAccess.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                mc.screen);
    }

    // ---------------------------------------------------------------- scenarios

    private static void queueScenarios() {
        Minecraft mc = Minecraft.getInstance();
        HolderLookup.Provider ra = mc.level.registryAccess();
        SmartFilter.Compound root = (SmartFilter.Compound) FTBFilterSystemAPI.api().parseFilter("", ra);

        // 1. A new Item List filter starts empty; double-clicking one item saves exactly that item.
        step(null, 1, () -> {
            SmartFilter created = FTBFilterSystemAPI.api().createDefaultFilter(root, ItemListFilter.ID).orElseThrow();
            check(created instanceof ItemListFilter list && list.getItems().isEmpty(), "Add-menu default is an empty list");
            open(new ItemListConfigScreen((ItemListFilter) created, parent = new RecordingParent()), true);
        });
        step("new filter starts with nothing selected and Done disabled", 3, () -> {
            check(itemScreen().getSelectedItems().isEmpty(), "nothing selected, got " + itemScreen().getSelectedItems());
            check(!itemScreen().isDoneButtonActive(), "Done disabled while nothing is selected");
            setSearch("minecraft:dirt");
        });
        step("screenshot", 8, () -> screenshot("1-new-filter-search-dirt"));
        step("double click in the grid saves only the double-clicked item (no default stone)", 1, () -> {
            int index = itemScreen().getVisibleItems().indexOf(Items.DIRT);
            check(index >= 0 && index < itemScreen().getGridCapacity(), "dirt visible in the first page of the grid, index " + index);
            int[] p = itemScreen().gridSlotCentre(index);
            click(p);
            click(p);
            check(parent.replaced instanceof ItemListFilter, "filter was saved");
            check(((ItemListFilter) parent.replaced).getItems().equals(List.of(Items.DIRT)), "saved items " + ((ItemListFilter) parent.replaced).getItems());
            check(Minecraft.getInstance().screen == parent, "screen closed after double click");
        });

        // 2. Double click on the "selected" row removes exactly one item; Done saves the rest.
        step(null, 2, () -> open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE, Items.DIRT, Items.OAK_LOG)),
                parent = new RecordingParent()), false));
        step("screenshot", 3, () -> screenshot("2-existing-filter-three-items"));
        step("double click on the selected row removes only one item", 1, () -> {
            int[] p = itemScreen().selectedSlotCentre(0);
            click(p);
            click(p);
            check(itemScreen().getSelectedItems().equals(List.of(Items.DIRT, Items.OAK_LOG)), "selection " + itemScreen().getSelectedItems());
        });
        step("a later single click still removes", 10, () -> {
            click(itemScreen().selectedSlotCentre(0));
            check(itemScreen().getSelectedItems().equals(List.of(Items.OAK_LOG)), "selection " + itemScreen().getSelectedItems());
        });
        step("Done saves the remaining selection", 1, () -> {
            click(centre(findButton(screen, "gui.done")));
            check(parent.replaced instanceof ItemListFilter saved && saved.getItems().equals(List.of(Items.OAK_LOG)), "saved " + parent.replaced);
        });

        // 3. Double click on an item that is already selected keeps it (and saves).
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE, Items.DIRT)), parent = new RecordingParent()), false);
            setSearch("minecraft:stone");
        });
        step("double click on an already-selected item keeps it", 8, () -> {
            int index = itemScreen().getVisibleItems().indexOf(Items.STONE);
            check(index >= 0 && index < itemScreen().getGridCapacity(), "stone visible, index " + index);
            int[] p = itemScreen().gridSlotCentre(index);
            click(p);
            click(p);
            check(parent.replaced instanceof ItemListFilter saved && Set.copyOf(saved.getItems()).equals(Set.of(Items.STONE, Items.DIRT)),
                    "saved " + parent.replaced);
        });

        // 4. Clear disables Done; clicking the disabled Done does nothing.
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            setSearch("");
        });
        step("clear disables Done and Done can't save an empty list", 3, () -> {
            click(centre(findButton(screen, "smart_filter_extras.gui.clear")));
            check(itemScreen().getSelectedItems().isEmpty(), "cleared");
            check(!itemScreen().isDoneButtonActive(), "Done disabled");
            click(centre(findButton(screen, "gui.done")));
            check(parent.replaced == null && Minecraft.getInstance().screen == screen, "nothing saved, screen still open");
        });
        step("screenshot", 2, () -> screenshot("3-cleared-selection"));

        // 5. Unknown IDs are kept and shown.
        step(null, 2, () -> {
            SmartFilter parsed = ((SmartFilter.Compound) FTBFilterSystemAPI.api()
                    .parseFilter("smart_filter_extras:items(minecraft:dirt,nosuchmod:widget)", ra)).getChildren().get(0);
            open(new ItemListConfigScreen((ItemListFilter) parsed, parent = new RecordingParent()), false);
        });
        step("screenshot", 3, () -> screenshot("4-missing-id-kept"));
        step("missing IDs are kept when saving", 1, () -> {
            check(itemScreen().getKeptMissingEntries().equals(List.of("nosuchmod:widget")), "kept " + itemScreen().getKeptMissingEntries());
            click(centre(findButton(screen, "gui.done")));
            check(parent.replaced instanceof ItemListFilter saved
                            && saved.getStringArg(ra).equals("minecraft:dirt,nosuchmod:widget"),
                    "saved " + (parent.replaced == null ? null : ((ItemListFilter) parent.replaced).getStringArg(ra)));
        });

        // 6. More than 9 selected items are reachable by paging.
        step(null, 2, () -> {
            List<Item> twelve = List.of(Items.STONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.DIRT, Items.COBBLESTONE,
                    Items.OAK_PLANKS, Items.SAND, Items.GRAVEL, Items.OAK_LOG, Items.GLASS, Items.BRICKS);
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, twelve), parent = new RecordingParent()), false);
        });
        step("paging shows items 10-12", 3, () -> {
            check(itemScreen().getSelectedPage() == 0, "starts on page 1");
            click(centre(findButton(screen, "smart_filter_extras.gui.page.next")));
            check(itemScreen().getSelectedPage() == 1, "page 2 after '>'");
        });
        step("screenshot", 2, () -> screenshot("5-paged-selection"));
        step("JEI shows its list and accepts drops on the item list screen", 1, ClientSmokeTest::checkJei);
        step(null, 1, () -> screen.onClose());

        // 6b. Layout, right click, Shift batch actions, the view toggle and copy/paste.
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            setSearch("minecraft:oak_");
        });
        step("panel fits the window and the grid grows with it", 8, () -> {
            ItemListConfigScreen s = itemScreen();
            var panel = s.getPanelArea();
            check(panel.getY() >= 0 && panel.getY() + panel.getHeight() <= s.height, "panel " + panel.getY() + "+" + panel.getHeight() + " within " + s.height);
            check(s.getGridRows() == ItemListConfigScreen.gridRowsFor(s.height) && s.getGridRows() >= 4, "rows " + s.getGridRows() + " for height " + s.height);
        });
        step("right click in the grid removes, left click adds", 1, () -> {
            int index = itemScreen().getVisibleItems().indexOf(Items.OAK_LOG);
            check(index >= 0 && index < itemScreen().getGridCapacity(), "oak log visible, index " + index);
            int[] p = itemScreen().gridSlotCentre(index);
            click(p);
            check(itemScreen().getSelectedItems().contains(Items.OAK_LOG), "left click added");
        });
        step("right click removes", 6, () -> {
            int[] p = itemScreen().gridSlotCentre(itemScreen().getVisibleItems().indexOf(Items.OAK_LOG));
            click(p, 1);
            click(p, 1);
            check(!itemScreen().getSelectedItems().contains(Items.OAK_LOG) && itemScreen().getSelectedItems().contains(Items.STONE),
                    "oak log removed, stone kept: " + itemScreen().getSelectedItems());
            check(parent.replaced == null, "right double click does not save");
        });
        step("Shift actions add and remove everything shown", 1, () -> {
            List<Item> shown = itemScreen().getVisibleItems();
            check(shown.size() > 3, "several oak items shown: " + shown.size());
            itemScreen().addAllVisible();
            check(itemScreen().getSelectedItems().containsAll(shown), "all shown items added");
            check(itemScreen().getSelectedItems().size() == shown.size() + 1, "plus the stone that was already selected");
            itemScreen().removeAllVisible();
            check(itemScreen().getSelectedItems().equals(List.of(Items.STONE)), "only stone left: " + itemScreen().getSelectedItems());
        });
        step("the view toggle shows only the selection", 1, () -> {
            click(centre(findButton(screen, "smart_filter_extras.gui.show")));
            check(itemScreen().isShowingSelectedOnly(), "toggled on");
            setSearch("");
        });
        step("screenshot", 8, () -> {
            check(itemScreen().getVisibleItems().equals(List.of(Items.STONE)), "grid shows the selection: " + itemScreen().getVisibleItems());
            screenshot("9-selected-only-view");
            click(centre(findButton(screen, "smart_filter_extras.gui.show")));
            check(!itemScreen().isShowingSelectedOnly(), "toggled off");
        });
        step("paste adds known IDs from text and skips unknown ones; copy text is the selection", 1, () -> {
            itemScreen().pasteText("minecraft:dirt, minecraft:sand\nnosuchmod:widget  smart_filter_extras:items(minecraft:gravel)");
            check(itemScreen().getSelectedItems().equals(List.of(Items.STONE, Items.GRAVEL)), "filter string in the text wins: " + itemScreen().getSelectedItems());
            itemScreen().pasteText("minecraft:dirt, minecraft:sand\nnosuchmod:widget");
            check(itemScreen().getSelectedItems().equals(List.of(Items.STONE, Items.GRAVEL, Items.DIRT, Items.SAND)), "plain list: " + itemScreen().getSelectedItems());
            check(itemScreen().getStatusMessage() != null && itemScreen().getStatusMessage().getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.gui.pasted") && tc.getArgs()[0].equals(2) && tc.getArgs()[1].equals(1),
                    "status: 2 pasted, 1 skipped: " + itemScreen().getStatusMessage());
            check(itemScreen().selectionAsText().equals("minecraft:stone,minecraft:gravel,minecraft:dirt,minecraft:sand"), "copy text: " + itemScreen().selectionAsText());
        });
        step("the Paste and Copy buttons use the real clipboard", 4, ClientSmokeTest::checkRealClipboard);
        step("screenshot", 1, () -> screenshot("10-after-paste"));
        step(null, 1, () -> screen.onClose());

        // 6b2. Keyboard: Ctrl+C / Ctrl+V on the selection, typing goes to the search field, Enter saves.
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            setSearch("");
        });
        step("item list keys: Ctrl+C / Ctrl+V work on the selection while the search field isn't focused", 3, ClientSmokeTest::checkItemListClipboardKeys);
        step("item list keys: typing goes to the search field, then Ctrl+V edits the search instead", 1, () -> {
            EditBox box = itemScreen().getSearchBox();
            check(screen.getFocused() != box, "search field not focused at first");
            type("dirt");
            check(screen.getFocused() == box && box.getValue().equals("dirt"), "typed into the search field: '" + box.getValue() + "'");
            List<Item> before = itemScreen().getSelectedItems();
            key(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL);
            check(itemScreen().getSelectedItems().equals(before), "Ctrl+V while searching doesn't paste into the selection");
            box.setValue("");
        });
        step("item list keys: Enter saves", 6, () -> {
            check(key(InputConstants.KEY_RETURN, 0), "Enter handled");
            check(parent.replaced instanceof ItemListFilter saved && saved.getItems().contains(Items.STONE), "saved " + parent.replaced);
            check(Minecraft.getInstance().screen == parent, "screen closed");
        });
        step(null, 2, () -> {
            SmartFilter created = FTBFilterSystemAPI.api().createDefaultFilter(root, ItemListFilter.ID).orElseThrow();
            open(new ItemListConfigScreen((ItemListFilter) created, parent = new RecordingParent()), true);
        });
        step("item list keys: Enter with nothing selected doesn't save", 2, () -> {
            key(InputConstants.KEY_RETURN, 0);
            check(parent.replaced == null && Minecraft.getInstance().screen == screen, "still open, nothing saved");
            screen.onClose();
        });

        // 6c. Variant mode: pick one specific potion.
        step(null, 2, () -> {
            SmartFilter created = FTBFilterSystemAPI.api().createDefaultFilter(root, ItemListFilter.ID).orElseThrow();
            open(new ItemListConfigScreen((ItemListFilter) created, parent = new RecordingParent()), true);
            setSearch("");
        });
        step("search clear button empties the search", 2, () -> {
            setSearch("abc");
            click(itemScreen().searchClearCentre());
            check(searchText().isEmpty(), "search cleared: '" + searchText() + "'");
            click(centre(findButton(screen, "smart_filter_extras.gui.variants")));
            check(itemScreen().isVariantMode(), "variant mode on");
            setSearch("minecraft:potion");
        });
        step("screenshot", 8, () -> screenshot("13-variant-mode"));
        step("variant mode lists each potion and saves exactly the one picked", 1, () -> {
            ItemEntry healing = ItemEntry.variantOf(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            int index = itemScreen().getVisibleEntries().indexOf(healing);
            check(index >= 0 && index < itemScreen().getGridCapacity(), "healing potion listed, index " + index + " of " + itemScreen().getVisibleEntries().size());
            check(itemScreen().getVisibleEntries().size() > 10, "many potion variants: " + itemScreen().getVisibleEntries().size());
            int[] p = itemScreen().gridSlotCentre(index);
            click(p);
            click(p);
            check(parent.replaced instanceof ItemListFilter saved && saved.getEntries().equals(List.of(healing)), "saved " + parent.replaced);
            ItemListFilter saved = (ItemListFilter) parent.replaced;
            check(saved.test(PotionContents.createItemStack(Items.POTION, Potions.HEALING)), "matches healing potion");
            check(!saved.test(PotionContents.createItemStack(Items.POTION, Potions.REGENERATION)), "not regeneration");
        });
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            click(centre(findButton(screen, "smart_filter_extras.gui.variants")));
            check(!itemScreen().isVariantMode(), "variant mode off again");
            setSearch("shitou");
        });
        step("pinyin search finds stone by 'shitou' (only with Just Enough Characters in Chinese)", 8, () -> {
            boolean chinese = Minecraft.getInstance().options.languageCode.startsWith("zh");
            if (!ItemCatalog.pinyinSearch() || !chinese) {
                record("pinyin search not applicable (JECH " + ItemCatalog.pinyinSearch() + ", language " + Minecraft.getInstance().options.languageCode + ")", true, "");
            } else {
                check(itemScreen().getVisibleItems().contains(Items.STONE), "shitou finds stone: " + itemScreen().getVisibleItems());
            }
            setSearch("");
            screen.onClose();
        });

        // 6d. Fuel Value filter: presets and a live preview.
        step(null, 2, () -> {
            SmartFilter created = FTBFilterSystemAPI.api().createDefaultFilter(root, FuelFilter.ID).orElseThrow();
            open(new FuelConfigScreen((FuelFilter) created, parent = new RecordingParent()), true);
        });
        step("fuel presets set the comparison and the preview follows", 3, () -> {
            FuelConfigScreen fuel = (FuelConfigScreen) screen;
            int anyFuel = fuel.getMatchingCount();
            check(anyFuel > 20, "many fuels match > 0: " + anyFuel);
            click(centre(findButton(screen, "item.minecraft.coal")));
            check(fuel.getOperator() == dev.ftb.mods.ftbfiltersystem.api.NumericComparison.ComparisonOp.GE
                    && fuel.getValueBox().getValue().equals("1600"), "coal preset: " + fuel.getOperator() + fuel.getValueBox().getValue());
            check(fuel.getMatchingCount() > 0 && fuel.getMatchingCount() < anyFuel, "fewer fuels burn at least 1600: " + fuel.getMatchingCount());
        });
        step("screenshot", 2, () -> screenshot("11-fuel-filter"));
        step("fuel filter screen: Enter saves the comparison (even right after clicking a preset button)", 1, () -> {
            check(screen.getFocused() instanceof Button, "the clicked preset button has focus");
            check(key(InputConstants.KEY_RETURN, 0), "Enter handled");
            check(parent.replaced instanceof FuelFilter fuel && fuel.getStringArg(ra).equals(">=1600"), "saved " + parent.replaced);
        });

        // 7. Block tag screen: a new filter starts with nothing ticked; long tag IDs can be typed; double click saves.
        step(null, 2, () -> {
            SmartFilter created = FTBFilterSystemAPI.api().createDefaultFilter(root, BlockTagFilter.ID).orElseThrow();
            open(new BlockTagConfigScreen((BlockTagFilter) created, parent = new RecordingParent()), true);
            blockScreen().getSearchBox().setValue("");
        });
        step("new block tag filter starts with nothing ticked; Enter doesn't save an empty filter", 8, () -> {
            check(blockScreen().getTickedTags().isEmpty(), "nothing ticked (the default isn't preselected): " + blockScreen().getTickedTags());
            check(!blockScreen().isDoneButtonActive(), "Done disabled");
            key(InputConstants.KEY_RETURN, 0);
            check(parent.replaced == null && Minecraft.getInstance().screen == screen, "Enter with nothing ticked doesn't save");
            int logs = blockScreen().getListedTags().indexOf(BlockTags.LOGS);
            check(logs >= 0, "#logs listed");
            click(blockScreen().tagRowCentre(logs));
            check(blockScreen().getTickedTags().equals(List.of(BlockTags.LOGS)) && blockScreen().isDoneButtonActive(), "logs ticked");
            check(blockScreen().getListRowMisalignment() == 0, "list scrolled to a whole row: " + blockScreen().getListScroll());
        });
        // Taken once the "tick a tag" toast from the Enter press has faded.
        step("screenshot", 60, () -> screenshot("6-block-tag-default"));
        step("block tag: typing a 26-character ID works", 1, () -> {
            blockScreen().getSearchBox().setValue("#minecraft:mineable/pickaxe");
            check(blockScreen().getSearchBox().getValue().equals("#minecraft:mineable/pickaxe"), "search box kept the full text: " + blockScreen().getSearchBox().getValue());
        });
        step("block tag: double click saves the clicked tag", 8, () -> {
            List<TagKey<Block>> listed = blockScreen().getListedTags();
            TagKey<Block> pickaxe = TagKey.create(Registries.BLOCK, ResourceLocation.parse("minecraft:mineable/pickaxe"));
            check(listed.contains(pickaxe), "pickaxe tag listed: " + listed);
            check(blockScreen().getTickedTags().equals(List.of(TagKey.create(Registries.BLOCK, ResourceLocation.parse("minecraft:logs"))))
                    && blockScreen().isDoneButtonActive(), "a ticked tag hidden by the search stays ticked: " + blockScreen().getTickedTags());
            int[] p = blockScreen().tagRowCentre(listed.indexOf(pickaxe));
            click(p);
            click(p);
            check(parent.replaced instanceof BlockTagFilter saved && saved.getTags().equals(List.of(TagKey.create(Registries.BLOCK, ResourceLocation.parse("minecraft:logs")), pickaxe)),
                    "logs kept and pickaxe ticked: " + (parent.replaced instanceof BlockTagFilter b ? b.getTags() : parent.replaced));
        });
        // 7b. Reverse lookup: only tags that contain the chosen block.
        step(null, 2, () -> {
            open(new BlockTagConfigScreen(new BlockTagFilter(root), parent = new RecordingParent()), false);
            blockScreen().getSearchBox().setValue("");
        });
        step("block tag reverse lookup lists only tags containing the block", 8, () -> {
            int all = blockScreen().getListedTags().size();
            check(blockScreen().acceptDrop(new ItemStack(Items.OAK_LOG)), "oak log accepted");
            List<TagKey<Block>> listed = blockScreen().getListedTags();
            check(!listed.isEmpty() && listed.size() < all, "fewer tags: " + listed.size() + " of " + all);
            check(listed.stream().allMatch(tag -> Blocks.OAK_LOG.defaultBlockState().is(tag)), "every listed tag contains oak log: " + listed);
            check(listed.contains(TagKey.create(Registries.BLOCK, ResourceLocation.parse("minecraft:logs"))), "#logs listed");
            check(!blockScreen().acceptDrop(new ItemStack(Items.STICK)) && blockScreen().getLookupItem() == Items.OAK_LOG, "a stick is refused");
        });
        step("screenshot", 2, () -> screenshot("12-block-tag-lookup"));
        step("lookup can be cleared and cycles through inventory blocks", 1, () -> {
            click(centre(findButton(screen, "smart_filter_extras.gui.lookup_clear")));
            check(blockScreen().getLookupItem() == null, "cleared");
            var inventory = Minecraft.getInstance().player.getInventory();
            inventory.items.set(9, new ItemStack(Items.STICK));
            inventory.items.set(10, new ItemStack(Items.STONE));
            inventory.items.set(11, new ItemStack(Items.SAND));
            click(blockScreen().lookupSlotCentre());
            check(blockScreen().getLookupItem() == Items.STONE, "first block item in the inventory (stick skipped): " + blockScreen().getLookupItem());
            click(blockScreen().lookupSlotCentre());
            check(blockScreen().getLookupItem() == Items.SAND, "next block item: " + blockScreen().getLookupItem());
            click(blockScreen().lookupSlotCentre(), 1);
            check(blockScreen().getLookupItem() == null, "right click clears");
            for (int slot = 9; slot <= 11; slot++) {
                inventory.items.set(slot, ItemStack.EMPTY);
            }
        });
        step("block tag list: clicking the scrollbar track jumps there", 1, () -> {
            int max = blockScreen().getListMaxScroll();
            check(max > 0, "the full tag list scrolls: max " + max);
            click(blockScreen().scrollbarPoint(1.0));
            check(blockScreen().getListScroll() == max, "bottom of the track scrolls to the end: " + blockScreen().getListScroll() + " of " + max);
            click(blockScreen().scrollbarPoint(0.0));
            check(blockScreen().getListScroll() == 0, "top of the track scrolls back: " + blockScreen().getListScroll());
            click(blockScreen().scrollbarPoint(0.5));
            double half = blockScreen().getListScroll();
            check(half > max * 0.3 && half < max * 0.7, "middle of the track lands mid-list: " + half + " of " + max);
            check(blockScreen().getListRowMisalignment() == 0, "track clicks land on a whole row: " + half);
        });
        step("block tag list: the wheel scrolls one whole row per notch", 1, () -> {
            double before = blockScreen().getListScroll();
            blockScreen().scrollList(-1);
            check(blockScreen().getListScroll() == before + 12, "one row down: " + before + " -> " + blockScreen().getListScroll());
            blockScreen().scrollList(0.4);
            blockScreen().scrollList(0.4);
            check(blockScreen().getListScroll() == before + 12, "small touchpad deltas don't move yet: " + blockScreen().getListScroll());
            blockScreen().scrollList(0.4);
            check(blockScreen().getListScroll() == before, "but add up to a row: " + blockScreen().getListScroll());
            check(blockScreen().getListRowMisalignment() == 0, "aligned");
        });
        step("screenshot", 2, () -> screenshot("14-block-tag-scrolled"));
        step("JEI shows its list and accepts block drops on the block tag screen", 1, ClientSmokeTest::checkJeiBlockTag);
        step(null, 2, () -> {
            open(new BlockTagConfigScreen(new BlockTagFilter(root), parent = new RecordingParent()), false);
            blockScreen().getSearchBox().setValue("mymod:custom_ores");
        });
        step("screenshot", 8, () -> screenshot("7-block-tag-typed-id"));
        step("block tag: a typed ID that isn't loaded can be chosen", 1, () -> {
            TagKey<Block> custom = TagKey.create(Registries.BLOCK, ResourceLocation.parse("mymod:custom_ores"));
            check(!blockScreen().getListedTags().isEmpty() && blockScreen().getListedTags().get(0).equals(custom), "typed entry first: " + blockScreen().getListedTags());
            click(blockScreen().tagRowCentre(0));
            check(blockScreen().isDoneButtonActive(), "Done enabled after selecting");
            click(centre(findButton(screen, "gui.done")));
            check(parent.replaced instanceof BlockTagFilter saved
                            && saved.getTags().equals(List.of(TagKey.create(Registries.BLOCK, ResourceLocation.parse("minecraft:logs")), custom)),
                    "saved " + (parent.replaced instanceof BlockTagFilter b ? b.getTags() : parent.replaced));
        });

        // 8. Item Tags filter: starts empty, long IDs, hover preview, lookup by any item, keys, Enter saves.
        TagKey<Item> ingots = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ingots"));
        step(null, 2, () -> {
            SmartFilter created = FTBFilterSystemAPI.api().createDefaultFilter(root, ItemTagListFilter.ID).orElseThrow();
            open(new ItemTagListConfigScreen((ItemTagListFilter) created, parent = new RecordingParent()), true);
            tagScreen().getSearchBox().setValue("#c:storage_blocks/iron");
        });
        step("item tags: new filter starts empty; a 22-character ID can be typed", 8, () -> {
            check(tagScreen().getTickedTags().isEmpty() && !tagScreen().isDoneButtonActive(), "nothing ticked, Done disabled");
            check(tagScreen().getSearchBox().getValue().equals("#c:storage_blocks/iron"), "full text kept: " + tagScreen().getSearchBox().getValue());
            check(tagScreen().getListedTags().contains(TagKey.create(Registries.ITEM, ResourceLocation.parse("c:storage_blocks/iron"))),
                    "#c:storage_blocks/iron listed: " + tagScreen().getListedTags());
            tagScreen().getSearchBox().setValue("c:ingots");
        });
        step("item tags: click ticks, the preview lists ingots, the row tooltip shows the tag's items", 8, () -> {
            int row = tagScreen().getListedTags().indexOf(ingots);
            check(row >= 0, "#c:ingots listed: " + tagScreen().getListedTags());
            int[] p = tagScreen().tagRowCentre(row);
            click(p);
            check(tagScreen().getTickedTags().equals(List.of(ingots)) && tagScreen().isDoneButtonActive(), "ticked: " + tagScreen().getTickedTags());
            check(tagScreen().getRowPreviewSize(ingots) > 3, "#c:ingots has several items: " + tagScreen().getRowPreviewSize(ingots));
            check(tagScreen().getRowPreviewStacks(ingots).stream().anyMatch(s -> s.is(Items.IRON_INGOT)), "iron ingot in the tooltip preview");
            check(tagScreen().getRowPreviewSize(TagKey.create(Registries.ITEM, ResourceLocation.parse("nosuchmod:nothing"))) == -1, "unknown tag: not loaded");
            check(ingots.equals(tagScreen().getRowTagAt(p[0], p[1])), "the hovered row is #c:ingots");
            moveMouse(p);
        });
        step("screenshot", 12, () -> screenshot("15-item-tag-row-tooltip"));
        step("item tags: reverse lookup takes any item", 1, () -> {
            moveMouse(new int[]{0, 0});
            tagScreen().getSearchBox().setValue("");
            check(tagScreen().acceptDrop(new ItemStack(Items.IRON_INGOT)), "iron ingot accepted");
            List<TagKey<Item>> listed = tagScreen().getListedTags();
            check(listed.contains(ingots), "#c:ingots listed for iron ingot: " + listed);
            check(listed.stream().allMatch(tag -> new ItemStack(Items.IRON_INGOT).is(tag)), "every listed tag contains iron ingot: " + listed);
            check(tagScreen().acceptDrop(new ItemStack(Items.STICK)) && tagScreen().getLookupItem() == Items.STICK, "a stick is fine too (any item)");
        });
        step("screenshot", 2, () -> screenshot("16-item-tag-lookup"));
        step("item tags: typing goes to the search field", 1, () -> {
            tagScreen().setLookup(null);
            // Give the list keyboard focus without toggling a row: click its scrollbar.
            click(tagScreen().scrollbarPoint(0.0));
            check(screen.getFocused() != null && screen.getFocused() != tagScreen().getSearchBox(), "the list has focus: " + screen.getFocused());
            check(tagScreen().getTickedTags().equals(List.of(ingots)), "still ticked: " + tagScreen().getTickedTags());
            type("ingot");
            check(screen.getFocused() == tagScreen().getSearchBox() && tagScreen().getSearchBox().getValue().equals("ingot"),
                    "typed into the search field: '" + tagScreen().getSearchBox().getValue() + "'");
        });
        step("item tags: Enter saves", 8, () -> {
            check(key(InputConstants.KEY_RETURN, 0), "Enter handled");
            check(parent.replaced instanceof ItemTagListFilter saved && saved.getTags().equals(List.of(ingots)), "saved " + parent.replaced);
        });
        step(null, 2, () -> open(new ItemTagListConfigScreen(new ItemTagListFilter(root, List.of(ingots)), parent = new RecordingParent()), false));
        step("JEI accepts item drops on the item tag screen", 8, () -> {
            if (!ModList.get().isLoaded("jei")) {
                record("JEI not installed in this run; skipped", true, "");
                return;
            }
            JeiCheck.runTagScreen(tagScreen(), null, new ItemStack(Items.GOLD_INGOT), Items.GOLD_INGOT);
        });

        // 8b. FFS can send at most 32767 characters for a whole filter: the item list stays within what's left.
        StringBuilder bigSibling = new StringBuilder("smart_filter_extras:items(");
        for (int i = 0; i < 200; i++) {
            bigSibling.append(i == 0 ? "" : ",").append("nosuchmod:").append("x".repeat(130)).append(i);
        }
        bigSibling.append(")");
        SmartFilter.Compound bigRoot = (SmartFilter.Compound) FTBFilterSystemAPI.api()
                .parseFilter(bigSibling + "smart_filter_extras:items(minecraft:stone)", ra);
        step(null, 2, () -> {
            open(new ItemListConfigScreen((ItemListFilter) bigRoot.getChildren().get(1), parent = new RecordingParent()), false);
            setSearch("");
        });
        step("item list: Shift-adding everything stops before the whole filter gets too long for FFS", 8, () -> {
            int remaining = itemScreen().getRemainingCharacters();
            check(remaining > 1000 && remaining < 5000, "the 29k-character sibling leaves a few thousand characters: " + remaining);
            itemScreen().addAllVisible();
            Component status = itemScreen().getStatusMessage();
            check(status != null && status.getContents() instanceof TranslatableContents tc && tc.getKey().equals("smart_filter_extras.gui.added_until_too_long"),
                    "says why it stopped: " + status);
            check(itemScreen().getRemainingCharacters() >= 0 && itemScreen().getSelectedItems().size() < 256, "within the length limit: "
                    + itemScreen().getRemainingCharacters() + " left, " + itemScreen().getSelectedItems().size() + " items");
            click(centre(findButton(screen, "gui.done")));
            check(parent.replaced instanceof ItemListFilter, "saved");
            int total = bigRoot.asString(ra).length() - bigRoot.getChildren().get(1).asString(ra).length() + parent.replaced.asString(ra).length();
            check(total <= 32767, "the whole filter would be " + total + " characters, within FFS's limit");
        });
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            if (!itemScreen().isVariantMode()) {
                click(centre(findButton(screen, "smart_filter_extras.gui.variants")));
            }
        });
        step("item list: a variant with too much data is refused with a reason", 2, () -> {
            ItemStack huge = new ItemStack(Items.PAPER);
            huge.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("x".repeat(3000)));
            check(!itemScreen().acceptDrop(huge), "refused");
            Component status = itemScreen().getStatusMessage();
            check(status != null && status.getContents() instanceof TranslatableContents tc && tc.getKey().equals("smart_filter_extras.gui.entry_too_big"),
                    "says why: " + status);
        });
        // The longest message the screens show: it wraps onto several lines instead of being cut off.
        step("screenshot", 2, () -> screenshot("23-long-toast"));
        step("item list: contents aren't part of a variant", 1, () -> {
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));
            check(itemScreen().acceptDrop(shulker) && itemScreen().getSelectedEntries().contains(ItemEntry.of(Items.SHULKER_BOX)),
                    "a filled shulker box is added as a plain shulker box: " + itemScreen().getSelectedEntries());
            click(centre(findButton(screen, "smart_filter_extras.gui.variants")));
            check(!itemScreen().isVariantMode(), "variant mode off again");
        });

        // 8c. Window resizing: the panel is rebuilt at a size that fits, keeping the edits.
        step("item list: a smaller window reopens the screen with fewer rows, keeping the selection", 2, () -> {
            ItemListConfigScreen before = itemScreen();
            List<ItemEntry> selected = before.getSelectedEntries();
            before.resize(Minecraft.getInstance(), before.width, 200);
            screen = Minecraft.getInstance().screen;
            check(screen instanceof ItemListConfigScreen && screen != before, "reopened");
            check(itemScreen().getGridRows() == ItemListConfigScreen.gridRowsFor(200) && itemScreen().getGridRows() < before.getGridRows(),
                    "rows " + before.getGridRows() + " -> " + itemScreen().getGridRows());
            check(itemScreen().getPanelArea().getHeight() <= 200, "panel fits in 200: " + itemScreen().getPanelArea().getHeight());
            check(itemScreen().getSelectedEntries().equals(selected), "selection kept: " + itemScreen().getSelectedEntries());
            int realHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            screen.resize(Minecraft.getInstance(), screen.width, realHeight);
            screen = Minecraft.getInstance().screen;
            check(itemScreen().getGridRows() == before.getGridRows() && itemScreen().getSelectedEntries().equals(selected), "back to full size");
        });
        step("item list keys: Backspace edits the search even after a button took focus", 2, () -> {
            type("dirt");
            click(centre(findButton(screen, "smart_filter_extras.gui.show")));
            click(centre(findButton(screen, "smart_filter_extras.gui.show")));
            check(screen.getFocused() != itemScreen().getSearchBox(), "a button has focus");
            check(key(InputConstants.KEY_BACKSPACE, 0), "Backspace handled");
            check(itemScreen().getSearchBox().getValue().equals("dir") && screen.getFocused() == itemScreen().getSearchBox(),
                    "search is now '" + itemScreen().getSearchBox().getValue() + "'");
            itemScreen().getSearchBox().setValue("");
            screen.onClose();
        });
        step(null, 2, () -> {
            open(new BlockTagConfigScreen(new BlockTagFilter(root, List.of(BlockTags.LOGS, BlockTags.PLANKS)), parent = new RecordingParent()), false);
            blockScreen().getSearchBox().setValue("");
            blockScreen().acceptDrop(new ItemStack(Items.OAK_LOG));
        });
        step("tag screen: a smaller window reopens it with fewer rows, keeping ticks and lookup", 8, () -> {
            BlockTagConfigScreen before = blockScreen();
            before.resize(Minecraft.getInstance(), before.width, 200);
            screen = Minecraft.getInstance().screen;
            check(screen instanceof BlockTagConfigScreen && screen != before, "reopened");
            check(blockScreen().getListRows() == AbstractTagConfigScreen.listRowsFor(200) && blockScreen().getListRows() < before.getListRows(),
                    "rows " + before.getListRows() + " -> " + blockScreen().getListRows());
            check(blockScreen().getPanelArea().getHeight() <= 200, "panel fits in 200: " + blockScreen().getPanelArea().getHeight());
            check(blockScreen().getTickedTags().equals(List.of(BlockTags.LOGS, BlockTags.PLANKS)) && blockScreen().getLookupItem() == Items.OAK_LOG,
                    "ticks and lookup kept: " + blockScreen().getTickedTags() + ", " + blockScreen().getLookupItem());
            screen.resize(Minecraft.getInstance(), screen.width, Minecraft.getInstance().getWindow().getGuiScaledHeight());
            screen = Minecraft.getInstance().screen;
            check(blockScreen().getListRows() == before.getListRows(), "back to full size");
            blockScreen().setLookup(null);
        });

        // 8d. "Ticked only" view on the tag screens.
        step("tag screen: the 'ticked only' toggle lists just the ticked tags", 8, () -> {
            check(!blockScreen().isTickedOnly(), "off at first");
            click(centre(findButton(screen, "smart_filter_extras.gui.tag_ticked_only")));
            check(blockScreen().isTickedOnly(), "on");
            check(blockScreen().getListedTags().equals(List.of(BlockTags.LOGS, BlockTags.PLANKS)), "only ticked tags: " + blockScreen().getListedTags());
            click(blockScreen().tagRowCentre(0));
            check(blockScreen().getTickedTags().equals(List.of(BlockTags.PLANKS)) && blockScreen().getListedTags().size() == 2,
                    "unticking keeps the row in place until the list is rebuilt");
        });
        step("screenshot", 2, () -> screenshot("19-tag-ticked-only"));
        step("tag screen: toggling 'ticked only' off lists every tag again", 8, () -> {
            click(centre(findButton(screen, "smart_filter_extras.gui.tag_ticked_only")));
            check(!blockScreen().isTickedOnly() && blockScreen().getListedTags().size() > 50, "all tags: " + blockScreen().getListedTags().size());
            screen.onClose();
        });

        // 8d2. Grid marks: a variant already matched by its plain item, and a plain item with only some variants picked.
        ItemEntry healingEntry = ItemEntry.variantOf(PotionContents.createItemStack(Items.POTION, Potions.HEALING));
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.POTION)), parent = new RecordingParent()), false);
            if (!itemScreen().isVariantMode()) {
                click(centre(findButton(screen, "smart_filter_extras.gui.variants")));
            }
            setSearch("minecraft:potion");
        });
        step("item list: variants already matched by the plain item are marked as covered", 8, () -> {
            check(itemScreen().markOf(healingEntry) == ItemListConfigScreen.Mark.COVERED, "healing potion covered by plain potion: " + itemScreen().markOf(healingEntry));
            check(itemScreen().markOf(ItemEntry.of(Items.POTION)) == ItemListConfigScreen.Mark.SELECTED, "plain potion selected");
            check(itemScreen().markOf(ItemEntry.of(Items.STONE)) == ItemListConfigScreen.Mark.NONE, "stone not marked");
        });
        step("screenshot", 2, () -> screenshot("22-variants-covered"));
        step(null, 2, () -> {
            click(centre(findButton(screen, "smart_filter_extras.gui.variants")));
            check(!itemScreen().isVariantMode(), "variant mode off again");
            open(new ItemListConfigScreen(ItemListFilter.of(root, List.of(healingEntry), List.of(), ra), parent = new RecordingParent()), false);
        });
        step("item list: a plain item with only some variants picked is marked as partial", 2, () -> {
            check(itemScreen().markOf(ItemEntry.of(Items.POTION)) == ItemListConfigScreen.Mark.PARTIAL, "potion partial: " + itemScreen().markOf(ItemEntry.of(Items.POTION)));
            check(itemScreen().markOf(healingEntry) == ItemListConfigScreen.Mark.SELECTED, "the healing variant itself is selected");
        });

        // 8e. Unsaved changes: Cancel / Esc ask first, Done doesn't.
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            setSearch("minecraft:dirt");
        });
        step("unsaved changes: without edits, Cancel closes straight away", 8, () -> {
            check(!itemScreen().hasUnsavedChanges(), "no edits yet");
            click(centre(findButton(screen, "gui.cancel")));
            check(Minecraft.getInstance().screen == parent && parent.replaced == null, "closed without asking");
        });
        step(null, 2, () -> {
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            setSearch("minecraft:dirt");
        });
        step("unsaved changes: after an edit, Cancel asks", 8, () -> {
            int dirt = itemScreen().getVisibleItems().indexOf(Items.DIRT);
            check(dirt >= 0, "dirt listed");
            click(itemScreen().gridSlotCentre(dirt));
            check(itemScreen().hasUnsavedChanges(), "dirt added");
            click(centre(findButton(screen, "gui.cancel")));
            check(Minecraft.getInstance().screen instanceof ConfirmScreen, "asks: " + Minecraft.getInstance().screen);
        });
        step("screenshot", 2, () -> screenshot("20-unsaved-changes"));
        step("unsaved changes: Keep editing (and Esc on the question) return with the edits", 1, () -> {
            click(centre(findButton(Minecraft.getInstance().screen, "smart_filter_extras.gui.unsaved.keep")));
            check(Minecraft.getInstance().screen == screen, "back in the editor");
            check(itemScreen().getSelectedItems().equals(List.of(Items.STONE, Items.DIRT)), "edits kept: " + itemScreen().getSelectedItems());
            check(key(InputConstants.KEY_ESCAPE, 0) && Minecraft.getInstance().screen instanceof ConfirmScreen, "Esc in the editor asks too");
            key(InputConstants.KEY_ESCAPE, 0);
            check(Minecraft.getInstance().screen == screen, "Esc on the question keeps editing");
        });
        step("unsaved changes: Discard closes without saving; Done after edits doesn't ask", 2, () -> {
            click(centre(findButton(screen, "gui.cancel")));
            click(centre(findButton(Minecraft.getInstance().screen, "smart_filter_extras.gui.unsaved.discard")));
            check(Minecraft.getInstance().screen == parent && parent.replaced == null, "discarded: " + Minecraft.getInstance().screen);
            open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
            itemScreen().pasteText("minecraft:dirt");
            click(centre(findButton(screen, "gui.done")));
            check(Minecraft.getInstance().screen == parent && parent.replaced instanceof ItemListFilter saved
                    && saved.getItems().equals(List.of(Items.STONE, Items.DIRT)), "saved without asking: " + parent.replaced);
        });
        step(null, 2, () -> open(new FuelConfigScreen(new FuelFilter(root), parent = new RecordingParent()), false));
        step("unsaved changes: the fuel screen asks too", 2, () -> {
            click(centre(findButton(screen, "item.minecraft.coal")));
            check(((FuelConfigScreen) screen).hasUnsavedChanges(), "comparison changed");
            key(InputConstants.KEY_ESCAPE, 0);
            check(Minecraft.getInstance().screen instanceof ConfirmScreen, "asks");
            click(centre(findButton(Minecraft.getInstance().screen, "smart_filter_extras.gui.unsaved.discard")));
            check(Minecraft.getInstance().screen == parent && parent.replaced == null, "discarded");
        });

        // 8f. Tag screens: copy, paste and untick all.
        step(null, 2, () -> {
            open(new BlockTagConfigScreen(new BlockTagFilter(root, List.of(BlockTags.LOGS)), parent = new RecordingParent()), false);
            blockScreen().getSearchBox().setValue("");
        });
        step("tag screen: pasting tag IDs or filter strings ticks those tags", 8, () -> {
            check(screen.getFocused() != blockScreen().getSearchBox(), "search field not focused at first, so Ctrl+C / Ctrl+V go to the ticks");
            blockScreen().pasteTicks("or(block_tag(minecraft:planks)item_tag(mymod:things))");
            TagKey<Block> things = TagKey.create(Registries.BLOCK, ResourceLocation.parse("mymod:things"));
            check(blockScreen().getTickedTags().equals(List.of(BlockTags.LOGS, BlockTags.PLANKS, things)), "ticked: " + blockScreen().getTickedTags());
            check(blockScreen().getStatusMessage() != null && blockScreen().getStatusMessage().getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.gui.tag_pasted") && tc.getArgs()[0].equals(2) && tc.getArgs()[1].equals(1),
                    "2 ticked, 1 not loaded: " + blockScreen().getStatusMessage());
            check(blockScreen().getListedTags().get(0).equals(things), "the unknown tag is listed at the top: " + blockScreen().getListedTags().get(0));
            blockScreen().pasteTicks("hello world");
            check(blockScreen().getStatusMessage() != null && blockScreen().getStatusMessage().getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.gui.tag_paste_nothing"), "nothing found: " + blockScreen().getStatusMessage());
        });
        step("screenshot", 2, () -> screenshot("21-tag-toolbar"));
        step("tag screen: untick all, then Ctrl+V / Ctrl+C through the real clipboard", 1, () -> {
            click(centre(findButton(screen, "smart_filter_extras.gui.clear")));
            check(blockScreen().getTickedTags().isEmpty() && !blockScreen().isDoneButtonActive(), "all unticked");
            var keyboard = Minecraft.getInstance().keyboardHandler;
            String saved = readWithRetry(keyboard);
            try {
                if (!writeWithRetry(keyboard, "minecraft:logs, #minecraft:planks")) {
                    record("OS clipboard busy; tag Ctrl+V / Ctrl+C round trip skipped", true, "");
                    return;
                }
                check(key(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL), "Ctrl+V handled");
                check(blockScreen().getTickedTags().equals(List.of(BlockTags.LOGS, BlockTags.PLANKS)), "Ctrl+V ticked: " + blockScreen().getTickedTags());
                check(key(InputConstants.KEY_C, GLFW.GLFW_MOD_CONTROL), "Ctrl+C handled");
                Component status = blockScreen().getStatusMessage();
                if (status != null && status.getContents() instanceof TranslatableContents tc && tc.getKey().equals("smart_filter_extras.gui.clipboard_busy")) {
                    record("OS clipboard busy during Ctrl+C; reported instead of claiming success", true, "");
                } else {
                    String copied = readWithRetry(keyboard);
                    check(copied.equals("minecraft:logs,minecraft:planks"), "Ctrl+C copied the ticks: " + copied);
                }
            } finally {
                if (!saved.isEmpty()) {
                    writeWithRetry(keyboard, saved);
                }
            }
            screen.onClose();
            check(Minecraft.getInstance().screen instanceof ConfirmScreen, "planks added since opening, so closing asks");
            click(centre(findButton(Minecraft.getInstance().screen, "smart_filter_extras.gui.unsaved.discard")));
        });

        // 9. Recipe viewers: no default key binding collides with our shortcuts; EMI integration when EMI is installed.
        step("no JEI / EMI key binding uses Enter, Ctrl+C or Ctrl+V", 1, ClientSmokeTest::checkRecipeViewerKeys);
        if (ModList.get().isLoaded("emi")) {
            waitFor("EMI to finish loading", 20 * 60, EmiCheck::loaded);
            step(null, 2, () -> {
                open(new ItemListConfigScreen(ItemListFilter.ofItems(root, List.of(Items.STONE)), parent = new RecordingParent()), false);
                setSearch("");
            });
            step("EMI's registry has the item list screen's bounds and drop handler, and drops work", 10, () -> EmiCheck.runItemList(itemScreen()));
            step("screenshot", 2, () -> screenshot("17-emi-item-list"));
            step(null, 2, () -> open(new BlockTagConfigScreen(new BlockTagFilter(root), parent = new RecordingParent()), false));
            step("EMI drop handler on the block tag screen: blocks accepted, non-blocks refused", 10, () -> EmiCheck.runTagScreen(blockScreen(),
                    new ItemStack(Items.STICK), new ItemStack(Items.OAK_PLANKS), Items.OAK_PLANKS));
            step(null, 2, () -> open(new ItemTagListConfigScreen(new ItemTagListFilter(root, List.of(ingots)), parent = new RecordingParent()), false));
            step("EMI drop handler on the item tag screen", 10, () -> EmiCheck.runTagScreen(tagScreen(),
                    null, new ItemStack(Items.GOLD_INGOT), Items.GOLD_INGOT));
            step("screenshot", 2, () -> screenshot("18-emi-item-tag"));
            step("note: EMI 1.1.24 on NeoForge only draws its sidebars on container screens, so they don't appear on these screens", 1, () -> {
            });
        } else {
            step("EMI not installed in this run; skipped", 1, () -> {
            });
        }

        // 9b. FFS's own editor: a filter saved by 2.x opens as today's filter, and the Add menu lists each filter once
        //     (the old IDs are registered so old filters keep working, but stay out of the menu).
        step("FFS editor: a 2.x filter opens as an Item List, and the Add menu has no duplicates", 1, () -> {
            SmartFilter old = FTBFilterSystemAPI.api().parseFilter("smartfilterextras:items(minecraft:stone)item(minecraft:dirt,minecraft:sand)", ra);
            check(old.asString(ra).equals("smart_filter_extras:items(minecraft:stone)smart_filter_extras:items(minecraft:dirt,minecraft:sand)"),
                    "old forms read as Item Lists: " + old.asString(ra));
            Minecraft.getInstance().setScreen(new dev.ftb.mods.ftbfiltersystem.client.gui.FilterScreen(Component.literal("smoke"), old,
                    net.minecraft.world.InteractionHand.MAIN_HAND));
            Screen editor = Minecraft.getInstance().screen;
            java.util.Map<String, Integer> menu = new java.util.TreeMap<>();
            for (GuiEventListener listener : editor.children()) {
                if (listener instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents tc
                        && tc.getKey().startsWith("filter.") && tc.getKey().endsWith(".name")) {
                    menu.merge(tc.getKey(), 1, Integer::sum);
                }
            }
            Minecraft.getInstance().setScreen(null);
            for (String key : List.of("filter.smart_filter_extras.items.name", "filter.smart_filter_extras.item_tag.name",
                    "filter.smart_filter_extras.block_tag.name", "filter.smart_filter_extras.fuel.name", "filter.ftbfiltersystem.item.name")) {
                check(menu.getOrDefault(key, 0) == 1, key + " listed " + menu.getOrDefault(key, 0) + " times");
            }
            check(menu.values().stream().allMatch(n -> n == 1), "every entry once: " + menu);
            check(menu.keySet().stream().noneMatch(k -> k.contains("smartfilterextras") || k.contains("ftbfiltersystemaddons")), "no old IDs: " + menu);
        });

        // 10. Search cost, for the record (no pass/fail threshold).
        step("search timing", 1, ClientSmokeTest::measureSearch);
    }

    private static ItemTagListConfigScreen tagScreen() {
        return (ItemTagListConfigScreen) screen;
    }

    /**
     * Our shortcuts are Enter (save) and Ctrl+C / Ctrl+V (item list). JEI handles its own key bindings before the
     * screen does, so a recipe viewer binding on one of these keys would take it away from us.
     */
    private static void checkRecipeViewerKeys() {
        List<String> viewerKeys = new ArrayList<>();
        List<String> clashes = new ArrayList<>();
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            String name = mapping.getName().toLowerCase(Locale.ROOT);
            String category = mapping.getCategory().toLowerCase(Locale.ROOT);
            if (!(name.contains("jei") || name.contains("emi") || category.contains("jei") || category.contains("emi"))) {
                continue;
            }
            InputConstants.Key bound = mapping.getKey();
            String modifier = mapping.getKeyModifier().name();
            viewerKeys.add(mapping.getName() + "=" + modifier + "+" + bound.getName());
            int code = bound.getValue();
            boolean enter = bound.getType() == InputConstants.Type.KEYSYM && (code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER);
            boolean copyPaste = bound.getType() == InputConstants.Type.KEYSYM && (code == InputConstants.KEY_C || code == InputConstants.KEY_V)
                    && modifier.equals("CONTROL");
            if (enter || copyPaste) {
                clashes.add(mapping.getName() + "=" + modifier + "+" + bound.getName());
            }
        }
        record("recipe viewer key bindings seen: " + viewerKeys.size(), true, String.join(", ", viewerKeys));
        check(clashes.isEmpty(), "clashing bindings: " + clashes);
    }

    /**
     * Search cost per catalog entry, extrapolated to a 40,000-entry modpack catalog. "old" is what 2.2.0 did on
     * every search (rebuild the variant entries, look up and lower-case every display name); "new" is the cached
     * search the screen uses now. Both must find the same entries.
     */
    private static void measureSearch() {
        Minecraft mc = Minecraft.getInstance();
        List<ItemEntry> plain = ItemCatalog.entries(ItemCatalog.Source.CREATIVE, false, mc);
        List<ItemEntry> variants = ItemCatalog.entries(ItemCatalog.Source.CREATIVE, true, mc);
        List<ItemStack> creativeStacks = variants.stream().map(ItemEntry::toStack).toList();
        int reps = 10;
        long start = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            for (ItemStack stack : creativeStacks) {
                ItemEntry.variantOf(stack);
            }
        }
        double buildUs = (System.nanoTime() - start) / 1000.0 / reps / variants.size();
        Map<ItemEntry, ItemStack> stacks = new HashMap<>();
        for (ItemEntry entry : variants) {
            stacks.put(entry, entry.toStack());
        }
        StringBuilder detail = new StringBuilder(String.format(Locale.ROOT,
                "%d items, %d variant entries; old: rebuilding variant entries %.2f us/entry (%.0f ms per 40k), new: cached",
                plain.size(), variants.size(), buildUs, buildUs * 40));
        double worstNew = 0;
        for (String query : List.of("a", "stone", "shitou", "@minecraft", "iron ingot")) {
            List<ItemEntry> expected = new ArrayList<>();
            for (ItemEntry entry : variants) {
                if (ItemCatalog.matches(stacks.get(entry), query)) {  // also warms up
                    expected.add(entry);
                }
            }
            start = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                for (ItemEntry entry : variants) {
                    ItemCatalog.matches(stacks.get(entry), query);
                }
            }
            double oldUs = (System.nanoTime() - start) / 1000.0 / reps / variants.size();
            List<ItemEntry> found = ItemCatalog.search(variants, query, stacks::get);  // fills the cache
            check(found.equals(expected), "cached search finds the same entries for '" + query + "': " + found.size() + " vs " + expected.size());
            start = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                ItemCatalog.search(variants, query, stacks::get);
            }
            double newUs = (System.nanoTime() - start) / 1000.0 / reps / variants.size();
            worstNew = Math.max(worstNew, newUs);
            detail.append(String.format(Locale.ROOT, "; '%s' (%d hits) old %.2f us/entry = %.0f ms per 40k, new %.2f us/entry = %.0f ms per 40k",
                    query, expected.size(), oldUs, oldUs * 40, newUs, newUs * 40));
        }
        record("search timing (pinyin " + ItemCatalog.pinyinSearch() + ", " + mc.options.languageCode + ")", true, detail.toString());
        check(worstNew * 40 < 50, String.format(Locale.ROOT, "a cached search over 40k entries stays under 50 ms (worst %.0f ms)", worstNew * 40));
    }

    private static void checkJei() {
        if (!ModList.get().isLoaded("jei")) {
            record("JEI not installed in this run; skipped", true, "");
            return;
        }
        JeiCheck.run(itemScreen());
        screenshot("8-jei-overlay");
    }

    /**
     * Best effort: Windows denies clipboard access while another program holds it, so writes are verified and
     * retried. The player's clipboard is restored afterwards. If the OS keeps refusing, this is reported as skipped.
     */
    private static void checkRealClipboard() {
        var keyboard = Minecraft.getInstance().keyboardHandler;
        String saved = readWithRetry(keyboard);
        try {
            if (!writeWithRetry(keyboard, "minecraft:cobblestone")) {
                record("OS clipboard busy; real clipboard round trip skipped (logic covered above)", true, "");
                return;
            }
            click(centre(findButton(screen, "smart_filter_extras.gui.paste")));
            check(itemScreen().getSelectedItems().contains(Items.COBBLESTONE), "Paste button read the clipboard: " + itemScreen().getSelectedItems());
            click(centre(findButton(screen, "smart_filter_extras.gui.copy")));
            Component status = itemScreen().getStatusMessage();
            boolean reportedBusy = status != null && status.getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.gui.clipboard_busy");
            String copied = readWithRetry(keyboard);
            if (reportedBusy) {
                // The OS refused the write; the button must then say so rather than claim success.
                record("OS clipboard busy during Copy; the button reported it instead of claiming success", true, "");
            } else {
                check(copied.equals(itemScreen().selectionAsText()), "Copy button wrote the selection: " + copied);
            }
        } finally {
            if (!saved.isEmpty()) {
                writeWithRetry(keyboard, saved);
            }
        }
    }

    /** Like {@link #checkRealClipboard()}, but through Ctrl+C / Ctrl+V key events. */
    private static void checkItemListClipboardKeys() {
        var keyboard = Minecraft.getInstance().keyboardHandler;
        check(screen.getFocused() != itemScreen().getSearchBox(), "search field not focused when the screen opens");
        String saved = readWithRetry(keyboard);
        try {
            if (!writeWithRetry(keyboard, "minecraft:gravel")) {
                record("OS clipboard busy; Ctrl+C / Ctrl+V round trip skipped", true, "");
                return;
            }
            check(key(InputConstants.KEY_V, GLFW.GLFW_MOD_CONTROL), "Ctrl+V handled");
            check(itemScreen().getSelectedItems().equals(List.of(Items.STONE, Items.GRAVEL)), "Ctrl+V pasted gravel: " + itemScreen().getSelectedItems());
            check(key(InputConstants.KEY_C, GLFW.GLFW_MOD_CONTROL), "Ctrl+C handled");
            Component status = itemScreen().getStatusMessage();
            boolean reportedBusy = status != null && status.getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("smart_filter_extras.gui.clipboard_busy");
            if (reportedBusy) {
                record("OS clipboard busy during Ctrl+C; reported instead of claiming success", true, "");
            } else {
                String copied = readWithRetry(keyboard);
                check(copied.equals("minecraft:stone,minecraft:gravel"), "Ctrl+C copied the selection: " + copied);
            }
        } finally {
            if (!saved.isEmpty()) {
                writeWithRetry(keyboard, saved);
            }
        }
    }

    private static boolean writeWithRetry(net.minecraft.client.KeyboardHandler keyboard, String text) {
        for (int attempt = 0; attempt < 10; attempt++) {
            keyboard.setClipboard(text);
            if (text.equals(readWithRetry(keyboard))) {
                return true;
            }
            sleep(50);
        }
        return false;
    }

    private static String readWithRetry(net.minecraft.client.KeyboardHandler keyboard) {
        String text = "";
        for (int attempt = 0; attempt < 10 && text.isEmpty(); attempt++) {
            if (attempt > 0) {
                sleep(30);
            }
            text = keyboard.getClipboard();
        }
        return text;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void checkJeiBlockTag() {
        if (!ModList.get().isLoaded("jei")) {
            record("JEI not installed in this run; skipped", true, "");
            return;
        }
        JeiCheck.runBlockTag(blockScreen());
    }

    // ---------------------------------------------------------------- helpers

    private static void step(String name, int delayTicks, Runnable action) {
        STEPS.add(new Step(name, delayTicks, action));
    }

    /** A step that waits (checking every 5 ticks, up to {@code maxTicks}) until the condition holds. */
    private static void waitFor(String what, int maxTicks, BooleanSupplier condition) {
        int[] waited = {0};
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (condition.getAsBoolean()) {
                return;
            }
            waited[0] += 5;
            check(waited[0] <= maxTicks, "timed out waiting for " + what);
            STEPS.addFirst(new Step(null, 5, self[0]));
        };
        STEPS.add(new Step(null, 1, self[0]));
    }

    /** A key press sent through the screen's own handler (modifiers as in the GLFW event). */
    private static boolean key(int keyCode, int modifiers) {
        return Minecraft.getInstance().screen.keyPressed(keyCode, 0, modifiers);
    }

    private static void type(String text) {
        for (char c : text.toCharArray()) {
            Minecraft.getInstance().screen.charTyped(c, 0);
        }
    }

    /**
     * Moves Minecraft's idea of the mouse (not the OS cursor) to a point in GUI coordinates, so hover tooltips can
     * be screenshotted. Dev-only: the field names are the Mojang names used in the dev runtime.
     */
    private static void moveMouse(int[] guiPoint) {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.getWindow().getGuiScale();
        try {
            for (String name : List.of("xpos", "ypos")) {
                java.lang.reflect.Field field = net.minecraft.client.MouseHandler.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setDouble(mc.mouseHandler, (name.equals("xpos") ? guiPoint[0] : guiPoint[1]) * scale + scale / 2);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("can't move the mouse: " + e);
        }
    }

    private static void open(AbstractFilterConfigScreen<?> configScreen, boolean isNewFilter) {
        configScreen.setDeleteOnCancel(isNewFilter);
        screen = configScreen;
        Minecraft.getInstance().setScreen(configScreen);
        // Park the mouse above the panel, away from JEI / EMI: hovering one of their items highlights drop
        // targets and shows a tooltip, which would clutter the screenshots.
        moveMouse(new int[]{configScreen.width / 2, 1});
    }

    private static String searchText() {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof EditBox box) {
                return box.getValue();
            }
        }
        throw new AssertionError("no search box");
    }

    private static ItemListConfigScreen itemScreen() {
        return (ItemListConfigScreen) screen;
    }

    private static BlockTagConfigScreen blockScreen() {
        return (BlockTagConfigScreen) screen;
    }

    private static void setSearch(String text) {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof EditBox box) {
                box.setValue(text);
                return;
            }
        }
        throw new AssertionError("no search box");
    }

    /** A real left click through the screen's own input handling (press and release), like a mouse would do. */
    private static void click(int[] point) {
        click(point, 0);
    }

    private static void click(int[] point, int button) {
        Screen current = Minecraft.getInstance().screen;
        if (current == null) {
            throw new AssertionError("no screen to click on");
        }
        current.mouseClicked(point[0], point[1], button);
        current.mouseReleased(point[0], point[1], button);
    }

    private static int[] centre(AbstractWidget widget) {
        return new int[]{widget.getX() + widget.getWidth() / 2, widget.getY() + widget.getHeight() / 2};
    }

    private static Button findButton(Screen s, String keyOrLiteral) {
        for (GuiEventListener listener : s.children()) {
            if (listener instanceof Button button) {
                Component message = button.getMessage();
                if (message.getContents() instanceof TranslatableContents tc ? tc.getKey().equals(keyOrLiteral) : message.getString().equals(keyOrLiteral)) {
                    return button;
                }
            }
        }
        throw new AssertionError("button not found: " + keyOrLiteral);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void record(String name, boolean passed, String detail) {
        if (!passed) {
            failures++;
        }
        RESULTS.add("{\"name\":" + json(name) + ",\"passed\":" + passed + ",\"detail\":" + json(detail) + "}");
        SmartFilterExtras.LOGGER.info("[client smoke] {} {} {}", passed ? "PASS" : "FAIL", name, detail);
    }

    private static void screenshot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "sfe-" + name + ".png", mc.getMainRenderTarget(), message -> {
        });
    }

    private static void finish(Minecraft mc) {
        finished = true;
        String json = "{\"failures\":" + failures + ",\"results\":[\n  " + String.join(",\n  ", RESULTS) + "\n]}\n";
        try {
            Files.writeString(Path.of(mc.gameDirectory.getAbsolutePath(), "smoke-result.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            SmartFilterExtras.LOGGER.error("Could not write smoke-result.json", e);
        }
        SmartFilterExtras.LOGGER.info("[client smoke] finished with {} failure(s)", failures);
        mc.stop();
    }

    private static String json(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    /** Stands in for FFS's filter editor screen and records what the config screen does to it. */
    static final class RecordingParent extends AbstractFilterScreen {
        SmartFilter replaced;
        int deleted;

        RecordingParent() {
            super(Component.literal("smoke test parent"));
        }

        @Override
        public void findAndSelect(SmartFilter filter) {
        }

        @Override
        public <T extends SmartFilter> void replaceFilter(T filter, T newFilter) {
            replaced = newFilter;
        }

        @Override
        public void deleteSelectedFilter(boolean rebuildList) {
            deleted++;
        }
    }
}
