package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.FontScalable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A {@link Container} that can change size, and stretches or shrinks its children along with itself.
 * <p>
 * <b>Following the window.</b> A top-level ResizableContainer (one added straight to a {@link Window}) tracks
 * the window's size proportionally: if it covers half the window's width, it keeps covering half. Its position
 * scales the same way. Turn that off with {@link #setResizeWithWindow(boolean)} to resize it only through
 * {@link #resizeTo}. A ResizableContainer nested inside another container is never driven by the window; if
 * its parent is a ResizableContainer, it is scaled as one of that parent's children.
 * <p>
 * <b>Dragging.</b> {@link #setUserResizable} lets the user resize it by dragging its corners (or any {@link
 * Handle}s you choose).
 * <p>
 * <b>Limits.</b> {@link #setMinSize} and {@link #setMaxSize} clamp the size, however it is driven.
 * <p>
 * <b>Children.</b> Every child, at any depth, has its position (relative to this container) and size scaled by
 * the same factors as the container. {@link #setFixedSize} keeps a particular child at its own size, moving only
 * its top-left corner; {@link #setScaleChildren(boolean) setScaleChildren(false)} resizes just the container. A
 * {@link me.piitex.engine.ui.layout.Layout} child re-places its own children every frame, so for those only
 * sizes are scaled.
 * <p>
 * <b>Fonts.</b> Text is scaled too (see {@link FontScalable}), by the smaller of the width and height factors so
 * it still fits. {@link #setFontSizeLimits} keeps it readable; {@link #setScaleFonts(boolean)
 * setScaleFonts(false)} turns it off.
 */
public class ResizableContainer extends Container {

    /**
     * A spot on the container's border the user can grab to resize it. Each one moves the edge(s) on its side.
     */
    public enum Handle {
        NORTH_WEST(-1, -1, CursorMode.RESIZE_NWSE), NORTH(0, -1, CursorMode.RESIZE_VERTICAL), NORTH_EAST(1, -1, CursorMode.RESIZE_NESW),
        WEST(-1, 0, CursorMode.RESIZE_HORIZONTAL), EAST(1, 0, CursorMode.RESIZE_HORIZONTAL),
        SOUTH_WEST(-1, 1, CursorMode.RESIZE_NESW), SOUTH(0, 1, CursorMode.RESIZE_VERTICAL), SOUTH_EAST(1, 1, CursorMode.RESIZE_NWSE);

        public static final Set<Handle> CORNERS = Collections.unmodifiableSet(EnumSet.of(NORTH_WEST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST));
        public static final Set<Handle> EDGES = Collections.unmodifiableSet(EnumSet.of(NORTH, SOUTH, WEST, EAST));

        private final int sideX, sideY; // -1 = left/top edge, 1 = right/bottom edge, 0 = neither
        private final CursorMode cursor;

        Handle(int sideX, int sideY, CursorMode cursor) {
            this.sideX = sideX;
            this.sideY = sideY;
            this.cursor = cursor;
        }

        /** The cursor shown while hovering or dragging this handle. */
        public CursorMode getCursorMode() {
            return cursor;
        }

        /** The handle on side ({@code sideX}, {@code sideY}), each -1, 0 or 1; null for (0, 0). */
        private static Handle of(int sideX, int sideY) {
            for (Handle handle : values()) {
                if (handle.sideX == sideX && handle.sideY == sideY) return handle;
            }
            return null;
        }
    }

    private static final Color HANDLE_COLOR = new Color(1f, 1f, 1f, 0.18f);
    private static final Color HANDLE_COLOR_ACTIVE = new Color(1f, 1f, 1f, 0.55f);

    // Size limits and what gets scaled
    private double minWidth = 0, minHeight = 0;
    private double maxWidth = Double.POSITIVE_INFINITY, maxHeight = Double.POSITIVE_INFINITY;
    private boolean scaleChildren = true;
    private boolean scaleFonts = true;
    private float minFontSize = 0f, maxFontSize = Float.POSITIVE_INFINITY;
    private final Set<Element> fixedSize = Collections.newSetFromMap(new WeakHashMap<>());

    // Following the window: this container's bounds when the window had size (refWindowWidth x refWindowHeight).
    // Captured lazily, and dropped whenever something else resizes the container.
    private boolean resizeWithWindow = true;
    private boolean hasReference = false;
    private double refWindowWidth, refWindowHeight;
    private double refX, refY, refWidth, refHeight;

    // Dragging by the handles
    private final Set<Handle> handles = EnumSet.noneOf(Handle.class);
    private double grip = 10;
    private boolean showHandles = false;
    private Handle activeHandle = null;
    private double dragMouseX, dragMouseY, dragStartX, dragStartY, dragStartWidth, dragStartHeight;

    // Font scale: how big the container is now relative to the size it was authored at (1 = as authored).
    private double authoredWidth = 0, authoredHeight = 0; // the container's size before its first resize
    private double fontScale = 1.0;
    private final Map<Element, FontState> fontStates = new WeakHashMap<>();

    /** What this container last set an element's fonts to, and the sizes at scale 1 those came from. */
    private record FontState(float[] base, float[] applied) {
    }

    /** One resize, as the numbers {@link #scaleElement} needs. */
    private record Transform(double sx, double sy, double oldFontScale, double newFontScale) {
    }

    public ResizableContainer(double width, double height) {
        super(width, height);
    }

    public ResizableContainer(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    // ------------------------------------------------------------------------------------------ limits

    /** The smallest size this container will shrink to. 0 (the default) means no minimum. Applies right away. */
    public void setMinSize(double minWidth, double minHeight) {
        this.minWidth = Math.max(0, minWidth);
        this.minHeight = Math.max(0, minHeight);
        resizeTo(getWidth(), getHeight());
    }

    /** The largest size this container will grow to. {@code Double.POSITIVE_INFINITY} (the default) means no maximum. Applies right away. */
    public void setMaxSize(double maxWidth, double maxHeight) {
        this.maxWidth = Math.max(0, maxWidth);
        this.maxHeight = Math.max(0, maxHeight);
        resizeTo(getWidth(), getHeight());
    }

    public double getMinWidth() {
        return minWidth;
    }

    public double getMinHeight() {
        return minHeight;
    }

    public double getMaxWidth() {
        return maxWidth;
    }

    public double getMaxHeight() {
        return maxHeight;
    }

    // ------------------------------------------------------------------------------------------ what scales

    /** Whether children are scaled along with this container (default true). */
    public void setScaleChildren(boolean scaleChildren) {
        this.scaleChildren = scaleChildren;
    }

    public boolean isScaleChildren() {
        return scaleChildren;
    }

    /** Whether text is scaled along with the container (default true). Only has an effect while children are scaled. */
    public void setScaleFonts(boolean scaleFonts) {
        this.scaleFonts = scaleFonts;
    }

    public boolean isScaleFonts() {
        return scaleFonts;
    }

    /**
     * Clamps every scaled font to [min, max] points, so text can't shrink to unreadable or grow absurd. 0 and
     * {@code Float.POSITIVE_INFINITY} (the defaults) mean no limit.
     */
    public void setFontSizeLimits(float min, float max) {
        this.minFontSize = Math.max(0f, min);
        this.maxFontSize = Math.max(this.minFontSize, max);
    }

    /**
     * Keeps {@code element} (a descendant of this container, at any depth) at its own size and font size when this
     * container is resized, though its top-left corner still follows the scaling. This suits a button that should
     * stay button-sized. Pass false to go back to scaling it.
     */
    public void setFixedSize(Element element, boolean fixed) {
        if (fixed) fixedSize.add(element);
        else fixedSize.remove(element);
    }

    public boolean isFixedSize(Element element) {
        return fixedSize.contains(element);
    }

    // ------------------------------------------------------------------------------------------ following the window

    /** Whether a top-level container tracks the window's size (default true). See the class docs. */
    public void setResizeWithWindow(boolean resizeWithWindow) {
        this.resizeWithWindow = resizeWithWindow;
        hasReference = false;
    }

    public boolean isResizeWithWindow() {
        return resizeWithWindow;
    }

    /**
     * Resizes this container to (width, height), clamped to the min/max, scaling the children with it. Its
     * position doesn't change. Later window-following is measured from this new size.
     */
    public void resizeTo(double width, double height) {
        applyBounds(getX(), getY(), width, height);
    }

    @Override
    public void update(float delta) {
        if (resizeWithWindow && getParent() == null && activeHandle == null) {
            Window window = resolveWindow();
            if (window != null) {
                syncToWindow(window);
            }
        }
        super.update(delta);
    }

    private void syncToWindow(Window window) {
        double windowWidth = window.getWindowOptions().getWidth();
        double windowHeight = window.getWindowOptions().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) return;

        if (!hasReference) {
            refWindowWidth = windowWidth;
            refWindowHeight = windowHeight;
            refX = getX();
            refY = getY();
            refWidth = getWidth();
            refHeight = getHeight();
            hasReference = true;
            return;
        }

        double rx = windowWidth / refWindowWidth;
        double ry = windowHeight / refWindowHeight;
        applyBounds(refX * rx, refY * ry, refWidth * rx, refHeight * ry);
        hasReference = true; // applyBounds drops it; this resize came from the reference itself
    }

    // ------------------------------------------------------------------------------------------ dragging

    /**
     * Lets the user resize this container by dragging its four corners (true), or turns that off (false). Use
     * {@link #setResizeHandles} to choose different handles, such as the edges too. Dragging respects {@link
     * #setMinSize}/{@link #setMaxSize} and scales the children like any other resize. Off by default.
     * <p>
     * A handle is a strip of {@link #setResizeGrip} points just inside the border. It takes the mouse ahead of
     * any child under it, so leave that margin clear of anything that needs clicking. Hovering a handle shows
     * the matching resize cursor, and the cursor stays that way for the whole drag.
     */
    public void setUserResizable(boolean userResizable) {
        setResizeHandles(userResizable ? Handle.CORNERS : Collections.emptySet());
    }

    /** Whether any handle is enabled. */
    public boolean isUserResizable() {
        return !handles.isEmpty();
    }

    /** Chooses exactly which handles the user can drag; none turns user resizing off. e.g. {@code setResizeHandles(Handle.SOUTH_EAST)}. */
    public void setResizeHandles(Handle... enabled) {
        setResizeHandles(Arrays.asList(enabled));
    }

    /** As {@link #setResizeHandles(Handle...)}, e.g. {@code setResizeHandles(Handle.EDGES)}. */
    public void setResizeHandles(Collection<Handle> enabled) {
        handles.clear();
        handles.addAll(enabled);
        if (handles.isEmpty()) activeHandle = null;
    }

    /** How many points inside the border a handle reaches (default 10). */
    public void setResizeGrip(double grip) {
        this.grip = Math.max(1, grip);
    }

    public double getResizeGrip() {
        return grip;
    }

    /**
     * Draws a translucent square over each enabled corner (and a strip over each enabled edge) so the user can
     * see where to grab; the one under the mouse, or being dragged, is brighter. Off by default.
     */
    public void setShowResizeHandles(boolean showHandles) {
        this.showHandles = showHandles;
    }

    public boolean isShowResizeHandles() {
        return showHandles;
    }

    /** The enabled handle under (px, py), or null. A corner wins over the edges it overlaps. */
    public Handle handleAt(double px, double py) {
        if (handles.isEmpty() || !contains(px, py)) return null;

        double left = getAbsoluteX(), top = getAbsoluteY();
        int sideX = px - left <= grip ? -1 : left + getWidth() - px <= grip ? 1 : 0;
        int sideY = py - top <= grip ? -1 : top + getHeight() - py <= grip ? 1 : 0;

        Handle exact = Handle.of(sideX, sideY);
        if (exact != null && handles.contains(exact)) return exact;

        // A disabled corner still counts as the edge(s) it sits on.
        Handle horizontal = Handle.of(sideX, 0), vertical = Handle.of(0, sideY);
        if (horizontal != null && handles.contains(horizontal)) return horizontal;
        if (vertical != null && handles.contains(vertical)) return vertical;
        return null;
    }

    /** The handle being dragged, or else the one under the mouse. */
    private Handle currentHandle() {
        if (activeHandle != null) return activeHandle;
        return MouseTracker.isInWindow() ? handleAt(MouseTracker.getX(), MouseTracker.getY()) : null;
    }

    @Override
    public boolean interceptsPointer(double px, double py) {
        return isEnabled() && handleAt(px, py) != null;
    }

    @Override
    public CursorMode getCursorMode() {
        Handle handle = currentHandle();
        return handle != null ? handle.getCursorMode() : super.getCursorMode();
    }

    @Override
    public boolean canDrag(double x, double y) {
        return super.canDrag(x, y) || handleAt(x, y) != null;
    }

    @Override
    public void onDragStart(double x, double y) {
        Handle handle = handleAt(x, y);
        if (handle == null) {
            super.onDragStart(x, y); // an ordinary move-drag, if this container is draggable
            return;
        }
        activeHandle = handle;
        dragMouseX = x;
        dragMouseY = y;
        dragStartX = getX();
        dragStartY = getY();
        dragStartWidth = getWidth();
        dragStartHeight = getHeight();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (activeHandle == null) {
            super.onDragUpdate(x, y);
            return;
        }

        double newWidth = dragStartWidth + activeHandle.sideX * (x - dragMouseX);
        double newHeight = dragStartHeight + activeHandle.sideY * (y - dragMouseY);
        newWidth = Math.max(minWidth, Math.min(newWidth, maxWidth));
        newHeight = Math.max(minHeight, Math.min(newHeight, maxHeight));

        // A left/top handle keeps the opposite edge fixed. Using the clamped size means the container stops
        // against a min/max instead of sliding.
        double newX = activeHandle.sideX < 0 ? dragStartX + dragStartWidth - newWidth : dragStartX;
        double newY = activeHandle.sideY < 0 ? dragStartY + dragStartHeight - newHeight : dragStartY;

        applyBounds(newX, newY, newWidth, newHeight);
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (activeHandle == null) {
            super.onDragEnd(x, y);
            return;
        }
        activeHandle = null;
    }

    @Override
    public void render(Renderer renderer) {
        super.render(renderer);
        if (!showHandles || !isEnabled() || handles.isEmpty()) return;

        Handle highlighted = currentHandle();
        float g = (float) Math.min(grip, Math.min(getWidth(), getHeight()) / 2);
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();

        for (Handle handle : handles) {
            float hx = handle.sideX < 0 ? x : handle.sideX > 0 ? x + w - g : x + g;
            float hy = handle.sideY < 0 ? y : handle.sideY > 0 ? y + h - g : y + g;
            float hw = handle.sideX == 0 ? w - 2 * g : g;
            float hh = handle.sideY == 0 ? h - 2 * g : g;
            renderer.drawQuad(hx, hy, hw, hh, handle == highlighted ? HANDLE_COLOR_ACTIVE : HANDLE_COLOR, 0f);
        }
    }

    // ------------------------------------------------------------------------------------------ scaling

    /**
     * Moves/resizes this container to the given bounds (size clamped to the limits), carrying the children
     * along, and drops the window-following reference so that measures from wherever this leaves things.
     */
    private void applyBounds(double x, double y, double width, double height) {
        hasReference = false;

        width = Math.max(minWidth, Math.min(width, maxWidth));
        height = Math.max(minHeight, Math.min(height, maxHeight));

        double oldX = getX(), oldY = getY(), oldWidth = getWidth(), oldHeight = getHeight();
        if (x == oldX && y == oldY && width == oldWidth && height == oldHeight) return;

        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);

        if (!scaleChildren) return;

        // Fonts follow the size relative to the original, not an accumulation of each step's ratio: min() of
        // per-step ratios isn't the min of the totals (narrow only the width, then widen it back, and the
        // height ratio of 1 would cap every step at 1 so the font would never recover).
        if (authoredWidth <= 0) {
            authoredWidth = oldWidth;
            authoredHeight = oldHeight;
        }
        double newFontScale = authoredWidth > 0 && authoredHeight > 0
                ? Math.min(width / authoredWidth, height / authoredHeight)
                : 1.0;

        Transform transform = new Transform(
                oldWidth > 0 ? width / oldWidth : 1, oldHeight > 0 ? height / oldHeight : 1,
                fontScale, newFontScale);
        for (Element child : getElements().values()) {
            scaleElement(child, transform);
        }
        fontScale = newFontScale;
    }

    /**
     * Scales {@code element} and its descendants with this container. Everything is positioned relative to its
     * container, so one factor per axis applies at every depth: an element's own x/y and size scale, and its
     * children (relative to it) scale by the same amount.
     */
    private void scaleElement(Element element, Transform t) {
        element.setX(element.getX() * t.sx());
        element.setY(element.getY() * t.sy());

        if (fixedSize.contains(element)) {
            return; // keeps its size and font (and so its children's); only its corner moved
        }

        element.setWidth(element.getWidth() * t.sx());
        element.setHeight(element.getHeight() * t.sy());
        if (scaleFonts && element instanceof FontScalable scalable) {
            scaleFont(element, scalable, t); // after the size: a TextOverlay re-measures its own box when its font changes
        }
        if (element instanceof Container container) {
            for (Element child : container.getElements().values()) {
                scaleElement(child, t);
            }
        }
    }

    private void scaleFont(Element element, FontScalable scalable, Transform t) {
        float[] current = scalable.getFontSizes();
        FontState state = fontStates.get(element);

        // New to us, or changed by the user since we last set it: what it is now is its size at the old scale.
        float[] base;
        if (state == null || !Arrays.equals(state.applied(), current)) {
            base = new float[current.length];
            for (int i = 0; i < current.length; i++) {
                base[i] = (float) (current[i] / t.oldFontScale());
            }
        } else {
            base = state.base();
        }

        float[] scaled = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            scaled[i] = (float) Math.max(minFontSize, Math.min(base[i] * t.newFontScale(), maxFontSize));
        }
        scalable.setFontSizes(scaled);
        fontStates.put(element, new FontState(base, scalable.getFontSizes())); // read back: the element may adjust what it stores
    }
}
