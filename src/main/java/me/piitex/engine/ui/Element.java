package me.piitex.engine.ui;

import me.piitex.engine.ui.animation.FadeTransition;
import me.piitex.engine.ui.animation.Transition;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.ConstellationBackground;
import me.piitex.engine.ui.background.DotGridBackground;
import me.piitex.engine.ui.color.*;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.events.ElementRenderEvent;
import me.piitex.engine.ui.events.ElementUpdateEvent;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.text.TooltipManager;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.utils.FormID;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.utils.FormIDRegistry;
import me.piitex.engine.utils.Namespace;

import java.util.ArrayList;
import java.util.List;
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
public class Element {
    private final FormID id;
    private int suggestedDrawIndex;
    private int drawIndex;
    private boolean enabled = true;
    private double x, y, z, width, height;
    private boolean hovered;
    private Layout.Alignment textAlignment;
    private CursorMode cursorMode;
    private boolean focusTraversable = false;
    private int tabIndex = -1;
    private Background background;
    private Styling styling = new Styling();
    private final List<Transition> transitions = new ArrayList<>();
    private float opacity = 1f;
    // Drawn scaled around scaleOriginX/Y, in the same space as x and y. NaN is the Element's center.
    private float scale = 1f;
    private double scaleOriginX = Double.NaN, scaleOriginY = Double.NaN;
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
     * The top-left corner's x coordinate, in logical points, <b>relative to the container this element is in</b>
     * (its top-left corner, and for a {@link me.piitex.engine.ui.scroll.ScrollContainer} the scrolled content
     * origin). For an element added straight to a {@link me.piitex.engine.Window} that is the window. Moving a
     * container moves everything inside it without touching their own x/y. Use {@link #getAbsoluteX()} for the
     * position in the window.
     */
    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
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

    public void setY(double y) {
        this.y = y;
    }

    /**
     * Unused by rendering and hit-testing, both of which are purely 2D. It is reserved for depth handling beyond the draw-index system above.
     */
    public double getZ() {
        return z;
    }

    /**
     * Sets the reserved depth value. See {@link #getZ()}.
     */
    public void setZ(double z) {
        this.z = z;
    }

    /**
     * Convenience for setting x and y together in one call, e.g. when moving an Element as part of an animation.
     */
    public void setPosition(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Width in logical points.
     */
    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    /**
     * Height in logical points.
     */
    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
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
     * The OS mouse cursor shape to show while this Element is hovered, or null (the
     * default) to use the cursor of the nearest ancestor Container that has one, or
     * {@link CursorMode#DEFAULT} if none does. It is applied automatically by the engine's input processing whenever
     * hover state changes; see {@link CursorMode} and
     * {@link me.piitex.engine.input.CursorManager}. Nothing else needs wiring up: set it
     * once per Element, such as {@code TEXT} for a text field.
     */
    public CursorMode getCursorMode() {
        return cursorMode;
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
        return background;
    }

    public void setBackground(Background background) {
        this.background = background;
    }

    /**
     * This Element's background, border, and hover colors along with its border and corner geometry. Never null: a fresh {@link Styling} is created in the field initializer if none is set explicitly.
     */
    public Styling getStyling() {
        return styling;
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
     * instead, since that's a different draw call entirely rather than an overload.
     * <p>
     * Known gap: a {@link LinearGradient} border ({@code styling.getBorderColor()})
     * is not drawn, because {@link Renderer} has no gradient-border primitive. Only a
     * plain {@link Color} border renders.
     * <p>
     * Subclasses that override this (e.g. {@link me.piitex.engine.ui.overlays.TextOverlay})
     * should generally call {@code super.render(renderer)} first so the background/
     * border/onRender-hook sequence still runs, then add their own drawing on top.
     */
    public void render(Renderer renderer) {
        if (!enabled) return;

        // 1. Draw the background fill
        Paint activePaint = hovered ? styling.getHoverColor() : styling.getBackgroundColor();

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
        if (background != null) {
            background.render(renderer, (float) x, (float) y, (float) width, (float) height);
        }

        // 3. Draw the border outline on top
        if (styling.getBorderThickness() > 0) {
            Paint borderPaint = styling.getBorderColor();

            if (borderPaint instanceof Color) {
                renderer.drawBorder(
                        (float) x, (float) y, (float) width, (float) height,
                        (Color) borderPaint, styling.getBorderThickness(), styling.getCornerRadius()
                );
            } else if (borderPaint instanceof LinearGradient) {
                // Gradient borders are not drawn: Renderer has no gradient-border
                // primitive. Adding one would require a drawGradientBorder method there.
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

        if (background != null) {
            background.update(delta, (float) getAbsoluteX(), (float) getAbsoluteY(), (float) width, (float) height);
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
                    continue;
                }
                transition.update(this, delta);
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

        if (opacity > 0f) {
            boolean scaled = scale != 1f;
            if (scaled) {
                float pivotX = Double.isNaN(scaleOriginX) ? (float) (x + width / 2.0) : (float) scaleOriginX;
                float pivotY = Double.isNaN(scaleOriginY) ? (float) (y + height / 2.0) : (float) scaleOriginY;
                renderer.pushScale(scale, pivotX, pivotY);
            }
            if (opacity < 1f) {
                renderer.pushOpacityLayer((float) x, (float) y, (float) width, (float) height, opacity);
                render(renderer);
                renderer.popOpacityLayer();
            } else {
                render(renderer);
            }
            if (scaled) renderer.popScale();
        }

        for (Transition transition : transitions) {
            transition.render(renderer, this);
        }
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
     * Whether this Element currently has at least one {@link Transition} running.
     */
    public boolean isTransitioning() {
        return !transitions.isEmpty();
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
        transitions.removeIf(t -> t instanceof FadeTransition);
        setOpacity(fade.startOpacity());
        playTransition(fade);
    }

    /**
     * Sets position and size together in one call.
     */
    public void setBounds(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
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
     * The currently registered click listener, or null if none is set.
     */
    public Consumer<MouseClickEvent> getMouseClickListenerConsumer() {
        return mouseClickListenerConsumer;
    }

    /**
     * Registers a callback invoked when this Element is pressed, with any mouse button. A press on a child that has no click listener of its own goes up to the nearest ancestor that has one, so this also fires for presses on the labels or icons inside this Element; only one listener runs per press. Only one listener is held at a time; calling this again replaces the previous listener rather than adding a second one.
     */
    public void onMouseClick(Consumer<MouseClickEvent> mouseClickListenerConsumer) {
        this.mouseClickListenerConsumer = mouseClickListenerConsumer;
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