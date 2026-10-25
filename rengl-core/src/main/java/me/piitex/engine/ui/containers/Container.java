package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.Styling;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Houses child Elements, and is itself an Element: it has its own background and
 * border, and can be clicked, hovered, and animated. That is why it draws its own
 * background and border before its children in {@link #render}.
 * <p>
 * This class implements {@link Themeable} but does not register itself with
 * {@link ThemeManager} automatically, unlike
 * {@link me.piitex.engine.ui.overlays.TextFieldOverlay} and
 * {@link me.piitex.engine.ui.overlays.ButtonOverlay}. Most Containers exist purely to
 * group and position children, and are left at Element's own default, which is
 * transparent unless set otherwise. Forcing every Container into an opaque themed panel
 * would break that, most visibly for a full-window root Container meant to let
 * {@link me.piitex.engine.Window#setBackgroundColor} show through. Call
 * {@link #useTheme()} on a specific Container that should read as a themed surface or
 * panel.
 * <p>
 * It also implements {@link Draggable}, gated the same opt-in way as theming. See
 * {@link #setDraggable}.
 */
public class Container extends Element implements Themeable, Draggable {
    private final ConcurrentSkipListMap<Integer, Element> elements = new ConcurrentSkipListMap<>();

    private Container parent;
    private Window window;
    private me.piitex.engine.ui.PopupHost popupHost;

    private boolean draggable = false;
    private double dragAnchorMouseX, dragAnchorMouseY;
    private double dragAnchorX, dragAnchorY;

    private enum DragBoundsMode {AUTO, FIXED, PARENT, WINDOW, UNBOUNDED}

    private DragBoundsMode dragBoundsMode = DragBoundsMode.AUTO;
    private double dragBoundsX, dragBoundsY, dragBoundsWidth, dragBoundsHeight;

    private final List<Container> collideables = new ArrayList<>();

    private boolean clipping = true;

    private boolean usesTheme;
    private Look plain; // how it looked before it first used the theme, to go back to

    public Container(double width, double height) {
        this(0, 0, width, height);
    }

    public Container(double x, double y, double width, double height) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        // No hover feedback by default: hovering keeps showing whatever background the Container is given.
        getStyling().setHoverFollowsBackground(true);
    }

    /**
     * Adds {@code child} on top of the existing children. Its x/y are <b>relative to this container</b>:
     * {@code (0, 0)} is this container's top-left corner, wherever it currently sits or later moves to, so it
     * makes no difference whether the container is positioned yet. Use {@link Element#getAbsoluteX()} for the
     * position in the window.
     */
    public void addElement(Element child) {
        int nextIndex = elements.isEmpty() ? 0 : elements.lastKey() + 1;
        elements.put(nextIndex, child);
        adopt(child);
    }

    /**
     * Adds every element of {@code children} in order, each one on top of the previous. Same as calling
     * {@link #addElement(Element)} for each, including in layouts, split and tab containers that override it.
     * Null entries are skipped.
     */
    public void addElements(Collection<? extends Element> children) {
        for (Element child : children) {
            if (child != null) addElement(child);
        }
    }

    /**
     * As {@link #addElements(Collection)}: {@code container.addElements(title, field, button)}.
     */
    public void addElements(Element... children) {
        addElements(Arrays.asList(children));
    }

    /**
     * As {@link #addElement(Element)}, at a specific draw index, <b>replacing</b> whatever is there. To put a child at
     * a position and keep the others, use {@link #insertElement(Element, int)}.
     */
    public void addElement(Element child, int index) {
        elements.put(index, child);
        adopt(child);
    }

    /**
     * Puts {@code child} at draw position {@code index} and moves the children from there on up by one, so none is
     * lost. Index 0 puts it at the back, behind everything; an index past the last child, or any index in an empty
     * container, adds it on top, as {@link #addElement(Element)} does. A negative index is 0. In a layout the child
     * also takes that place in the row or column.
     * <p>
     * A container whose indices are fixed slots, such as a split, does not support this.
     *
     * @throws UnsupportedOperationException from a container that keeps its own numbering
     */
    public void insertElement(Element child, int index) {
        if (keepsIndices()) throw new UnsupportedOperationException("This container's indices are fixed slots; it cannot insert");
        if (elements.isEmpty() || index > elements.lastKey()) {
            addElement(child);
            return;
        }
        int at = Math.max(0, index);
        // From the top down, each child is copied to the next index before its own is overwritten, so a frame being
        // drawn on the render thread sees one twice at worst, never not at all.
        List<Map.Entry<Integer, Element>> shifting = new ArrayList<>(elements.tailMap(at, true).entrySet());
        for (int i = shifting.size() - 1; i >= 0; i--) {
            elements.put(shifting.get(i).getKey() + 1, shifting.get(i).getValue());
        }
        elements.put(at, child);
        adopt(child);
    }

    private void adopt(Element child) {
        child.owner = this;
        if (child instanceof Container childContainer) {
            childContainer.parent = this;
        }
        child.applyAnchor();
    }

    /**
     * Also moves the {@link Element#setAnchor anchored} children to their new places.
     */
    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        applyChildAnchors();
    }

    /**
     * Also moves the {@link Element#setAnchor anchored} children to their new places.
     */
    @Override
    public void setHeight(double height) {
        super.setHeight(height);
        applyChildAnchors();
    }

    private void applyChildAnchors() {
        for (Element element : elements.values()) {
            element.applyAnchor();
        }
    }

    /**
     * Removes {@code element} from this Container, wherever it sits, and detaches it (clears its owner/parent) so it can be re-added elsewhere. The children after it move down one index, so the indices stay 0..n-1 (except in a container that keeps fixed slots, like {@link SplitContainer}). Returns false if {@code element} was not a direct child.
     */
    public boolean removeElement(Element element) {
        if (element == null) return false;
        for (Map.Entry<Integer, Element> entry : elements.entrySet()) {
            if (entry.getValue() == element) {
                return removeElement(entry.getKey()) != null;
            }
        }
        return false;
    }

    /**
     * Removes and returns the child at {@code index}, or null if there isn't one. See {@link #removeElement(Element)}.
     */
    public Element removeElement(int index) {
        Element removed = elements.remove(index);
        if (removed != null) {
            removed.owner = null;
            if (removed instanceof Container childContainer) {
                childContainer.parent = null;
            }
            closeGaps();
            removed.onRemoved();
        }
        return removed;
    }

    /**
     * Also tells every child it was removed.
     */
    @Override
    public void onRemoved() {
        for (Element element : elements.values()) element.onRemoved();
    }

    /**
     * Whether the children's indices are fixed slots that a removal must leave alone. Plain containers and layouts
     * number their children 0..n-1 in draw order and close up after a removal; a container that gives indices a
     * meaning of their own (a split's two panes and its divider) overrides this to return true.
     */
    protected boolean keepsIndices() {
        return false;
    }

    /** Renumbers the children 0..n-1 in draw order. Each one is copied down before its old index is dropped, so a frame
     * being drawn on the render thread sees it twice at worst, never not at all. */
    private void closeGaps() {
        if (keepsIndices()) return;
        int next = 0;
        for (Map.Entry<Integer, Element> entry : elements.entrySet()) {
            int key = entry.getKey();
            if (key != next) {
                elements.put(next, entry.getValue());
                elements.remove(key);
            }
            next++;
        }
    }


    public ConcurrentSkipListMap<Integer, Element> getElements() {
        return elements;
    }

    /**
     * The Container this one was added into via {@link #addElement}, or null if it's a top-level Window container (or hasn't been added anywhere yet). Used by {@link #bringToFront()} and {@link #setDragBoundsToParent()}.
     */
    public Container getParent() {
        return parent;
    }

    /**
     * Called by {@link Window#addContainer} so a top-level Container, and transitively through {@link #resolveWindow()} everything nested inside it, can resolve the Window's current size for the default drag-to-window-edges clamp and for {@link #setDragBoundsToWindow()}. Not meant to be called directly.
     */
    public void setWindow(Window window) {
        this.window = window;
    }

    /**
     * Walks up {@link #getParent()} to the top-level Container and returns the Window it was added to, or null if this subtree isn't on a Window (yet).
     */
    public Window resolveWindow() {
        Container root = this;
        while (root.parent != null) {
            root = root.parent;
        }
        return root.window;
    }

    /**
     * Makes {@code host} the place the popups of everything in this top-level Container open, in place of a Window: a screen in
     * a 3D scene is one. Not meant to be called directly; {@code UiScreen} does it.
     */
    public void setPopupHost(me.piitex.engine.ui.PopupHost host) {
        this.popupHost = host;
    }

    /**
     * Walks up to the top-level Container and returns where its popups open: the host it was given with
     * {@link #setPopupHost}, else the Window it was added to, or null if neither (yet).
     */
    public me.piitex.engine.ui.PopupHost resolvePopupHost() {
        Container root = this;
        while (root.parent != null) {
            root = root.parent;
        }
        return root.popupHost != null ? root.popupHost : root.window;
    }

    /**
     * Opts this Container into {@link ThemeManager}-driven styling: applies the
     * current {@link Theme}'s container tokens as a baseline, via {@link
     * #applyTheme}, and keeps re-applying them on every future {@link
     * ThemeManager#setCurrent}. Only the background, hover, border, thickness, and
     * corner radius that have not been set explicitly are affected. Call this before any
     * one-off customization that should stick permanently: a property customized
     * afterward, through {@link #getStyling()}'s setters, is pinned against every future
     * theme switch rather than overwritten by it. See
     * {@link me.piitex.engine.ui.theme.Overridable} for the exact rule. This is not
     * called automatically; the class docs explain why.
     */
    public void useTheme() {
        useTheme(true);
    }

    /**
     * {@link #useTheme()} with {@code true}, or with {@code false} stops following the theme: this Container goes back
     * to the plain look it had before it first used one, transparent unless set otherwise, and later theme changes
     * pass it by. Anything set explicitly is left as it was.
     */
    public void useTheme(boolean use) {
        if (use) {
            if (plain == null) plain = Look.of(getStyling());
            usesTheme = true;
            ThemeManager.register(this);
        } else if (usesTheme) {
            usesTheme = false;
            ThemeManager.unregister(this);
            if (plain != null) plain.restore(getStyling());
            applyThemeEffect(null, null);
        }
    }

    /**
     * Whether this Container follows the theme, as {@link #useTheme()} makes it.
     */
    public boolean isUsingTheme() {
        return usesTheme;
    }

    /** What a container's styling gives a theme to restore. */
    private record Look(Paint background, Paint hover, boolean hoverFollowsBackground, Paint border, float thickness, float radius) {
        static Look of(Styling styling) {
            return new Look(styling.getBackgroundColor(), styling.getHoverColor(), styling.isHoverFollowsBackground(),
                    styling.getBorderColor(), styling.getBorderThickness(), styling.getCornerRadius());
        }

        void restore(Styling styling) {
            styling.applyThemeBackgroundColor(background);
            styling.applyThemeHoverColor(hover);
            if (hoverFollowsBackground) styling.setHoverFollowsBackground(true);
            styling.applyThemeBorderColor(border);
            styling.applyThemeBorderThickness(thickness);
            styling.applyThemeCornerRadius(radius);
        }
    }

    /**
     * Applies {@code theme}'s container tokens (background, border, corner radius) to
     * this Container's box. Only takes effect once this Container has opted in via
     * {@link #useTheme()}. The hover color is set to match the background, so a themed
     * panel has no built-in hover feedback the way a button does, and a click listener
     * on it does not flash back to whatever hover color it had before.
     */
    @Override
    public void applyTheme(Theme theme) {
        Paint fill = Styling.ownCopy(theme.getContainerBackground()); // one copy for both, so it is only advanced once
        getStyling().applyThemeBackgroundColor(fill);
        getStyling().applyThemeHoverColor(fill);
        getStyling().applyThemeBorderColor(theme.getContainerBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        applyThemeEffect(theme.getContainerEffect(), theme.assetBase());
        if (this instanceof ScrollContainer scrollContainer) {
            scrollContainer.getScrollBarStyle().applyThemeThumbColor(theme.getScrollThumb());
            scrollContainer.getScrollBarStyle().applyThemeTrackColor(theme.getScrollTrack());
        }
    }

    /**
     * Whether this Container can be dragged by the mouse. Off by default, so an
     * ordinary layout-grouping Container, which is most of them, does not start moving
     * because the user clicked and dragged across its empty background.
     * {@link me.piitex.engine.input.InputProcessor} gives a left-button press to the nearest
     * {@link Draggable} at or above the Element under the cursor that wants it. A press on
     * this Container's own area, on a plain child that is not Draggable (a label, an image),
     * or on a child Container that does not want the drag is therefore handed up to this
     * Container. A child that takes the drag itself, such as a text field or slider, keeps
     * it, and so does anything with a click listener (a button, a clickable row): pressing
     * it clicks it without moving this Container.
     * <p>
     * By default, a draggable Container also cannot be dragged past the Window's own
     * edges. Each edge is clamped independently, so dragging purely left stops when the
     * Container's left edge reaches the Window's left edge without also capping how far
     * it can move vertically. Call {@link #setDragBounds} or
     * {@link #setDragBoundsToParent()} to confine it to something else, or
     * {@link #clearDragBounds()} to allow dragging it off-screen entirely.
     * <p>
     * Separately, see {@link #addCollideable} for making a draggable Container also
     * stop against specific other Containers, not just the Window's edges.
     */
    public void setDraggable(boolean draggable) {
        this.draggable = draggable;
    }

    public boolean isDraggable() {
        return draggable;
    }

    /**
     * A plain Container only takes a drag if it opted in via {@link #setDraggable}; otherwise the press is handed up to its owner.
     */
    @Override
    public boolean canDrag(double x, double y) {
        return draggable;
    }

    @Override
    public void onDragStart(double x, double y) {
        if (!draggable) return;
        bringToFront();
        dragAnchorMouseX = x;
        dragAnchorMouseY = y;
        dragAnchorX = getX();
        dragAnchorY = getY();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (!draggable) return;
        moveTo(dragAnchorX + (x - dragAnchorMouseX), dragAnchorY + (y - dragAnchorMouseY));
    }

    @Override
    public void onDragEnd(double x, double y) {
        // No-op: the position is already committed live, on every onDragUpdate.
    }

    /**
     * Confines where this Container can be moved to (via {@link #moveTo}, including by
     * dragging). The rectangle (x, y, width, height) is the area its own box must stay
     * fully inside, in the same space as {@link #getX()}, meaning relative to the
     * container this one is in. This overrides the automatic window-edge clamp a
     * draggable Container gets by default (see {@link #setDraggable}). See
     * {@link #setDragBoundsToParent()} for the common case of confining a Container to
     * whatever it was added into.
     */
    public void setDragBounds(double x, double y, double width, double height) {
        dragBoundsMode = DragBoundsMode.FIXED;
        dragBoundsX = x;
        dragBoundsY = y;
        dragBoundsWidth = width;
        dragBoundsHeight = height;
    }

    /**
     * Confines this Container to {@link #getParent()}'s box instead of the Window's. The
     * parent's box is read live on every {@link #moveTo} rather than captured once, so
     * this keeps working if the parent is later resized or moved, and it can be called
     * either before or after adding this Container to that parent. While there is no
     * parent, such as on a top-level Window container or before this Container has been
     * added anywhere, it falls back to the default window-edge clamp.
     */
    public void setDragBoundsToParent() {
        dragBoundsMode = DragBoundsMode.PARENT;
    }

    /**
     * Confines this Container to the Window's own box. The size is read live from
     * {@link Window#getWindowOptions()} on every {@link #moveTo}, so it keeps working
     * through a resize. This already happens automatically for a draggable Container
     * (see {@link #setDraggable}); call it explicitly only to get the same clamping on a
     * Container that is not draggable but is still moved through {@link #moveTo} some
     * other way. It has no effect until this Container, or an ancestor, is on a Window.
     * See {@link #resolveWindow()}.
     */
    public void setDragBoundsToWindow() {
        dragBoundsMode = DragBoundsMode.WINDOW;
    }

    /**
     * Removes every bound, including the automatic window-edge clamp a draggable Container gets by default, so {@link #moveTo} places this Container exactly where asked, off-screen included.
     */
    public void clearDragBounds() {
        dragBoundsMode = DragBoundsMode.UNBOUNDED;
    }

    /**
     * Registers {@code other} as a solid obstacle {@link #moveTo} (including by
     * dragging) refuses to move this Container into. The relationship is one-directional:
     * this Container collides with {@code other}, but not the reverse, unless
     * {@code other.addCollideable(this)} is also called. Collision is a property of the
     * Container being moved, checked against whatever it has been told to watch out for,
     * rather than a mutual agreement between the two. This matches the way the Window's
     * own edges always block a draggable Container regardless of any flag on the Window.
     * A no-op for {@code null} or {@code this}, and adding the same Container twice does
     * not add it a second time.
     * <p>
     * A Container with nothing registered here, the default, can be dragged straight
     * through anything. There is no separate on/off flag; an empty list never blocks a
     * move.
     */
    public void addCollideable(Container other) {
        if (other != null && other != this && !collideables.contains(other)) {
            collideables.add(other);
        }
    }

    /**
     * Unregisters {@code other}. See {@link #addCollideable}. A no-op if it was not registered.
     */
    public void removeCollideable(Container other) {
        collideables.remove(other);
    }

    /**
     * Unregisters every Container added through {@link #addCollideable}, so {@link #moveTo} ignores collision entirely again.
     */
    public void clearCollideables() {
        collideables.clear();
    }

    /**
     * The Containers this one collides with. See {@link #addCollideable}. This is the live list, not a copy.
     */
    public List<Container> getCollideables() {
        return collideables;
    }

    /**
     * Whether children are clipped to this Container's own box. On by default, so a
     * child positioned, dragged, or scrolled outside its parent's bounds does not draw
     * past the edge and spill over what is behind and around it. Set it false to let
     * children render unclipped, for instance an overlay or badge meant to hang outside
     * its parent's box.
     * <p>
     * {@link me.piitex.engine.ui.scroll.ScrollContainer} always clips regardless of this
     * flag, since clipping to the viewport is what makes it scrollable, so this setting
     * has no effect there.
     */
    public void setClipping(boolean clipping) {
        this.clipping = clipping;
    }

    public boolean isClipping() {
        return clipping;
    }

    /**
     * Whether this Container claims a pointer press/hover at (px, py) even though one of its children also
     * contains that point, so an edge or corner grip that overlaps its children still receives the mouse.
     * {@link me.piitex.engine.input.InputProcessor} asks before descending into the children. Never true by
     * default; see {@link ResizableContainer#setUserResizable}.
     */
    public boolean interceptsPointer(double px, double py) {
        return false;
    }

    /**
     * Whether a pointer press, hover or wheel at (px, py), where no child is, falls through to whatever is drawn
     * below this Container. True for a container that shows nothing and listens for nothing there: no visible
     * background or border, no {@link me.piitex.engine.ui.Element#setBackground background effect}, no click listener,
     * context menu, cursor or tooltip, and neither draggable nor scrollable. That is what lets a transparent
     * full-window root sit above another container without swallowing its clicks.
     */
    public boolean isPointerPassthrough(double px, double py) {
        if (this instanceof me.piitex.engine.ui.scroll.Scrollable || canDrag(px, py)) return false;
        if (getMouseClickListenerConsumer() != null || getContextMenu() != null || getCursorMode() != null
                || getTooltip() != null || getBackground() != null) return false;
        Styling s = getStyling();
        return !isVisible(s.getBackgroundColor()) && !(s.getBorderThickness() > 0f && isVisible(s.getBorderColor()));
    }

    private static boolean isVisible(Paint paint) {
        return paint != null && !(paint instanceof me.piitex.engine.ui.color.Color c && c.getA() <= 0f);
    }

    /**
     * Clamps a proposed X move (window space) against every obstacle that currently overlaps this
     * Container on the Y axis (checked at {@code y}). The move stops flush against the
     * nearest obstacle in the direction of travel rather than being blocked outright, so
     * a Container can slide along an obstacle's edge. Returns {@code proposedX}
     * unchanged if there is no movement on X.
     */
    private double clampAxisX(double proposedX, double y) {
        double currentX = getAbsoluteX();
        if (proposedX == currentX) return proposedX;

        double width = getWidth();
        double top = y, bottom = y + getHeight();

        if (proposedX > currentX) { // moving right
            double limit = Double.MAX_VALUE;
            for (Container o : collideables) {
                double oy = o.getAbsoluteY(), ox = o.getAbsoluteX();
                if (oy >= bottom || oy + o.getHeight() <= top) continue; // no vertical overlap
                if (ox >= currentX + width) { // ahead of (or touching) where this already is
                    limit = Math.min(limit, ox - width);
                }
            }
            return Math.min(proposedX, limit);
        } else { // moving left
            double limit = -Double.MAX_VALUE;
            for (Container o : collideables) {
                double oy = o.getAbsoluteY();
                if (oy >= bottom || oy + o.getHeight() <= top) continue;
                double obstacleRight = o.getAbsoluteX() + o.getWidth();
                if (obstacleRight <= currentX) { // behind (or touching) where this already is
                    limit = Math.max(limit, obstacleRight);
                }
            }
            return Math.max(proposedX, limit);
        }
    }

    /**
     * The vertical counterpart of {@link #clampAxisX}.
     */
    private double clampAxisY(double x, double proposedY) {
        double currentY = getAbsoluteY();
        if (proposedY == currentY) return proposedY;

        double height = getHeight();
        double left = x, right = x + getWidth();

        if (proposedY > currentY) { // moving down
            double limit = Double.MAX_VALUE;
            for (Container o : collideables) {
                double ox = o.getAbsoluteX(), oy = o.getAbsoluteY();
                if (ox >= right || ox + o.getWidth() <= left) continue; // no horizontal overlap
                if (oy >= currentY + height) {
                    limit = Math.min(limit, oy - height);
                }
            }
            return Math.min(proposedY, limit);
        } else { // moving up
            double limit = -Double.MAX_VALUE;
            for (Container o : collideables) {
                double ox = o.getAbsoluteX();
                if (ox >= right || ox + o.getWidth() <= left) continue;
                double obstacleBottom = o.getAbsoluteY() + o.getHeight();
                if (obstacleBottom <= currentY) {
                    limit = Math.max(limit, obstacleBottom);
                }
            }
            return Math.max(proposedY, limit);
        }
    }

    /**
     * Moves this Container to (x, y), in the same space as {@link #getX()}, meaning
     * relative to the container it is in. The position is clamped per
     * {@link #setDragBounds}, {@link #setDragBoundsToParent()}, or
     * {@link #setDragBoundsToWindow()} if any of those were called, or automatically to
     * the Window's own edges if this Container is draggable and none of them were (see
     * {@link #setDraggable}). Everything inside it moves along, since children are
     * positioned relative to their container. Dragging uses this, and it is equally the
     * right tool for moving a Container programmatically, such as from an onUpdate hook.
     */
    public void moveTo(double x, double y) {
        // The clamps work in window space; x, y and the result are in this container's own space.
        double originX = getAbsoluteX() - getX(), originY = getAbsoluteY() - getY();
        x += originX;
        y += originY;

        double boundsX = originX + dragBoundsX, boundsY = originY + dragBoundsY;
        double boundsWidth = dragBoundsWidth, boundsHeight = dragBoundsHeight;
        boolean clamp = false;

        DragBoundsMode effectiveMode = dragBoundsMode;
        if (effectiveMode == DragBoundsMode.AUTO) {
            // No bounds explicitly chosen. A draggable Container defaults to the
            // Window's edges; anything else, such as a Container only ever moved
            // programmatically, stays unclamped.
            effectiveMode = draggable ? DragBoundsMode.WINDOW : DragBoundsMode.UNBOUNDED;
        }

        if (effectiveMode == DragBoundsMode.FIXED) {
            clamp = true;
        } else if (effectiveMode == DragBoundsMode.PARENT && parent != null) {
            boundsX = parent.getAbsoluteX();
            boundsY = parent.getAbsoluteY();
            boundsWidth = parent.getWidth();
            boundsHeight = parent.getHeight();
            clamp = true;
        } else if (effectiveMode == DragBoundsMode.WINDOW) {
            Window win = resolveWindow();
            if (win != null) {
                boundsX = 0;
                boundsY = 0;
                boundsWidth = win.getWindowOptions().getWidth();
                boundsHeight = win.getWindowOptions().getHeight();
                clamp = true;
            }
        }

        if (clamp) {
            double maxX = Math.max(boundsX, boundsX + boundsWidth - getWidth());
            double maxY = Math.max(boundsY, boundsY + boundsHeight - getHeight());
            x = Math.max(boundsX, Math.min(x, maxX));
            y = Math.max(boundsY, Math.min(y, maxY));
        }

        if (!collideables.isEmpty()) {
            // X resolves first against the CURRENT y, then Y resolves against the
            // now-resolved x, so a diagonal move slides along whichever axis is not
            // blocked instead of the two checks contradicting each other.
            x = clampAxisX(x, getAbsoluteY());
            y = clampAxisY(x, y);
        }

        setX(x - originX);
        setY(y - originY);
    }

    /**
     * Where this container's children's coordinate space starts, in the window: this container's own position
     * plus {@link #childOffsetX()}. What {@link Element#getAbsoluteX()} adds for an element inside it.
     */
    public double getContentOriginX() {
        return getAbsoluteX() + childOffsetX();
    }

    /**
     * As {@link #getContentOriginX()}, for y.
     */
    public double getContentOriginY() {
        return getAbsoluteY() + childOffsetY();
    }

    /**
     * How far this container shifts its children's space from its own top-left, horizontally. 0, except for a scrolled container.
     */
    protected double childOffsetX() {
        return 0;
    }

    /**
     * As {@link #childOffsetX()}, for y.
     */
    protected double childOffsetY() {
        return 0;
    }

    /**
     * Reorders this Container to draw last (i.e. on top) among its siblings in {@link
     * #getParent()}. A no-op if it has no parent, such as a top-level Window container,
     * or if it is already frontmost. Called automatically from {@link #onDragStart} so
     * picking up an overlapping draggable panel raises it above the others, the way a
     * window manager would. Call it directly to get the same behavior on a plain click
     * instead of a drag. Layouts whose child order sets positions, such as {@link
     * me.piitex.engine.ui.layout.VerticalLayout}, ignore it.
     */
    public void bringToFront() {
        if (parent != null) {
            parent.moveChildToFront(this);
        }
    }

    /**
     * Moves {@code child} to the highest draw index. Called by {@link #bringToFront()} on the child's parent.
     */
    protected void moveChildToFront(Element child) {
        if (elements.isEmpty() || elements.lastEntry().getValue() == child) return;

        Integer key = null;
        for (Map.Entry<Integer, Element> entry : elements.entrySet()) {
            if (entry.getValue() == child) {
                key = entry.getKey();
                break;
            }
        }
        if (key == null) return;

        elements.remove(key);
        int frontKey = elements.isEmpty() ? 0 : elements.lastKey() + 1;
        elements.put(frontKey, child);
        closeGaps();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        renderBackground(renderer);

        if (clipping) {
            pushChildClip(renderer);
            renderChildren(renderer);
            renderer.popClip();
        } else {
            renderChildren(renderer);
        }
    }

    /**
     * Clips what is drawn next to this Container's box, rounded by its {@link me.piitex.engine.ui.color.Styling#getCornerRadius() corner
     * radius} so children stay inside rounded corners. Pair it with exactly one {@link Renderer#popClip()}.
     */
    protected void pushChildClip(Renderer renderer) {
        float radius = getStyling().getCornerRadius();
        if (radius > 0f) {
            renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), radius);
        } else {
            renderer.pushClip((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight());
        }
    }

    /**
     * Draws just this Container's own background and border, which is {@link Element#render}'s half of {@link #render}. It is split out rather than inlined so {@link me.piitex.engine.ui.scroll.ScrollContainer} can insert a clip between this and {@link #renderChildren}.
     */
    protected void renderBackground(Renderer renderer) {
        super.render(renderer);
    }

    /**
     * Draws every child, in z-order. This is the other half of {@link #render}. See {@link #renderBackground}.
     */
    protected void renderChildren(Renderer renderer) {
        // Children are drawn in this container's own space: shift the canvas to where it is.
        renderer.pushTranslate((float) (getX() + childOffsetX()), (float) (getY() + childOffsetY()));
        for (Element child : elements.values()) {
            if (child.isEnabled()) {
                child.renderWithTransitions(renderer);
            }
        }
        renderer.popTranslate();
    }

    @Override
    public void update(float delta) {
        super.update(delta);

        for (Element element : elements.values()) {
            element.applyAnchor(); // an anchored child may have been resized by something that doesn't say so
            element.update(delta);
        }
    }
}