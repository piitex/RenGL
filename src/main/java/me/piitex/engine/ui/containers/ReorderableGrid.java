package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.scroll.ScrollContainer;

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
 */
public class ReorderableGrid extends ScrollContainer {
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

    private Container pressed;
    private boolean lifted;
    private int startIndex;
    private double pressX, pressY, grabX, grabY, lastX, lastY;

    public ReorderableGrid(double width, double height, double itemWidth, double itemHeight, double gap) {
        super(width, height);
        this.itemWidth = itemWidth;
        this.itemHeight = itemHeight;
        this.gap = gap;
    }

    /**
     * Replaces the grid's items and lays them out immediately, in the order given.
     */
    public void setItems(List<? extends Container> newItems) {
        for (Container item : items) removeElement(item);
        items.clear();
        pressed = null;
        lifted = false;
        armed = null;
        hoverTarget = null;
        outside = false;

        for (Container item : newItems) {
            items.add(item);
            addElement(item);
        }
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setX(slotX(i));
            items.get(i).setY(slotY(i));
        }
    }

    /**
     * The items in their current order, including a drag in progress.
     */
    public List<Container> getItems() {
        return List.copyOf(items);
    }

    /**
     * Called once when a drag ends with an item in a different position than it started, with the new order.
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
        if (liftListener != null) liftListener.accept(item, false);

        if (target != null) {
            if (dropOntoListener != null) dropOntoListener.accept(item, target);
        } else if (dropOutsideListener != null && !withinBounds(x, y)) {
            dropOutsideListener.accept(item);
        } else if (reorderListener != null && items.indexOf(item) != startIndex) {
            reorderListener.accept(List.copyOf(items));
        }
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

        // Everything but the lifted item eases toward its slot: the ones it displaced slide aside, and a just
        // dropped item settles into place.
        double blend = 1 - Math.exp(-SLIDE_RATE * delta);
        for (int i = 0; i < items.size(); i++) {
            Container item = items.get(i);
            if (lifted && item == pressed) continue;
            item.setX(approach(item.getX(), slotX(i), blend));
            item.setY(approach(item.getY(), slotY(i), blend));
        }
    }

    private static double approach(double current, double target, double blend) {
        double next = current + (target - current) * blend;
        return Math.abs(target - next) < 0.25 ? target : next;
    }
}
