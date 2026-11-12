package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.overlays.Overlay;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

import java.util.function.Consumer;

/**
 * Two panes separated by a divider the user drags to trade space between them, filling
 * the role JavaFX's {@code SplitPane} plays. It is limited to two panes; nest one inside
 * another for more. {@link Orientation#HORIZONTAL} puts the panes side by side with a
 * vertical divider, and {@link Orientation#VERTICAL} stacks them with a horizontal one.
 * <p>
 * The panes are set with {@link #setFirst} and {@link #setSecond} (or two {@link
 * #addElement} calls, filling first then second). Removing a pane empties its side; the
 * divider cannot be removed. Each pane is resized to fill its side every frame, so
 * anything that lays itself out inside, such as a {@link Layout} or a
 * {@link me.piitex.engine.ui.scroll.ScrollContainer}, simply gets more or less room. A
 * pane that is a {@link Container} is set to clip its children so they cannot spill
 * across the divider while squeezed.
 * <p>
 * The divider is tracked as a fraction of the space, {@link #getDividerPosition()}, so
 * it keeps its proportion when this container is resized. Neither pane can be dragged
 * smaller than {@link #getMinPaneSize()}. The first pane additionally cannot be dragged
 * larger than {@link #getMaxPaneSize()}, if set — the second pane has no such ceiling, so
 * a sidebar-style first pane can be capped while the main pane still takes whatever space
 * that leaves. If the available space is too small to satisfy a bound, it relaxes toward
 * the midpoint rather than producing an inverted range.
 */
public class SplitContainer extends Layout {

    public enum Orientation {
        /**
         * Panes side by side, divided by a vertical bar.
         */
        HORIZONTAL,
        /**
         * Panes stacked top over bottom, divided by a horizontal bar.
         */
        VERTICAL
    }

    private static final int FIRST = 0;
    private static final int DIVIDER = 1;
    private static final int SECOND = 2;

    private final Orientation orientation;
    private final Divider divider = new Divider();
    private double dividerPosition = 0.5;
    private float dividerThickness = 6f;
    private float minPaneSize = 40f;
    private float maxPaneSize = -1f;
    private Consumer<Double> onDividerMoved;

    public SplitContainer(Orientation orientation, double width, double height) {
        this(orientation, 0, 0, width, height);
    }

    public SplitContainer(Orientation orientation, double x, double y, double width, double height) {
        super(x, y, width, height);
        this.orientation = orientation == null ? Orientation.HORIZONTAL : orientation;

        // Transparent as a baseline: the panes and divider paint themselves.
        // Non-pinning, same as the other composites.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);

        divider.setCursorMode(this.orientation == Orientation.HORIZONTAL
                ? CursorMode.RESIZE_HORIZONTAL : CursorMode.RESIZE_VERTICAL);
        super.addElement(divider, DIVIDER);
    }

    public Orientation getOrientation() {
        return orientation;
    }

    /**
     * The left (or top) pane, or null.
     */
    public Element getFirst() {
        return getElements().get(FIRST);
    }

    /**
     * The right (or bottom) pane, or null.
     */
    public Element getSecond() {
        return getElements().get(SECOND);
    }

    /**
     * Sets the left (or top) pane, replacing any existing one.
     */
    public void setFirst(Element pane) {
        setPane(FIRST, pane);
    }

    /**
     * Sets the right (or bottom) pane, replacing any existing one.
     */
    public void setSecond(Element pane) {
        setPane(SECOND, pane);
    }

    private void setPane(int slot, Element pane) {
        if (getElements().get(slot) == pane) return;
        super.removeElement(slot); // detaches the pane it replaces
        if (pane == null) return;
        int other = slot == FIRST ? SECOND : FIRST;
        if (getElements().get(other) == pane) {
            super.removeElement(other); // moving a pane to the other side
        }
        if (pane instanceof Container container) {
            container.setClipping(true);
        }
        super.addElement(pane, slot); // Layout's index overload: stores it and relayouts
    }

    /**
     * Fills the first pane, then the second; a third call throws, since a SplitContainer holds exactly two.
     */
    @Override
    public void addElement(Element element) {
        if (getFirst() == null) {
            setFirst(element);
        } else if (getSecond() == null) {
            setSecond(element);
        } else {
            throw new IllegalStateException("SplitContainer already has two panes; nest another SplitContainer for more");
        }
    }

    @Override
    public void addElement(Element element, int index) {
        throw new UnsupportedOperationException("Use setFirst/setSecond");
    }

    /**
     * The two panes and the divider sit in fixed slots, so removing one never renumbers the others.
     */
    @Override
    protected boolean keepsIndices() {
        return true;
    }

    /**
     * Removing a pane empties its side. The divider cannot be removed.
     */
    @Override
    public Element removeElement(int index) {
        if (getElements().get(index) == divider) {
            throw new UnsupportedOperationException("The divider cannot be removed");
        }
        return super.removeElement(index);
    }

    /**
     * Panes keep their sides, so a pane that is dragged or brought to front stays where it is. They never overlap,
     * so front and back make no difference.
     */
    @Override
    protected void moveChildToFront(Element child) {
    }

    /**
     * Where the divider sits, as a fraction of the space between the panes (0 = all second pane, 1 = all first), before {@link #getMinPaneSize()} limits it.
     */
    public double getDividerPosition() {
        return dividerPosition;
    }

    /**
     * Does NOT fire {@link #onDividerMoved}; only user drags do.
     */
    public void setDividerPosition(double position) {
        dividerPosition = Math.max(0, Math.min(1, position));
        relayout();
    }

    public float getDividerThickness() {
        return dividerThickness;
    }

    /**
     * Width of the divider bar, and so of its grab area.
     */
    public void setDividerThickness(float thickness) {
        dividerThickness = Math.max(1f, thickness);
        relayout();
    }

    public float getMinPaneSize() {
        return minPaneSize;
    }

    /**
     * The smallest size, in points along the split axis, either pane can be dragged down to.
     */
    public void setMinPaneSize(float minPaneSize) {
        this.minPaneSize = Math.max(0f, minPaneSize);
        relayout();
    }

    /**
     * The largest size, in points along the split axis, the first pane can be dragged up
     * to, or non-positive (the default) for no maximum. Unlike {@link #getMinPaneSize()},
     * this bounds only the first pane — a sidebar-style pane is typically the one that
     * should not grow without limit, while the second pane is expected to take whatever
     * space that leaves.
     */
    public float getMaxPaneSize() {
        return maxPaneSize;
    }

    /**
     * @param maxPaneSize a non-positive value disables the maximum entirely.
     */
    public void setMaxPaneSize(float maxPaneSize) {
        this.maxPaneSize = maxPaneSize;
        relayout();
    }

    /**
     * Registers the callback fired with the new {@link #getDividerPosition()} as the user drags the divider. Only one is held at a time.
     */
    public void onDividerMoved(Consumer<Double> onDividerMoved) {
        this.onDividerMoved = onDividerMoved;
    }

    /**
     * Space along the split axis available to the two panes, i.e. without the divider or padding.
     */
    private double available() {
        double total = orientation == Orientation.HORIZONTAL ? getWidth() : getHeight();
        return Math.max(0, total - 2 * getPadding() - dividerThickness);
    }

    /**
     * Size of the first pane along the split axis, after the min/max-size limits.
     */
    private double firstSize() {
        double available = available();
        return clampFirstSize(dividerPosition * available, available);
    }

    /**
     * Clamps a candidate first-pane size to {@link #minPaneSize} on both panes (relaxed
     * toward the midpoint of {@code available} if it alone would not fit) and additionally
     * to {@link #maxPaneSize} on the first pane only — see {@link #getMaxPaneSize()} for
     * why that bound is one-sided. If {@code maxPaneSize} is set below the minimum the
     * first pane is already held to, it is relaxed up to that minimum instead of producing
     * an inverted range.
     */
    private double clampFirstSize(double candidate, double available) {
        double min = Math.min(minPaneSize, available / 2.0);
        double lower = min;
        double upper = available - min;
        if (maxPaneSize > 0) {
            upper = Math.min(upper, Math.max(maxPaneSize, lower));
        }
        return Math.max(lower, Math.min(upper, candidate));
    }

    @Override
    protected void layoutChildren() {
        double available = available();
        double first = firstSize();
        double second = available - first;
        double left = getPadding();
        double top = getPadding();
        double crossWidth = getWidth() - 2 * getPadding();
        double crossHeight = getHeight() - 2 * getPadding();

        Element firstPane = getFirst();
        Element secondPane = getSecond();
        if (orientation == Orientation.HORIZONTAL) {
            place(firstPane, left, top, first, crossHeight);
            place(divider, left + first, top, dividerThickness, crossHeight);
            place(secondPane, left + first + dividerThickness, top, second, crossHeight);
        } else {
            place(firstPane, left, top, crossWidth, first);
            place(divider, left, top + first, crossWidth, dividerThickness);
            place(secondPane, left, top + first + dividerThickness, crossWidth, second);
        }
    }

    private static void place(Element element, double x, double y, double width, double height) {
        if (element == null) return;
        element.setWidth(width);
        element.setHeight(height);
        element.setX(x);
        element.setY(y);
    }

    /**
     * The draggable bar between the panes. A child like any other, so hit-testing, hover and the resize cursor need no special handling.
     */
    private final class Divider extends Overlay implements Themeable, Draggable, me.piitex.engine.ui.Focusable {
        private double grabOffset;

        Divider() {
            getStyling().applyThemeBorderThickness(0f);
            setFocusTraversable(true);
            ThemeManager.register(this);
        }

        /* Accessibility: Tab to the divider, then the arrow keys along the split move it */

        @Override
        protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
            return me.piitex.engine.access.AccessibleRole.SLIDER;
        }

        @Override
        protected String defaultAccessibleName() {
            return "Divider";
        }

        /** How much of the space the first pane has: "50 percent". */
        @Override
        public String getAccessibleValue() {
            double available = available();
            return (available <= 0 ? 0 : Math.round(firstSize() / available * 100)) + " percent";
        }

        @Override
        public String getAccessibleDescription() {
            String set = super.getAccessibleDescription();
            if (set != null) return set;
            return orientation == Orientation.HORIZONTAL ? "Left and Right move it" : "Up and Down move it";
        }

        @Override
        protected boolean usesFocusRing() {
            return true;
        }

        @Override
        public void onFocusGained() {
        }

        @Override
        public void onFocusLost() {
        }

        @Override
        public void onCharTyped(int codepoint) {
        }

        @Override
        public boolean grabsKeyboard() {
            return false;
        }

        @Override
        public boolean handlesKey(int key, int mods) {
            return key == lessKey() || key == moreKey() || key == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME
                    || key == org.lwjgl.glfw.GLFW.GLFW_KEY_END;
        }

        private int lessKey() {
            return orientation == Orientation.HORIZONTAL ? org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT : org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
        }

        private int moreKey() {
            return orientation == Orientation.HORIZONTAL ? org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT : org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
        }

        @Override
        public void onKeyPressed(int key, int action, int mods) {
            if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) return;
            double available = available();
            if (available <= 0) return;
            double step = ((mods & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0 ? 0.10 : 0.02) * available;
            double size = firstSize();
            if (key == lessKey()) size -= step;
            else if (key == moreKey()) size += step;
            else if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME) size = 0;
            else if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_END) size = available;
            else return;
            double next = clampFirstSize(size, available) / available;
            if (next == dividerPosition) return;
            dividerPosition = next;
            relayout();
            if (onDividerMoved != null) onDividerMoved.accept(dividerPosition);
            notifyAccessibleChange();
        }

        @Override
        public void applyTheme(Theme theme) {
            getStyling().applyThemeBackgroundColor(theme.getScrollTrack());
            getStyling().applyThemeHoverColor(theme.getScrollThumb());
        }

        @Override
        public void render(Renderer renderer) {
            if (!isEnabled()) return;
            super.render(renderer);
        }

        @Override
        public void onDragStart(double x, double y) {
            // Remember where on the bar it was grabbed so it doesn't jump to center under the cursor.
            grabOffset = orientation == Orientation.HORIZONTAL ? x - getAbsoluteX() : y - getAbsoluteY();
        }

        @Override
        public void onDragUpdate(double x, double y) {
            double available = available();
            if (available <= 0) return;
            double origin = (orientation == Orientation.HORIZONTAL ? SplitContainer.this.getAbsoluteX() : SplitContainer.this.getAbsoluteY()) + getPadding();
            double mouse = orientation == Orientation.HORIZONTAL ? x : y;
            double desired = mouse - grabOffset - origin;
            double clamped = clampFirstSize(desired, available);
            double next = clamped / available;
            if (next != dividerPosition) {
                dividerPosition = next;
                relayout();
                if (onDividerMoved != null) onDividerMoved.accept(dividerPosition);
            }
        }

        @Override
        public void onDragEnd(double x, double y) {
            notifyAccessibleChange(); // once, where it was let go, not for every pixel on the way
        }
    }

    /**
     * What a screen reader calls the divider, such as "Sidebar width". Default "Divider".
     */
    public void setDividerAccessibleName(String name) {
        divider.setAccessibleName(name);
    }
}
