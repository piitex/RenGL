package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.image.IconCache;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.TextFit;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * A grid of item slots that the player moves things between: a backpack, a chest, an equipment panel, a hotbar, a shop's
 * stock.
 * <pre>{@code
 * InventoryGridOverlay<Item> bag = new InventoryGridOverlay<>(6, 4, 48);
 * bag.setImageProvider(Item::sprite);
 * bag.setMaxStack(item -> item.stackSize());
 * bag.setTooltipProvider(Item::description);
 * bag.add(potion, 5);
 *
 * InventoryGridOverlay<Item> chest = new InventoryGridOverlay<>(6, 3, 48);
 * bag.link(chest);                                 // items can be dragged between the two
 * bag.onUse(slot -> use(bag.getItem(slot)));       // right-click or double-click
 * }</pre>
 * The grid holds up to a slot's {@linkplain #setMaxStack stack limit} of one kind of item in each slot. The items are the
 * game's own objects of type {@code T}, told apart by {@code equals}; the grid draws them with the {@linkplain
 * #setImageProvider image} or {@linkplain #setIconProvider icon} you give, or else their label. It keeps no other rules, so
 * the game decides what an item is.
 * <p>
 * <b>Moving.</b> The player presses a slot and drags: the whole stack, or half of it with Ctrl held. Dropped on an empty slot
 * the items move there; on a slot of the same item they join it up to the limit; on a different item the two swap, when the
 * whole stack was dragged and both slots accept what they are given. A drop that is not allowed leaves everything as it was.
 * Shift-click sends a stack to a {@linkplain #link linked grid}. A drop outside every grid goes to {@link #onDropOutside},
 * which can throw the items into the world; without one it is a cancelled drag. The same moves are available to code as
 * {@link #move}, {@link #add} and {@link #remove}.
 * <p>
 * <b>Slots.</b> {@link #setLocked a locked slot} cannot be used or dropped on, like a bag slot not yet bought, and {@link
 * #setSlotFilter a slot filter} restricts what a slot takes, like a helmet slot taking only helmets. A click selects a slot
 * ({@link #onSelect}), right-click or double-click calls {@link #onUse}, and the arrow keys and Enter do the same from the
 * keyboard.
 * <p>
 * Every change the player or the methods make reports the slots affected through {@link #onSlotChange}, so a game model can
 * follow. Colors come from the theme: the slots from the field background and border, the selection and drop highlight from
 * the accent.
 *
 * @param <T> the type of item
 */
public class InventoryGridOverlay<T> extends Overlay implements Themeable, Draggable, Focusable {
    private static final long DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final int DEFAULT_STACK = 99;

    /**
     * Receives items dropped outside every grid. Return true when the items were taken (thrown away, dropped in the world), so
     * the grid removes them; false cancels the drag.
     */
    @FunctionalInterface
    public interface DropHandler<T> {
        boolean drop(int slot, T item, int count);
    }

    /** One drag in progress: {@code count} of what is in {@code slot} of {@code source}. */
    private record Drag<T>(InventoryGridOverlay<T> source, int slot, int count) {
    }

    private int columns;
    private int rows;
    private final double slotSize;
    private float gap = 4f;
    private float padding = 4f;
    private float cornerRadius = 6f;

    private Object[] items;
    private int[] counts;
    private boolean[] locked;
    private Predicate<?>[] slotFilters;

    private ToIntFunction<T> maxStack = item -> DEFAULT_STACK;
    private Predicate<T> filter;
    private Function<T, Image> imageProvider;
    private Function<T, IconAsset> iconProvider;
    private Function<T, String> labelProvider = String::valueOf;
    private Function<T, Paint> borderProvider;
    private Function<T, String> tooltipProvider;
    private final List<InventoryGridOverlay<T>> links = new ArrayList<>();

    private IntConsumer onSlotChange;
    private IntConsumer onSelect;
    private IntConsumer onUse;
    private DropHandler<T> onDropOutside;

    private int selected = -1;
    private Drag<T> drag;
    private double pointerX; // in window coordinates, while dragging
    private double pointerY;
    private double pressX;
    private double pressY;
    private int pressSlot = -1; // the slot the press began on, item or not
    private boolean shiftPress;
    private boolean moved; // the pointer left the slot it pressed on
    private boolean focused;
    private int lastPressSlot = -1;
    private long lastPressNanos;

    private final Overridable<Paint> slotColor = new Overridable<>(new Color(0.2f, 0.2f, 0.2f, 1f));
    private final Overridable<Paint> slotBorderColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> accentColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> textColor = new Overridable<>(Color.WHITE);

    private final Typeface typeface = Font.getDefault();
    private org.jetbrains.skia.Font countFont;
    private org.jetbrains.skia.Font labelFont;

    /**
     * An empty grid of {@code columns} by {@code rows} slots, each {@code slotSize} points square.
     */
    public InventoryGridOverlay(int columns, int rows, double slotSize) {
        this.slotSize = slotSize;
        allocate(Math.max(1, columns), Math.max(1, rows));
        setFocusTraversable(true);
        // The slots are drawn by hand; Element's box behind them stays clear so the grid sits on any panel.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        setBuiltInClickListener(this::onClick);
        ThemeManager.register(this);
    }

    /* Size */

    private void allocate(int columns, int rows) {
        int count = columns * rows;
        items = items == null ? new Object[count] : Arrays.copyOf(items, count);
        counts = counts == null ? new int[count] : Arrays.copyOf(counts, count);
        locked = locked == null ? new boolean[count] : Arrays.copyOf(locked, count);
        slotFilters = slotFilters == null ? new Predicate<?>[count] : Arrays.copyOf(slotFilters, count);
        this.columns = columns;
        this.rows = rows;
        if (selected >= count) selected = -1;
        resize();
    }

    private void resize() {
        setWidth(padding * 2 + columns * slotSize + (columns - 1) * gap);
        setHeight(padding * 2 + rows * slotSize + (rows - 1) * gap);
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    public int getSlotCount() {
        return items.length;
    }

    /**
     * Changes the shape of the grid. Slots keep their numbers (left to right, then down), so growing a grid by rows keeps every
     * item where it was and shrinking it drops the items in the slots cut off.
     */
    public void setGrid(int columns, int rows) {
        if (columns == this.columns && rows == this.rows) return;
        drag = null; // its slot may be gone
        allocate(Math.max(1, columns), Math.max(1, rows));
    }

    public double getSlotSize() {
        return slotSize;
    }

    /**
     * The space between slots; 4 by default.
     */
    public void setGap(float gap) {
        this.gap = Math.max(0f, gap);
        resize();
    }

    public void setPadding(float padding) {
        this.padding = Math.max(0f, padding);
        resize();
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = cornerRadius;
    }

    /* Contents */

    private void check(int slot) {
        if (slot < 0 || slot >= items.length) throw new IndexOutOfBoundsException("slot " + slot + " of " + items.length);
    }

    @SuppressWarnings("unchecked")
    public T getItem(int slot) {
        check(slot);
        return (T) items[slot];
    }

    /**
     * How many of the slot's item there are; 0 for an empty slot.
     */
    public int getCount(int slot) {
        check(slot);
        return counts[slot];
    }

    public boolean isEmpty(int slot) {
        check(slot);
        return items[slot] == null;
    }

    /**
     * Puts {@code count} of {@code item} in a slot, replacing what was there, regardless of locks, filters and the stack limit
     * (this is the game setting the contents, not the player moving them). A null item or a count of 0 or less empties it.
     * Reports the slot through {@link #onSlotChange}.
     */
    public void setSlot(int slot, T item, int count) {
        check(slot);
        if (item == null || count <= 0) {
            items[slot] = null;
            counts[slot] = 0;
        } else {
            items[slot] = item;
            counts[slot] = count;
        }
        changed(slot);
    }

    public void clearSlot(int slot) {
        setSlot(slot, null, 0);
    }

    /**
     * Empties every slot, reporting each one that had something.
     */
    public void clear() {
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) clearSlot(i);
        }
    }

    /**
     * The number of {@code item} in all the slots together.
     */
    public int countOf(T item) {
        int total = 0;
        for (int i = 0; i < items.length; i++) {
            if (Objects.equals(items[i], item)) total += counts[i];
        }
        return total;
    }

    /**
     * The first slot holding {@code item}, or -1.
     */
    public int indexOf(T item) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].equals(item)) return i;
        }
        return -1;
    }

    /**
     * The first empty slot that is not locked, or -1.
     */
    public int firstEmpty() {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null && !locked[i]) return i;
        }
        return -1;
    }

    /**
     * Adds {@code count} of {@code item} the way a game does: topping up the stacks of the same item first, then using empty
     * slots, skipping locked slots and ones whose filter refuses it. Returns how many did not fit (0 when all did).
     */
    public int add(T item, int count) {
        Objects.requireNonNull(item, "item");
        int left = Math.max(0, count);
        int limit = Math.max(1, maxStack.applyAsInt(item));
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < items.length && left > 0; i++) {
                if (locked[i] || !accepts(i, item)) continue;
                boolean same = item.equals(items[i]);
                if (pass == 0 ? !same || counts[i] >= limit : items[i] != null) continue;
                int room = limit - counts[i];
                int put = Math.min(room, left);
                items[i] = item;
                counts[i] += put;
                left -= put;
                changed(i);
            }
        }
        return left;
    }

    /**
     * Takes up to {@code count} of {@code item} out, from the last slots first, and returns how many were taken.
     */
    public int remove(T item, int count) {
        int left = Math.max(0, count);
        for (int i = items.length - 1; i >= 0 && left > 0; i--) {
            if (!item.equals(items[i])) continue;
            int take = Math.min(counts[i], left);
            counts[i] -= take;
            if (counts[i] == 0) items[i] = null;
            left -= take;
            changed(i);
        }
        return Math.max(0, count) - left;
    }

    /* Rules */

    public boolean isLocked(int slot) {
        check(slot);
        return locked[slot];
    }

    /**
     * A locked slot cannot be picked up from, dropped on, used or selected by the player, and {@link #add} skips it. The game
     * can still {@link #setSlot} it.
     */
    public void setLocked(int slot, boolean locked) {
        check(slot);
        this.locked[slot] = locked;
        if (locked && drag != null && drag.source == this && drag.slot == slot) drag = null;
    }

    /**
     * Restricts what one slot takes, such as {@code item -> item.isHelmet()}; null takes anything the grid's own {@link
     * #setFilter filter} allows.
     */
    public void setSlotFilter(int slot, Predicate<T> accepts) {
        check(slot);
        slotFilters[slot] = accepts;
    }

    /**
     * Restricts what the whole grid takes; null, the default, takes anything.
     */
    public void setFilter(Predicate<T> filter) {
        this.filter = filter;
    }

    @SuppressWarnings("unchecked")
    private boolean accepts(int slot, T item) {
        if (filter != null && !filter.test(item)) return false;
        Predicate<T> slotFilter = (Predicate<T>) slotFilters[slot];
        return slotFilter == null || slotFilter.test(item);
    }

    /**
     * The most of one kind of item a slot holds, by item; 99 for every item by default. 1 makes an item not stack.
     */
    public void setMaxStack(ToIntFunction<T> maxStack) {
        this.maxStack = maxStack == null ? item -> DEFAULT_STACK : maxStack;
    }

    private int limitOf(T item) {
        return Math.max(1, maxStack.applyAsInt(item));
    }

    /* Moving */

    /**
     * Moves {@code count} of the item in {@code slot} of this grid to {@code slot} of {@code target} (this grid or a linked
     * one) by the rules the player's drop follows. Returns whether anything moved. Reports the slots changed.
     *
     * @param count how many to move; at least the whole stack moves the stack, and the swap case needs the whole stack
     */
    public boolean move(int slot, InventoryGridOverlay<T> target, int targetSlot, int count) {
        check(slot);
        target.check(targetSlot);
        if (target == this && targetSlot == slot) return false;
        T item = getItem(slot);
        int have = counts[slot];
        if (item == null || have == 0 || locked[slot]) return false;
        if (target.locked[targetSlot] || !target.accepts(targetSlot, item)) return false;
        int moving = Math.max(1, Math.min(count, have));
        T there = target.getItem(targetSlot);
        if (there == null) {
            int put = Math.min(moving, target.limitOf(item));
            target.items[targetSlot] = item;
            target.counts[targetSlot] = put;
            take(slot, put);
            target.changed(targetSlot);
            return true;
        }
        if (there.equals(item)) {
            int room = target.limitOf(item) - target.counts[targetSlot];
            int put = Math.min(room, moving);
            if (put <= 0) return false;
            target.counts[targetSlot] += put;
            take(slot, put);
            target.changed(targetSlot);
            return true;
        }
        // A different item: the two swap, but only whole stacks, and only if each side takes the other's.
        if (moving < have || !accepts(slot, there)) return false;
        int theirs = target.counts[targetSlot];
        if (have > target.limitOf(item) || theirs > limitOf(there)) return false;
        target.items[targetSlot] = item;
        target.counts[targetSlot] = have;
        items[slot] = there;
        counts[slot] = theirs;
        changed(slot);
        target.changed(targetSlot);
        return true;
    }

    private void take(int slot, int count) {
        counts[slot] -= count;
        if (counts[slot] <= 0) {
            counts[slot] = 0;
            items[slot] = null;
        }
        changed(slot);
    }

    /**
     * Sends the stack in {@code slot} to the first linked grid that has room for any of it, the way Shift-click does. Returns
     * whether anything moved.
     */
    public boolean quickMove(int slot) {
        check(slot);
        T item = getItem(slot);
        if (item == null || locked[slot]) return false;
        boolean any = false;
        for (InventoryGridOverlay<T> other : links) {
            if (counts[slot] == 0) break;
            int before = counts[slot];
            int left = other.add(item, counts[slot]);
            if (left < before) {
                any = true;
                take(slot, before - left);
            }
        }
        return any;
    }

    /**
     * Joins this grid with {@code other} so items can be dragged between them and quick-moved. Links work both ways and
     * between any number of grids.
     */
    public void link(InventoryGridOverlay<T> other) {
        if (other == this || links.contains(other)) return;
        links.add(other);
        other.links.add(this);
    }

    public void unlink(InventoryGridOverlay<T> other) {
        links.remove(other);
        other.links.remove(this);
    }

    /* Events */

    /**
     * Called with the number of each slot whose contents changed, however it changed: by the player, {@link #add}, {@link
     * #setSlot} or the other grid of a drag. Runs on the thread that made the change.
     */
    public void onSlotChange(IntConsumer onSlotChange) {
        this.onSlotChange = onSlotChange;
    }

    private void changed(int slot) {
        if (onSlotChange != null) onSlotChange.accept(slot);
    }

    /**
     * Called with the slot the player selected by clicking it or with the keys.
     */
    public void onSelect(IntConsumer onSelect) {
        this.onSelect = onSelect;
    }

    /**
     * Called with the slot the player used: a right-click, a double-click, or Enter. Not called for an empty slot.
     */
    public void onUse(IntConsumer onUse) {
        this.onUse = onUse;
    }

    /**
     * Called when the player drops items outside every grid. See {@link DropHandler}.
     */
    public void onDropOutside(DropHandler<T> onDropOutside) {
        this.onDropOutside = onDropOutside;
    }

    public int getSelectedSlot() {
        return selected;
    }

    /**
     * Selects a slot (-1 for none) without calling {@link #onSelect}.
     */
    public void setSelectedSlot(int slot) {
        selected = slot < 0 || slot >= items.length ? -1 : slot;
    }

    private void select(int slot) {
        if (slot == selected) return;
        selected = slot;
        if (onSelect != null && slot >= 0) onSelect.accept(slot);
    }

    private void use(int slot) {
        if (slot >= 0 && items[slot] != null && !locked[slot] && onUse != null) onUse.accept(slot);
    }

    /* Look */

    public void setImageProvider(Function<T, Image> imageProvider) {
        this.imageProvider = imageProvider;
    }

    /**
     * Draws a vector icon, tinted with the text color, for items with no image. Used when the {@linkplain #setImageProvider
     * image provider} gives null or is not set.
     */
    public void setIconProvider(Function<T, IconAsset> iconProvider) {
        this.iconProvider = iconProvider;
    }

    /**
     * The text drawn for an item that has neither image nor icon: {@code String.valueOf} by default, cut to fit the slot.
     */
    public void setLabelProvider(Function<T, String> labelProvider) {
        this.labelProvider = labelProvider == null ? String::valueOf : labelProvider;
    }

    /**
     * The color of a slot's frame by the item in it, such as a rarity color; null for the plain slot border.
     */
    public void setBorderProvider(Function<T, Paint> borderProvider) {
        this.borderProvider = borderProvider;
    }

    /**
     * The tooltip text for the item under the pointer; null or empty text shows none.
     */
    public void setTooltipProvider(Function<T, String> tooltipProvider) {
        this.tooltipProvider = tooltipProvider;
    }

    public Paint getSlotColor() {
        return slotColor.get();
    }

    public void setSlotColor(Paint slotColor) {
        this.slotColor.set(slotColor);
    }

    public Paint getSlotBorderColor() {
        return slotBorderColor.get();
    }

    public void setSlotBorderColor(Paint slotBorderColor) {
        this.slotBorderColor.set(slotBorderColor);
    }

    public Paint getAccentColor() {
        return accentColor.get();
    }

    public void setAccentColor(Paint accentColor) {
        this.accentColor.set(accentColor);
    }

    public Paint getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    /* Geometry */

    /**
     * The slot under a point in window coordinates, or -1 when it is on a gap, the padding or outside the grid.
     */
    public int slotAt(double windowX, double windowY) {
        double x = windowX - getAbsoluteX() - padding, y = windowY - getAbsoluteY() - padding;
        if (x < 0 || y < 0) return -1;
        double cell = slotSize + gap;
        int col = (int) (x / cell), row = (int) (y / cell);
        if (col >= columns || row >= rows) return -1;
        if (x - col * cell > slotSize || y - row * cell > slotSize) return -1; // in the gap
        return row * columns + col;
    }

    /**
     * The top-left corner of a slot in the element's drawing coordinates, as {x, y}.
     */
    public double[] getSlotOrigin(int slot) {
        check(slot);
        int col = slot % columns, row = slot / columns;
        return new double[]{getX() + padding + col * (slotSize + gap), getY() + padding + row * (slotSize + gap)};
    }

    /* Interaction */

    /**
     * Whether a drag is in progress, started on this grid.
     */
    public boolean isDragging() {
        return drag != null && drag.source == this;
    }

    /**
     * The slot where the current drag started, or -1.
     */
    public int getDragSlot() {
        return isDragging() ? drag.slot : -1;
    }

    /**
     * How many items the current drag carries; 0 when none.
     */
    public int getDragCount() {
        return isDragging() ? drag.count : 0;
    }

    /**
     * The modifier bits held now, from the window's input. Separate so a test can say so without a window.
     */
    protected int heldModifiers() {
        me.piitex.engine.Window window = getWindow();
        return window == null ? 0 : window.getInput().getModifiers();
    }

    @Override
    public void onDragStart(double x, double y) {
        pressSlot = slotAt(x, y);
        pointerX = pressX = x;
        pointerY = pressY = y;
        moved = false;
        drag = null;
        shiftPress = (heldModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (pressSlot < 0 || items[pressSlot] == null || locked[pressSlot]) return;
        // Shift-click is a quick move, done on release, so it carries nothing.
        if (shiftPress) return;
        int have = counts[pressSlot];
        boolean half = (heldModifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 && have > 1;
        drag = new Drag<>(this, pressSlot, half ? (have + 1) / 2 : have);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        pointerX = x;
        pointerY = y;
        if (!moved && Math.hypot(x - pressX, y - pressY) > 4 && slotAt(x, y) != pressSlot) moved = true;
    }

    @Override
    public void onDragEnd(double x, double y) {
        Drag<T> finished = drag;
        drag = null;
        int releasedOn = slotAt(x, y);
        boolean clicked = !moved && pressSlot >= 0 && releasedOn == pressSlot && !locked[pressSlot];

        if (clicked && shiftPress && items[pressSlot] != null && quickMove(pressSlot)) return;
        if (clicked) {
            clickedSlot(pressSlot);
            return;
        }
        if (finished != null) drop(finished, x, y);
    }

    private void clickedSlot(int slot) {
        select(slot);
        long now = System.nanoTime();
        if (slot == lastPressSlot && now - lastPressNanos < DOUBLE_CLICK_NANOS) {
            lastPressSlot = -1;
            use(slot);
        } else {
            lastPressSlot = slot;
            lastPressNanos = now;
        }
    }

    private void drop(Drag<T> finished, double x, double y) {
        T item = getItem(finished.slot);
        if (item == null) return;
        List<InventoryGridOverlay<T>> candidates = new ArrayList<>();
        candidates.add(this);
        candidates.addAll(links);
        for (InventoryGridOverlay<T> grid : candidates) {
            if (!grid.isOver(x, y)) continue;
            int target = grid.slotAt(x, y);
            if (target >= 0) move(finished.slot, grid, target, finished.count);
            return; // over a grid but not a slot, or the move was refused: cancelled either way
        }
        if (onDropOutside != null && onDropOutside.drop(finished.slot, item, finished.count)) {
            take(finished.slot, finished.count);
        }
    }

    private boolean isOver(double windowX, double windowY) {
        double ax = getAbsoluteX(), ay = getAbsoluteY();
        return windowX >= ax && windowX < ax + getWidth() && windowY >= ay && windowY < ay + getHeight();
    }

    private void onClick(MouseClickEvent event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.getAction() != GLFW.GLFW_PRESS) return;
        int slot = slotAt(event.getMouseX(), event.getMouseY());
        if (slot < 0 || locked[slot]) return;
        select(slot);
        use(slot);
    }

    /* Keys */

    public boolean isFocused() {
        return focused;
    }

    @Override
    public void onFocusGained() {
        focused = true;
        if (selected < 0) selected = 0;
    }

    @Override
    public void onFocusLost() {
        focused = false;
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return;
        int from = selected < 0 ? 0 : selected;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> select(Math.max(0, from - 1));
            case GLFW.GLFW_KEY_RIGHT -> select(Math.min(items.length - 1, from + 1));
            case GLFW.GLFW_KEY_UP -> select(from - columns >= 0 ? from - columns : from);
            case GLFW.GLFW_KEY_DOWN -> select(from + columns < items.length ? from + columns : from);
            case GLFW.GLFW_KEY_HOME -> select(0);
            case GLFW.GLFW_KEY_END -> select(items.length - 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (action == GLFW.GLFW_PRESS) use(selected);
            }
            default -> {
            }
        }
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        slotColor.applyTheme(theme.getFieldBackground());
        slotBorderColor.applyTheme(theme.getFieldBorder());
        accentColor.applyTheme(theme.getAccent());
        textColor.applyTheme(theme.getText());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        slotColor.clearExplicit();
        slotBorderColor.clearExplicit();
        accentColor.clearExplicit();
        textColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(slotColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(slotBorderColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(accentColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        updateTooltip();
    }

    private void updateTooltip() {
        if (tooltipProvider == null) return;
        int hover = hoveredSlot();
        String text = hover >= 0 && items[hover] != null && !isDragging() ? tooltipProvider.apply(getItem(hover)) : null;
        setTooltip(text == null || text.isEmpty() ? null : text);
    }

    /**
     * The slot under the pointer, or -1.
     */
    public int hoveredSlot() {
        return MouseTracker.isInWindow() ? slotAt(MouseTracker.getX(), MouseTracker.getY()) : -1;
    }

    /* Rendering */

    private Drag<T> activeDrag() {
        if (drag != null) return drag;
        for (InventoryGridOverlay<T> other : links) {
            if (other.drag != null) return other.drag;
        }
        return null;
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        Drag<T> active = activeDrag();
        double[] pointer = active == null ? null : pointerOf(active);
        int dropSlot = active != null && pointer != null && isOver(pointer[0], pointer[1]) ? slotAt(pointer[0], pointer[1]) : -1;
        int hover = hoveredSlot();
        float size = (float) slotSize;

        for (int i = 0; i < items.length; i++) {
            double[] o = getSlotOrigin(i);
            float x = (float) o[0], y = (float) o[1];
            renderer.drawQuad(x, y, size, size, slotColor.get(), cornerRadius);
            @SuppressWarnings("unchecked") T item = (T) items[i];
            Paint border = item != null && borderProvider != null && borderProvider.apply(item) != null
                    ? borderProvider.apply(item) : slotBorderColor.get();
            renderer.drawBorder(x, y, size, size, border, 1.5f, cornerRadius);
            if (i == hover && !locked[i]) {
                renderer.pushAlpha(0.12f);
                renderer.drawQuad(x, y, size, size, textColor.get(), cornerRadius);
                renderer.popAlpha();
            }
            if (item != null) {
                boolean source = active != null && active.source == this && active.slot == i;
                int shownCount = source ? counts[i] - active.count : counts[i];
                boolean ghosted = source && shownCount <= 0;
                if (ghosted) renderer.pushAlpha(0.3f);
                drawItem(renderer, item, x, y, size, 1f);
                if (ghosted) renderer.popAlpha();
                if (shownCount > 1) drawCount(renderer, shownCount, x, y, size);
            }
            if (locked[i]) drawLock(renderer, x, y, size);
            if (i == selected) {
                renderer.drawBorder(x - 1f, y - 1f, size + 2f, size + 2f, accentColor.get(), focused ? 3f : 2f, cornerRadius + 1f);
            }
            if (i == dropSlot) {
                boolean ok = canPlace(active, i);
                renderer.pushAlpha(0.28f);
                renderer.drawQuad(x, y, size, size, ok ? new Color(0.3f, 0.85f, 0.4f, 1f) : new Color(0.9f, 0.3f, 0.3f, 1f), cornerRadius);
                renderer.popAlpha();
            }
        }

        if (active != null && active.source == this && pointer != null) {
            // The held stack follows the pointer, drawn last so it is over every slot.
            T held = getItem(active.slot);
            if (held != null) {
                float gx = (float) (pointer[0] - getAbsoluteX() + getX() - size / 2f);
                float gy = (float) (pointer[1] - getAbsoluteY() + getY() - size / 2f);
                renderer.pushAlpha(0.85f);
                drawItem(renderer, held, gx, gy, size, 0.9f);
                renderer.popAlpha();
                if (active.count > 1) drawCount(renderer, active.count, gx, gy, size);
            }
        }
    }

    private double[] pointerOf(Drag<T> d) {
        return new double[]{d.source.pointerX, d.source.pointerY};
    }

    private boolean canPlace(Drag<T> d, int targetSlot) {
        InventoryGridOverlay<T> src = d.source;
        T item = src.getItem(d.slot);
        if (item == null || (src == this && targetSlot == d.slot)) return false;
        if (locked[targetSlot] || !accepts(targetSlot, item)) return false;
        T there = getItem(targetSlot);
        if (there == null) return true;
        if (there.equals(item)) return counts[targetSlot] < limitOf(item);
        return d.count >= src.counts[d.slot] && src.accepts(d.slot, there)
                && src.counts[d.slot] <= limitOf(item) && counts[targetSlot] <= src.limitOf(there);
    }

    private void drawItem(Renderer renderer, T item, float x, float y, float size, float scale) {
        float inner = size * 0.7f * scale, left = x + (size - inner) / 2f, top = y + (size - inner) / 2f;
        Image image = imageProvider == null ? null : imageProvider.apply(item);
        if (image != null) {
            renderer.drawImage(image, left, top, inner, inner, 0f, 1f);
            return;
        }
        IconAsset icon = iconProvider == null ? null : iconProvider.apply(item);
        if (icon != null) {
            int px = Math.round(inner);
            renderer.drawImage(IconCache.loadResource(icon.resourcePath(), px, px), left, top, inner, inner, 0f, 1f,
                    AnimatedPaint.resolve(textColor.get()) instanceof Color c ? c : Color.WHITE);
            return;
        }
        org.jetbrains.skia.Font f = getLabelFont(size);
        String text = TextFit.ellipsize(String.valueOf(labelProvider.apply(item)), f, size - 8f);
        FontMetricsCompat m = FontMetricsCompat.of(f);
        float w = TextShaping.width(f, text), h = -m.getAscent() + m.getDescent();
        renderer.drawText(text, x + (size - w) / 2f, y + (size - h) / 2f, f, textColor.get());
    }

    private void drawCount(Renderer renderer, int count, float x, float y, float size) {
        org.jetbrains.skia.Font f = getCountFont(size);
        String text = String.valueOf(count);
        FontMetricsCompat m = FontMetricsCompat.of(f);
        float w = TextShaping.width(f, text), h = -m.getAscent() + m.getDescent();
        float tx = x + size - w - 4f, ty = y + size - h - 2f;
        renderer.drawText(text, tx + 1f, ty + 1f, f, Color.BLACK);
        renderer.drawText(text, tx, ty, f, textColor.get());
    }

    private void drawLock(Renderer renderer, float x, float y, float size) {
        renderer.pushAlpha(0.55f);
        renderer.drawQuad(x, y, size, size, Color.BLACK, cornerRadius);
        renderer.popAlpha();
        float pad = size * 0.3f;
        renderer.drawLine(x + pad, y + pad, x + size - pad, y + size - pad, slotBorderColor.get(), 2f);
        renderer.drawLine(x + size - pad, y + pad, x + pad, y + size - pad, slotBorderColor.get(), 2f);
    }

    private org.jetbrains.skia.Font getCountFont(float size) {
        float px = Math.max(10f, Math.min(16f, size * 0.28f));
        if (countFont == null || countFont.getSize() != px) {
            if (countFont != null) countFont.close();
            countFont = new org.jetbrains.skia.Font(typeface, px);
        }
        return countFont;
    }

    private org.jetbrains.skia.Font getLabelFont(float size) {
        float px = Math.max(10f, Math.min(14f, size * 0.24f));
        if (labelFont == null || labelFont.getSize() != px) {
            if (labelFont != null) labelFont.close();
            labelFont = new org.jetbrains.skia.Font(typeface, px);
        }
        return labelFont;
    }

    /**
     * Frees the fonts used for counts and labels, if any were drawn.
     */
    public void dispose() {
        if (countFont != null) {
            countFont.close();
            countFont = null;
        }
        if (labelFont != null) {
            labelFont.close();
            labelFont = null;
        }
    }
}
