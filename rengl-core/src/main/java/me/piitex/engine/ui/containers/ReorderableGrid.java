package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.ThemeManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * A scrolling grid of equally sized items that the user can rearrange by dragging. Pressing an item and moving
 * the mouse past a small threshold lifts it; it then follows the cursor while the other items slide aside to
 * open a slot where it would land, and letting go drops it there. Near the top or bottom edge, holding a lifted
 * item scrolls the grid.
 * <p>
 * A press that never moves is a click, reported through {@link #onItemClick}. It fires on release, not on press
 * as {@link Element#onMouseClick} does, since a press might still turn into a drag. An item therefore should
 * not register its own click listener for its main action. Anything <i>inside</i> an item that does have a click
 * listener (a button, say) keeps its press: those presses are left alone and never start a drag.
 * <p>
 * The grid lays items out left to right, wrapping to as many columns as fit the grid's width, in the order of
 * {@link #setItems}. It owns their position: it moves them to their slots itself, so an item's own x/y is
 * ignored. It reports the new order once, when a drag ends in a different place ({@link #onReorder}); persisting
 * it is up to the caller.
 * <p>
 * Optionally, some items can be drop targets ({@link #setDropTargets}), like a folder that other items can be
 * dropped into. Holding a lifted item over the middle of a target for a moment arms it (see {@link
 * #onTargetHighlight}) and releasing then reports {@link #onDropOnto} instead of a reorder. Over the target's
 * edges the item reorders as usual: at once once carried past the target's centre, or after a short pause if
 * it lingers before reaching it, so that approaching a target does not shove it aside.
 * Releasing a lifted item outside the grid reports {@link #onDropOutside}.
 * <p>
 * By default the items are a packed list: dropping one shifts the others along, so there are never gaps. With
 * {@link #setFreePlacement free placement} on, the grid is instead a set of fixed slots, some of them empty, and
 * an item can be dropped into any slot: an empty one moves it there, and an occupied one swaps the two.
 * <p>
 * Without a mouse, Tab reaches the grid. The arrow keys, Home and End move between items, Enter or Space chooses the
 * one it is on ({@link #onItemClick}), and M picks it up: the arrows then move it (in a packed list, through the
 * order; with free placement, over the slots), M or Enter puts it down and reports the new order as a drag would, and
 * Escape puts it back. A screen reader hears the item's name and place ("Sword, 3 of 8"); an item is named by its
 * {@link Element#setAccessibleName accessible name} or else the first text in it. Dropping onto a target (see
 * {@link #setDropTargets}) is a mouse drag only.
 */
public class ReorderableGrid extends ScrollContainer implements Focusable {
    private static final double DRAG_THRESHOLD = 6;
    private static final double AUTO_SCROLL_EDGE = 56;
    private static final double AUTO_SCROLL_SPEED = 900;
    private static final double SLIDE_RATE = 22;
    private static final double TARGET_INSET = 0.1;
    private static final float ARM_DELAY = 0.25f;
    private static final float EDGE_REORDER_DELAY = 0.6f;

    private final double itemWidth, itemHeight, gap;
    private final List<Container> items = new ArrayList<>();

    private Consumer<List<Container>> reorderListener;
    private Consumer<Container> clickListener;
    private BiConsumer<Container, Boolean> liftListener;
    private BiPredicate<Container, Container> dropTargets;
    private BiConsumer<Container, Container> dropOntoListener;
    private BiConsumer<Container, Boolean> highlightListener;
    private Consumer<Container> dropOutsideListener;
    private Consumer<Boolean> outsideListener;
    private Follower followListener;
    private Element dropBounds;
    private boolean outside;

    private Container hoverTarget;
    private boolean hoverInner;
    private float hoverTime;
    private Container armed;

    private boolean freePlacement;
    private int minSlots;
    private int previewSlot = -1;

    private Container pressed;
    private boolean lifted;
    private int startIndex;
    private double pressX, pressY, grabX, grabY, lastX, lastY;

    // Keyboard: the slot it is on, and an item picked up with M and not yet put down (-1 when none).
    private boolean focused;
    private int cursor;
    private int carryStart = -1;

    public ReorderableGrid(double width, double height, double itemWidth, double itemHeight, double gap) {
        super(width, height);
        this.itemWidth = itemWidth;
        this.itemHeight = itemHeight;
        this.gap = gap;
        setFocusTraversable(true);
    }

    /**
     * Replaces the grid's items and lays them out immediately, in the order given. With {@link #setFreePlacement
     * free placement} on, each entry's position in the list is its slot, and a null entry is an empty slot.
     * Without it, nulls are skipped.
     */
    public void setItems(List<? extends Container> newItems) {
        for (Container item : items) if (item != null) removeElement(item);
        items.clear();
        pressed = null;
        lifted = false;
        armed = null;
        hoverTarget = null;
        outside = false;
        previewSlot = -1;
        carryStart = -1;

        for (Container item : newItems) {
            if (item == null && !freePlacement) continue;
            items.add(item);
            if (item != null) addElement(item);
        }
        ensureSlots();
        cursor = Math.max(0, Math.min(cursor, items.size() - 1));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == null) continue;
            items.get(i).setX(slotX(i));
            items.get(i).setY(slotY(i));
        }
    }

    /**
     * Whether items can be dropped into any slot, empty ones included. Off by default.
     */
    public boolean isFreePlacement() {
        return freePlacement;
    }

    /**
     * Turns free placement on or off. On, the grid keeps a fixed set of slots: dropping a lifted item on an empty
     * slot puts it there and leaves a gap behind, and dropping it on an occupied slot swaps the two items. Off,
     * the default, items are a packed list and the others shift along to make room. The grid has at least enough
     * slots to fill its visible area (more through {@link #setSlotCount}), always whole rows. Turning it on keeps
     * the items in their current slots; turning it off packs them together, in slot order. Set it before
     * {@link #setItems} if the list should contain empty slots.
     */
    public void setFreePlacement(boolean freePlacement) {
        if (this.freePlacement == freePlacement || lifted) return;
        this.freePlacement = freePlacement;
        if (freePlacement) ensureSlots();
        else items.removeIf(java.util.Objects::isNull);
    }

    /**
     * The least number of slots a grid with free placement has, rounded up to whole rows. The default of 0 gives
     * just enough to fill the visible area. Slots beyond that are added when {@link #setItems} gets a longer
     * list. Has no effect without free placement.
     */
    public void setSlotCount(int slots) {
        this.minSlots = Math.max(0, slots);
        ensureSlots();
    }

    /**
     * The slots of a grid with free placement, in order, with null for an empty one. Without free placement, the
     * same as {@link #getItems()}. Unlike that, this list is not immutable-by-copy: it is a fresh list each call
     * which may hold nulls.
     */
    public List<Container> getSlots() {
        return new ArrayList<>(items);
    }

    /**
     * The index of the slot {@code item} is in, or -1 if it is not in this grid.
     */
    public int slotOf(Container item) {
        return item == null ? -1 : items.indexOf(item);
    }

    /**
     * Pads the slot list with empty slots up to the visible area, {@link #setSlotCount} and whole rows.
     */
    private void ensureSlots() {
        if (!freePlacement) return;
        int columns = columns();
        int visibleRows = (int) Math.ceil((getHeight() + gap) / (itemHeight + gap));
        int wanted = Math.max(Math.max(minSlots, items.size()), columns * Math.max(1, visibleRows));
        wanted = (wanted + columns - 1) / columns * columns;
        while (items.size() < wanted) items.add(null);
    }

    @Override
    public double getMaxScrollY() {
        double max = super.getMaxScrollY();
        if (!freePlacement || items.isEmpty()) return max;
        int rows = (items.size() + columns() - 1) / columns();
        return Math.max(max, rows * (itemHeight + gap) - gap - getHeight());
    }

    /**
     * The items in their current order, including a drag in progress. Empty slots (see {@link #setFreePlacement})
     * are left out; use {@link #getSlots()} to see them.
     */
    public List<Container> getItems() {
        return items.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Called once when a drag ends with an item in a different position than it started, with the new order. With
     * {@link #setFreePlacement free placement} the list is every slot in order, with null for an empty one.
     */
    public void onReorder(Consumer<List<Container>> listener) {
        this.reorderListener = listener;
    }

    /**
     * Called when an item is pressed and released without being dragged.
     */
    public void onItemClick(Consumer<Container> listener) {
        this.clickListener = listener;
    }

    /**
     * Called with true when an item is picked up and with false when it is dropped, to give it a lifted look.
     */
    public void onLift(BiConsumer<Container, Boolean> listener) {
        this.liftListener = listener;
    }

    /**
     * Makes some items drop targets: {@code accepts} is asked with the lifted item and a candidate target and
     * says whether the lifted one may be dropped onto it. Without this, no item is a target.
     */
    public void setDropTargets(BiPredicate<Container, Container> accepts) {
        this.dropTargets = accepts;
    }

    /**
     * Called with (lifted item, target) when a lifted item is released over an armed target. The item stays in
     * its slot; the caller decides what dropping onto the target means and normally rebuilds the grid.
     */
    public void onDropOnto(BiConsumer<Container, Container> listener) {
        this.dropOntoListener = listener;
    }

    /**
     * Called with (target, true) when a lifted item has been held over a target long enough that releasing would
     * drop into it, and (target, false) when that stops, so the target can show it.
     */
    public void onTargetHighlight(BiConsumer<Container, Boolean> listener) {
        this.highlightListener = listener;
    }

    /**
     * Called when a lifted item is released with the pointer outside the grid's box. No reorder is reported then.
     */
    public void onDropOutside(Consumer<Container> listener) {
        this.dropOutsideListener = listener;
    }

    /**
     * Called with true when a lifted item's pointer leaves the grid's box and with false when it comes back or the
     * item is dropped, so a drop zone outside the grid can show that releasing there will count.
     */
    public void onOutsideChanged(Consumer<Boolean> listener) {
        this.outsideListener = listener;
    }

    /**
     * Called every time the lifted item follows the pointer, with where its top-left corner would be in the window
     * if it were not held inside the grid. A card dragged out of the grid is clipped and pinned at the edge, so
     * something outside can draw a stand-in that carries on with the pointer.
     */
    public void onFollow(Follower listener) {
        this.followListener = listener;
    }

    /**
     * Judges "outside" (see {@link #onOutsideChanged} and {@link #onDropOutside}) against {@code bounds} instead of
     * the grid's own box. A popout whose grid is only part of a panel passes the panel, so leaving the panel is
     * what counts. Null goes back to the grid.
     */
    public void setDropBounds(Element bounds) {
        this.dropBounds = bounds;
    }

    /**
     * Which slot the window point falls in (clamped to the grid), or -1 with no items. Accounts for scrolling.
     */
    public int slotIndexAt(double windowX, double windowY) {
        if (items.isEmpty()) return -1;
        return slotAt(windowX - getContentOriginX(), windowY - getContentOriginY());
    }

    private boolean withinBounds(double x, double y) {
        return (dropBounds != null ? dropBounds : this).contains(x, y);
    }

    /**
     * Receives the position of the lifted item's top-left corner in the window.
     */
    @FunctionalInterface
    public interface Follower {
        void moved(Container item, double x, double y);
    }

    /**
     * Whether an item is currently being dragged.
     */
    public boolean isReordering() {
        return lifted;
    }

    // --- Layout ------------------------------------------------------------------------------------

    private int columns() {
        return Math.max(1, (int) ((getWidth() + gap) / (itemWidth + gap)));
    }

    private double slotX(int index) {
        return (index % columns()) * (itemWidth + gap);
    }

    private double slotY(int index) {
        return (index / columns()) * (itemHeight + gap);
    }

    private int slotAt(double centerX, double centerY) {
        int columns = columns();
        int col = Math.max(0, Math.min(columns - 1, (int) Math.floor(centerX / (itemWidth + gap))));
        int row = Math.max(0, (int) Math.floor(centerY / (itemHeight + gap)));
        return Math.min(items.size() - 1, row * columns + col);
    }

    // --- Pressing ----------------------------------------------------------------------------------

    private Container itemAt(double windowX, double windowY) {
        double x = windowX - getContentOriginX(), y = windowY - getContentOriginY();
        for (Container item : items) {
            if (item == null) continue;
            if (x >= item.getX() && x < item.getX() + itemWidth && y >= item.getY() && y < item.getY() + itemHeight) {
                return item;
            }
        }
        return null;
    }

    /**
     * True if the topmost element under the point, inside {@code item}, or any element between it and the item
     * has a click listener of its own: that press belongs to the control, not to a drag.
     */
    private boolean pressesControl(Container item, double x, double y) {
        Container current = item;
        while (true) {
            Element next = null;
            for (Element child : current.getElements().descendingMap().values()) {
                if (child.isEnabled() && child.contains(x, y)) {
                    next = child;
                    break;
                }
            }
            if (next == null) return false;
            if (next.getMouseClickListenerConsumer() != null) return true;
            if (!(next instanceof Container container)) return false;
            current = container;
        }
    }

    @Override
    public boolean canDrag(double x, double y) {
        if (thumbAt(x, y)) return true;
        Container item = itemAt(x, y);
        return item != null && !pressesControl(item, x, y);
    }

    @Override
    public void onDragStart(double x, double y) {
        if (thumbAt(x, y)) {
            super.onDragStart(x, y);
            return;
        }
        pressed = itemAt(x, y);
        if (pressed == null) return;
        lifted = false;
        startIndex = items.indexOf(pressed);
        pressX = lastX = x;
        pressY = lastY = y;
        grabX = x - getContentOriginX() - pressed.getX();
        grabY = y - getContentOriginY() - pressed.getY();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (pressed == null) {
            super.onDragUpdate(x, y);
            return;
        }
        lastX = x;
        lastY = y;
        if (!lifted) {
            if (Math.hypot(x - pressX, y - pressY) < DRAG_THRESHOLD) return;
            lifted = true;
            pressed.bringToFront();
            if (liftListener != null) liftListener.accept(pressed, true);
            speak("Picked up " + labelOf(pressed));
        }
        followPointer(0f);
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (pressed == null) {
            super.onDragEnd(x, y);
            return;
        }
        Container item = pressed;
        boolean wasLifted = lifted;
        pressed = null;
        lifted = false;

        if (!wasLifted) {
            if (clickListener != null) clickListener.accept(item);
            return;
        }
        Container target = armed;
        disarm();
        setOutside(false);
        if (freePlacement && (target != null || (dropOutsideListener != null && !withinBounds(x, y)))) previewSlot = -1;
        if (liftListener != null) liftListener.accept(item, false);

        if (target != null) {
            if (dropOntoListener != null) dropOntoListener.accept(item, target);
            speak("Dropped " + labelOf(item) + " onto " + labelOf(target));
        } else if (dropOutsideListener != null && !withinBounds(x, y)) {
            dropOutsideListener.accept(item);
            speak("Dropped " + labelOf(item) + " outside");
        } else if (freePlacement) {
            int slot = previewSlot;
            previewSlot = -1;
            if (slot >= 0 && slot != startIndex && startIndex >= 0 && startIndex < items.size()) {
                // An empty slot just receives the item; an occupied one trades places with it.
                items.set(startIndex, items.get(slot));
                items.set(slot, item);
                if (reorderListener != null) reorderListener.accept(getSlots());
                speak("Dropped " + labelOf(item) + " in slot " + (slot + 1));
            } else {
                speak("Put back " + labelOf(item));
            }
        } else {
            if (reorderListener != null && items.indexOf(item) != startIndex) reorderListener.accept(List.copyOf(items));
            speak(items.indexOf(item) == startIndex ? "Put back " + labelOf(item)
                    : "Dropped " + labelOf(item) + ", " + (items.indexOf(item) + 1) + " of " + items.size());
        }
    }

    // --- Keyboard and accessibility ----------------------------------------------------------------

    /**
     * What an item is called to a screen reader: its accessible name, else the first text inside it, else "Item".
     */
    private String labelOf(Container item) {
        if (item == null) return "empty";
        String name = item.getAccessibleName();
        if (name != null && !name.isBlank()) return name;
        String text = firstText(item);
        return text == null ? "Item" : text;
    }

    private static String firstText(Container container) {
        for (Element child : container.getElements().values()) {
            if (child instanceof TextOverlay text && text.getText() != null && !text.getText().isBlank()) return text.getText().strip();
            if (child instanceof Container inner && !(child instanceof ReorderableGrid)) {
                String found = firstText(inner);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void speak(String text) {
        me.piitex.engine.Window window = getWindow();
        if (window != null) window.announce(text, true);
    }

    @Override
    public boolean isFocusTraversable() {
        return super.isFocusTraversable() && !items.isEmpty();
    }

    @Override
    protected boolean usesFocusRing() {
        return true;
    }

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.LIST;
    }

    /**
     * The item the keyboard is on and where it is: "Sword, 3 of 8", "Sword, picked up, 3 of 8", or with free placement
     * "Slot 5 of 24, empty".
     */
    @Override
    public String getAccessibleValue() {
        int n = items.size();
        if (n == 0) return "Empty";
        int c = Math.max(0, Math.min(cursor, n - 1));
        Container item = items.get(c);
        if (freePlacement) {
            StringBuilder text = new StringBuilder("Slot ").append(c + 1).append(" of ").append(n).append(", ").append(labelOf(item));
            if (carryStart >= 0) text.append(c == carryStart ? ", picked up" : ", carrying " + labelOf(items.get(carryStart)));
            return text.toString();
        }
        return labelOf(item) + (carryStart >= 0 ? ", picked up, " : ", ") + (c + 1) + " of " + n;
    }

    @Override
    public String getAccessibleDescription() {
        String set = super.getAccessibleDescription();
        return set != null ? set : "Arrow keys move between items, Enter chooses one, M picks one up to move it";
    }

    @Override
    public void onFocusGained() {
        focused = true;
        cursor = Math.max(0, Math.min(cursor, items.size() - 1));
    }

    @Override
    public void onFocusLost() {
        if (carryStart >= 0) putBack(false);
        focused = false;
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    /**
     * Clicking the grid does not take the keyboard from the game; once Tab has focused it the arrows, Home, End, Enter,
     * Space and M are its own.
     */
    @Override
    public boolean grabsKeyboard() {
        return false;
    }

    @Override
    public boolean handlesKey(int key, int mods) {
        if ((mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0) return false;
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_HOME,
                 GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_M -> true;
            case GLFW.GLFW_KEY_ESCAPE -> carryStart >= 0;
            default -> false;
        };
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE || items.isEmpty() || lifted) return;
        int n = items.size(), columns = columns();
        cursor = Math.max(0, Math.min(cursor, n - 1));
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> moveCursor(cursor - 1);
            case GLFW.GLFW_KEY_RIGHT -> moveCursor(cursor + 1);
            case GLFW.GLFW_KEY_UP -> moveCursor(cursor - columns);
            case GLFW.GLFW_KEY_DOWN -> moveCursor(cursor + columns);
            case GLFW.GLFW_KEY_HOME -> moveCursor(0);
            case GLFW.GLFW_KEY_END -> moveCursor(n - 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (action != GLFW.GLFW_PRESS) return;
                if (carryStart >= 0) putDown();
                else if (items.get(cursor) != null && clickListener != null) clickListener.accept(items.get(cursor));
            }
            case GLFW.GLFW_KEY_M -> {
                if (action != GLFW.GLFW_PRESS) return;
                if (carryStart >= 0) putDown();
                else pickUp();
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (carryStart >= 0) putBack(true);
            }
            default -> {
            }
        }
    }

    /**
     * Whether an item has been picked up with the keyboard and is waiting to be put down.
     */
    public boolean isCarrying() {
        return carryStart >= 0;
    }

    private void moveCursor(int target) {
        int n = items.size();
        if (target < 0 || target >= n || target == cursor) return;
        if (carryStart >= 0) {
            if (freePlacement) {
                previewSlot = target == carryStart ? -1 : target;
            } else {
                items.add(target, items.remove(cursor)); // the picked-up item travels through the order
            }
        }
        cursor = target;
        scrollToSlot(target);
        notifyAccessibleChange();
    }

    private void scrollToSlot(int slot) {
        double top = slotY(slot), bottom = top + itemHeight, offset = getScrollOffsetY();
        if (top < offset) scrollBy(0, top - offset);
        else if (bottom > offset + getHeight()) scrollBy(0, bottom - offset - getHeight());
    }

    private void pickUp() {
        Container item = items.get(cursor);
        if (item == null) {
            speak("Nothing to pick up");
            return;
        }
        carryStart = cursor;
        if (liftListener != null) liftListener.accept(item, true);
        speak("Picked up " + labelOf(item) + ". The arrow keys move it, M or Enter puts it down, Escape cancels.");
    }

    private void putDown() {
        int start = carryStart;
        carryStart = -1;
        if (freePlacement) {
            Container item = items.get(start);
            int slot = previewSlot;
            previewSlot = -1;
            if (liftListener != null) liftListener.accept(item, false);
            if (slot < 0 || slot == start) {
                cursor = start;
                speak("Put back " + labelOf(item));
                return;
            }
            Container other = items.get(slot);
            items.set(start, other);
            items.set(slot, item);
            cursor = slot;
            if (reorderListener != null) reorderListener.accept(getSlots());
            speak("Put " + labelOf(item) + " in slot " + (slot + 1) + (other == null ? "" : ", swapped with " + labelOf(other)));
            return;
        }
        Container item = items.get(cursor);
        if (liftListener != null) liftListener.accept(item, false);
        if (cursor == start) {
            speak("Put back " + labelOf(item));
            return;
        }
        if (reorderListener != null) reorderListener.accept(List.copyOf(items));
        speak("Put " + labelOf(item) + " at " + (cursor + 1) + " of " + items.size());
    }

    private void putBack(boolean say) {
        int start = carryStart;
        carryStart = -1;
        previewSlot = -1;
        Container item;
        if (freePlacement) {
            item = items.get(start);
        } else {
            item = items.remove(cursor);
            items.add(start, item);
        }
        cursor = start;
        if (liftListener != null) liftListener.accept(item, false);
        if (say) speak("Cancelled. " + labelOf(item) + ", " + (start + 1) + " of " + items.size());
    }

    @Override
    public void render(Renderer renderer) {
        super.render(renderer);
        if (!focused || !isEnabled() || items.isEmpty() || lifted) return;
        int c = Math.max(0, Math.min(cursor, items.size() - 1));
        float x = (float) (getContentOriginX() + slotX(c)), y = (float) (getContentOriginY() + slotY(c));
        renderer.pushClip((float) getAbsoluteX(), (float) getAbsoluteY(), (float) getWidth(), (float) getHeight());
        renderer.drawBorder(x - 2f, y - 2f, (float) itemWidth + 4f, (float) itemHeight + 4f,
                ThemeManager.getCurrent().getAccent(), carryStart >= 0 ? 4f : 2f, 4f);
        renderer.popClip();
    }

    // --- Dragging ----------------------------------------------------------------------------------

    /**
     * Puts the lifted item under the pointer (kept inside the grid) and moves it in the order to the slot it now
     * overlaps most. Runs on every move and every frame, so the item stays under the cursor while the grid
     * scrolls beneath it.
     */
    private void followPointer(float delta) {
        int columns = columns();
        int rows = (items.size() + columns - 1) / columns;
        // The item's centre may go anywhere over the grid, so it can reach the far side of an edge slot.
        double contentWidth = columns * (itemWidth + gap) - gap, contentHeight = rows * (itemHeight + gap) - gap;
        double x = Math.max(-itemWidth / 2, Math.min(contentWidth - itemWidth / 2, lastX - getContentOriginX() - grabX));
        double y = Math.max(-itemHeight / 2, Math.min(contentHeight - itemHeight / 2, lastY - getContentOriginY() - grabY));
        pressed.setX(x);
        pressed.setY(y);

        setOutside(!withinBounds(lastX, lastY));
        if (followListener != null) followListener.moved(pressed, lastX - grabX, lastY - grabY);
        if (outside && dropOutsideListener != null) {
            // Heading for a drop zone outside the grid: nothing inside should shuffle meanwhile.
            disarm();
            return;
        }

        double centerX = x + itemWidth / 2, centerY = y + itemHeight / 2;
        int target = slotAt(centerX, centerY);
        int current = items.indexOf(pressed);
        if (freePlacement) {
            followFree(target, current, centerX, centerY, delta);
            return;
        }
        if (target == current) {
            disarm();
            return;
        }

        Container occupant = items.get(target);
        if (dropTargets != null && dropTargets.test(pressed, occupant)) {
            boolean inner = insideInner(target, centerX, centerY);
            if (hoverTarget != occupant || hoverInner != inner) {
                disarm();
                hoverTarget = occupant;
                hoverInner = inner;
                hoverTime = 0;
            }
            hoverTime += delta;
            if (inner) {
                if (hoverTime >= ARM_DELAY && armed == null) arm(occupant);
                return;
            }
            // Outside the middle. Carried past the target's centre, the item is on its way to the far side, so
            // it swaps at once; still short of the centre, it only swaps if it lingers there, which leaves
            // room to reach the middle without the target sliding away first.
            double dirX = slotX(target) - slotX(current), dirY = slotY(target) - slotY(current);
            double along = (centerX - (slotX(target) + itemWidth / 2)) * dirX + (centerY - (slotY(target) + itemHeight / 2)) * dirY;
            if (along <= 0 && hoverTime < EDGE_REORDER_DELAY) return;
        } else {
            disarm();
        }

        items.remove(current);
        items.add(target, pressed);
    }

    /**
     * The free placement counterpart of the end of {@link #followPointer}: nothing else moves while an item is
     * carried, the slot under it is only noted (and an occupant there eased toward the slot the item came from, to
     * show the swap) until the item is dropped. Over a drop target's middle it arms the target instead.
     */
    private void followFree(int target, int current, double centerX, double centerY, float delta) {
        previewSlot = -1;
        if (target == current) {
            disarm();
            return;
        }
        Container occupant = items.get(target);
        if (occupant != null && dropTargets != null && dropTargets.test(pressed, occupant)) {
            boolean inner = insideInner(target, centerX, centerY);
            if (hoverTarget != occupant || hoverInner != inner) {
                disarm();
                hoverTarget = occupant;
                hoverInner = inner;
                hoverTime = 0;
            }
            hoverTime += delta;
            if (inner) {
                if (hoverTime >= ARM_DELAY && armed == null) arm(occupant);
                return;
            }
        } else {
            disarm();
        }
        previewSlot = target;
    }

    /**
     * Whether the point is in the middle of slot {@code index}, away from its edges.
     */
    private boolean insideInner(int index, double px, double py) {
        double insetX = itemWidth * TARGET_INSET, insetY = itemHeight * TARGET_INSET;
        double sx = slotX(index), sy = slotY(index);
        return px >= sx + insetX && px <= sx + itemWidth - insetX && py >= sy + insetY && py <= sy + itemHeight - insetY;
    }

    private void setOutside(boolean now) {
        if (outside == now) return;
        outside = now;
        if (outsideListener != null) outsideListener.accept(now);
    }

    private void arm(Container target) {
        armed = target;
        if (highlightListener != null) highlightListener.accept(target, true);
    }

    private void disarm() {
        if (armed != null && highlightListener != null) highlightListener.accept(armed, false);
        armed = null;
        hoverTarget = null;
        hoverTime = 0;
    }

    private void autoScroll(float delta) {
        double top = getAbsoluteY(), bottom = top + getHeight();
        double push = 0;
        if (lastY < top + AUTO_SCROLL_EDGE) {
            push = -Math.min(1, (top + AUTO_SCROLL_EDGE - lastY) / AUTO_SCROLL_EDGE);
        } else if (lastY > bottom - AUTO_SCROLL_EDGE) {
            push = Math.min(1, (lastY - (bottom - AUTO_SCROLL_EDGE)) / AUTO_SCROLL_EDGE);
        }
        if (push != 0) scrollBy(0, push * AUTO_SCROLL_SPEED * delta);
    }

    @Override
    public CursorMode getCursorMode() {
        return lifted ? CursorMode.RESIZE_ALL : super.getCursorMode();
    }

    @Override
    public void update(float delta) {
        super.update(delta);

        if (lifted) {
            autoScroll(delta);
            followPointer(delta);
        }
        ensureSlots();

        // Everything but the lifted item eases toward its slot: the ones it displaced slide aside, and a just
        // dropped item settles into place.
        double blend = 1 - Math.exp(-SLIDE_RATE * delta);
        for (int i = 0; i < items.size(); i++) {
            Container item = items.get(i);
            if (item == null || (lifted && item == pressed)) continue;
            // In free placement the item under the carried one makes room by drifting to the carried one's slot.
            int slot = lifted && freePlacement && i == previewSlot ? startIndex : i;
            item.setX(approach(item.getX(), slotX(slot), blend));
            item.setY(approach(item.getY(), slotY(slot), blend));
        }
    }

    private static double approach(double current, double target, double blend) {
        double next = current + (target - current) * blend;
        return Math.abs(target - next) < 0.25 ? target : next;
    }
}
