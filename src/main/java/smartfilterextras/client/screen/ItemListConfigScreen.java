package smartfilterextras.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterConfigScreen;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.filter.ItemEntry;
import smartfilterextras.filter.ItemIdList;
import smartfilterextras.filter.ItemListFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Config screen for {@link ItemListFilter}.
 *
 * <pre>
 *  Item List                        [compass][chest] (i)
 *  [search.................... x]   [view][variants]
 *  +-------------- grid 9 x 4..8 --------------+ |#|
 *  Selected 12            < 2/3 >  [!][copy][paste][bin]
 *  [selected row, 9 slots ...........................]
 * </pre>
 *
 * <ul>
 *     <li>Grid: left click adds or removes, right click removes, double click makes sure the entry is selected and
 *     saves. Shift + left/right click adds/removes everything shown. Hovered slots say what a click will do.</li>
 *     <li>Variant mode lists every distinct variant (each potion, enchanted book...); picking one only matches
 *     stacks with those components.</li>
 *     <li>Selected row: click removes (hover shows a red cross). A quick second click on the same slot is ignored,
 *     so a double click can't remove the entry that slid into its place.</li>
 *     <li>New filters start empty; Done is disabled while nothing is selected. Unknown entries are kept unless the
 *     player drops them with the warning button.</li>
 *     <li>Keys: Enter saves; typing anywhere searches; Ctrl+C / Ctrl+V copy and paste the selection while the
 *     search field isn't being edited.</li>
 * </ul>
 */
public class ItemListConfigScreen extends AbstractFilterConfigScreen<ItemListFilter> implements ItemDropTarget {
    private static final int PANEL_WIDTH = 194;
    private static final int SLOT = Ui.SLOT;
    private static final int GRID_COLS = 9;
    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = 8;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 37;
    private static final int SCROLL_X = 174;
    private static final int SCROLL_W = 12;
    private static final int SCROLL_KNOB_H = 15;
    private static final int TOOL = 16;
    private static final int SMALL_TOOL = 14;
    private static final int PAGE_SIZE = 9;
    /** Selection header (4 + 16) + selected row (18) + bottom margin (6). */
    private static final int BELOW_GRID = 4 + 16 + 18 + 6;
    /** Space the base class adds for the title and the Done/Cancel row. */
    private static final int BASE_EXTRA = 40;

    private static final ResourceLocation SCROLLER = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller");
    private static final ResourceLocation SCROLLER_DISABLED = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller_disabled");

    // Remembered between openings of the screen.
    private static String lastSearch = "";
    private static ItemCatalog.Source lastSource = ItemCatalog.Source.CREATIVE;
    private static boolean lastSelectedOnly = false;
    private static boolean lastVariants = false;

    /** Longest one entry with components may be (a written book or a mod's stored data can be far longer). */
    private static final int MAX_ENTRY_CHARS = 2048;
    /** Head room under FFS's 32767-character limit, so a rounding slip can't push a filter over it. */
    private static final int SAFETY_CHARS = 256;

    private final int gridRows;
    private final Map<ItemEntry, Integer> entryLengths = new HashMap<>();
    private int maxArgumentLength = -1;
    private boolean newFilter;
    private boolean savedAndClosing;
    private final SelectionModel<ItemEntry> selection = new SelectionModel<>(ItemListFilter.MAX_ENTRIES);
    private final List<String> missing = new ArrayList<>();
    private final ClickTracker clicks = new ClickTracker();
    private final Map<ItemEntry, ItemStack> stacks = new HashMap<>();
    private final Ui.Toast toast = new Ui.Toast();
    /** Items selected as plain entries (match every variant), and items with variant entries; for grid marks. */
    private final Set<Item> plainSelected = new HashSet<>();
    private final Set<Item> variantSelected = new HashSet<>();

    private List<ItemEntry> visible = new ArrayList<>();
    private int scrollRow;
    private int page;
    private boolean draggingScrollbar;
    private boolean initialised;

    private SearchField search;
    private IconButton prevPageButton;
    private IconButton nextPageButton;
    private IconButton missingButton;
    private IconButton copyButton;
    private IconButton pasteButton;
    private IconButton clearButton;
    @Nullable
    private net.minecraft.client.gui.components.Button doneButton;

    public ItemListConfigScreen(ItemListFilter filter, AbstractFilterScreen parentScreen) {
        this(filter, parentScreen, gridRowsFor(Minecraft.getInstance().getWindow().getGuiScaledHeight()));
    }

    private ItemListConfigScreen(ItemListFilter filter, AbstractFilterScreen parentScreen, int gridRows) {
        super(filter, parentScreen, PANEL_WIDTH, contentHeight(gridRows));
        this.gridRows = gridRows;
        missing.addAll(filter.getMissingEntries());
        selection.setCapacity(ItemListFilter.MAX_ENTRIES - missing.size());
        selection.addAll(filter.getEntries());
    }

    /** As many grid rows (4..8) as fit on a screen of this (GUI-scaled) height, with a small margin. */
    public static int gridRowsFor(int screenHeight) {
        int spare = screenHeight - 8 - BASE_EXTRA - contentHeight(0);
        return Mth.clamp(spare / SLOT, MIN_ROWS, MAX_ROWS);
    }

    private static int contentHeight(int rows) {
        return GRID_Y + rows * SLOT + BELOW_GRID;
    }

    private int gridBottom() {
        return GRID_Y + gridRows * SLOT;
    }

    private int headerY() {
        return gridBottom() + 4;
    }

    private int selectedRowY() {
        return headerY() + 16;
    }

    /**
     * FFS calls this with {@code true} only for a filter that was just added from the "Add..." menu, before the
     * screen is shown. Such a filter starts with an empty selection, so nothing is ever included by default.
     */
    @Override
    public void setDeleteOnCancel(boolean deleteOnCancel) {
        super.setDeleteOnCancel(deleteOnCancel);
        newFilter = deleteOnCancel;
        if (deleteOnCancel && !initialised) {
            selection.clear();
            missing.clear();
            selection.setCapacity(ItemListFilter.MAX_ENTRIES);
        }
    }

    @Override
    protected void init() {
        super.init();

        // Title row: item source tabs, left of FFS's info icon.
        int tabX = leftPos + guiWidth - 19 - 4 - 2 * TOOL - 2;
        for (ItemCatalog.Source source : ItemCatalog.Source.values()) {
            addRenderableWidget(new IconButton(tabX, topPos + 3, TOOL, source.icon(), source.displayName(), b -> setSource(source))
                    .pressedWhen(() -> lastSource == source)
                    .withTooltip(source.tooltip()));
            tabX += TOOL + 2;
        }

        // Search row: search field, then the two view toggles.
        int togglesX = leftPos + guiWidth - 8 - (2 * TOOL + 2);
        String previousSearch = lastSearch;
        var box = makeSearchEditBox(leftPos + 8, topPos + 19, () -> lastSearch, s -> lastSearch = s);
        box.setMaxLength(64);
        box.setValue(previousSearch);
        box.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                ItemCatalog.pinyinSearch() ? "smart_filter_extras.gui.search_tooltip_pinyin" : "smart_filter_extras.gui.search_tooltip")));
        // The search row starts at 19, right below the title row's 16 px tabs and FFS's info icon (3..19).
        search = new SearchField(box, leftPos + 8, topPos + 20, togglesX - 4 - (leftPos + 8));
        search.setHint(font, Component.translatable("smart_filter_extras.gui.search_hint").withStyle(ChatFormatting.DARK_GRAY));

        addRenderableWidget(new IconButton(togglesX, topPos + 19, TOOL, () -> Ui.ICON_VIEW_SELECTED, 12, 12,
                Component.translatable("smart_filter_extras.gui.show"), b -> toggleSelectedOnly())
                .pressedWhen(() -> lastSelectedOnly)
                .withTooltip(Component.translatable("smart_filter_extras.gui.show_tooltip")));
        addRenderableWidget(new IconButton(togglesX + TOOL + 2, topPos + 19, TOOL, () -> Ui.ICON_VARIANTS, 12, 12,
                Component.translatable("smart_filter_extras.gui.variants"), b -> toggleVariants())
                .pressedWhen(() -> lastVariants)
                .withTooltip(Component.translatable("smart_filter_extras.gui.variants_tooltip")));

        // Selection header: paging and the selection tools, right-aligned (positions set in layoutHeader()).
        int y = topPos + headerY() + 1;
        prevPageButton = addRenderableWidget(new IconButton(0, y, 10, SMALL_TOOL, () -> Ui.ICON_ARROW_LEFT, 5, 9,
                Component.translatable("smart_filter_extras.gui.page.previous"), b -> changePage(-1)));
        nextPageButton = addRenderableWidget(new IconButton(0, y, 10, SMALL_TOOL, () -> Ui.ICON_ARROW_RIGHT, 5, 9,
                Component.translatable("smart_filter_extras.gui.page.next"), b -> changePage(1)));
        missingButton = addRenderableWidget(new IconButton(0, y, SMALL_TOOL, () -> Ui.ICON_WARNING, 12, 12,
                Component.translatable("smart_filter_extras.gui.drop_missing"), b -> dropMissing()));
        copyButton = addRenderableWidget(new IconButton(0, y, SMALL_TOOL, () -> Ui.ICON_COPY, 12, 12,
                Component.translatable("smart_filter_extras.gui.copy"), b -> copyToClipboard())
                .withTooltip(Component.translatable("smart_filter_extras.gui.copy_tooltip")));
        pasteButton = addRenderableWidget(new IconButton(0, y, SMALL_TOOL, () -> Ui.ICON_PASTE, 12, 12,
                Component.translatable("smart_filter_extras.gui.paste"), b -> pasteFromClipboard())
                .withTooltip(Component.translatable("smart_filter_extras.gui.paste_tooltip")));
        clearButton = addRenderableWidget(new IconButton(0, y, SMALL_TOOL, () -> Ui.ICON_TRASH, 12, 12,
                Component.translatable("smart_filter_extras.gui.clear"), b -> clearAll())
                .withTooltip(Component.translatable("smart_filter_extras.gui.clear_tooltip")));

        doneButton = ScreenSupport.findDoneButton(this);
        initialised = true;

        updateVisible();
        refreshControls();
        // The search field isn't focused at first, so Ctrl+C / Ctrl+V work on the selection right away;
        // typing anywhere still goes to the search field (see charTyped).
        setFocused(null);
    }

    /**
     * The panel height is fixed when the screen is created (FFS's base class), so if the window now fits a
     * different number of grid rows, the screen is reopened at the new size with everything carried over.
     * Otherwise a smaller window would cut off the title and the Done / Cancel buttons.
     */
    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        int rows = gridRowsFor(height);
        if (rows == gridRows) {
            super.resize(minecraft, width, height);
            return;
        }
        ItemListConfigScreen resized = new ItemListConfigScreen(filter, parentScreen, rows);
        resized.setDeleteOnCancel(newFilter);
        resized.missing.clear();
        resized.missing.addAll(missing);
        resized.selection.clear();
        resized.selection.setCapacity(selection.capacity());
        resized.selection.addAll(selection.asList());
        resized.page = page;
        resized.scrollRow = scrollRow;
        minecraft.setScreen(resized);
    }

    private HolderLookup.Provider registryAccess() {
        return minecraft.level != null ? minecraft.level.registryAccess() : net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Override
    protected void doScheduledUpdate() {
        updateVisible();
    }

    @Override
    @Nullable
    protected ItemListFilter makeNewFilter() {
        if (selection.isEmpty()) {
            return null;
        }
        // FFS's applyChanges() calls this, saves, then closes the screen: that close must not ask about losing edits.
        savedAndClosing = true;
        return ItemListFilter.of(filter.getParent(), selection.asList(), missing, registryAccess());
    }

    /** Cancel and Esc ask first if the selection differs from the filter as it was opened. */
    @Override
    public void onClose() {
        if (savedAndClosing || !hasUnsavedChanges()) {
            super.onClose();
        } else {
            UnsavedChanges.confirmDiscard(this, super::onClose);
        }
    }

    /** Order doesn't count: removing and re-adding an item leaves the filter matching exactly the same. */
    public boolean hasUnsavedChanges() {
        Set<ItemEntry> original = newFilter ? Set.of() : Set.copyOf(filter.getEntries());
        Set<String> originalMissing = newFilter ? Set.of() : Set.copyOf(filter.getMissingEntries());
        return !Set.copyOf(selection.asList()).equals(original) || !Set.copyOf(missing).equals(originalMissing);
    }

    // ---------------------------------------------------------------- state changes

    private void updateVisible() {
        List<ItemEntry> source = lastSelectedOnly ? selection.asList() : ItemCatalog.entries(lastSource, lastVariants, minecraft);
        visible = ItemCatalog.search(source, search.box().getValue(), this::stack);
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    private void setSource(ItemCatalog.Source source) {
        lastSource = source;
        lastSelectedOnly = false;
        scrollRow = 0;
        updateVisible();
    }

    private void toggleSelectedOnly() {
        lastSelectedOnly = !lastSelectedOnly;
        scrollRow = 0;
        updateVisible();
    }

    private void toggleVariants() {
        lastVariants = !lastVariants;
        scrollRow = 0;
        updateVisible();
        toast.show(Component.translatable(lastVariants ? "smart_filter_extras.gui.variants_on" : "smart_filter_extras.gui.variants_off"), Ui.BLUE);
    }

    private void onGridClick(ItemEntry entry, int button) {
        if (button == 1) {
            if (Screen.hasShiftDown()) {
                removeAllVisible();
            } else if (selection.remove(entry).changed()) {
                afterSelectionChanged();
            }
            return;
        }
        if (Screen.hasShiftDown()) {
            addAllVisible();
            return;
        }
        if (clicks.click(entry, Util.getMillis())) {
            // Double click: make sure the entry is included (the first click may have just removed it), then save.
            if (!selection.contains(entry) && !addOrExplain(entry)) {
                return;
            }
            applyChanges();
            return;
        }
        if (selection.contains(entry)) {
            selection.remove(entry);
        } else {
            addOrExplain(entry);
        }
        // The grid is deliberately not refreshed here, even in "selected only" mode: slots must not shift
        // between the two clicks of a double click.
        afterSelectionChanged();
    }

    private void onSelectedSlotClick(int slot) {
        if (clicks.click("selected:" + slot, Util.getMillis())) {
            return;
        }
        List<ItemEntry> pageItems = selection.page(page, PAGE_SIZE);
        if (slot < pageItems.size()) {
            selection.remove(pageItems.get(slot));
            afterSelectionChanged();
        }
    }

    /** Shift + left click: add every entry currently shown in the grid (up to the limits). */
    public void addAllVisible() {
        int added = 0;
        AddResult stoppedBy = AddResult.ADDED;
        for (ItemEntry entry : visible) {
            AddResult result = tryAdd(entry);
            if (result == AddResult.ADDED) {
                added++;
            } else if (result == AddResult.LIMIT || result == AddResult.TOO_LONG) {
                stoppedBy = result;
                break;
            } else if (result == AddResult.ENTRY_TOO_BIG && stoppedBy == AddResult.ADDED) {
                stoppedBy = result;
            }
        }
        afterSelectionChanged();
        toast.show(addedMessage(added, stoppedBy), stoppedBy == AddResult.ADDED ? Ui.GREEN : Ui.RED);
    }

    // ---------------------------------------------------------------- limits

    private enum AddResult { ADDED, PRESENT, LIMIT, TOO_LONG, ENTRY_TOO_BIG }

    /**
     * Adds an entry unless that would break one of the limits: {@link ItemListFilter#MAX_ENTRIES} entries, a
     * {@link #MAX_ENTRY_CHARS} cap on a single entry with components, and FFS's 32767-character limit for the
     * whole filter (this list plus everything else in the filter tree). FFS can't send or store a longer filter;
     * on a server, trying to would disconnect the player and lose the edit.
     */
    private AddResult tryAdd(ItemEntry entry) {
        if (selection.contains(entry)) {
            return AddResult.PRESENT;
        }
        int length = entryLength(entry);
        if (!entry.isPlain() && length > MAX_ENTRY_CHARS) {
            return AddResult.ENTRY_TOO_BIG;
        }
        if (argumentLength() + 1 + length > maxArgumentLength()) {
            return AddResult.TOO_LONG;
        }
        return selection.add(entry) == SelectionModel.Change.FULL ? AddResult.LIMIT : AddResult.ADDED;
    }

    /** {@link #tryAdd} for a single entry the player picked: says why if it couldn't be added. */
    private boolean addOrExplain(ItemEntry entry) {
        AddResult result = tryAdd(entry);
        switch (result) {
            case LIMIT -> showLimitReached();
            case TOO_LONG -> toast.show(Component.translatable("smart_filter_extras.gui.too_long", ItemIdList.MAX_FILTER_CHARS), Ui.RED);
            case ENTRY_TOO_BIG -> toast.show(Component.translatable("smart_filter_extras.gui.entry_too_big", entryLength(entry)), Ui.RED);
            default -> {
            }
        }
        return result == AddResult.ADDED || result == AddResult.PRESENT;
    }

    private Component addedMessage(int added, AddResult stoppedBy) {
        return switch (stoppedBy) {
            case LIMIT -> Component.translatable("smart_filter_extras.gui.added_until_limit", added, ItemListFilter.MAX_ENTRIES);
            case TOO_LONG -> Component.translatable("smart_filter_extras.gui.added_until_too_long", added);
            case ENTRY_TOO_BIG -> Component.translatable("smart_filter_extras.gui.added_skipped_big", added);
            default -> Component.translatable("smart_filter_extras.gui.added", added);
        };
    }

    private int entryLength(ItemEntry entry) {
        return entryLengths.computeIfAbsent(entry, e -> e.serialize(registryAccess()).length());
    }

    /** Length of this filter's argument as it would be saved now (entries and kept missing entries, comma-separated). */
    private int argumentLength() {
        int length = 0;
        int count = 0;
        for (ItemEntry entry : selection.asList()) {
            length += entryLength(entry);
            count++;
        }
        for (String entry : missing) {
            length += entry.length();
            count++;
        }
        return length + Math.max(0, count - 1);
    }

    /**
     * How long this filter's argument may get: FFS's limit, minus everything else in the filter tree, minus head
     * room for the rest of FFS's network message.
     */
    private int maxArgumentLength() {
        if (maxArgumentLength < 0) {
            HolderLookup.Provider ra = registryAccess();
            SmartFilter root = filter;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            boolean inTree = filter.getParent() != null && filter.getParent().getChildren().contains(filter);
            int rest = root == filter ? 0 : root.asString(ra).length() - (inTree ? filter.asString(ra).length() : 0);
            int wrapper = filter.asString(ra).length() - filter.getStringArg(ra).length();
            maxArgumentLength = ItemIdList.MAX_FILTER_CHARS - SAFETY_CHARS - rest - wrapper;
        }
        return maxArgumentLength;
    }

    /** Smoke test: how many characters of the limit are left for this list. */
    public int getRemainingCharacters() {
        return maxArgumentLength() - argumentLength();
    }

    /** Shift + right click: remove every entry currently shown in the grid. */
    public void removeAllVisible() {
        int removed = 0;
        for (ItemEntry entry : visible) {
            if (selection.remove(entry).changed()) {
                removed++;
            }
        }
        afterSelectionChanged();
        toast.show(Component.translatable("smart_filter_extras.gui.removed", removed), Ui.GREEN);
    }

    /** The player is told if another program kept the clipboard locked (see {@link ClipboardSupport}). */
    private void copyToClipboard() {
        if (ClipboardSupport.write(minecraft, selectionAsText())) {
            toast.show(Component.translatable("smart_filter_extras.gui.copied", selection.size() + missing.size()), Ui.GREEN);
        } else {
            toast.show(Component.translatable("smart_filter_extras.gui.clipboard_busy"), Ui.RED);
        }
    }

    /** The selection (and kept missing entries) as the comma-separated text the Copy button writes. */
    public String selectionAsText() {
        List<String> entries = new ArrayList<>();
        HolderLookup.Provider ra = registryAccess();
        selection.asList().forEach(entry -> entries.add(entry.serialize(ra)));
        entries.addAll(missing);
        return ItemIdList.join(entries);
    }

    private void pasteFromClipboard() {
        pasteText(ClipboardSupport.read(minecraft));
    }

    /** Adds the entries found in the text; see {@link ItemIdList#extractEntries(String)} for accepted formats. */
    public void pasteText(String text) {
        List<String> found = ItemIdList.extractEntries(text);
        int added = 0;
        int skipped = 0;
        AddResult stoppedBy = AddResult.ADDED;
        for (String raw : found) {
            Optional<ItemEntry> entry = resolve(raw);
            if (entry.isEmpty()) {
                skipped++;
                continue;
            }
            AddResult result = tryAdd(entry.get());
            if (result == AddResult.ADDED) {
                added++;
            } else if (result == AddResult.LIMIT || result == AddResult.TOO_LONG) {
                stoppedBy = result;
                break;
            } else if (result == AddResult.ENTRY_TOO_BIG && stoppedBy == AddResult.ADDED) {
                stoppedBy = result;
            }
        }
        if (lastSelectedOnly) {
            updateVisible();
        }
        afterSelectionChanged();
        if (found.isEmpty()) {
            toast.show(Component.translatable("smart_filter_extras.gui.paste_nothing"), Ui.RED);
        } else if (stoppedBy != AddResult.ADDED) {
            toast.show(addedMessage(added, stoppedBy), Ui.RED);
        } else {
            toast.show(Component.translatable("smart_filter_extras.gui.pasted", added, skipped), skipped > 0 ? Ui.ORANGE : Ui.GREEN);
        }
    }

    private Optional<ItemEntry> resolve(String raw) {
        if (raw.indexOf('[') < 0) {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(raw)).filter(i -> i != Items.AIR).map(ItemEntry::of);
        }
        try {
            return Optional.of(ItemEntry.parse(raw, registryAccess()));
        } catch (CommandSyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void changePage(int delta) {
        page = selection.clampPage(page + delta, PAGE_SIZE);
        clicks.reset();
        refreshControls();
    }

    private void clearAll() {
        int cleared = selection.size() + missing.size();
        selection.clear();
        missing.clear();
        selection.setCapacity(ItemListFilter.MAX_ENTRIES);
        page = 0;
        if (lastSelectedOnly) {
            updateVisible();
        }
        refreshControls();
        toast.show(Component.translatable("smart_filter_extras.gui.cleared", cleared), Ui.GREEN);
    }

    private void dropMissing() {
        int dropped = missing.size();
        missing.clear();
        selection.setCapacity(ItemListFilter.MAX_ENTRIES);
        refreshControls();
        toast.show(Component.translatable("smart_filter_extras.gui.dropped_missing", dropped), Ui.GREEN);
    }

    private void afterSelectionChanged() {
        page = selection.clampPage(page, PAGE_SIZE);
        refreshControls();
    }

    private void refreshControls() {
        plainSelected.clear();
        variantSelected.clear();
        for (ItemEntry entry : selection.asList()) {
            (entry.isPlain() ? plainSelected : variantSelected).add(entry.item());
        }
        int pages = selection.pageCount(PAGE_SIZE);
        prevPageButton.visible = nextPageButton.visible = pages > 1;
        prevPageButton.active = page > 0;
        nextPageButton.active = page < pages - 1;
        clearButton.active = !selection.isEmpty() || !missing.isEmpty();
        copyButton.active = !selection.isEmpty() || !missing.isEmpty();
        missingButton.visible = !missing.isEmpty();
        missingButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(missingTooltip()));
        if (doneButton != null) {
            doneButton.active = !selection.isEmpty();
        }
        layoutHeader();
    }

    /** Right-aligns the header tools; the warning and paging buttons only take space while shown. */
    private void layoutHeader() {
        int x = leftPos + guiWidth - 8;
        for (IconButton button : List.of(clearButton, pasteButton, copyButton, missingButton)) {
            if (button.visible) {
                x -= button.getWidth();
                button.setX(x);
                x -= 2;
            }
        }
        if (nextPageButton.visible) {
            x -= 4 + nextPageButton.getWidth();
            nextPageButton.setX(x);
            x -= 26 + prevPageButton.getWidth();
            prevPageButton.setX(x);
        }
    }

    private void showLimitReached() {
        toast.show(Component.translatable("smart_filter_extras.gui.limit_reached", ItemListFilter.MAX_ENTRIES), Ui.RED);
    }

    private Component missingTooltip() {
        MutableComponent text = Component.translatable("smart_filter_extras.gui.missing_tooltip", missing.size());
        int shown = 0;
        for (String entry : missing) {
            if (shown++ == 8) {
                text.append("\n…");
                break;
            }
            text.append("\n").append(Component.literal(entry.length() > 48 ? entry.substring(0, 47) + "…" : entry).withStyle(ChatFormatting.GRAY));
        }
        return text.append("\n").append(Component.translatable("smart_filter_extras.gui.missing_click").withStyle(ChatFormatting.YELLOW));
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        search.renderFrame(graphics, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        ItemEntry hoveredGrid = null;
        ItemEntry hoveredSelected = null;
        int gridLeft = leftPos + GRID_X;
        int gridTop = topPos + GRID_Y;
        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int index = (scrollRow + row) * GRID_COLS + col;
                ItemEntry entry = index < visible.size() ? visible.get(index) : null;
                int x = gridLeft + col * SLOT;
                int y = gridTop + row * SLOT;
                boolean hover = ScreenSupport.isInside(mouseX, mouseY, x, y, SLOT, SLOT);
                renderGridSlot(graphics, x, y, entry, hover);
                if (hover && entry != null) {
                    hoveredGrid = entry;
                }
            }
        }
        if (visible.isEmpty()) {
            Component empty = Component.translatable(lastSelectedOnly && selection.isEmpty()
                    ? "smart_filter_extras.gui.nothing_selected" : "smart_filter_extras.gui.no_matches");
            Ui.pill(graphics, font, empty, gridLeft + GRID_COLS * SLOT / 2, gridTop + (gridRows * SLOT - 14) / 2, GRID_COLS * SLOT, Ui.ORANGE, 0.85F);
        }
        renderScrollbar(graphics);

        // Selection header.
        int headerTop = topPos + headerY();
        int labelRight = (prevPageButton.visible ? prevPageButton.getX() : firstVisibleTool()) - 4;
        Component count = Component.translatable("smart_filter_extras.gui.selected_count", selection.size());
        if (font.width(count) > labelRight - (leftPos + 8)) {
            // Paging plus the unknown-entries warning leave little room; the number matters more than the word.
            count = Component.literal(Integer.toString(selection.size()));
        }
        Ui.drawClipped(graphics, font, count, leftPos + 8, headerTop + 4, labelRight - (leftPos + 8), Ui.TEXT);
        if (prevPageButton.visible) {
            String pageText = (page + 1) + "/" + selection.pageCount(PAGE_SIZE);
            int centre = (prevPageButton.getRight() + nextPageButton.getX()) / 2;
            graphics.drawString(font, pageText, centre - font.width(pageText) / 2, headerTop + 4, Ui.TEXT, false);
        }

        // Selected row: one sunken strip with a hint while empty, slots once something is selected.
        List<ItemEntry> pageItems = selection.page(page, PAGE_SIZE);
        int rowTop = topPos + selectedRowY();
        if (selection.isEmpty()) {
            Ui.inset(graphics, leftPos + 8, rowTop, PAGE_SIZE * SLOT, SLOT, 0xFF8B8B8B);
            Component hint = Component.translatable("smart_filter_extras.gui.hint_empty");
            Ui.drawClipped(graphics, font, hint, leftPos + 8 + Math.max(4, (PAGE_SIZE * SLOT - font.width(hint)) / 2), rowTop + 5,
                    PAGE_SIZE * SLOT - 8, 0xFFE8E8E8);
        } else {
            for (int i = 0; i < PAGE_SIZE; i++) {
                ItemEntry entry = i < pageItems.size() ? pageItems.get(i) : null;
                int x = leftPos + 8 + i * SLOT;
                boolean hover = ScreenSupport.isInside(mouseX, mouseY, x, rowTop, SLOT, SLOT);
                renderSelectedSlot(graphics, x, rowTop, entry, hover);
                if (hover && entry != null) {
                    hoveredSelected = entry;
                }
            }
        }

        // Shift hint while hovering the grid, otherwise the toast.
        int toastY = gridTop + gridRows * SLOT - 16;
        int centreX = gridLeft + GRID_COLS * SLOT / 2;
        if (toast.current() == null && Screen.hasShiftDown() && isOverGrid(mouseX, mouseY)) {
            Ui.pill(graphics, font, Component.translatable("smart_filter_extras.gui.hint_shift", visible.size()), centreX, toastY, GRID_COLS * SLOT, Ui.BLUE, 1.0F);
        } else {
            toast.render(graphics, font, centreX, toastY, GRID_COLS * SLOT);
        }

        if (hoveredGrid != null) {
            Mark mark = markOf(hoveredGrid);
            List<Component> hints = new ArrayList<>();
            if (mark == Mark.COVERED) {
                hints.add(Component.translatable("smart_filter_extras.gui.tip_covered").withStyle(ChatFormatting.GREEN));
            } else if (mark == Mark.PARTIAL) {
                hints.add(Component.translatable("smart_filter_extras.gui.tip_partial").withStyle(ChatFormatting.BLUE));
            }
            hints.add(Component.translatable(mark == Mark.SELECTED ? "smart_filter_extras.gui.tip_selected" : "smart_filter_extras.gui.tip_unselected")
                    .withStyle(ChatFormatting.DARK_GRAY));
            renderEntryTooltip(graphics, hoveredGrid, mouseX, mouseY, hints);
        } else if (hoveredSelected != null) {
            renderEntryTooltip(graphics, hoveredSelected, mouseX, mouseY,
                    List.of(Component.translatable("smart_filter_extras.gui.tip_remove").withStyle(ChatFormatting.RED)));
        }
    }

    private int firstVisibleTool() {
        int x = leftPos + guiWidth - 8;
        for (IconButton button : List.of(missingButton, copyButton, pasteButton, clearButton)) {
            if (button.visible) {
                return button.getX();
            }
        }
        return x;
    }

    /** The item's own tooltip plus our hint lines. The stack is passed on so other mods' tooltip additions work too. */
    private void renderEntryTooltip(GuiGraphics graphics, ItemEntry entry, int mouseX, int mouseY, List<Component> hints) {
        ItemStack stack = stack(entry);
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, stack));
        if (!entry.isPlain()) {
            lines.add(Component.translatable("smart_filter_extras.gui.tip_variant").withStyle(ChatFormatting.BLUE));
        }
        lines.addAll(hints);
        graphics.renderTooltip(font, lines, stack.getTooltipImage(), stack, mouseX, mouseY);
    }

    /** How an entry relates to the selection, for the grid marks and tooltip. */
    public enum Mark {
        /** The entry itself is selected. */
        SELECTED,
        /** A variant whose plain item is selected, so it already matches. */
        COVERED,
        /** A plain item with some of its variants selected (not the item as a whole). */
        PARTIAL,
        NONE
    }

    public Mark markOf(ItemEntry entry) {
        if (selection.contains(entry)) {
            return Mark.SELECTED;
        }
        if (!entry.isPlain() && plainSelected.contains(entry.item())) {
            return Mark.COVERED;
        }
        if (entry.isPlain() && variantSelected.contains(entry.item())) {
            return Mark.PARTIAL;
        }
        return Mark.NONE;
    }

    private void renderGridSlot(GuiGraphics graphics, int x, int y, @Nullable ItemEntry entry, boolean hover) {
        Ui.slot(graphics, x, y);
        if (entry == null) {
            return;
        }
        Mark mark = markOf(entry);
        if (mark == Mark.SELECTED) {
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x6048C048);
        } else if (mark == Mark.COVERED) {
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x2848C048);
        }
        graphics.renderItem(stack(entry), x + 1, y + 1);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        if (mark == Mark.SELECTED) {
            graphics.blitSprite(Ui.ICON_CHECK_SMALL, x + SLOT - 8, y + 1, 7, 6);
        } else if (mark == Mark.COVERED) {
            graphics.setColor(1.0F, 1.0F, 1.0F, 0.45F);
            graphics.blitSprite(Ui.ICON_CHECK_SMALL, x + SLOT - 8, y + 1, 7, 6);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        } else if (mark == Mark.PARTIAL) {
            graphics.fill(x + 1, y + SLOT - 3, x + SLOT - 1, y + SLOT - 1, 0xC03F6FD0);
        }
        if (hover) {
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x60FFFFFF);
        }
        graphics.pose().popPose();
    }

    private void renderSelectedSlot(GuiGraphics graphics, int x, int y, @Nullable ItemEntry entry, boolean hover) {
        Ui.slot(graphics, x, y);
        if (entry == null) {
            return;
        }
        graphics.renderItem(stack(entry), x + 1, y + 1);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        if (!entry.isPlain()) {
            graphics.fill(x + 1, y + SLOT - 3, x + SLOT - 1, y + SLOT - 1, 0xC03F6FD0);
        }
        if (hover) {
            graphics.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x80D04040);
            graphics.setColor(1.0F, 0.85F, 0.85F, 1.0F);
            graphics.blitSprite(Ui.ICON_CLEAR_SMALL, x + 6, y + 6, 7, 7);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        graphics.pose().popPose();
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int x = leftPos + SCROLL_X;
        int y = topPos + GRID_Y;
        int height = gridRows * SLOT;
        Ui.inset(graphics, x - 1, y, SCROLL_W + 2, height, 0xFF6F6F6F);
        int max = maxScroll();
        int knobY = y + 1 + (max == 0 ? 0 : Math.round((height - 2 - SCROLL_KNOB_H) * (scrollRow / (float) max)));
        graphics.blitSprite(max > 0 ? SCROLLER : SCROLLER_DISABLED, x, knobY, SCROLL_W, SCROLL_KNOB_H);
    }

    private ItemStack stack(ItemEntry entry) {
        return stacks.computeIfAbsent(entry, ItemEntry::toStack);
    }

    // ---------------------------------------------------------------- input

    /**
     * Enter saves. Ctrl+C / Ctrl+V copy and paste the selection, unless the search field is being edited (then
     * they edit the search text as usual).
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Keys.isEnter(keyCode) && Keys.enterSaves(this)) {
            save();
            return true;
        }
        if (Keys.backspaceToSearch(this, search.box(), keyCode, scanCode, modifiers)) {
            return true;
        }
        if (getFocused() != search.box()) {
            if (Keys.isCtrl(keyCode, modifiers, InputConstants.KEY_C)) {
                if (copyButton.active) {
                    copyToClipboard();
                } else {
                    toast.show(Component.translatable("smart_filter_extras.gui.nothing_selected"), Ui.ORANGE);
                }
                return true;
            }
            if (Keys.isCtrl(keyCode, modifiers, InputConstants.KEY_V)) {
                pasteFromClipboard();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return Keys.typeToSearch(this, search.box(), codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    private void save() {
        if (selection.isEmpty()) {
            toast.show(Component.translatable("smart_filter_extras.gui.nothing_selected"), Ui.ORANGE);
        } else {
            applyChanges();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && search.mouseClicked(mouseX, mouseY)) {
            setFocused(search.box());
            return true;
        }
        if (button == 0 && maxScroll() > 0
                && ScreenSupport.isInside(mouseX, mouseY, leftPos + SCROLL_X, topPos + GRID_Y, SCROLL_W, gridRows * SLOT)) {
            draggingScrollbar = true;
            scrollToMouse(mouseY);
            return true;
        }
        if (button == 0 || button == 1) {
            ItemEntry gridEntry = gridEntryAt(mouseX, mouseY);
            if (gridEntry != null) {
                onGridClick(gridEntry, button);
                return true;
            }
            int selectedSlot = selectedSlotAt(mouseX, mouseY);
            if (selectedSlot >= 0) {
                onSelectedSlotClick(selectedSlot);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int direction = scrollY > 0 ? -1 : 1;
            if (ScreenSupport.isInside(mouseX, mouseY, leftPos + 8, topPos + selectedRowY(), PAGE_SIZE * SLOT, SLOT)) {
                changePage(direction);
                return true;
            }
            if (isOverGrid(mouseX, mouseY)) {
                scrollRow = Mth.clamp(scrollRow + direction, 0, maxScroll());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isOverGrid(double mouseX, double mouseY) {
        return ScreenSupport.isInside(mouseX, mouseY, leftPos + GRID_X, topPos + GRID_Y, SCROLL_X + SCROLL_W - GRID_X, gridRows * SLOT);
    }

    private int maxScroll() {
        int rows = (visible.size() + GRID_COLS - 1) / GRID_COLS;
        return Math.max(0, rows - gridRows);
    }

    private void scrollToMouse(double mouseY) {
        double usable = gridRows * SLOT - SCROLL_KNOB_H;
        double t = (mouseY - (topPos + GRID_Y) - SCROLL_KNOB_H / 2.0) / usable;
        scrollRow = Mth.clamp((int) Math.round(t * maxScroll()), 0, maxScroll());
    }

    @Nullable
    private ItemEntry gridEntryAt(double mouseX, double mouseY) {
        int col = Mth.floor((mouseX - (leftPos + GRID_X)) / SLOT);
        int row = Mth.floor((mouseY - (topPos + GRID_Y)) / SLOT);
        if (col < 0 || col >= GRID_COLS || row < 0 || row >= gridRows) {
            return null;
        }
        int index = (scrollRow + row) * GRID_COLS + col;
        return index < visible.size() ? visible.get(index) : null;
    }

    private int selectedSlotAt(double mouseX, double mouseY) {
        if (!ScreenSupport.isInside(mouseX, mouseY, leftPos + 8, topPos + selectedRowY(), PAGE_SIZE * SLOT, SLOT)) {
            return -1;
        }
        return Mth.floor((mouseX - (leftPos + 8)) / SLOT);
    }

    // ---------------------------------------------------------------- recipe viewer drop target

    @Override
    public Rect2i getPanelArea() {
        return new Rect2i(leftPos, topPos, guiWidth, guiHeight);
    }

    @Override
    public Rect2i getDropArea() {
        return new Rect2i(leftPos + GRID_X, topPos + GRID_Y, GRID_COLS * SLOT, selectedRowY() + SLOT - GRID_Y);
    }

    /** In variant mode the dropped stack's components are kept; otherwise just its item. */
    @Override
    public boolean acceptDrop(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ItemEntry entry = lastVariants ? ItemEntry.variantOf(stack) : ItemEntry.of(stack.getItem());
        if (!addOrExplain(entry)) {
            return false;
        }
        if (lastSelectedOnly && !visible.contains(entry) && ItemCatalog.matches(stack(entry), search.box().getValue())) {
            visible.add(entry);
        }
        afterSelectionChanged();
        return true;
    }

    // ---------------------------------------------------------------- used by the dev-only client smoke test

    public List<ItemEntry> getSelectedEntries() {
        return selection.asList();
    }

    /** Items of the selected entries, in order. */
    public List<Item> getSelectedItems() {
        return selection.asList().stream().map(ItemEntry::item).toList();
    }

    public List<String> getKeptMissingEntries() {
        return List.copyOf(missing);
    }

    public List<ItemEntry> getVisibleEntries() {
        return List.copyOf(visible);
    }

    /** Items shown in the grid, in order (index 0 is the top-left slot when not scrolled). */
    public List<Item> getVisibleItems() {
        return visible.stream().map(ItemEntry::item).toList();
    }

    public boolean isDoneButtonActive() {
        return doneButton != null && doneButton.active;
    }

    public net.minecraft.client.gui.components.EditBox getSearchBox() {
        return search.box();
    }

    public boolean isShowingSelectedOnly() {
        return lastSelectedOnly;
    }

    public boolean isVariantMode() {
        return lastVariants;
    }

    public ItemCatalog.Source getSource() {
        return lastSource;
    }

    public int getSelectedPage() {
        return page;
    }

    public int getGridRows() {
        return gridRows;
    }

    /** Number of grid slots visible without scrolling. */
    public int getGridCapacity() {
        return gridRows * GRID_COLS;
    }

    /** Centre of grid slot {@code index} in screen coordinates. */
    public int[] gridSlotCentre(int index) {
        return new int[]{leftPos + GRID_X + (index % GRID_COLS) * SLOT + SLOT / 2, topPos + GRID_Y + (index / GRID_COLS) * SLOT + SLOT / 2};
    }

    /** Centre of selected-row slot {@code index} (0..8) in screen coordinates. */
    public int[] selectedSlotCentre(int index) {
        return new int[]{leftPos + 8 + index * SLOT + SLOT / 2, topPos + selectedRowY() + SLOT / 2};
    }

    public int[] searchClearCentre() {
        Rect2i box = new Rect2i(search.box().getX(), search.box().getY(), search.box().getWidth(), search.box().getHeight());
        return new int[]{box.getX() + box.getWidth() + 9, box.getY() + 4};
    }

    @Nullable
    public Component getStatusMessage() {
        return toast.current();
    }
}
