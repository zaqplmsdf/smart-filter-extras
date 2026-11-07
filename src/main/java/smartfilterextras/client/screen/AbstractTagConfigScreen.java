package smartfilterextras.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterConfigScreen;
import dev.ftb.mods.ftbfiltersystem.api.client.gui.AbstractFilterScreen;
import dev.ftb.mods.ftbfiltersystem.api.filter.SmartFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import smartfilterextras.filter.TagListArgument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Shared config screen for the tag filters ({@link BlockTagConfigScreen}, {@link ItemTagListConfigScreen}): a
 * searchable list of every tag of one registry with the number of entries in each. Several tags can be ticked
 * (the filter matches any of them); a preview shows entries from the ticked tags.
 *
 * <ul>
 *     <li>Click a row to tick or untick it; double click ticks it and saves. Hovering a row shows what's in the
 *     tag.</li>
 *     <li>Reverse lookup: put an item in the slot (click to cycle through your inventory, scroll to step, right
 *     click to clear, or drag one in from JEI / EMI) and the list only shows tags containing it.</li>
 *     <li>A typed tag ID that isn't loaded (e.g. from a data pack missing in this world) is offered at the top.</li>
 *     <li>The toggle next to the search field shows only the ticked tags.</li>
 *     <li>Enter saves; typing anywhere goes to the search field.</li>
 *     <li>A newly added filter starts with nothing ticked, so a default tag is never saved by accident.</li>
 *     <li>The list shows 4 to 8 rows, depending on the window height.</li>
 * </ul>
 *
 * @param <T> the registry's element type (blocks or items)
 * @param <F> the filter type
 */
public abstract class AbstractTagConfigScreen<T, F extends SmartFilter> extends AbstractFilterConfigScreen<F> implements ItemDropTarget {
    private static final int PANEL_WIDTH = 200;
    private static final int LOOKUP_Y = 38;
    private static final int LIST_Y = 60;
    private static final int ROW_HEIGHT = 12;
    /** Vanilla lists start the first row 4 px below the top. */
    private static final int LIST_TOP_PADDING = 4;
    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = 8;
    /** Everything below the list: gap, preview label, preview slots, bottom margin. */
    private static final int BELOW_LIST = 4 + 10 + Ui.SLOT + 2;
    /** Space the base class adds for the title and the Done/Cancel row. */
    private static final int BASE_EXTRA = 40;
    private static final int PREVIEW_SLOTS = 10;
    private static final int TOOLTIP_ITEMS = 27;
    private static final long ROW_TOOLTIP_DELAY_MS = 250L;
    private static final int SLOT = Ui.SLOT;
    private static final int TOOL = 16;
    private static final int SMALL_TOOL = 14;

    // Remembered between openings, per registry.
    private static final Map<ResourceKey<?>, String> LAST_SEARCH = new HashMap<>();
    private static final Map<ResourceKey<?>, Boolean> TICKED_ONLY = new HashMap<>();

    /** Translation keys that differ between the block and the item version. */
    protected record Texts(String lookupHint, String lookupTooltip, String lookupClear, String lookupNone, String lookupRejected,
                           String preview, String previewEmpty, String count) {
    }

    private record RowPreview(List<ItemStack> stacks, int size) {
    }

    private final ClickTracker clicks = new ClickTracker();
    private final SelectionModel<TagKey<T>> ticked = new SelectionModel<>(TagListArgument.MAX_TAGS);
    private final Ui.Toast toast = new Ui.Toast();
    private final Map<TagKey<T>, RowPreview> rowPreviews = new HashMap<>();
    private final int listRows;
    /** Whole rows plus the top padding, so a snapped list shows whole rows only. */
    private final int listHeight;

    private SearchField search;
    private TagList list;
    private IconButton clearLookupButton;
    private IconButton copyTicksButton;
    private IconButton clearTicksButton;
    @Nullable
    private Button doneButton;
    private boolean initialised;
    private boolean newFilter;
    private boolean savedAndClosing;
    /** The ticks as the filter was opened, to tell whether Cancel would lose anything. */
    private Set<TagKey<T>> originalTicks;
    private List<ItemStack> previewStacks = List.of();
    private int previewTotal;
    @Nullable
    private Item lookupItem;
    @Nullable
    private Set<TagKey<T>> lookupTags;
    @Nullable
    private TagKey<T> hoveredRowTag;
    private long hoveredRowSince;

    /** @param listRows visible list rows, see {@link #listRowsFor(int)} */
    protected AbstractTagConfigScreen(F filter, AbstractFilterScreen parentScreen, List<TagKey<T>> initialTags, int listRows) {
        super(filter, parentScreen, PANEL_WIDTH, contentHeight(listRows));
        this.listRows = listRows;
        this.listHeight = listHeight(listRows);
        ticked.addAll(initialTags);
        originalTicks = Set.copyOf(ticked.asList());
    }

    /** As many list rows (4..8) as fit on a screen of this (GUI-scaled) height, with a small margin. */
    public static int listRowsFor(int screenHeight) {
        int spare = screenHeight - 8 - BASE_EXTRA - contentHeight(0);
        return Mth.clamp(spare / ROW_HEIGHT, MIN_ROWS, MAX_ROWS);
    }

    /** List rows for the current window. */
    protected static int currentListRows() {
        return listRowsFor(Minecraft.getInstance().getWindow().getGuiScaledHeight());
    }

    private static int listHeight(int rows) {
        return rows * ROW_HEIGHT + LIST_TOP_PADDING;
    }

    private static int contentHeight(int rows) {
        return LIST_Y + listHeight(rows) + BELOW_LIST;
    }

    private int previewLabelY() {
        return LIST_Y + listHeight + 4;
    }

    private int previewY() {
        return previewLabelY() + 10;
    }

    /** A new screen for the same filter with a different number of list rows. */
    protected abstract AbstractTagConfigScreen<T, F> withListRows(int rows);

    /**
     * The panel height is fixed when the screen is created (FFS's base class), so if the window now fits a
     * different number of list rows, the screen is reopened at the new size with everything carried over.
     * Otherwise a smaller window would cut off the title and the Done / Cancel buttons.
     */
    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        int rows = listRowsFor(height);
        if (rows == listRows) {
            super.resize(minecraft, width, height);
            return;
        }
        AbstractTagConfigScreen<T, F> resized = withListRows(rows);
        resized.setDeleteOnCancel(newFilter);
        resized.ticked.clear();
        resized.ticked.addAll(ticked.asList());
        resized.lookupItem = lookupItem;
        resized.lookupTags = lookupTags;
        minecraft.setScreen(resized);
    }

    // ---------------------------------------------------------------- what differs between block and item tags

    protected abstract Registry<T> registry();

    protected abstract F createFilter(List<TagKey<T>> tags);

    /** Tags of whatever this item stands for, or {@code null} if it can't be looked up on this screen. */
    @Nullable
    protected abstract Set<TagKey<T>> tagsOf(Item item);

    /** The item shown for a registry element in previews ({@link Items#AIR} if there is none). */
    protected abstract Item itemOf(T value);

    /** Faded icon in the empty lookup slot. */
    protected abstract ItemStack lookupPlaceholder();

    protected abstract Texts texts();

    // ---------------------------------------------------------------- setup

    /**
     * FFS calls this with {@code true} only for a filter that was just added from the "Add..." menu, before the
     * screen is shown. Such a filter starts with nothing ticked: with several tags allowed, a preselected default
     * would otherwise end up in the filter next to the tag the player actually picked.
     */
    @Override
    public void setDeleteOnCancel(boolean deleteOnCancel) {
        super.setDeleteOnCancel(deleteOnCancel);
        newFilter = deleteOnCancel;
        if (deleteOnCancel && !initialised) {
            ticked.clear();
            originalTicks = Set.of();
        }
    }

    /** Cancel and Esc ask first if the ticked tags differ from the filter as it was opened. */
    @Override
    public void onClose() {
        if (savedAndClosing || !hasUnsavedChanges()) {
            super.onClose();
        } else {
            UnsavedChanges.confirmDiscard(this, super::onClose);
        }
    }

    public boolean hasUnsavedChanges() {
        return !Set.copyOf(ticked.asList()).equals(originalTicks);
    }

    private boolean tickedOnly() {
        return TICKED_ONLY.getOrDefault(registry().key(), false);
    }

    private void toggleTickedOnly() {
        TICKED_ONLY.put(registry().key(), !tickedOnly());
        rebuildList();
    }

    @Override
    protected void init() {
        super.init();

        ResourceKey<?> registryKey = registry().key();
        String previousSearch = LAST_SEARCH.getOrDefault(registryKey, "");
        EditBox box = makeSearchEditBox(leftPos + 8, topPos + 20, () -> LAST_SEARCH.getOrDefault(registryKey, ""), s -> LAST_SEARCH.put(registryKey, s));
        box.setMaxLength(128);
        box.setValue(previousSearch);
        box.setTooltip(Tooltip.create(Component.translatable("smart_filter_extras.gui.tag_search_tooltip")));
        // The search row starts at 19, right below FFS's info icon (3..19).
        search = new SearchField(box, leftPos + 8, topPos + 20, guiWidth - 16 - TOOL - 2);
        search.setHint(font, Component.translatable("smart_filter_extras.gui.tag_search_hint").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(new IconButton(leftPos + guiWidth - 8 - TOOL, topPos + 19, TOOL, () -> Ui.ICON_VIEW_SELECTED, 12, 12,
                Component.translatable("smart_filter_extras.gui.tag_ticked_only"), b -> toggleTickedOnly())
                .pressedWhen(this::tickedOnly)
                .withTooltip(Component.translatable("smart_filter_extras.gui.tag_ticked_only_tooltip")));

        // Lookup row, right end: [x] (only while looking up), then copy, paste and untick-all.
        int toolY = topPos + LOOKUP_Y + 2;
        int right = leftPos + guiWidth - 8;
        clearTicksButton = addRenderableWidget(new IconButton(right - SMALL_TOOL, toolY, SMALL_TOOL, () -> Ui.ICON_TRASH, 12, 12,
                Component.translatable("smart_filter_extras.gui.clear"), b -> untickAll())
                .withTooltip(Component.translatable("smart_filter_extras.gui.tag_clear_tooltip")));
        addRenderableWidget(new IconButton(right - 2 * SMALL_TOOL - 2, toolY, SMALL_TOOL, () -> Ui.ICON_PASTE, 12, 12,
                Component.translatable("smart_filter_extras.gui.paste"), b -> pasteTicks(ClipboardSupport.read(minecraft)))
                .withTooltip(Component.translatable("smart_filter_extras.gui.tag_paste_tooltip")));
        copyTicksButton = addRenderableWidget(new IconButton(right - 3 * SMALL_TOOL - 4, toolY, SMALL_TOOL, () -> Ui.ICON_COPY, 12, 12,
                Component.translatable("smart_filter_extras.gui.copy"), b -> copyTicks())
                .withTooltip(Component.translatable("smart_filter_extras.gui.tag_copy_tooltip")));
        clearLookupButton = addRenderableWidget(new IconButton(copyTicksButton.getX() - 4 - SMALL_TOOL, toolY, SMALL_TOOL,
                () -> Ui.ICON_CLEAR_SMALL, 7, 7, Component.translatable(texts().lookupClear()), b -> setLookup(null))
                .withTooltip(Component.translatable(texts().lookupClear())));

        list = addRenderableWidget(new TagList(minecraft, guiWidth - 16, listHeight, topPos + LIST_Y));
        list.setX(leftPos + 8);

        doneButton = ScreenSupport.findDoneButton(this);
        initialised = true;
        rebuildList();
        onTickedChanged();
        clearLookupButton.visible = lookupItem != null;
        // The search field isn't focused at first, so Ctrl+C / Ctrl+V work on the ticked tags right away;
        // typing anywhere still goes to the search field (see charTyped).
        setFocused(null);
    }

    // ---------------------------------------------------------------- copy / paste / untick all

    /** Copies the ticked tags as a comma-separated list, the same text as the filter's argument. */
    private void copyTicks() {
        if (ticked.isEmpty()) {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_none_selected"), Ui.ORANGE);
        } else if (ClipboardSupport.write(minecraft, TagListArgument.join(ticked.asList()))) {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_copied", ticked.size()), Ui.GREEN);
        } else {
            toast.show(Component.translatable("smart_filter_extras.gui.clipboard_busy"), Ui.RED);
        }
    }

    /** Ticks the tags found in the text; see {@link TagListArgument#extractTagIds(String)} for accepted formats. */
    public void pasteTicks(String text) {
        List<ResourceLocation> ids = TagListArgument.extractTagIds(text);
        int added = 0;
        int notLoaded = 0;
        boolean full = false;
        for (ResourceLocation id : ids) {
            TagKey<T> tag = TagKey.create(registry().key(), id);
            SelectionModel.Change change = ticked.add(tag);
            if (change == SelectionModel.Change.FULL) {
                full = true;
                break;
            }
            if (change == SelectionModel.Change.ADDED) {
                added++;
                if (!exists(tag)) {
                    notLoaded++;
                }
            }
        }
        onTickedChanged();
        rebuildList();
        if (ids.isEmpty()) {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_paste_nothing"), Ui.RED);
        } else if (full) {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_limit", TagListArgument.MAX_TAGS), Ui.RED);
        } else {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_pasted", added, notLoaded), notLoaded > 0 ? Ui.ORANGE : Ui.GREEN);
        }
    }

    private void untickAll() {
        int count = ticked.size();
        ticked.clear();
        onTickedChanged();
        if (tickedOnly()) {
            rebuildList();
        }
        toast.show(Component.translatable("smart_filter_extras.gui.tag_cleared", count), Ui.GREEN);
    }

    @Override
    protected void doScheduledUpdate() {
        rebuildList();
    }

    @Override
    @Nullable
    protected F makeNewFilter() {
        if (ticked.isEmpty()) {
            return null;
        }
        // FFS's applyChanges() calls this, saves, then closes the screen: that close must not ask about losing edits.
        savedAndClosing = true;
        return createFilter(ticked.asList());
    }

    private void toggle(TagKey<T> tag) {
        if (ticked.toggle(tag) == SelectionModel.Change.FULL) {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_limit", TagListArgument.MAX_TAGS), Ui.RED);
        }
        onTickedChanged();
    }

    private void onTickedChanged() {
        if (doneButton != null) {
            doneButton.active = !ticked.isEmpty();
        }
        copyTicksButton.active = clearTicksButton.active = !ticked.isEmpty();
        updatePreview();
    }

    private void save() {
        if (ticked.isEmpty()) {
            toast.show(Component.translatable("smart_filter_extras.gui.tag_none_selected"), Ui.ORANGE);
        } else {
            applyChanges();
        }
    }

    private void rebuildList() {
        String query = search.box().getValue().trim().toLowerCase(Locale.ROOT);
        String plainQuery = query.startsWith("#") ? query.substring(1) : query;

        Stream<TagKey<T>> candidates = lookupTags != null ? lookupTags.stream() : registry().getTagNames();
        boolean onlyTicked = tickedOnly();
        List<TagKey<T>> tags = candidates
                .filter(tag -> !onlyTicked || ticked.contains(tag))
                .filter(tag -> plainQuery.isEmpty() || tag.location().toString().contains(plainQuery))
                .sorted(Comparator.comparing(TagKey::location))
                .toList();

        List<TagList.TagEntry> entries = new ArrayList<>();
        Set<TagKey<T>> listed = new LinkedHashSet<>(tags);
        if (lookupTags == null) {
            ResourceLocation typed = TagListArgument.parseTagId(query);
            if (typed != null && plainQuery.contains(":")) {
                TagKey<T> typedTag = TagKey.create(registry().key(), typed);
                if (!listed.contains(typedTag) && !exists(typedTag)) {
                    entries.add(list.new TagEntry(typedTag, true));
                    listed.add(typedTag);
                }
            }
            // Ticked tags that don't exist in this world stay visible (and untickable) at the top.
            for (TagKey<T> tag : ticked.asList()) {
                if (!listed.contains(tag) && !exists(tag) && (plainQuery.isEmpty() || tag.location().toString().contains(plainQuery))) {
                    entries.add(list.new TagEntry(tag, true));
                    listed.add(tag);
                }
            }
        }
        for (TagKey<T> tag : tags) {
            entries.add(list.new TagEntry(tag, false));
        }
        list.replaceAll(entries);
        ticked.asList().stream().findFirst().flatMap(first -> entries.stream().filter(e -> e.tag.equals(first)).findFirst())
                .ifPresent(list::show);
    }

    private boolean exists(TagKey<T> tag) {
        return registry().getTag(tag).isPresent();
    }

    private Optional<HolderSet.Named<T>> contents(TagKey<T> tag) {
        return registry().getTag(tag);
    }

    /** Preview: entries from the ticked tags (union), the first {@link #PREVIEW_SLOTS} that have an item. */
    private void updatePreview() {
        Set<T> values = new LinkedHashSet<>();
        for (TagKey<T> tag : ticked.asList()) {
            contents(tag).ifPresent(set -> set.forEach(holder -> values.add(holder.value())));
        }
        previewTotal = values.size();
        previewStacks = firstItems(values.stream(), PREVIEW_SLOTS);
    }

    /** The first {@code limit} distinct items of these values (values without an item are skipped). */
    private List<ItemStack> firstItems(Stream<T> values, int limit) {
        Set<Item> items = new LinkedHashSet<>();
        for (var it = values.iterator(); it.hasNext() && items.size() < limit; ) {
            Item item = itemOf(it.next());
            if (item != Items.AIR) {
                items.add(item);
            }
        }
        return items.stream().map(ItemStack::new).toList();
    }

    private RowPreview rowPreview(TagKey<T> tag) {
        return rowPreviews.computeIfAbsent(tag, t -> contents(t)
                .map(set -> new RowPreview(firstItems(set.stream().map(Holder::value), TOOLTIP_ITEMS), set.size()))
                .orElseGet(() -> new RowPreview(List.of(), -1)));
    }

    // ---------------------------------------------------------------- reverse lookup

    /** Show only tags containing what this item stands for; {@code null} shows every tag again. */
    public void setLookup(@Nullable Item item) {
        Set<TagKey<T>> tags = item == null ? null : tagsOf(item);
        if (item != null && tags == null) {
            return;
        }
        lookupItem = item;
        lookupTags = tags;
        rebuildList();
        clearLookupButton.visible = item != null;
    }

    /** Step through the suitable items in the player's inventory. */
    private void cycleLookup(int direction) {
        List<Item> candidates = ItemCatalog.items(ItemCatalog.Source.INVENTORY, minecraft).stream()
                .filter(item -> tagsOf(item) != null)
                .toList();
        if (candidates.isEmpty()) {
            toast.show(Component.translatable(texts().lookupNone()), Ui.ORANGE);
            return;
        }
        int index = lookupItem == null ? -1 : candidates.indexOf(lookupItem);
        int next = index < 0 ? (direction > 0 ? 0 : candidates.size() - 1) : Mth.positiveModulo(index + direction, candidates.size());
        setLookup(candidates.get(next));
    }

    private boolean isOverLookupSlot(double mouseX, double mouseY) {
        return ScreenSupport.isInside(mouseX, mouseY, leftPos + 8, topPos + LOOKUP_Y, SLOT, SLOT);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && search.mouseClicked(mouseX, mouseY)) {
            setFocused(search.box());
            return true;
        }
        if (isOverLookupSlot(mouseX, mouseY)) {
            if (button == 1) {
                setLookup(null);
            } else if (button == 0) {
                cycleLookup(1);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && isOverLookupSlot(mouseX, mouseY)) {
            cycleLookup(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Keys.isEnter(keyCode) && Keys.enterSaves(this)) {
            save();
            return true;
        }
        if (Keys.backspaceToSearch(this, search.box(), keyCode, scanCode, modifiers)) {
            return true;
        }
        // Ctrl+C / Ctrl+V copy and paste the ticked tags, unless the search field is being edited.
        if (getFocused() != search.box()) {
            if (Keys.isCtrl(keyCode, modifiers, InputConstants.KEY_C)) {
                copyTicks();
                return true;
            }
            if (Keys.isCtrl(keyCode, modifiers, InputConstants.KEY_V)) {
                pasteTicks(ClipboardSupport.read(minecraft));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return Keys.typeToSearch(this, search.box(), codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        search.renderFrame(graphics, mouseX, mouseY);
        Ui.inset(graphics, leftPos + 7, topPos + LIST_Y - 1, guiWidth - 14, listHeight + 2, 0xFFA9A9A9);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        ItemStack hovered = null;
        Component hoveredText = null;

        // Lookup row.
        int slotX = leftPos + 8;
        int slotY = topPos + LOOKUP_Y;
        Ui.slot(graphics, slotX, slotY);
        boolean overSlot = isOverLookupSlot(mouseX, mouseY);
        if (lookupItem != null) {
            ItemStack lookupStack = new ItemStack(lookupItem);
            graphics.renderItem(lookupStack, slotX + 1, slotY + 1);
            MutableComponent text = Component.empty().append(lookupStack.getHoverName())
                    .append(Component.translatable("smart_filter_extras.gui.lookup_active", lookupTags == null ? 0 : lookupTags.size()));
            Ui.drawClipped(graphics, font, text, slotX + SLOT + 5, slotY + 5, clearLookupButton.getX() - 4 - (slotX + SLOT + 5), Ui.TEXT);
            if (overSlot) {
                hovered = lookupStack;
            }
        } else {
            graphics.setColor(1.0F, 1.0F, 1.0F, 0.35F);
            graphics.renderFakeItem(lookupPlaceholder(), slotX + 1, slotY + 1);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            Ui.drawClipped(graphics, font, Component.translatable(texts().lookupHint()), slotX + SLOT + 5, slotY + 5,
                    copyTicksButton.getX() - 4 - (slotX + SLOT + 5), Ui.TEXT_DIM);
        }
        if (overSlot) {
            graphics.fill(slotX + 1, slotY + 1, slotX + SLOT - 1, slotY + SLOT - 1, 0x50FFFFFF);
            if (hovered == null) {
                hoveredText = Component.translatable(texts().lookupTooltip());
            }
        }

        // Ticked summary and preview.
        Component label;
        if (ticked.isEmpty()) {
            label = Component.translatable("smart_filter_extras.gui.tag_none_selected");
        } else if (previewTotal == 0) {
            label = Component.translatable(texts().previewEmpty(), ticked.size());
        } else {
            label = Component.translatable(texts().preview(), ticked.size(), previewTotal);
        }
        Ui.drawClipped(graphics, font, label, leftPos + 8, topPos + previewLabelY(), guiWidth - 16, Ui.TEXT);
        if (ScreenSupport.isInside(mouseX, mouseY, leftPos + 8, topPos + previewLabelY() - 1, guiWidth - 16, 10) && !ticked.isEmpty()) {
            MutableComponent all = Component.translatable("smart_filter_extras.gui.tag_ticked_list");
            ticked.asList().forEach(tag -> all.append("\n#" + tag.location()));
            hoveredText = all;
        }
        for (int i = 0; i < PREVIEW_SLOTS; i++) {
            int x = leftPos + 8 + i * SLOT;
            int y = topPos + previewY();
            Ui.slot(graphics, x, y);
            if (i < previewStacks.size()) {
                graphics.renderItem(previewStacks.get(i), x + 1, y + 1);
                if (ScreenSupport.isInside(mouseX, mouseY, x, y, SLOT, SLOT)) {
                    hovered = previewStacks.get(i);
                }
            }
        }

        if (list.children().isEmpty()) {
            Component empty = Component.translatable(tickedOnly() && ticked.isEmpty()
                    ? "smart_filter_extras.gui.tag_nothing_ticked" : "smart_filter_extras.gui.tag_no_matches");
            Ui.pill(graphics, font, empty, leftPos + guiWidth / 2, topPos + LIST_Y + (listHeight - 14) / 2, guiWidth - 24, Ui.ORANGE, 0.85F);
        }
        toast.render(graphics, font, leftPos + guiWidth / 2, topPos + LIST_Y + listHeight - 17, guiWidth - 24);

        if (hovered != null) {
            graphics.renderTooltip(font, hovered, mouseX, mouseY);
        } else if (hoveredText != null) {
            graphics.renderTooltip(font, font.split(hoveredText, 200), mouseX, mouseY);
        } else {
            renderRowTooltip(graphics, mouseX, mouseY);
        }
    }

    /** After a short hover on a row: the tag's ID, its first entries as icons, its size and what a click does. */
    private void renderRowTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        TagList.TagEntry row = list.hoveredRow();
        TagKey<T> tag = row == null ? null : row.tag;
        long now = Util.getMillis();
        if (tag == null || !tag.equals(hoveredRowTag)) {
            hoveredRowTag = tag;
            hoveredRowSince = now;
            return;
        }
        if (now - hoveredRowSince < ROW_TOOLTIP_DELAY_MS || toast.current() != null) {
            return;
        }
        RowPreview preview = rowPreview(tag);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("#" + tag.location()));
        if (preview.size() < 0) {
            lines.add(Component.translatable("smart_filter_extras.gui.tag_not_loaded_long").withStyle(ChatFormatting.GOLD));
        } else {
            lines.add(Component.translatable(texts().count(), preview.size()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable(ticked.contains(tag) ? "smart_filter_extras.gui.tag_tip_ticked" : "smart_filter_extras.gui.tag_tip_unticked")
                .withStyle(ChatFormatting.DARK_GRAY));
        Optional<TooltipComponent> image = preview.stacks().isEmpty() ? Optional.empty()
                : Optional.of(new TagPreviewTooltip(preview.stacks(), Math.max(0, preview.size() - preview.stacks().size())));
        graphics.renderTooltip(font, lines, image, mouseX, mouseY);
    }

    // ---------------------------------------------------------------- recipe viewer drop target

    @Override
    public Rect2i getPanelArea() {
        return new Rect2i(leftPos, topPos, guiWidth, guiHeight);
    }

    /** The lookup row and the tag list: dropping an item anywhere there looks it up. */
    @Override
    public Rect2i getDropArea() {
        return new Rect2i(leftPos + 8, topPos + LOOKUP_Y, guiWidth - 16, LIST_Y + listHeight - LOOKUP_Y);
    }

    @Override
    public boolean canAcceptDrop(ItemStack stack) {
        return !stack.isEmpty() && tagsOf(stack.getItem()) != null;
    }

    @Override
    public boolean acceptDrop(ItemStack stack) {
        if (!canAcceptDrop(stack)) {
            toast.show(Component.translatable(texts().lookupRejected()), Ui.RED);
            return false;
        }
        setLookup(stack.getItem());
        return true;
    }

    // ---------------------------------------------------------------- used by the dev-only client smoke test

    /** The first ticked tag, or {@code null}. */
    @Nullable
    public TagKey<T> getSelectedTag() {
        return ticked.isEmpty() ? null : ticked.asList().get(0);
    }

    public List<TagKey<T>> getTickedTags() {
        return ticked.asList();
    }

    @Nullable
    public Item getLookupItem() {
        return lookupItem;
    }

    public EditBox getSearchBox() {
        return search.box();
    }

    public boolean isDoneButtonActive() {
        return doneButton != null && doneButton.active;
    }

    /** Tags currently listed, top to bottom. */
    public List<TagKey<T>> getListedTags() {
        return list.children().stream().map(e -> e.tag).toList();
    }

    /** Centre of list row {@code index} in screen coordinates; scrolls the row into view first. */
    public int[] tagRowCentre(int index) {
        list.show(list.children().get(index));
        return list.rowCentre(index);
    }

    /** A point on the list's scrollbar track, {@code fraction} of the way down (0 = top, 1 = bottom). */
    public int[] scrollbarPoint(double fraction) {
        int y = list.getY() + 1 + (int) Math.round(Mth.clamp(fraction, 0, 1) * (list.getHeight() - 3));
        return new int[]{list.getX() + list.getWidth() - 3, y};
    }

    public double getListScroll() {
        return list.getScrollAmount();
    }

    public int getListMaxScroll() {
        return list.getMaxScroll();
    }

    /** Scrolls the tag list with the mouse wheel over its centre, like a player would. */
    public void scrollList(double scrollY) {
        mouseScrolled(list.getX() + list.getWidth() / 2.0, list.getY() + list.getHeight() / 2.0, 0, scrollY);
    }

    /** Pixel offset of the top visible row's top edge from the list's first row position (0 = aligned). */
    public int getListRowMisalignment() {
        return (int) list.getScrollAmount() % ROW_HEIGHT;
    }

    public int getListRows() {
        return listRows;
    }

    public boolean isTickedOnly() {
        return tickedOnly();
    }

    /** The tag of the row under the given point, if any (what a hover tooltip would describe). */
    @Nullable
    public TagKey<T> getRowTagAt(double mouseX, double mouseY) {
        TagList.TagEntry entry = list.isMouseOver(mouseX, mouseY) ? list.entryAt(mouseX, mouseY) : null;
        return entry == null ? null : entry.tag;
    }

    /** Size and preview items for a tag's hover tooltip; size is -1 for a tag that isn't loaded. */
    public int getRowPreviewSize(TagKey<T> tag) {
        return rowPreview(tag).size();
    }

    public List<ItemStack> getRowPreviewStacks(TagKey<T> tag) {
        return rowPreview(tag).stacks();
    }

    public int[] lookupSlotCentre() {
        return new int[]{leftPos + 8 + SLOT / 2, topPos + LOOKUP_Y + SLOT / 2};
    }

    @Nullable
    public Component getStatusMessage() {
        return toast.current();
    }

    // ---------------------------------------------------------------- list widget

    final class TagList extends ObjectSelectionList<TagList.TagEntry> {
        private static final int TOP_PADDING = LIST_TOP_PADDING;

        private boolean thumbHeld;
        private double wheelRemainder;

        TagList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
        }

        void replaceAll(List<TagEntry> entries) {
            replaceEntries(entries);
            setScrollAmount(0);
        }

        void show(TagEntry entry) {
            int index = children().indexOf(entry);
            if (index >= 0) {
                int rowTop = index * itemHeight;
                if (rowTop < getScrollAmount() || rowTop + itemHeight > getScrollAmount() + height - TOP_PADDING) {
                    centerScrollOn(entry);
                }
            }
        }

        int[] rowCentre(int index) {
            return new int[]{getRowLeft() + getRowWidth() / 2, getRowTop(index) + itemHeight / 2};
        }

        @Nullable
        TagEntry hoveredRow() {
            return getHovered();
        }

        @Nullable
        TagEntry entryAt(double mouseX, double mouseY) {
            return getEntryAtPosition(mouseX, mouseY);
        }

        /** Scroll positions are kept on whole rows, so no row is ever cut in half at the top. */
        private double snap(double amount) {
            return Mth.clamp(Math.round(amount / itemHeight) * (double) itemHeight, 0, getMaxScroll());
        }

        @Override
        protected void centerScrollOn(TagEntry entry) {
            setScrollAmount(snap(children().indexOf(entry) * itemHeight + itemHeight / 2.0 - height / 2.0));
        }

        /** One row per wheel notch (vanilla scrolls half a row); fractional touchpad deltas add up. */
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            wheelRemainder += scrollY;
            int rows = (int) wheelRemainder;
            wheelRemainder -= rows;
            if (rows != 0) {
                setScrollAmount(snap(getScrollAmount() - rows * (double) itemHeight));
            }
            return true;
        }

        /** The 4 px padding above the first row shows nothing, so a row scrolled up past it isn't drawn there. */
        @Override
        protected void enableScissor(GuiGraphics graphics) {
            graphics.enableScissor(getX(), getY() + TOP_PADDING - 1, getRight(), getBottom());
        }

        /** Nor can that hidden row be hovered or clicked through the padding. */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return super.isMouseOver(mouseX, mouseY) && (mouseY >= getY() + TOP_PADDING - 1 || isOverScrollbar(mouseX, mouseY));
        }

        /** Rows stop 10 px short of each side, clear of the 6 px scrollbar. */
        @Override
        public int getRowWidth() {
            return width - 20;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - SCROLLBAR_WIDTH;
        }

        /** Vanilla's black scrollbar is replaced by a light one matching the list, drawn in {@link #renderDecorations}. */
        @Override
        protected boolean scrollbarVisible() {
            return false;
        }

        /** Same formula vanilla uses for dragging, so the drawn thumb tracks the mouse exactly. */
        private int thumbHeight() {
            return Mth.clamp((int) ((float) (height * height) / (float) getMaxPosition()), 32, height - 8);
        }

        private int thumbTop() {
            int max = getMaxScroll();
            return getY() + (max <= 0 ? 0 : (int) getScrollAmount() * (height - thumbHeight()) / max);
        }

        private boolean isOverScrollbar(double mouseX, double mouseY) {
            return getMaxScroll() > 0 && ScreenSupport.isInside(mouseX, mouseY, getScrollbarPosition(), getY(), SCROLLBAR_WIDTH, height);
        }

        @Override
        protected void renderDecorations(GuiGraphics graphics, int mouseX, int mouseY) {
            if (thumbHeld && !minecraft.mouseHandler.isLeftPressed()) {
                thumbHeld = false;
            }
            if (getMaxScroll() <= 0) {
                return;
            }
            int x = getScrollbarPosition();
            graphics.fill(x, getY(), x + SCROLLBAR_WIDTH, getBottom(), 0xFF8B8B8B);
            int top = thumbTop();
            int bottom = top + thumbHeight();
            boolean hot = thumbHeld || ScreenSupport.isInside(mouseX, mouseY, x, top, SCROLLBAR_WIDTH, bottom - top);
            // Raised knob: white top/left, dark bottom/right, like a vanilla button.
            graphics.fill(x, top, x + SCROLLBAR_WIDTH, bottom, 0xFF555555);
            graphics.fill(x, top, x + SCROLLBAR_WIDTH - 1, bottom - 1, 0xFFFFFFFF);
            graphics.fill(x + 1, top + 1, x + SCROLLBAR_WIDTH - 1, bottom - 1, hot ? 0xFFDCDCDC : 0xFFC6C6C6);
        }

        /** Clicking the track jumps the thumb there (centred on the mouse); vanilla then handles the drag. */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
                int top = thumbTop();
                int thumb = thumbHeight();
                if (mouseY < top || mouseY >= top + thumb) {
                    double t = (mouseY - getY() - thumb / 2.0) / (height - thumb);
                    setScrollAmount(snap(t * getMaxScroll()));
                }
                thumbHeld = true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        /** Selection is drawn by the rows themselves (tick boxes), not as vanilla's single highlight. */
        @Override
        protected boolean isSelectedItem(int index) {
            return false;
        }

        @Override
        protected void renderListBackground(GuiGraphics graphics) {
        }

        @Override
        protected void renderListSeparators(GuiGraphics graphics) {
        }

        final class TagEntry extends ObjectSelectionList.Entry<TagEntry> {
            final TagKey<T> tag;
            private final boolean notLoaded;
            private final Component countText;

            TagEntry(TagKey<T> tag, boolean notLoaded) {
                this.tag = tag;
                this.notLoaded = notLoaded;
                this.countText = notLoaded
                        ? Component.translatable("smart_filter_extras.gui.tag_not_loaded")
                        : Component.literal(String.valueOf(contents(tag).map(HolderSet::size).orElse(0)));
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean on = ticked.contains(tag);
                if (on) {
                    graphics.fill(left - 2, top - 1, left + rowWidth + 2, top + rowHeight - 1, 0xFFD3E2F4);
                } else if (hovering) {
                    graphics.fill(left - 2, top - 1, left + rowWidth + 2, top + rowHeight - 1, 0xFFBDBDBD);
                }
                // Tick box.
                Ui.inset(graphics, left, top, 9, 9, on ? 0xFF3FB43F : 0xFFE6E6E6);
                if (on) {
                    graphics.blitSprite(Ui.ICON_CHECK_SMALL, left + 1, top + 1, 7, 6);
                }
                int countWidth = font.width(countText);
                graphics.drawString(font, countText, left + rowWidth - countWidth - 1, top + 1, notLoaded ? 0xFF9A5000 : 0xFF4A4A4A, false);
                int textX = left + 13;
                int maxText = rowWidth - 13 - countWidth - 6;
                // Namespace dimmed, path dark: easier to scan a long list.
                String namespace = tag.location().getNamespace() + ":";
                String path = tag.location().getPath();
                int nsWidth = font.width(namespace);
                if (nsWidth + font.width(path) > maxText) {
                    path = font.plainSubstrByWidth(path, Math.max(0, maxText - nsWidth - font.width("…"))) + "…";
                }
                graphics.drawString(font, namespace, textX, top + 1, notLoaded ? 0xFF7A5020 : 0xFF4E4E4E, false);
                graphics.drawString(font, path, textX + nsWidth, top + 1, notLoaded ? 0xFF8A4A00 : 0xFF1C1C1C, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button != 0) {
                    return false;
                }
                if (clicks.click(tag, Util.getMillis())) {
                    // Double click: make sure it's ticked (the first click may have just unticked it), then save.
                    if (!ticked.contains(tag)) {
                        toggle(tag);
                    }
                    if (!ticked.isEmpty()) {
                        applyChanges();
                    }
                } else {
                    toggle(tag);
                }
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", tag.location().toString());
            }
        }
    }
}
