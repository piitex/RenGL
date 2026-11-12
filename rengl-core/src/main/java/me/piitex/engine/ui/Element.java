package me.piitex.engine.ui;

import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.animation.FadeTransition;
import me.piitex.engine.ui.animation.Transition;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.graphics.ShaderEffect;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.BackgroundKinds;
import me.piitex.engine.ui.background.ConstellationBackground;
import me.piitex.engine.ui.background.DotGridBackground;
import me.piitex.engine.ui.color.*;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.events.ElementRenderEvent;
import me.piitex.engine.ui.events.ElementUpdateEvent;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.text.TooltipManager;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import me.piitex.engine.utils.FormID;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.utils.FormIDRegistry;
import me.piitex.engine.utils.Namespace;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base class for everything the engine can draw and hit-test: a rectangular region
 * with position, size, styling, and hooks for input/render/update events.
 * {@link Container} extends this to also hold child Elements,
 * which is why every Element is itself drawable even though most concrete Elements
 * (e.g. {@link me.piitex.engine.ui.overlays.TextOverlay}) add their own visual on
 * top of what this base class already draws.
 * <p>
 * x/y is always the top-left corner, in the same logical-point coordinate space the
 * {@link Renderer} interface uses. See that interface's class-level docs for the
 * coordinate and threading contract that applies to everything drawn here.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Element {
    private final FormID id;
    private int suggestedDrawIndex;
    private int drawIndex;
    private boolean enabled = true;
    private double x, y, width, height;
    // Set by setAnchor; x and y then follow the container's size. See applyAnchor.
    private Anchor anchor, pivot;
    private double anchorOffsetX, anchorOffsetY, anchorMargin = -1;
    // Set by setRelativeSize: width and height follow the container's size. NaN is off. See applyRelativeSize.
    private double relativeWidth = Double.NaN, relativeHeight = Double.NaN, relativeInset;
    private double layoutWeight;
    private boolean hovered;
    private List<Consumer<Theme>> themeStyles; // see onTheme
    private Themeable themeStylist;            // held here so the theme registry (weak) keeps it as long as this Element lives
    private Layout.Alignment textAlignment;
    private CursorMode cursorMode;
    private boolean focusTraversable = false;
    private int tabIndex = -1;
    private final Overridable<Background> background = new Overridable<>(null);
    private Background themeEffect; // the one a theme made, kept so a later theme change can update it instead of starting over
    private Styling styling = new Styling();
    private final List<Transition> transitions = new ArrayList<>();
    private final Map<Transition, Runnable> transitionAfter = new java.util.IdentityHashMap<>();
    private float opacity = 1f;
    // Drawn scaled around scaleOriginX/Y, in the same space as x and y. NaN is the Element's center.
    private float scale = 1f;
    private double scaleOriginX = Double.NaN, scaleOriginY = Double.NaN;
    private ShaderEffect effect;
    private String tooltip;
    private PopupMenu contextMenu;
    /**
     * Set by {@link Container#addElement}; lets an Element find its Window without a back-reference from callers.
     */
    public Container owner;
    private float tooltipDelay = 0.5f;
    private Theme tooltipTheme;

    // Event handling
    private Consumer<MouseClickEvent> mouseClickListenerConsumer;
    private boolean disabled;
    private float disabledOpacity = DEFAULT_DISABLED_OPACITY;
    private Runnable onDisabledClick;
    private Consumer<MouseClickEvent> builtInClickListener;
    private Consumer<MouseClickEvent> combinedClickListener;
    private boolean clickOnRelease;
    private boolean propagateClicks;
    private boolean dragsContainer;
    private Consumer<ElementRenderEvent> elementRenderEventConsumer;
    private Consumer<ElementUpdateEvent> elementUpdateEventConsumer;

    public static final FormIDRegistry ENGINE_REGISTRY = new FormIDRegistry(Namespace.ELEMENT);

    /**
     * Registers this Element with the engine-wide ID registry, generating a unique {@link FormID} keyed by its fully qualified class. See {@link FormIDRegistry#generate(Class)} for why the fully qualified name is used rather than a bare simple name.
     */
    public Element() {
        this.id = ENGINE_REGISTRY.generate(this.getClass());
    }

    /**
     * This Element's engine-assigned identifier, unique among all Elements of the same concrete type.
     */
    public FormID getId() {
        return id;
    }

    /**
     * The actual z-order index this Element was last drawn at, as assigned by its
     * parent Container. Read-only from outside the package; use
     * {@link #getSuggestedDrawIndex()} or {@code Container.addElement(int, Element)} to
     * influence draw order rather than setting this directly.
     */
    public int getDrawIndex() {
        return drawIndex;
    }

    /**
     * Sets the actual draw-order index. Package-private by design: only a parent Container assigns this, at the point where it places the Element.
     */
    protected void setDrawIndex(int drawIndex) {
        this.drawIndex = drawIndex;
    }

    /**
     * The draw-order hint this Element was constructed or last configured with; a parent Container is free to use, ignore, or reassign this when actually placing the Element.
     */
    public int getSuggestedDrawIndex() {
        return suggestedDrawIndex;
    }

    /**
     * Sets the draw-order hint a parent Container should prefer when this Element is added. This does not change {@link #getDrawIndex()}, which is only assigned once a Container places the Element.
     */
    public void setSuggestedDrawIndex(int suggestedDrawIndex) {
        this.suggestedDrawIndex = suggestedDrawIndex;
    }

    /**
     * Whether this Element renders, updates, and, for Containers, propagates those calls to its children. A disabled Element is fully inert: {@link #render} and {@link #update} both return immediately.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables this Element. See {@link #isEnabled()}.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * How much of itself a {@link #setDisabled disabled} element draws, unless {@link #setDisabledOpacity} says otherwise.
     */
    public static final float DEFAULT_DISABLED_OPACITY = 0.45f;

    /**
     * Shows this element as unavailable: drawn faded, with no hover color, a "not allowed" cursor, and it takes no
     * clicks, drags or keyboard focus. A disabled {@link me.piitex.engine.ui.containers.Container} disables everything
     * inside it. The element stays visible and keeps its {@link #setTooltip tooltip}, so the tooltip can say why. This
     * is different from {@link #setEnabled}, which hides an element and removes it from input altogether.
     * <p>
     * A click on a disabled element runs {@link #onDisabledClick}, if there is one.
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /**
     * Whether {@link #setDisabled} was called on this element. Use {@link #isEffectivelyDisabled()} to include a
     * disabled container above it.
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Whether this element, or any container it is inside, is {@link #setDisabled disabled}.
     */
    public boolean isEffectivelyDisabled() {
        for (Element e = this; e != null; e = e.owner) {
            if (e.disabled) return true;
        }
        return false;
    }

    /**
     * Registers what a click on this element does while it is {@link #setDisabled disabled}, such as telling the person
     * why nothing happened. Only one is held; null removes it. A disabled container's handler also runs for a click on
     * a child of it that has none of its own.
     */
    public void onDisabledClick(Runnable onDisabledClick) {
        this.onDisabledClick = onDisabledClick;
    }

    /**
     * The nearest element at or above this one that is {@link #setDisabled disabled}, or null if none is.
     */
    public Element getDisablingElement() {
        for (Element e = this; e != null; e = e.owner) {
            if (e.disabled) return e;
        }
        return null;
    }

    /**
     * Runs the {@link #onDisabledClick} handler, if there is one.
     */
    public void fireDisabledClick() {
        if (onDisabledClick != null) onDisabledClick.run();
    }

    /**
     * How much of itself a {@link #setDisabled disabled} element draws, from 0 (nothing) to 1 (no change).
     * {@link #DEFAULT_DISABLED_OPACITY} by default. This is separate from {@link #setOpacity}, so a fade and a disabled
     * element combine.
     */
    public void setDisabledOpacity(float opacity) {
        this.disabledOpacity = Math.clamp(opacity, 0f, 1f);
    }

    public float getDisabledOpacity() {
        return disabledOpacity;
    }

    /**
     * The top-left corner's x coordinate, in logical points, <b>relative to the container this element is in</b>
     * (its top-left corner, and for a {@link me.piitex.engine.ui.scroll.ScrollContainer} the scrolled content
     * origin). For an element added straight to a {@link me.piitex.engine.Window} that is the window. Moving a
     * container moves everything inside it without touching their own x/y. Use {@link #getAbsoluteX()} for the
     * position in the window. For an {@link #setAnchor anchored} element it is where the anchor puts it.
     */
    public double getX() {
        return x;
    }

    /**
     * Moves the left edge to {@code x}, relative to the container. This drops any {@link #setAnchor anchor}, so the
     * element stays where it is put.
     */
    public void setX(double x) {
        this.x = x;
        anchor = null;
    }

    /**
     * The top-left corner's y coordinate, in logical points, relative to its container. See {@link #getX()}.
     */
    public double getY() {
        return y;
    }

    /**
     * The x coordinate in the window: {@link #getX()} plus the position of every container above this element.
     * Walks up the containers each call, so read it once if you need it repeatedly.
     */
    public double getAbsoluteX() {
        return x + (owner != null ? owner.getContentOriginX() : 0);
    }

    /**
     * The y coordinate in the window. See {@link #getAbsoluteX()}.
     */
    public double getAbsoluteY() {
        return y + (owner != null ? owner.getContentOriginY() : 0);
    }

    /**
     * Converts a window x coordinate (a mouse position, say) into the space {@link #getX()} lives in, so it can
     * be compared with this element's own geometry.
     */
    public double toLocalX(double windowX) {
        return windowX - (getAbsoluteX() - x);
    }

    /**
     * As {@link #toLocalX(double)}, for y.
     */
    public double toLocalY(double windowY) {
        return windowY - (getAbsoluteY() - y);
    }

    /**
     * Moves the top edge to {@code y}, relative to the container. Drops any {@link #setAnchor anchor}, as
     * {@link #setX} does.
     */
    public void setY(double y) {
        this.y = y;
        anchor = null;
    }

    /**
     * Convenience for setting x and y together in one call, e.g. when moving an Element as part of an animation.
     * Drops any {@link #setAnchor anchor}, as {@link #setX} does.
     */
    public void setPosition(double x, double y) {
        this.x = x;
        this.y = y;
        anchor = null;
    }

    /**
     * Keeps this element at the same place in its container however the container is resized, with no
     * {@code onResize} code: {@code button.setAnchor(Anchor.BOTTOM_RIGHT, -16, -16)} keeps a button 16 points in
     * from the container's bottom-right corner. The element's own point at {@code anchor} is put on the container's
     * point at {@code anchor}, so {@link Anchor#CENTER} centers it and {@link Anchor#TOP_RIGHT} puts its top-right
     * corner in the container's top-right corner. The offsets then move it, in points: positive is right and down.
     * <p>
     * The position is worked out right away, again whenever the container or this element is resized, and every
     * frame. It is relative to the container's box, so it has no effect on an element with no container, such as a
     * container added straight to the {@link me.piitex.engine.Window}, nor inside a
     * {@link me.piitex.engine.ui.layout.Layout}, which places its children itself. Setting the position by hand
     * ({@link #setX}, {@link #setY}, {@link #setPosition}, {@link #setBounds}, or dragging it) drops the anchor. So
     * does passing null here.
     */
    public void setAnchor(Anchor anchor, double offsetX, double offsetY) {
        setAnchor(anchor, anchor, offsetX, offsetY);
    }

    /**
     * {@link #setAnchor(Anchor, double, double)} with no offset.
     */
    public void setAnchor(Anchor anchor) {
        setAnchor(anchor, anchor, 0, 0);
    }

    /**
     * As {@link #setAnchor(Anchor, double, double)}, but puts this element's point at {@code pivot} on the
     * container's point at {@code anchor}, rather than the same point on both.
     * {@code setAnchor(Anchor.at(0.5, 0.3), Anchor.TOP_CENTER, 0, 0)} centers the element across, with its top a
     * 30% of the way down.
     */
    public void setAnchor(Anchor anchor, Anchor pivot, double offsetX, double offsetY) {
        this.anchor = anchor;
        this.pivot = pivot == null ? anchor : pivot;
        this.anchorOffsetX = offsetX;
        this.anchorOffsetY = offsetY;
        applyAnchor();
    }

    /**
     * The point in the container this element is anchored to, or null if it is not anchored. See
     * {@link #setAnchor(Anchor, Anchor, double, double)}.
     */
    public Anchor getAnchor() {
        return anchor;
    }

    /**
     * The point of this element put on the {@link #getAnchor() anchor}, or null if it is not anchored.
     */
    public Anchor getPivot() {
        return anchor == null ? null : pivot;
    }

    /**
     * Keeps an {@link #setAnchor anchored} element at least {@code margin} points inside every edge of its container,
     * so a small window can't push it off. When it is too big to fit, its top-left corner stays at the margin. A
     * negative margin, the default, turns this off.
     */
    public void setAnchorMargin(double margin) {
        this.anchorMargin = margin;
        applyAnchor();
    }

    /**
     * The margin set with {@link #setAnchorMargin}, or a negative number if there is none.
     */
    public double getAnchorMargin() {
        return anchorMargin;
    }

    /**
     * Called when this element is taken out of the container it was in with {@link Container#removeElement}, and for
     * every element inside a container that is removed. Does nothing by default. An element that keeps something
     * running when it is not drawn, like a video, overrides it to hold that. It is not called when the whole window or
     * a top-level container goes.
     */
    public void onRemoved() {
    }

    /**
     * Sizes this element as a fraction of its container, with no {@code onResize} code:
     * {@code panel.setRelativeSize(0.5, 1)} keeps a panel half as wide as its container and as tall as it. The
     * fractions are of the container's box, or of a {@link Layout}'s area inside its padding. A negative or NaN
     * fraction leaves that axis to {@link #setWidth} and {@link #setHeight}. While a fraction is set it wins over
     * those, and over {@link me.piitex.engine.ui.containers.ResizableContainer} scaling. Position it with {@link #setAnchor}.
     * <p>
     * The size is worked out right away, again whenever the container is resized, and every frame. It has no effect
     * on an element with no container. In a {@link Layout}, {@link #setLayoutWeight} wins along the layout's own
     * axis, and a {@link me.piitex.engine.ui.layout.DockLayout} or {@link me.piitex.engine.ui.layout.GridLayout}
     * still measures the fraction against the whole layout, not the child's edge or cell.
     */
    public void setRelativeSize(double widthFraction, double heightFraction) {
        relativeWidth = widthFraction < 0 ? Double.NaN : widthFraction;
        relativeHeight = heightFraction < 0 ? Double.NaN : heightFraction;
        applyAnchor();
    }

    /**
     * {@link #setRelativeSize} for the width alone.
     */
    public void setRelativeWidth(double fraction) {
        relativeWidth = fraction < 0 ? Double.NaN : fraction;
        applyAnchor();
    }

    /**
     * {@link #setRelativeSize} for the height alone.
     */
    public void setRelativeHeight(double fraction) {
        relativeHeight = fraction < 0 ? Double.NaN : fraction;
        applyAnchor();
    }

    /**
     * The fraction of its container's width this element is kept at, or NaN if its width is its own.
     */
    public double getRelativeWidth() {
        return relativeWidth;
    }

    /**
     * The fraction of its container's height this element is kept at, or NaN if its height is its own.
     */
    public double getRelativeHeight() {
        return relativeHeight;
    }

    /**
     * Points taken off each side of a {@link #setRelativeSize relative} size, so {@code setRelativeSize(1, 1)} with an
     * inset of 8 leaves an 8 point border all round. 0 by default.
     */
    public void setRelativeInset(double inset) {
        this.relativeInset = Math.max(0, inset);
        applyAnchor();
    }

    public double getRelativeInset() {
        return relativeInset;
    }

    /**
     * Makes this element fill its container, {@code margin} points in from every edge: a relative size of 1 by 1 with
     * that inset, centered. {@code fill(0)} covers it exactly.
     */
    public void fill(double margin) {
        relativeWidth = 1;
        relativeHeight = 1;
        relativeInset = Math.max(0, margin);
        anchor = Anchor.CENTER;
        pivot = Anchor.CENTER;
        anchorOffsetX = 0;
        anchorOffsetY = 0;
        applyAnchor();
    }

    /**
     * Gives this element a share of the space a {@link me.piitex.engine.ui.layout.HorizontalLayout} or
     * {@link me.piitex.engine.ui.layout.VerticalLayout} has left over along its own axis, after the children without a
     * weight and the spacing. Two children weighing 1 and 2 share it a third and two thirds, whatever their own
     * sizes were. 0, the default, keeps the element at its own size. It does nothing outside those two layouts, nor
     * in one that {@link me.piitex.engine.ui.layout.Layout#setSizeToContent sizes itself to its content} along that axis, which
     * would have no space left over.
     */
    public void setLayoutWeight(double weight) {
        this.layoutWeight = Math.max(0, weight);
        if (owner instanceof Layout layout) layout.relayout();
    }

    public double getLayoutWeight() {
        return layoutWeight;
    }

    /**
     * Sets the size a {@link #setRelativeSize relative} size works out to inside a box {@code boxWidth} by
     * {@code boxHeight}. Does nothing for an axis with no fraction. The engine calls it, so there is rarely a reason to.
     */
    public void applyRelativeSize(double boxWidth, double boxHeight) {
        if (!Double.isNaN(relativeWidth)) {
            double w = Math.max(0, boxWidth * relativeWidth - 2 * relativeInset);
            if (Math.abs(w - width) > 1e-6) setWidth(w);
        }
        if (!Double.isNaN(relativeHeight)) {
            double h = Math.max(0, boxHeight * relativeHeight - 2 * relativeInset);
            if (Math.abs(h - height) > 1e-6) setHeight(h);
        }
    }

    /**
     * Moves an {@link #setAnchor anchored} element to where its anchor puts it in its container. Does nothing when it
     * is not anchored, has no container or is in a {@link Layout}. The engine calls it whenever that could change,
     * and every frame from the container's update, so there is rarely a need to call it.
     */
    public void applyAnchor() {
        if (owner == null || owner instanceof Layout) return;
        applyRelativeSize(owner.getWidth(), owner.getHeight());
        if (anchor == null) return;
        double boxWidth = owner.getWidth(), boxHeight = owner.getHeight();
        double newX = boxWidth * anchor.x() - width * pivot.x() + anchorOffsetX;
        double newY = boxHeight * anchor.y() - height * pivot.y() + anchorOffsetY;
        if (anchorMargin >= 0) {
            newX = Math.max(anchorMargin, Math.min(newX, boxWidth - width - anchorMargin));
            newY = Math.max(anchorMargin, Math.min(newY, boxHeight - height - anchorMargin));
        }
        x = newX;
        y = newY;
    }

    /**
     * Width in logical points.
     */
    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
        applyAnchor();
    }

    /**
     * Height in logical points.
     */
    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
        applyAnchor();
    }

    /**
     * Whether the cursor is currently over this Element, as last determined by input hit-testing. Drives which of {@link Styling#getHoverColor()}/{@link Styling#getBackgroundColor()} is used in {@link #render}.
     */
    public boolean isHovered() {
        return hovered;
    }

    /**
     * Sets hover state. Normally driven by the engine's input processing, not called directly by application code.
     */
    public void setHovered(boolean hovered) {
        this.hovered = hovered;
    }

    /**
     * Styles this Element from the theme, now and again whenever {@link ThemeManager#setCurrent} switches it. For an
     * Element whose look is set by hand: anything given a color through a setter is pinned against the theme, so a
     * theme switch would leave it as it was; the callback sets the colors again for the new theme instead, with no
     * rebuilding:
     * <pre>{@code
     * badge.onTheme(theme -> {
     *     badge.getStyling().setBackgroundColor(theme.getAccentTint());
     *     badge.setTextColor(theme.getText());
     * });
     * }</pre>
     * Several callbacks run in the order they were added. A callback is kept as long as this Element is, and does not
     * stop the Element's own themed look. Called on the thread that switches the theme, which is the render thread
     * when the switch comes from a button.
     */
    public void onTheme(Consumer<Theme> style) {
        if (style == null) return;
        if (themeStyles == null) {
            themeStyles = new ArrayList<>();
            themeStylist = theme -> {
                for (Consumer<Theme> each : List.copyOf(themeStyles)) each.accept(theme);
            };
            ThemeManager.register(themeStylist);
        }
        themeStyles.add(style);
        style.accept(ThemeManager.getCurrent());
    }

    /**
     * The OS mouse cursor shape to show while this Element is hovered, or null (the
     * default) to use the cursor of the nearest ancestor Container that has one, or
     * {@link CursorMode#DEFAULT} if none does. It is applied automatically by the engine's input processing whenever
     * hover state changes; see {@link CursorMode} and
     * {@link me.piitex.engine.input.CursorManager}. Nothing else needs wiring up: set it
     * once per Element, such as {@code TEXT} for a text field.
     */
    public CursorMode getCursorMode() {
        return isEffectivelyDisabled() ? CursorMode.NOT_ALLOWED : cursorMode;
    }

    public void setCursorMode(CursorMode cursorMode) {
        this.cursorMode = cursorMode;
    }

    /**
     * Whether Tab/Shift+Tab (handled by {@link me.piitex.engine.input.InputProcessor},
     * see {@link me.piitex.engine.Window#focusNext()}/{@link me.piitex.engine.Window#focusPrevious()})
     * can land keyboard focus on this Element. Off by default on the base
     * {@link Element}, since most Elements, such as a plain {@link Container}, an image,
     * or static text, have nothing for keyboard focus to do. A concrete subclass meant
     * to receive typed input turns it on for itself, the way
     * {@link me.piitex.engine.ui.overlays.TextFieldOverlay} does in its constructor.
     * This has no effect unless the Element also implements {@link Focusable}. A mouse
     * click grants focus regardless of this flag, which gates Tab traversal only.
     */
    public boolean isFocusTraversable() {
        return focusTraversable;
    }

    public void setFocusTraversable(boolean focusTraversable) {
        this.focusTraversable = focusTraversable;
    }

    /**
     * Where this Element falls in Tab order relative to other tab stops, or -1 (the
     * default) to use natural order. Natural order is the order Elements were added to
     * their Container, walked depth-first across the Window's Containers, which is the
     * same order {@link #getDrawIndex()} produces. Elements with a non-negative index are visited
     * first, in ascending order (ties keep natural order), followed by every
     * natural-order element; the same convention HTML's {@code tabindex} uses. Only
     * matters when {@link #isFocusTraversable()} is also true.
     */
    public int getTabIndex() {
        return tabIndex;
    }

    public void setTabIndex(int tabIndex) {
        this.tabIndex = tabIndex;
    }

    // --- Accessibility --------------------------------------------------------------------------------------------

    private me.piitex.engine.access.AccessibleRole accessibleRole;
    private String accessibleName, accessibleDescription;
    private Element labelledBy;
    private boolean accessibleHidden;
    private boolean focusRingVisible;

    /**
     * What this Element is to someone who cannot see it: a button, a slider, a check box. Each built-in control reports
     * its own; {@link #setAccessibleRole} overrides it, such as for a {@link me.piitex.engine.ui.containers.Container}
     * that is a labelled group. {@link me.piitex.engine.access.AccessibleRole#NONE} for an Element that adds nothing.
     * Read by {@link me.piitex.engine.access.AccessibilityTree} and by the accessibility host.
     */
    public me.piitex.engine.access.AccessibleRole getAccessibleRole() {
        return accessibleRole != null ? accessibleRole : defaultAccessibleRole();
    }

    /**
     * Overrides {@link #getAccessibleRole()}. Null goes back to the Element's own.
     */
    public void setAccessibleRole(me.piitex.engine.access.AccessibleRole role) {
        this.accessibleRole = role;
    }

    /**
     * What a subclass is when nothing has been set. {@code NONE} here.
     */
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.NONE;
    }

    /**
     * The name a screen reader gives this Element: "Save" for a save button. It is the first that exists of
     * {@link #setAccessibleName the name set}, the text of the Element named by {@link #setLabelledBy}, what the
     * control knows about itself (a button's label), and last its {@link #setTooltip tooltip}, which names an icon button. Null when there is none, which a check box or a slider has
     * until one is given, since the words beside it are separate Elements.
     */
    public String getAccessibleName() {
        if (accessibleName != null) return accessibleName;
        if (labelledBy != null) {
            String name = labelledBy.accessibleName != null ? labelledBy.accessibleName : labelledBy.defaultAccessibleName();
            if (name != null) return name;
        }
        String own = defaultAccessibleName();
        if ((own == null || own.isBlank()) && tooltip != null && !tooltip.isBlank()) return tooltip; // an icon button
        return own;
    }

    public void setAccessibleName(String name) {
        this.accessibleName = name;
    }

    /**
     * What a subclass calls itself when no name has been set, such as a button's text. Null here.
     */
    protected String defaultAccessibleName() {
        return null;
    }

    /**
     * Takes this Element's name from {@code label}, usually the text beside a check box or slider, so the two are read
     * together and the words are written once. Null clears it.
     */
    public void setLabelledBy(Element label) {
        this.labelledBy = label == this ? null : label;
    }

    /**
     * Longer help read after the name and role, such as what a setting does. Null by default, or the {@link #setTooltip
     * tooltip} when there is one and it is not already the name.
     */
    public String getAccessibleDescription() {
        if (accessibleDescription == null && tooltip != null && !tooltip.isBlank() && !tooltip.equals(getAccessibleName())) {
            return tooltip; // the hover help is the longer help a screen reader would give too
        }
        return accessibleDescription;
    }

    public void setAccessibleDescription(String description) {
        this.accessibleDescription = description;
    }

    /**
     * The current value as words: a slider's "40", a field's text, a drop-down's chosen item. Null for an Element with
     * none. A subclass that has one overrides this.
     */
    public String getAccessibleValue() {
        return null;
    }

    /**
     * Whether this is checked or on, for a check box, radio button or switch; null for anything that cannot be.
     */
    public Boolean getAccessibleChecked() {
        return null;
    }

    /**
     * Whether the accessibility tree leaves this Element, and everything in it, out. For decoration that would only add
     * noise: a divider, a background image.
     */
    public boolean isAccessibleHidden() {
        return accessibleHidden;
    }

    public void setAccessibleHidden(boolean hidden) {
        this.accessibleHidden = hidden;
    }

    /**
     * Whether this Element draws a ring around itself while the keyboard focus is on it, for a control that has no
     * focus look of its own, such as a button. A text field draws its own and leaves this false.
     */
    protected boolean usesFocusRing() {
        return false;
    }

    /**
     * Called by the {@link me.piitex.engine.Window} as focus arrives or leaves. The ring shows only when Tab or a key
     * brought focus here, not when a click did, as a browser's does.
     */
    public void setFocusRingVisible(boolean visible) {
        this.focusRingVisible = visible;
    }

    public boolean isFocusRingVisible() {
        return focusRingVisible;
    }

    /**
     * Tells the window's accessibility host that this Element's value or state just changed on its own, so a screen
     * reader can say the new one. The built-in controls call it when the keyboard changes them; a custom control that
     * does the same should too. A no-op with no host, or before the Element is on a window.
     */
    public void notifyAccessibleChange() {
        me.piitex.engine.Window window = getWindow();
        if (window != null) window.notifyAccessibleChange(this);
    }

    private void drawFocusRing(Renderer renderer) {
        float pad = 2f;
        renderer.drawBorder((float) x - pad, (float) y - pad, (float) width + 2 * pad, (float) height + 2 * pad,
                me.piitex.engine.ui.theme.ThemeManager.getCurrent().getAccent(), 2f, getStyling().getCornerRadius() + pad);
    }

    /**
     * The Window this Element is (transitively) part of, or null if it hasn't been added
     * to a Container on a Window yet. Needed by Elements that open floating UI, e.g.
     * {@link me.piitex.engine.ui.overlays.DropdownOverlay}.
     */
    public me.piitex.engine.Window getWindow() {
        Container c = this instanceof Container self ? self : owner;
        return c == null ? null : c.resolveWindow();
    }

    /**
     * Where this Element's floating panels open (a dropdown's list, a context menu, a picker): its {@link #getWindow()}, or
     * the screen it is shown on if it is in a 3D scene. Null if it is on neither yet.
     */
    public PopupHost getPopupHost() {
        Container c = this instanceof Container self ? self : owner;
        return c == null ? null : c.resolvePopupHost();
    }

    /**
     * Text shown in a small floating bubble near the mouse once the cursor has rested
     * on this Element for {@link #getTooltipDelay()} seconds, or null (the default) for
     * none. This works on every Element, Containers included. A Container's tooltip
     * shows while hovering it or any child that has no tooltip of its own; the deepest
     * hovered Element with a tooltip wins. Use {@code \n} for multiple lines.
     * Drawn above everything else by {@link TooltipManager} and
     * colored from the active {@link me.piitex.engine.ui.theme.Theme}'s tooltip tokens.
     */
    public String getTooltip() {
        return tooltip;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
    }

    /**
     * Seconds the cursor must rest on this Element before {@link #getTooltip()} appears. Default 0.5.
     */
    public float getTooltipDelay() {
        return tooltipDelay;
    }

    public void setTooltipDelay(float tooltipDelay) {
        this.tooltipDelay = Math.max(0f, tooltipDelay);
    }

    /**
     * The theme this Element's tooltip is drawn with, or null (the default) for the current
     * {@link me.piitex.engine.ui.theme.ThemeManager} theme. For a preview that shows a theme
     * that isn't applied yet.
     */
    public me.piitex.engine.ui.theme.Theme getTooltipTheme() {
        return tooltipTheme;
    }

    public void setTooltipTheme(me.piitex.engine.ui.theme.Theme tooltipTheme) {
        this.tooltipTheme = tooltipTheme;
    }

    /**
     * The menu opened at the pointer when this Element is right-clicked, or null (the
     * default) for none. This works on every Element, Containers included. A right-click
     * on a child that has no context menu of its own falls through to the nearest
     * ancestor that does, and the deepest one wins. Open it directly with
     * {@link PopupMenu#show} for other triggers.
     */
    public PopupMenu getContextMenu() {
        return contextMenu;
    }

    public void setContextMenu(PopupMenu contextMenu) {
        this.contextMenu = contextMenu;
    }

    /**
     * An effect painted behind this Element's own flat {@link Styling} background and
     * border, and before {@link #onRender}. {@link ConstellationBackground},
     * {@link me.piitex.engine.ui.background.ElectricNodeBackground}, and
     * {@link DotGridBackground} are examples. Null, the default, draws nothing extra.
     * See {@link Background}'s own docs for why this is attached here rather than added
     * as a child Element: a {@link me.piitex.engine.ui.layout.Layout} never sees it, so
     * it cannot disturb how the Layout positions the real children, which is what adding
     * a full-size overlay Element as an actual child would do.
     */
    public Background getBackground() {
        return background.get();
    }

    /**
     * Attaches {@code background}, or removes the effect with null. Like every explicit set it is pinned: a theme's
     * effect, see {@link me.piitex.engine.ui.theme.Theme#getContainerEffect()}, doesn't replace it.
     */
    public void setBackground(Background background) {
        Background old = this.background.get();
        this.background.set(background);
        if (old != null && old != this.background.get()) old.onDetached();
    }

    /**
     * Theme-facing counterpart to {@link #setBackground}: a no-op once that has been called. See {@link Overridable}.
     */
    public void applyThemeBackground(Background background) {
        this.background.applyTheme(background);
    }

    /**
     * Whether {@link #setBackground} has been called, so a theme's effect no longer applies to this element.
     */
    public boolean isBackgroundSet() {
        return background.isExplicit();
    }

    /**
     * Undoes {@link #setBackground}: the effect is the theme's again, if it has one, or none.
     */
    public void resetBackground() {
        background.clearExplicit();
    }

    /**
     * Gives this element the effect a theme asks for, as a baseline that {@link #setBackground} overrides: {@code settings}
     * as {@link me.piitex.engine.ui.background.BackgroundKinds} reads them, or none for null. The effect this made before
     * is updated rather than replaced where it can be, so it doesn't start over each time the theme is applied.
     *
     * @param base the folder pictures are looked for in
     */
    public void applyThemeEffect(Map<String, Object> settings, Path base) {
        Background made = null;
        if (settings != null) {
            try {
                made = BackgroundKinds.apply(themeEffect, settings, base);
            } catch (IllegalArgumentException e) {
                made = null; // a theme with a bad effect gives none, rather than breaking every panel
            }
        }
        themeEffect = made;
        applyThemeBackground(made);
    }

    /**
     * This Element's background, border, and hover colors along with its border and corner geometry. Never null: a fresh {@link Styling} is created in the field initializer if none is set explicitly.
     */
    public Styling getStyling() {
        return styling;
    }

    /**
     * Undoes every explicit color and geometry set on this element, so it follows the theme again, starting now:
     * its {@link #getStyling() styling} and, in widgets that have them, their own colors such as a text or fill
     * color. To undo just one property, use the matching {@code resetXxx} on {@link Styling}. An element that never
     * followed a theme goes back to its own default look.
     */
    public void followTheme() {
        styling.followTheme();
    }

    /**
     * Replaces this Element's Styling wholesale. {@link me.piitex.engine.ui.overlays.TextOverlay} and similar subclasses configure specific Styling defaults, such as a transparent background, in their constructors, and calling this afterward overwrites those defaults as well.
     */
    public void setStyling(Styling styling) {
        this.styling = styling;
    }

    /**
     * Draws this Element's background fill and border, then fires the user's
     * {@link #onRender} hook so custom drawing can layer on top. No-ops entirely if
     * {@link #isEnabled()} is false.
     * <p>
     * Background/border {@link Paint} is resolved here rather than inside the
     * {@link Renderer}: a plain {@link Color} goes through {@link Renderer#drawQuad}/
     * {@link Renderer#drawBorder}, while a {@link LinearGradient} (including an
     * animated {@link ScrollingGradient}) goes through {@link Renderer#drawGradient}
     * or {@link Renderer#drawGradientBorder}.
     * <p>
     * Subclasses that override this (e.g. {@link me.piitex.engine.ui.overlays.TextOverlay})
     * should generally call {@code super.render(renderer)} first so the background/
     * border/onRender-hook sequence still runs, then add their own drawing on top.
     */
    public void render(Renderer renderer) {
        if (!enabled) return;

        // 1. Draw the background fill
        Paint activePaint = AnimatedPaint.resolve(hovered ? styling.getHoverColor() : styling.getBackgroundColor());

        if (activePaint instanceof Color) {
            renderer.drawQuad(
                    (float) x, (float) y, (float) width, (float) height,
                    (Color) activePaint, styling.getCornerRadius()
            );
        } else if (activePaint instanceof LinearGradient grad) {
            // Passing grad itself, not its unpacked colors, is what lets a
            // ScrollingGradient background scroll. See drawGradient's docs.
            renderer.drawGradient(
                    (float) x, (float) y, (float) width, (float) height,
                    grad, styling.getCornerRadius()
            );
        }

        // 2. Draw the attached Background effect, if any, above the flat fill and
        // below the border, so the border still frames it cleanly. See setBackground's
        // docs for why this is an attached effect rather than a child Element.
        Background effect = background.get();
        if (effect != null) {
            effect.render(renderer, (float) x, (float) y, (float) width, (float) height);
        }

        // 3. Draw the border outline on top
        if (styling.getBorderThickness() > 0) {
            Paint borderPaint = AnimatedPaint.resolve(styling.getBorderColor());

            if (borderPaint instanceof Color) {
                renderer.drawBorder(
                        (float) x, (float) y, (float) width, (float) height,
                        (Color) borderPaint, styling.getBorderThickness(), styling.getCornerRadius()
                );
            } else if (borderPaint instanceof LinearGradient grad) {
                // Passed as-is so a ScrollingGradient border scrolls, same as the fill above.
                renderer.drawGradientBorder(
                        (float) x, (float) y, (float) width, (float) height,
                        grad, styling.getBorderThickness(), styling.getCornerRadius()
                );
            }
        }

        // 4. Let the user draw on top of the built-in visuals
        if (elementRenderEventConsumer != null) {
            elementRenderEventConsumer.accept(new ElementRenderEvent(this, renderer));
        }
    }

    /**
     * Where this element's text sits inside its own box, or null (the default) for the overlay's natural
     * placement: top-left for {@code TextOverlay}, centered for a button, left for a field. Unrelated to a
     * layout's alignment, which positions the element itself. Only meaningful when the box is larger than the text.
     */
    public me.piitex.engine.ui.layout.Layout.Alignment getTextAlignment() {
        return textAlignment;
    }

    public void setTextAlignment(me.piitex.engine.ui.layout.Layout.Alignment textAlignment) {
        this.textAlignment = textAlignment;
    }

    /**
     * Horizontal fraction of the free space to place before the text (0 left, 0.5 center, 1 right): the explicit alignment, else {@code natural}.
     */
    protected float textAlignH(float natural) {
        return textAlignment != null ? textAlignment.horizontal() : natural;
    }

    /**
     * Vertical counterpart of {@link #textAlignH}.
     */
    protected float textAlignV(float natural) {
        return textAlignment != null ? textAlignment.vertical() : natural;
    }

    /**
     * The color an overlay should draw its text with right now: {@link Styling#getTextHoverColor()} while this
     * Element is hovered and one is set, otherwise {@code normal}. Overlays call this at their text draw sites.
     */
    protected Paint resolveTextColor(Paint normal) {
        Paint hover = styling.getTextHoverColor();
        return hovered && hover != null ? hover : normal;
    }

    /**
     * Advances this Element's per-frame state: ticks any {@link AnimatedPaint}
     * (e.g. a {@link ScrollingGradient}) sitting in {@link #getStyling()}'s
     * background/border/hover colors, then fires the user's {@link #onUpdate} hook.
     * No-ops entirely if {@link #isEnabled()} is false.
     * <p>
     * {@code delta} is the elapsed time in seconds since the previous frame. See
     * {@link ElementUpdateEvent#getDelta()}. Callers such as {@code Container.update}
     * are responsible for invoking this once per frame for every Element that should
     * animate. A subclass or container that does not call it on its children freezes any
     * {@link AnimatedPaint} they use, since nothing else advances their offset.
     */
    public void update(float delta) {
        if (!enabled) return;

        advanceIfAnimated(styling.getBackgroundColor(), delta);
        advanceIfAnimated(styling.getBorderColor(), delta);
        // Skipped when hover is the background itself (already advanced above), or an animated paint would run at double speed.
        if (styling.getHoverColor() != styling.getBackgroundColor()) {
            advanceIfAnimated(styling.getHoverColor(), delta);
        }
        advanceIfAnimated(styling.getTextHoverColor(), delta);

        Background effect = background.get();
        if (effect != null) {
            effect.update(delta, (float) getAbsoluteX(), (float) getAbsoluteY(), (float) width, (float) height);
        }

        if (!transitions.isEmpty()) {
            // Iterate a snapshot, not `transitions` itself: a Transition's onComplete
            // callback (chaining a fadeOut off a fadeIn, say) can call playTransition()
            // on this same Element from inside transition.update() below, which would
            // otherwise structurally modify `transitions` while this loop is still
            // reading it and throw a ConcurrentModificationException. A transition
            // added that way starts getting updated next frame instead of this one. It
            // is still included in this frame's renderWithTransitions() call, since it
            // is already in the real list, and is drawn at its fresh initial state.
            for (Transition transition : new ArrayList<>(transitions)) {
                if (transition.isFinished()) {
                    // Already had its one final render() at its terminal state last frame, so drop it now.
                    transitions.remove(transition);
                    transitionAfter.remove(transition); // ended by stop(), which fires nothing
                    continue;
                }
                transition.update(this, delta);
                runAfter(transition);
            }
        }

        if (elementUpdateEventConsumer != null) {
            elementUpdateEventConsumer.accept(new ElementUpdateEvent(this, delta));
        }
    }

    /**
     * The entry point for drawing this Element as a whole. Call this rather than
     * {@link #render} directly from anywhere responsible for drawing an Element, such as
     * {@code Container}'s child loop or {@code Engine}'s top-level container loop.
     * <p>
     * It applies {@link #getOpacity()}, which a running {@link FadeTransition} adjusts
     * every frame through {@link Transition#update}, by wrapping the entire draw in one
     * offscreen layer through {@link Renderer#pushOpacityLayer}. That draw covers the
     * background, the border, subclass-specific content, and, for a {@link Container},
     * every child, so a fade affects everything this Element draws as a single unit.
     * Drawing the background, border, and subclass text or image each at their own
     * reduced alpha, without a shared layer, would double-blend wherever two of them
     * overlap and would not touch subclass content at all, since {@link #render} cannot
     * reach into what a subclass draws after calling {@code super.render(...)}. The
     * layer is skipped at full opacity, the common case, to avoid the extra offscreen
     * composite when nothing is fading, and this Element's own content is not drawn at
     * all once fully transparent.
     * <p>
     * Before any of that, each active {@link Transition#renderBackground} effect is
     * drawn. An example is the solid color a color-crossfading {@link FadeTransition}
     * dissolves from and to, which has to land underneath this Element's own, possibly
     * fully transparent, content rather than on top of it. After everything else, each
     * active {@link Transition#render} effect is drawn on top, outside the opacity
     * layer. A plain {@link FadeTransition} draws nothing at either point, since it
     * works purely by adjusting {@link #getOpacity()}, but these are the hooks available
     * to a transition type that draws its own content.
     */
    public void renderWithTransitions(Renderer renderer) {
        if (!enabled) return;

        for (Transition transition : transitions) {
            transition.renderBackground(renderer, this);
        }

        float shown = disabled ? opacity * disabledOpacity : opacity;
        boolean hoveredBefore = hovered;
        if (disabled) setHovered(false); // an element that cannot be used does not light up under the mouse
        if (shown > 0f) {
            boolean scaled = scale != 1f;
            if (scaled) {
                float pivotX = Double.isNaN(scaleOriginX) ? (float) (x + width / 2.0) : (float) scaleOriginX;
                float pivotY = Double.isNaN(scaleOriginY) ? (float) (y + height / 2.0) : (float) scaleOriginY;
                renderer.pushScale(scale, pivotX, pivotY);
            }
            if (shown < 1f) {
                renderer.pushOpacityLayer((float) x, (float) y, (float) width, (float) height, shown);
                renderWithEffect(renderer);
                renderer.popOpacityLayer();
            } else {
                renderWithEffect(renderer);
            }
            if (focusRingVisible && usesFocusRing()) drawFocusRing(renderer);
            if (scaled) renderer.popScale();
        }
        if (disabled) setHovered(hoveredBefore);

        for (Transition transition : transitions) {
            transition.render(renderer, this);
        }
    }

    private void renderWithEffect(Renderer renderer) {
        ShaderEffect current = effect;
        if (current == null || current.isClosed()) {
            render(renderer);
            return;
        }
        renderer.pushEffect(current, (float) x, (float) y, (float) width, (float) height);
        render(renderer);
        renderer.popEffect();
    }

    /**
     * Passes everything this Element draws, children included, through a shader: post-processing, such as a
     * vignette, a color grade or a CRT look over a scene's container. The shader must declare
     * {@code uniform shader content;}, which holds the Element's drawing, and its {@code coord} is in the same space
     * as {@link #getX()} and {@link #getY()}. The result is clipped to the Element's bounds. It is only how it's
     * drawn: hit testing and layout are unchanged. Null removes it. See {@link ShaderEffect}.
     * <p>
     * The effect is drawn before {@link #setOpacity opacity} fades the result. The Element doesn't close it.
     */
    public void setEffect(ShaderEffect effect) {
        if (effect != null && !effect.hasContent()) {
            throw new IllegalArgumentException("An Element's effect must declare 'uniform shader "
                    + ShaderEffect.CONTENT + ";' to receive what the Element draws");
        }
        this.effect = effect;
    }

    public ShaderEffect getEffect() {
        return effect;
    }

    /**
     * Renders this Element (children included) into a {@link Snapshot} at its own size, one pixel per point.
     * Works on the render thread; from anywhere else use {@link #takeSnapshotAsync}. Close the result when done.
     */
    public Snapshot takeSnapshot() {
        return takeSnapshot(1f);
    }

    /**
     * As {@link #takeSnapshot()}, at {@code scale} pixels per point: 2 is retina-sharp, 0.25 a small thumbnail.
     */
    public Snapshot takeSnapshot(float scale) {
        return Snapshot.of(this, scale);
    }

    /**
     * Captures at the start of the next frame and calls {@code callback} on the render thread. Safe from any thread.
     */
    public void takeSnapshotAsync(float scale, java.util.function.Consumer<Snapshot> callback) {
        Snapshot.ofAsync(this, scale, callback);
    }

    /**
     * This Element's overall opacity in [0, 1] (default 1 = fully opaque), applied by
     * {@link #renderWithTransitions} to everything it draws as a single unit. See that
     * method's docs. It is normally driven by a running {@link FadeTransition}, but it
     * can also be set directly for a statically translucent Element.
     */
    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    public float getScale() {
        return scale;
    }

    /**
     * Draws this Element, children included, at {@code scale} times its size around its {@link #setScaleOrigin origin}.
     * Default 1. It is only how it's drawn: hit testing, layout and the size stay the same, so it suits an animation,
     * see {@link me.piitex.engine.ui.animation.ScaleTransition}, more than a permanently smaller Element.
     */
    public void setScale(float scale) {
        this.scale = Math.max(0f, scale);
    }

    /**
     * The point {@link #setScale} scales around, in the same coordinate space as {@link #getX()} and {@link #getY()}.
     */
    public void setScaleOrigin(double x, double y) {
        scaleOriginX = x;
        scaleOriginY = y;
    }

    /**
     * Back to scaling around the Element's center, the default.
     */
    public void clearScaleOrigin() {
        scaleOriginX = scaleOriginY = Double.NaN;
    }

    /**
     * Attaches a {@link Transition} that the engine will drive from the next {@link #update}/{@link #renderWithTransitions} onward until it reports {@link Transition#isFinished()}. Several can run at once.
     */
    public void playTransition(Transition transition) {
        if (transition != null) {
            transitions.add(transition);
        }
    }

    /**
     * As {@link #playTransition(Transition)}, then runs {@code after} once, on the frame the transition finishes:
     * when it runs out or is ended early through {@link #finishTransitions()}, a skip, or {@link Transition#finish}. If the
     * transition is already finished it runs at once. It does not run if the transition is {@link Transition#stop() stopped}, or if this Element is dropped before it
     * finishes. This works for any {@link Transition}, so it is how to do something after one that was built without a
     * completion callback, such as swapping scenes once an end transition has played:
     * <pre>{@code
     * container.playTransition(endTransition, () -> showNextScene());
     * }</pre>
     * {@code after} may play another transition, on this Element or another, which is how transitions are chained.
     */
    public void playTransition(Transition transition, Runnable after) {
        if (transition == null || transition.isFinished()) {
            if (after != null) after.run();
            return;
        }
        if (after != null) transitionAfter.put(transition, after);
        playTransition(transition);
    }

    /**
     * Whether this Element currently has at least one {@link Transition} running.
     */
    public boolean isTransitioning() {
        return !transitions.isEmpty();
    }

    /**
     * Whether a running {@link Transition} {@link Transition#isLocking() locks} the game. The window checks this for
     * every enabled Element once a frame.
     */
    public boolean hasLockingTransition() {
        for (Transition transition : transitions) {
            if (!transition.isFinished() && transition.isLocking()) return true;
        }
        return false;
    }

    /**
     * Ends every running transition now, leaving this Element in their final states and firing their completion
     * callbacks; see {@link Transition#finish}. A transition that cannot be finished is left running. Returns whether
     * any was finished.
     */
    public boolean finishTransitions() {
        return finishTransitions(false);
    }

    /**
     * As {@link #finishTransitions()}, but only those that lock the game and are {@link Transition#isSkippable()
     * skippable}. This is what a key press or click does while the window is locked.
     */
    public boolean skipLockingTransitions() {
        return finishTransitions(true);
    }

    private boolean finishTransitions(boolean onlySkippableLocks) {
        boolean any = false;
        // A snapshot, since a completion callback can play another transition on this Element.
        for (Transition transition : new ArrayList<>(transitions)) {
            if (transition.isFinished()) continue;
            if (onlySkippableLocks && !(transition.isLocking() && transition.isSkippable())) continue;
            if (transition.finish(this)) {
                any = true;
                runAfter(transition);
            }
        }
        return any;
    }

    private void runAfter(Transition transition) {
        if (!transition.isFinished()) return;
        Runnable after = transitionAfter.remove(transition);
        if (after != null) after.run();
    }

    /**
     * Fades this Element in from fully transparent to fully opaque over {@code durationSeconds}. See {@link FadeTransition}.
     */
    public FadeTransition fadeIn(float durationSeconds) {
        return fadeIn(durationSeconds, (Runnable) null);
    }

    /**
     * As {@link #fadeIn(float)}, firing {@code onComplete} once the fade finishes.
     */
    public FadeTransition fadeIn(float durationSeconds, Runnable onComplete) {
        FadeTransition fade = new FadeTransition(durationSeconds, FadeTransition.Direction.IN, onComplete);
        playFade(fade);
        return fade;
    }

    /**
     * As {@link #fadeIn(float)}, but dissolving from solid {@code fromColor} into this Element instead of from plain transparency. See {@link FadeTransition}'s class docs.
     */
    public FadeTransition fadeIn(Color fromColor, float durationSeconds) {
        return fadeIn(fromColor, durationSeconds, null);
    }

    /**
     * As {@link #fadeIn(Color, float)}, firing {@code onComplete} once the fade finishes.
     */
    public FadeTransition fadeIn(Color fromColor, float durationSeconds, Runnable onComplete) {
        FadeTransition fade = new FadeTransition(durationSeconds, FadeTransition.Direction.IN, fromColor, onComplete);
        playFade(fade);
        return fade;
    }

    /**
     * Fades this Element out from fully opaque to fully transparent over {@code durationSeconds}. See {@link FadeTransition}.
     */
    public FadeTransition fadeOut(float durationSeconds) {
        return fadeOut(durationSeconds, (Runnable) null);
    }

    /**
     * As {@link #fadeOut(float)}, firing {@code onComplete} once the fade finishes.
     */
    public FadeTransition fadeOut(float durationSeconds, Runnable onComplete) {
        FadeTransition fade = new FadeTransition(durationSeconds, FadeTransition.Direction.OUT, onComplete);
        playFade(fade);
        return fade;
    }

    /**
     * As {@link #fadeOut(float)}, but dissolving from this Element into solid {@code toColor} instead of into plain transparency. See {@link FadeTransition}'s class docs.
     */
    public FadeTransition fadeOut(Color toColor, float durationSeconds) {
        return fadeOut(toColor, durationSeconds, null);
    }

    /**
     * As {@link #fadeOut(Color, float)}, firing {@code onComplete} once the fade finishes.
     */
    public FadeTransition fadeOut(Color toColor, float durationSeconds, Runnable onComplete) {
        FadeTransition fade = new FadeTransition(durationSeconds, FadeTransition.Direction.OUT, toColor, onComplete);
        playFade(fade);
        return fade;
    }

    /**
     * Like {@link #playTransition}, but first drops any {@link FadeTransition} already running on this Element. Without that, a fade started while another is still in flight, such as a click mid-fade, would stack, and both would write to {@link #setOpacity} every frame instead of the new one taking over.
     */
    private void playFade(FadeTransition fade) {
        transitions.removeIf(t -> {
            if (!(t instanceof FadeTransition)) return false;
            transitionAfter.remove(t);
            return true;
        });
        setOpacity(fade.startOpacity());
        playTransition(fade);
    }

    /**
     * Sets position and size together in one call. Drops any {@link #setAnchor anchor}, as {@link #setX} does.
     */
    public void setBounds(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        anchor = null;
    }

    /**
     * Whether the window point (px, py) falls within this Element's rectangular bounds, inclusive of both edges.
     * Used by input hit-testing to determine hover/click targets.
     */
    public boolean contains(double px, double py) {
        double ax = getAbsoluteX(), ay = getAbsoluteY();
        return px >= ax && px <= ax + width && py >= ay && py <= ay + height;
    }

    /**
     * The click listener that runs on a press: the element's own built-in one (a table's right-click selection, say)
     * and then the one set with {@link #onMouseClick}, or null if there is neither.
     */
    public Consumer<MouseClickEvent> getMouseClickListenerConsumer() {
        return combinedClickListener;
    }

    /**
     * Registers a callback invoked when this Element is pressed, with any mouse button, or, if it
     * {@link #setClickOnRelease clicks on release}, when a left press on it is released over it. A press on a child that has no click listener of its own goes up to the nearest ancestor that has one, so this also fires for presses on the labels or icons inside this Element; only one listener runs per press unless the Element {@link #setPropagateClicks propagates clicks}. Only one listener is held at a time; calling this again replaces the previous listener rather than adding a second one. An element that handles clicks itself ({@link me.piitex.engine.ui.containers.TableView}, {@link me.piitex.engine.ui.containers.TreeView}, {@link me.piitex.engine.ui.overlays.RichTextAreaOverlay}) keeps that handling and runs this one after it.
     */
    public void onMouseClick(Consumer<MouseClickEvent> mouseClickListenerConsumer) {
        this.mouseClickListenerConsumer = mouseClickListenerConsumer;
        combineClickListeners();
    }

    /**
     * For an element that reacts to clicks on its own: the listener it needs, kept apart from the one a user sets with
     * {@link #onMouseClick} so that setting one does not remove the other. It runs first.
     */
    protected void setBuiltInClickListener(Consumer<MouseClickEvent> listener) {
        this.builtInClickListener = listener;
        combineClickListeners();
    }

    private void combineClickListeners() {
        Consumer<MouseClickEvent> own = builtInClickListener, user = mouseClickListenerConsumer;
        if (own == null || user == null) {
            combinedClickListener = own != null ? own : user;
        } else {
            combinedClickListener = event -> {
                own.accept(event);
                user.accept(event);
            };
        }
    }

    /**
     * Whether the {@link #onMouseClick click listener} fires when the left button is released rather than pressed. See
     * {@link #setClickOnRelease}.
     */
    public boolean isClickOnRelease() {
        return clickOnRelease;
    }

    /**
     * Makes the {@link #onMouseClick click listener} fire the way a button's does: when the left button is released,
     * and only if it was pressed on this Element and is released still over it. Moving off before letting go cancels
     * the click, and a press that began elsewhere, such as on a popup scene still in its
     * {@link me.piitex.engine.game.Scene#setInputDelay input delay}, never fires it. Other mouse buttons are taken by
     * this Element without firing it, so they don't reach game input either. {@link me.piitex.engine.ui.overlays.ButtonOverlay},
     * the check box, radio button and toggle switch turn this on. Off by default, so a plain listener fires on the press.
     */
    public void setClickOnRelease(boolean clickOnRelease) {
        this.clickOnRelease = clickOnRelease;
    }

    /**
     * Whether a press that runs this Element's click listener goes on to the next ancestor with one. See
     * {@link #setPropagateClicks}.
     */
    public boolean isPropagateClicks() {
        return propagateClicks;
    }

    /**
     * Lets a press reach this Element's container as well. Normally only one click listener runs per press: the
     * topmost Element's, or the nearest ancestor's if it has none. With this on, after this Element's listener the press
     * goes on up to the next ancestor that has a listener, and so on while each one propagates, so a clickable card
     * and the button inside it both hear the click. Off by default.
     */
    public void setPropagateClicks(boolean propagateClicks) {
        this.propagateClicks = propagateClicks;
    }

    /**
     * Whether pressing and moving on this Element drags the container it sits in. See {@link #setDragsContainer}.
     */
    public boolean isDragsContainer() {
        return dragsContainer;
    }

    /**
     * Lets a clickable Element act as a handle for the {@link me.piitex.engine.ui.Draggable} container around it, such
     * as a title bar with a click listener on a draggable panel. Normally an Element that listens for clicks keeps its
     * press, so nothing above it is dragged. With this on, releasing the left button without moving is still a click
     * (it fires on release, as with {@link #setClickOnRelease}), and moving the mouse a few points while it is held
     * drags the container instead, and the click is dropped. Off by default.
     */
    public void setDragsContainer(boolean dragsContainer) {
        this.dragsContainer = dragsContainer;
    }

    /**
     * The currently registered render listener, or null if none is set.
     */
    public Consumer<ElementRenderEvent> getElementRenderEventConsumer() {
        return elementRenderEventConsumer;
    }

    /**
     * Registers a callback invoked at the end of every {@link #render}, after this Element's own background/border have been drawn, so custom drawing can layer on top. Only one listener is held at a time.
     */
    public void onRender(Consumer<ElementRenderEvent> elementRenderEventConsumer) {
        this.elementRenderEventConsumer = elementRenderEventConsumer;
    }

    /**
     * The currently registered update listener, or null if none is set.
     */
    public Consumer<ElementUpdateEvent> getElementUpdateEventConsumer() {
        return elementUpdateEventConsumer;
    }

    /**
     * Registers a callback invoked at the end of every {@link #update}, after this Element's own animated Paints have been advanced. Only one listener is held at a time.
     */
    public void onUpdate(Consumer<ElementUpdateEvent> elementUpdateEventConsumer) {
        this.elementUpdateEventConsumer = elementUpdateEventConsumer;
    }

    /* STATIC METHODS */

    /**
     * If {@code paint} is an {@link AnimatedPaint} (e.g. a {@link ScrollingGradient}), advances it by {@code delta} seconds; otherwise does nothing.
     */
    private static void advanceIfAnimated(Paint paint, float delta) {
        if (paint instanceof AnimatedPaint animated) {
            animated.advance(delta);
        }
    }

}