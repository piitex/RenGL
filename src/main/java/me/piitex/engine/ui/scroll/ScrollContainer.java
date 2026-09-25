package me.piitex.engine.ui.scroll;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;

/**
 * A {@link Container} that clips its children to its own box and lets them be scrolled
 * within it, by mouse wheel ({@link Scrollable}), by dragging the scrollbar thumb, or
 * programmatically through {@link #scrollBy} and {@link #scrollTo}. Each axis has an
 * optional, independently styleable scrollbar (see {@link #setScrollBarStyle}).
 * Everything else about a Container, including styling, theming, and the click, render,
 * and update hooks, works the same, and children are added and positioned exactly as
 * with a plain Container.
 * <p>
 * Scrolling shifts the space the children live in ({@link #getContentOriginX()}), not the children: a
 * child's x/y stays where you put it, relative to this container, and the scroll offset is applied when it
 * is drawn and hit-tested. How far content CAN scroll is derived from the children's extent each time, so it
 * stays correct as children are added, removed or resized.
 * <p>
 * <b>Known gap:</b> the scrollbar is drawn as an overlay on the container's own right/
 * bottom edge rather than in a reserved gutter, so it floats on top of whatever content
 * is underneath rather than making room for itself. If a child's bounds overlap that
 * strip, the child, being a real hit-tested Element, wins clicks there over the
 * scrollbar thumb drawn on top of it. Leave that edge strip clear in the content's own
 * sizing, as with any overlay-style scrollbar.
 */
public class ScrollContainer extends Container implements Scrollable {

    private boolean verticalScrollEnabled = true;
    private boolean horizontalScrollEnabled = false;
    private float scrollSpeed = 40f;

    private double scrollOffsetX = 0;
    private double scrollOffsetY = 0;

    private boolean scrollbarVisible = true;
    private ScrollBarStyle scrollBarStyle = new ScrollBarStyle();

    private boolean draggingVerticalThumb = false;
    private double thumbDragAnchorMouseY, thumbDragAnchorOffsetY;

    private boolean draggingHorizontalThumb = false;
    private double thumbDragAnchorMouseX, thumbDragAnchorOffsetX;

    public ScrollContainer(double width, double height) {
        this(0, 0, width, height);
    }

    public ScrollContainer(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    /**
     * Whether the mouse wheel or trackpad scrolls this container vertically. On by default.
     */
    public void setVerticalScrollEnabled(boolean enabled) {
        this.verticalScrollEnabled = enabled;
    }

    public boolean isVerticalScrollEnabled() {
        return verticalScrollEnabled;
    }

    /**
     * Whether the mouse wheel or trackpad scrolls this container horizontally. Off by default, since most scrollable content, such as lists and forms, needs only vertical scrolling.
     */
    public void setHorizontalScrollEnabled(boolean enabled) {
        this.horizontalScrollEnabled = enabled;
    }

    public boolean isHorizontalScrollEnabled() {
        return horizontalScrollEnabled;
    }

    /**
     * Logical points scrolled per unit of raw wheel offset. See {@link Scrollable#onScroll}. Larger is faster.
     */
    public void setScrollSpeed(float scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
    }

    public float getScrollSpeed() {
        return scrollSpeed;
    }

    /**
     * Whether a scrollbar is drawn at all. Wheel and programmatic scrolling still work when this is false; there is just nothing to grab with the mouse. On by default.
     */
    public void setScrollbarVisible(boolean visible) {
        this.scrollbarVisible = visible;
    }

    public boolean isScrollbarVisible() {
        return scrollbarVisible;
    }

    /**
     * The colors, thickness, and corner radius used for both the vertical and horizontal scrollbar. Mutate it in place, or replace it wholesale. See {@link ScrollBarStyle}.
     */
    public ScrollBarStyle getScrollBarStyle() {
        return scrollBarStyle;
    }

    public void setScrollBarStyle(ScrollBarStyle scrollBarStyle) {
        this.scrollBarStyle = scrollBarStyle == null ? new ScrollBarStyle() : scrollBarStyle;
    }

    public double getScrollOffsetX() {
        return scrollOffsetX;
    }

    public double getScrollOffsetY() {
        return scrollOffsetY;
    }

    /**
     * How much further this can scroll right, in logical points, or 0 if the content's
     * right edge does not extend past the viewport's right edge. Children are positioned
     * relative to this container's top-left corner, so the content extends to the
     * rightmost child's {@code x + width}. It is not assumed to start flush with the left
     * edge, since padding or a header child belongs to the content too.
     */
    public double getMaxScrollX() {
        if (getElements().isEmpty()) return 0;
        double rightmostEdge = -Double.MAX_VALUE;
        for (Element child : getElements().values()) {
            rightmostEdge = Math.max(rightmostEdge, child.getX() + child.getWidth());
        }
        return Math.max(0, rightmostEdge - getWidth());
    }

    /**
     * How much further this can scroll down, in logical points. This is the vertical counterpart of {@link #getMaxScrollX()}.
     */
    public double getMaxScrollY() {
        if (getElements().isEmpty()) return 0;
        double bottomEdge = -Double.MAX_VALUE;
        for (Element child : getElements().values()) {
            bottomEdge = Math.max(bottomEdge, child.getY() + child.getHeight());
        }
        return Math.max(0, bottomEdge - getHeight());
    }

    /**
     * Scrolls by (dx, dy) logical points relative to the current offset, clamped to the content's range. This is the same calculation {@link #onScroll} and thumb-dragging use. It is a no-op on an axis not enabled through {@link #setVerticalScrollEnabled} or {@link #setHorizontalScrollEnabled}.
     */
    public void scrollBy(double dx, double dy) {
        scrollTo(scrollOffsetX + dx, scrollOffsetY + dy);
    }

    /**
     * Scrolls directly to (x, y), clamped to [0, {@link #getMaxScrollX()}]/[0, {@link #getMaxScrollY()}]. A no-op on an axis that's not enabled.
     */
    public void scrollTo(double x, double y) {
        if (horizontalScrollEnabled) setScrollOffsetX(x);
        if (verticalScrollEnabled) setScrollOffsetY(y);
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        // GLFW reports a positive yoffset for scrolling up, away from the user. Content
        // should move up, revealing what is below, for the opposite downward gesture,
        // hence the negation. deltaX, a horizontal swipe, maps directly with no flip.
        scrollBy(deltaX * scrollSpeed, -deltaY * scrollSpeed);
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        // Same direction mapping as onScroll: positive deltaY moves content down (offset decreases).
        if (verticalScrollEnabled && deltaY != 0) {
            if (deltaY > 0 ? scrollOffsetY > 0 : scrollOffsetY < getMaxScrollY()) return true;
        }
        if (horizontalScrollEnabled && deltaX != 0) {
            if (deltaX < 0 ? scrollOffsetX > 0 : scrollOffsetX < getMaxScrollX()) return true;
        }
        return false;
    }

    private void setScrollOffsetX(double target) {
        scrollOffsetX = Math.max(0, Math.min(target, getMaxScrollX()));
    }

    private void setScrollOffsetY(double target) {
        scrollOffsetY = Math.max(0, Math.min(target, getMaxScrollY()));
    }

    /**
     * Scrolling moves the children's space, not the children: it is applied when drawing and hit-testing.
     */
    @Override
    protected double childOffsetX() {
        return -scrollOffsetX;
    }

    @Override
    protected double childOffsetY() {
        return -scrollOffsetY;
    }

    /**
     * The total span the vertical thumb represents: everything scrollable plus one full viewport. This is used only for sizing the thumb proportionally; {@link #getMaxScrollY()} is the source of truth for how far this can scroll.
     */
    private double effectiveContentHeight() {
        return getMaxScrollY() + getHeight();
    }

    /**
     * The horizontal counterpart of {@link #effectiveContentHeight()}.
     */
    private double effectiveContentWidth() {
        return getMaxScrollX() + getWidth();
    }

    /**
     * How far each end of a scrollbar track sits from the ends of its edge: the style's margin, or more when this
     * Container's rounded corners would otherwise cut into the bar, so the bar stays inside the curve.
     */
    private double trackEndInset() {
        double margin = scrollBarStyle.getMargin();
        // Skia shrinks a radius larger than half the box the same way when it draws the rounded clip.
        double radius = Math.min(getStyling().getCornerRadius(), Math.min(getWidth(), getHeight()) / 2.0);
        if (margin >= radius) return margin;
        // The bar's outer edge runs margin in from the side, and clears the corner's arc this far along it.
        double dx = radius - margin;
        return Math.max(margin, radius - Math.sqrt(radius * radius - dx * dx));
    }

    // --- Vertical scrollbar geometry -------------------------------------------------------------

    private double verticalTrackX() {
        return getX() + getWidth() - scrollBarStyle.getThickness() - scrollBarStyle.getMargin();
    }

    private double verticalTrackY() {
        return getY() + trackEndInset();
    }

    private double verticalTrackHeight() {
        return Math.max(0, getHeight() - 2 * trackEndInset());
    }

    private double verticalThumbLength() {
        double trackHeight = verticalTrackHeight();
        double contentH = effectiveContentHeight();
        if (contentH <= 0) return trackHeight;
        double raw = trackHeight * (getHeight() / contentH);
        return Math.max(scrollBarStyle.getMinThumbLength(), Math.min(trackHeight, raw));
    }

    private double verticalThumbY() {
        double maxScroll = getMaxScrollY();
        double travel = verticalTrackHeight() - verticalThumbLength();
        if (maxScroll <= 0 || travel <= 0) return verticalTrackY();
        return verticalTrackY() + travel * (scrollOffsetY / maxScroll);
    }

    private boolean verticalThumbContains(double px, double py) {
        if (!verticalScrollEnabled || getMaxScrollY() <= 0) return false;
        double x = verticalTrackX(), y = verticalThumbY(), w = scrollBarStyle.getThickness(), h = verticalThumbLength();
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    // --- Horizontal scrollbar geometry -----------------------------------------------------------

    private double horizontalTrackX() {
        return getX() + trackEndInset();
    }

    private double horizontalTrackY() {
        return getY() + getHeight() - scrollBarStyle.getThickness() - scrollBarStyle.getMargin();
    }

    private double horizontalTrackWidth() {
        return Math.max(0, getWidth() - 2 * trackEndInset());
    }

    private double horizontalThumbLength() {
        double trackWidth = horizontalTrackWidth();
        double contentW = effectiveContentWidth();
        if (contentW <= 0) return trackWidth;
        double raw = trackWidth * (getWidth() / contentW);
        return Math.max(scrollBarStyle.getMinThumbLength(), Math.min(trackWidth, raw));
    }

    private double horizontalThumbX() {
        double maxScroll = getMaxScrollX();
        double travel = horizontalTrackWidth() - horizontalThumbLength();
        if (maxScroll <= 0 || travel <= 0) return horizontalTrackX();
        return horizontalTrackX() + travel * (scrollOffsetX / maxScroll);
    }

    private boolean horizontalThumbContains(double px, double py) {
        if (!horizontalScrollEnabled || getMaxScrollX() <= 0) return false;
        double x = horizontalThumbX(), y = horizontalTrackY(), w = horizontalThumbLength(), h = scrollBarStyle.getThickness();
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    // --- Thumb dragging --------------------------------------------------------------------------

    /**
     * Checks for a press on either scrollbar thumb first; falls back to {@link
     * Container}'s own drag handling (moving this whole Container around, if {@link
     * #setDraggable} was also turned on) for a press anywhere else.
     */
    /**
     * True over a scrollbar thumb, so a child overlapping it (e.g. a text block) doesn't take the press.
     */
    @Override
    public boolean interceptsPointer(double px, double py) {
        return thumbAt(px, py);
    }

    /**
     * A press on a thumb is this container's to handle even when it isn't otherwise draggable, so it isn't handed up to an ancestor.
     */
    @Override
    public boolean canDrag(double x, double y) {
        return thumbAt(x, y) || super.canDrag(x, y);
    }

    /**
     * Whether a scrollbar thumb is under the window point.
     */
    protected boolean thumbAt(double windowX, double windowY) {
        if (!scrollbarVisible) return false;
        double localX = toLocalX(windowX), localY = toLocalY(windowY);
        return verticalThumbContains(localX, localY) || horizontalThumbContains(localX, localY);
    }

    @Override
    public void onDragStart(double x, double y) {
        double localX = toLocalX(x), localY = toLocalY(y); // thumb geometry is in this container's space
        if (verticalThumbContains(localX, localY)) {
            draggingVerticalThumb = true;
            thumbDragAnchorMouseY = y;
            thumbDragAnchorOffsetY = scrollOffsetY;
            return;
        }
        if (horizontalThumbContains(localX, localY)) {
            draggingHorizontalThumb = true;
            thumbDragAnchorMouseX = x;
            thumbDragAnchorOffsetX = scrollOffsetX;
            return;
        }
        super.onDragStart(x, y);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (draggingVerticalThumb) {
            double travel = verticalTrackHeight() - verticalThumbLength();
            double ratio = travel > 0 ? (y - thumbDragAnchorMouseY) / travel : 0;
            setScrollOffsetY(thumbDragAnchorOffsetY + ratio * getMaxScrollY());
            return;
        }
        if (draggingHorizontalThumb) {
            double travel = horizontalTrackWidth() - horizontalThumbLength();
            double ratio = travel > 0 ? (x - thumbDragAnchorMouseX) / travel : 0;
            setScrollOffsetX(thumbDragAnchorOffsetX + ratio * getMaxScrollX());
            return;
        }
        super.onDragUpdate(x, y);
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (draggingVerticalThumb) {
            draggingVerticalThumb = false;
            return;
        }
        if (draggingHorizontalThumb) {
            draggingHorizontalThumb = false;
            return;
        }
        super.onDragEnd(x, y);
    }

    // --- Rendering -------------------------------------------------------------------------------

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        renderBackground(renderer);

        pushChildClip(renderer);
        renderChildren(renderer);
        renderer.popClip();

        if (scrollbarVisible) {
            renderScrollbars(renderer);
        }
    }

    private void renderScrollbars(Renderer renderer) {
        if (verticalScrollEnabled && getMaxScrollY() > 0) {
            drawBar(renderer,
                    verticalTrackX(), verticalTrackY(), scrollBarStyle.getThickness(), verticalTrackHeight(),
                    verticalTrackX(), verticalThumbY(), scrollBarStyle.getThickness(), verticalThumbLength(),
                    draggingVerticalThumb);
        }
        if (horizontalScrollEnabled && getMaxScrollX() > 0) {
            drawBar(renderer,
                    horizontalTrackX(), horizontalTrackY(), horizontalTrackWidth(), scrollBarStyle.getThickness(),
                    horizontalThumbX(), horizontalTrackY(), horizontalThumbLength(), scrollBarStyle.getThickness(),
                    draggingHorizontalThumb);
        }
    }

    private void drawBar(Renderer renderer, double tx, double ty, double tw, double th, double bx, double by, double bw, double bh, boolean active) {
        Color track = scrollBarStyle.getTrackColor();
        if (track != null) {
            renderer.drawQuad((float) tx, (float) ty, (float) tw, (float) th, track, scrollBarStyle.getCornerRadius());
        }
        Color thumb = active ? scrollBarStyle.getThumbActiveColor() : scrollBarStyle.getThumbColor();
        if (thumb != null) {
            renderer.drawQuad((float) bx, (float) by, (float) bw, (float) bh, thumb, scrollBarStyle.getCornerRadius());
        }
    }
}
