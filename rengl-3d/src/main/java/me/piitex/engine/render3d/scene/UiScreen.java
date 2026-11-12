package me.piitex.engine.render3d.scene;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.input.UiHitTest;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.scroll.Scrollable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Container} of the 2D interface shown on a surface in the 3D world: a computer screen, a control panel, a
 * sign. The container is drawn into a {@link TextureData} that goes on a material, and the mouse is aimed at it with a
 * {@link Hit} from {@link Scene3D#pick}, so its buttons can be hovered and clicked from inside the scene.
 * <pre>{@code
 * Container ui = new Container(320, 200);
 * ui.addElement(new ButtonOverlay("Open the door", 200, 40));
 *
 * UiScreen screen = new UiScreen(ui, 2f);                       // a 640 x 400 texture
 * material.setEmissiveTexture(screen.texture());
 *
 * // every frame (or a few times a second):
 * screen.refresh(delta);
 * // when the player moves the mouse or clicks:
 * Hit hit = scene.pick(mouseX, mouseY, viewWidth, viewHeight);
 * screen.pointAt(hit, monitorNode);
 * if (clicked) screen.press();   // and release() when the button comes up
 * }</pre>
 * The container draws exactly as it would in a window, by the same renderer that takes a
 * {@link me.piitex.engine.gl.renderer.Snapshot}: on the CPU, so it needs no window and works the same on every graphics
 * API. Its size is taken when the screen is made, so give the container its size first.
 * <p>
 * Hover, clicks and the wheel follow the window's rules (the same {@link UiHitTest} finds the element, the same
 * click-on-release and disabled handling applies). Pressing a text field gives it the keyboard, with the caret
 * where it was pressed, and the game then sends keys and typed characters to {@link #key} and {@link #typeChar}
 * while {@link #hasFocus()}:
 * <pre>{@code
 * public void charTyped(int codepoint) { if (screen.hasFocus()) { screen.typeChar(codepoint); screen.refresh(); } }
 * public void keyPressed(int key, int action, int mods) {
 *     if (screen.key(key, action, mods)) screen.refresh();
 *     else movePlayer(key, action);
 * }
 * }</pre>
 * <h2>Dragging</h2>
 * A press that lands on something that drags (a slider's thumb, a scrollbar, a split container's divider, a resizable or
 * draggable container, a table's column edge, a text field's selection) is a drag until {@link #release}: every
 * {@link #pointAt} in between moves it, by the window's own rules for which element takes the drag. A drag that goes off the
 * screen holds at the last place on it (the pointer is {@linkplain #pointAway away}, but the drag goes on) and ends at
 * the release.
 * <h2>Popups</h2>
 * A dropdown's list, a context menu (a right {@link #press(int)}), a color or date picker and a spell checker's suggestions open
 * on the screen, over everything else on it, and are drawn into the texture. This screen is their {@link PopupHost}. While one is
 * open, pointing, pressing, the wheel and keys go to it first, a press outside it closes it (and is used up), and Escape closes
 * it too; a menu takes the arrow keys and Enter, so send keys to {@link #key} while {@link #hasFocus()}. A popup that is
 * open but not wholly inside the container is moved to be inside it.
 * <p>
 * Not thread-safe: draw and point from one thread, the way a window's interface is used. Close the screen when it is no
 * longer shown; the texture it made stays valid.
 */
public final class UiScreen implements AutoCloseable, PopupHost {
    private final Container root;
    private final TextureData texture;
    private final OffscreenRenderer renderer;

    private boolean pointing;
    private double pointerX, pointerY;
    /**
     * The elements a left press landed on whose click waits for the release.
     */
    private final List<Element> clickTargets = new ArrayList<>();
    /**
     * The disabled element a left press landed on, whose disabled click runs if the release is on it too.
     */
    private Element disabledTarget;
    /**
     * The element that has the keyboard, or null.
     */
    private Element focused;
    /**
     * The text field a left press landed on, which follows the pointer until the release to select text.
     */
    private Element dragTarget;
    /**
     * The draggable a press on a click-and-drag element is waiting to start dragging, once the pointer has moved
     * {@link #DRAG_THRESHOLD} points, or null.
     */
    private Element pendingDrag;
    private double pendingDragX, pendingDragY;
    private static final double DRAG_THRESHOLD = 4.0;

    // the open popup, which follows its anchor the way a window's does
    private Container popup;
    private Runnable popupDismiss;
    private Element popupAnchor;
    private double anchorStartX, anchorStartY, popupShiftX, popupShiftY, popupGap;
    private int popupSide;

    /**
     * A screen at one texture pixel per point of the container.
     */
    public UiScreen(Container root) {
        this(root, 1f);
    }

    /**
     * @param root  the interface to show; it must have a size. It is drawn at its own position, with nothing around it
     * @param scale texture pixels per point of the container: 2 for a screen that stays sharp up close, 0.5 for a cheap
     *              one. The texture is the container's size times this
     */
    public UiScreen(Container root, float scale) {
        if (root == null) throw new IllegalArgumentException("A screen needs a container to show");
        if (!(scale > 0f)) throw new IllegalArgumentException("scale must be positive: " + scale);
        int w = (int) Math.ceil(root.getWidth());
        int h = (int) Math.ceil(root.getHeight());
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("Cannot show a container with no size: " + w + "x" + h);
        this.root = root;
        root.setPopupHost(this);
        this.renderer = new OffscreenRenderer(w, h, scale);
        this.renderer.clear(0f, 0f, 0f, 1f);
        byte[] black = new byte[renderer.getPixelWidth() * renderer.getPixelHeight() * 4];
        for (int i = 3; i < black.length; i += 4) black[i] = (byte) 255;
        this.texture = new TextureData(renderer.getPixelWidth(), renderer.getPixelHeight(), black);
    }

    /**
     * The interface this screen shows.
     */
    public Container root() {
        return root;
    }

    /**
     * The picture, for a material's {@link Material#setEmissiveTexture emissive} or colour texture. It is the same
     * object for the life of the screen: {@link #refresh} rewrites it, and the scenes drawing it show the new picture
     * from their next frame. It is black until the first refresh.
     */
    public TextureData texture() {
        return texture;
    }

    /**
     * The colour behind the container, filling the whole texture before it is drawn. Opaque black by default, so
     * a container with no background of its own is a dark screen rather than a see-through one.
     */
    public UiScreen setBackground(Color color) {
        Color c = color != null ? color : Color.BLACK;
        renderer.clear(c.getR(), c.getG(), c.getB(), c.getA());
        return this;
    }

    /**
     * Draws the container into the texture as it looks now. Anything that changes the picture (a clock, a bar filling, a
     * hover colour) shows after this call. It costs a CPU draw and an upload of the whole texture, so a screen that
     * changes slowly can be refreshed a few times a second instead of every frame.
     */
    public void refresh() {
        // Whatever asks where the mouse is while it draws (a hovered row, a chart's tooltip) is told where the pointer is here.
        try (MouseTracker.Loan loan = MouseTracker.lend(pointerX, pointerY, pointing)) {
            followPopupAnchor();
            renderer.begin();
            // The container draws itself at its own x/y, so shift that to the corner of the texture.
            renderer.pushTranslate((float) -root.getX(), (float) -root.getY());
            root.renderWithTransitions(renderer);
            if (popup != null) popup.renderWithTransitions(renderer);
            renderer.popTranslate();
            renderer.end();
        }
        texture.update(renderer.readPixels());
    }

    /**
     * As {@link #refresh()}, first advancing the container's own animations (fades, animated backgrounds, scrolling) by
     * {@code delta} seconds, as a window does every frame.
     */
    public void refresh(float delta) {
        try (MouseTracker.Loan loan = MouseTracker.lend(pointerX, pointerY, pointing)) {
            root.update(delta);
            if (popup != null) popup.update(delta);
        }
        refresh();
    }

    /**
     * Aims the pointer at {@code uv}, a spot on the texture (top-left origin, 0 to 1 across), and hovers whatever is
     * under it. Null takes the pointer away, un-hovering everything.
     */
    public void pointAt(Vec2 uv) {
        if (uv == null) {
            pointAway();
            return;
        }
        pointing = true;
        pointerX = root.getX() + uv.x() * root.getWidth();
        pointerY = root.getY() + uv.y() * root.getHeight();
        // A press on a clickable element that drags its container turns into that drag once the pointer has moved a few points.
        if (pendingDrag != null && Math.hypot(pointerX - pendingDragX, pointerY - pendingDragY) >= DRAG_THRESHOLD) {
            Element started = pendingDrag;
            pendingDrag = null;
            clickTargets.clear();
            dragTarget = started;
            ((Draggable) started).onDragStart(pendingDragX, pendingDragY);
        }
        applyHover(topmost());
        if (dragTarget instanceof Draggable draggable) draggable.onDragUpdate(pointerX, pointerY);
    }

    /**
     * What is under the pointer: the popup's, if it is over the popup, else the container's.
     */
    private Element topmost() {
        if (popup != null) {
            Element inPopup = UiHitTest.findTopmostHit(popup, pointerX, pointerY);
            if (inPopup != null) return inPopup;
        }
        return UiHitTest.findTopmostHit(root, pointerX, pointerY);
    }

    private void applyHover(Element hit) {
        UiHitTest.applyHover(root, hit);
        if (popup != null) UiHitTest.applyHover(popup, hit);
    }

    /**
     * Aims the pointer where {@code hit} landed, if it landed on {@code screenNode}, the node showing this screen; a
     * hit on anything else, or none, takes the pointer away. That makes it the one call to make after every pick.
     */
    public void pointAt(Hit hit, Node3D screenNode) {
        pointAt(hit != null && hit.node() == screenNode ? hit.uv() : null);
    }

    /**
     * Takes the pointer away: nothing is hovered, and a press in progress will not click on release.
     */
    public void pointAway() {
        pointing = false;
        pendingDrag = null;
        applyHover(null);
    }

    /**
     * Whether the pointer is on the screen.
     */
    public boolean isPointing() {
        return pointing;
    }

    /**
     * The element under the pointer, or null when it is away or over nothing. The container itself when over its bare
     * background.
     */
    public Element hovered() {
        return pointing ? topmost() : null;
    }

    /**
     * Presses the left button at the pointer. See {@link #press(int)}.
     */
    public boolean press() {
        return press(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /**
     * Presses a mouse button at the pointer. The nearest element at or above the one pointed at that listens for
     * clicks gets it, so pressing a button's label presses the button. A button that clicks on release (most do) waits
     * for {@link #release}.
     *
     * @param button a {@code GLFW_MOUSE_BUTTON_*} number
     * @return whether the pointer was on something that takes clicks, so the press was used and is not the game's
     */
    public boolean press(int button) {
        boolean left = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (left) { // a press whose release was never seen
            clickTargets.clear();
            disabledTarget = null;
            dragTarget = null;
            pendingDrag = null;
        }
        // A press anywhere but on a field takes the keyboard away from it, as clicking away does in a window.
        if (!pointing) {
            clearFocus();
            closePopup();
            return false;
        }

        // An open popup is modal-ish: a press outside it just dismisses it, and that press is used up.
        if (popup != null && UiHitTest.findTopmostHit(popup, pointerX, pointerY) == null) {
            closePopup();
            return true;
        }

        Element hit = topmost();
        if (hit == null) {
            clearFocus();
            return false;
        }
        // A disabled element, or anything inside a disabled container, takes the press and does nothing with it.
        Element disabler = hit.getDisablingElement();
        if (disabler != null) {
            if (left) disabledTarget = disabler;
            return true;
        }

        MouseClickEvent event = new MouseClickEvent(pointerX, pointerY, button, GLFW.GLFW_PRESS);
        Element focusBefore = focused;
        boolean taken = false;
        for (Element e = hit; e != null; e = e.owner) {
            if (e.getMouseClickListenerConsumer() == null) continue;
            // A button-like element fires once the left button is released over it, not now. So does one that may turn
            // the press into a drag of its container, which a screen never does.
            if (!e.isClickOnRelease() && !(left && e.isDragsContainer())) {
                e.getMouseClickListenerConsumer().accept(event);
            } else if (left) {
                clickTargets.add(e);
            }
            taken = true;
            if (!e.isPropagateClicks()) break;
        }

        // A right press opens the context menu of the hit element, or of the nearest ancestor that has one.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            for (Element e = hit; e != null; e = e.owner) {
                if (e.getContextMenu() != null) {
                    e.getContextMenu().show(e, pointerX, pointerY);
                    taken = true;
                    break;
                }
            }
        }

        // A field takes the keyboard when pressed; anything else lets it go. A click listener that moved the focus
        // itself keeps it there.
        if (focused == focusBefore || focused == null) {
            focus(hit instanceof Focusable f && f.grabsKeyboard() ? hit : null); // a button does not take the keyboard
        }
        if (hit instanceof Focusable f && f.grabsKeyboard()) taken = true;
        if (!left) return taken;

        // The press goes to the nearest Draggable at or above the hit element. A container that does not want the drag and a
        // plain child that is not draggable hand it up to their owner. An element that listens for clicks is a control (a
        // button, a clickable row) and keeps its press, unless it drags its container, when the press waits to see whether
        // the pointer moves.
        Element candidate = hit;
        boolean throughClickable = false;
        while (candidate != null && !acceptsDrag(candidate)) {
            if (candidate.getMouseClickListenerConsumer() != null) {
                if (!candidate.isDragsContainer()) {
                    candidate = null;
                    break;
                }
                throughClickable = true;
            }
            candidate = candidate.owner;
        }
        if (candidate instanceof Draggable draggable) {
            if (throughClickable) {
                pendingDrag = candidate;
                pendingDragX = pointerX;
                pendingDragY = pointerY;
            } else {
                dragTarget = candidate;
                draggable.onDragStart(pointerX, pointerY);
            }
            taken = true;
        }
        return taken;
    }

    private boolean acceptsDrag(Element element) {
        if (!(element instanceof Draggable)) return false;
        return !(element instanceof Container container) || container.canDrag(pointerX, pointerY);
    }

    /**
     * Lets go of the left button. See {@link #release(int)}.
     */
    public void release() {
        release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /**
     * Lets go of a mouse button. The clicks that waited for the release fire for the elements the pointer is still on;
     * letting go somewhere else, or after {@link #pointAway}, cancels them. Only the left button has clicks to
     * finish.
     */
    public void release(int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        List<Element> targets = List.copyOf(clickTargets);
        clickTargets.clear();
        Element disabled = disabledTarget;
        disabledTarget = null;
        Element dragged = dragTarget;
        dragTarget = null;
        pendingDrag = null;
        if (dragged instanceof Draggable draggable) draggable.onDragEnd(pointerX, pointerY);
        if (!pointing) return;

        if (disabled != null && isUnderPointer(disabled)) disabled.fireDisabledClick();
        for (Element target : targets) {
            if (target.getMouseClickListenerConsumer() != null && !target.isEffectivelyDisabled() && isUnderPointer(target)) {
                target.getMouseClickListenerConsumer().accept(new MouseClickEvent(pointerX, pointerY, button, GLFW.GLFW_RELEASE));
            }
        }
    }

    /**
     * Points at {@code hit} and clicks the left button there: {@link #pointAt(Hit, Node3D)}, {@link #press()},
     * {@link #release()}. For a click that does not need to be held.
     *
     * @return whether the click landed on something that takes clicks
     */
    public boolean click(Hit hit, Node3D screenNode) {
        pointAt(hit, screenNode);
        boolean taken = press();
        release();
        return taken;
    }

    /**
     * Turns the wheel over whatever scrolls at the pointer, a list or a text area, the innermost first.
     *
     * @return whether something scrolled
     */
    public boolean scroll(double dx, double dy) {
        if (!pointing) return false;
        Element target = null;
        if (popup != null) target = UiHitTest.findScrollTarget(popup, pointerX, pointerY, dx, dy);
        if (target == null) target = UiHitTest.findScrollTarget(root, pointerX, pointerY, dx, dy);
        if (target instanceof Scrollable scrollable) {
            scrollable.onScroll(dx, dy);
            return true;
        }
        return false;
    }

    /**
     * The element that has the keyboard, a text field the player pressed or {@link #focus} gave it to, or null. One that
     * has been disabled since loses it here.
     */
    public Element focused() {
        if (focused != null && focused.isEffectivelyDisabled()) clearFocus();
        return focused;
    }

    /**
     * Whether an element on this screen has the keyboard. While it does, the game should send keys and typed
     * characters here ({@link #key}, {@link #typeChar}) instead of using them to move the player.
     */
    public boolean hasFocus() {
        return focused() != null || popup instanceof PopupKeyListener;
    }

    /**
     * Gives the keyboard to {@code element}, telling the one that had it. Anything that cannot take keys (not a
     * {@link Focusable}) or is disabled clears the focus instead. Nothing happens if it already has it.
     */
    public void focus(Element element) {
        if (!(element instanceof Focusable) || element.isEffectivelyDisabled()) element = null;
        if (focused == element) return;
        if (focused instanceof Focusable old) old.onFocusLost();
        focused = element;
        if (focused instanceof Focusable now) now.onFocusGained();
    }

    /**
     * Takes the keyboard away from whatever has it. The game calls this when the player walks away from the screen or
     * otherwise stops typing.
     */
    public void clearFocus() {
        focus(null);
    }

    /**
     * Types a character, a Unicode code point as {@code Viewport3D.InputListener.charTyped} gives it, into the focused
     * element.
     *
     * @return whether something had the keyboard to take it
     */
    public boolean typeChar(int codepoint) {
        if (focused() instanceof Focusable field && field.grabsKeyboard()) {
            field.onCharTyped(codepoint);
            return true;
        }
        return false;
    }

    /**
     * Sends a key to the focused element: the arrows, Home, End, Backspace, Delete, Enter, and shortcuts such as
     * Ctrl+A, C, V, X and Z. Tab and Shift+Tab move to the next and previous field on this screen (unless the focused
     * element wants Tab for itself), and Escape lets go of the keyboard.
     * <p>
     * A key being released is passed on to the element but is never reported as taken, so a key the game saw go down
     * before the field was focused still comes up for the game.
     *
     * @param key    a {@code GLFW_KEY_*} number
     * @param action {@code GLFW_PRESS}, {@code GLFW_REPEAT} or {@code GLFW_RELEASE}
     * @param mods   a GLFW modifier bitmask
     * @return whether a field had the keyboard and used the press or repeat; false means it is the game's
     */
    public boolean key(int key, int action, int mods) {
        boolean released = action == GLFW.GLFW_RELEASE;
        // An open menu takes every key and keeps it; Escape closes any popup.
        if (popup instanceof PopupKeyListener listener) {
            if (!released) listener.onPopupKey(key, mods);
            return !released;
        }
        if (popup != null && key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            closePopup();
            return true;
        }
        if (!(focused() instanceof Focusable field)) return false;
        if (key == GLFW.GLFW_KEY_TAB && !released) {
            if (field.wantsTab()) {
                field.onKeyPressed(key, action, mods);
            } else {
                moveFocus((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            clearFocus();
            return true;
        }
        if (!field.grabsKeyboard() && !field.handlesKey(key, mods)) return false; // a focused button leaves the other keys to the game
        field.onKeyPressed(key, action, mods);
        return !released;
    }

    /**
     * Moves the keyboard to the next field in Tab order, or the first if none has it, wrapping around at the end.
     * Nothing happens on a screen with no field. {@link #key} does this on Tab.
     */
    public void focusNext() {
        moveFocus(1);
    }

    /**
     * As {@link #focusNext()}, backwards: the previous field, or the last if none has the keyboard.
     */
    public void focusPrevious() {
        moveFocus(-1);
    }

    private void moveFocus(int direction) {
        List<Element> stops = new ArrayList<>();
        UiHitTest.collectFocusStops(root, stops);
        if (stops.isEmpty()) return;
        UiHitTest.sortTabOrder(stops);
        int current = stops.indexOf(focused());
        int next = current < 0
                ? (direction > 0 ? 0 : stops.size() - 1)
                : Math.floorMod(current + direction, stops.size());
        focus(stops.get(next));
    }

    /**
     * Whether {@code element} is, or contains, the topmost thing under the pointer.
     */
    private boolean isUnderPointer(Element element) {
        Element hit = UiHitTest.findTopmostHit(root, pointerX, pointerY);
        for (Element e = hit; e != null; e = e.owner) {
            if (e == element) return true;
        }
        return false;
    }

    // ---- popups ---------------------------------------------------------------------------------------------------

    /**
     * Opens {@code popup} over the container, closing any popup that is open. Done by the elements that have popups (a dropdown,
     * a menu), not by the game; see {@link PopupHost}.
     */
    @Override
    public void showPopup(Container popup, Element anchor, Runnable onDismiss) {
        closePopup();
        this.popup = popup;
        popup.setPopupHost(this);
        this.popupDismiss = onDismiss;
        this.popupAnchor = anchor;
        if (anchor != null) {
            anchorStartX = anchor.getAbsoluteX();
            anchorStartY = anchor.getAbsoluteY();
            popupShiftX = 0;
            popupShiftY = 0;
            double anchorBottom = anchorStartY + anchor.getHeight();
            double popupBottom = popup.getY() + popup.getHeight();
            if (popup.getY() >= anchorBottom - 0.5) {
                popupSide = 1;
                popupGap = popup.getY() - anchorBottom;
            } else if (popupBottom <= anchorStartY + 0.5) {
                popupSide = -1;
                popupGap = anchorStartY - popupBottom;
            } else {
                popupSide = 0;
                popupGap = 0;
            }
        }
        keepPopupInside();
    }

    @Override
    public void closePopup() {
        if (popup == null) return;
        Container closing = popup;
        Runnable listener = popupDismiss;
        popup = null;
        popupDismiss = null;
        popupAnchor = null;
        closing.setPopupHost(null);
        if (listener != null) listener.run();
        if (pointing) applyHover(topmost());
    }

    @Override
    public Container getPopup() {
        return popup;
    }

    /**
     * The container's own rectangle: a popup is kept inside it.
     */
    @Override
    public double[] getPopupArea() {
        return new double[]{root.getX(), root.getY(), root.getWidth(), root.getHeight()};
    }

    @Override
    public Element getFocusedElement() {
        return focused();
    }

    @Override
    public void requestFocus(Element element) {
        focus(element);
    }

    /**
     * Moves the popup to keep its offset from its anchor, flipped above or below it when it no longer fits, inside the
     * container; closes it if the anchor is gone.
     */
    private void followPopupAnchor() {
        Container open = popup;
        Element anchor = popupAnchor;
        if (open == null || anchor == null) return;
        if (!isShowing(anchor)) {
            closePopup();
            return;
        }
        double baseX = open.getX() - popupShiftX, baseY = open.getY() - popupShiftY;
        double width = open.getWidth(), height = open.getHeight();
        double[] area = getPopupArea();
        double top = area[1], bottom = area[1] + area[3];
        double anchorTop = anchor.getAbsoluteY(), anchorBottom = anchorTop + anchor.getHeight();
        double x = baseX + anchor.getAbsoluteX() - anchorStartX;
        double y = baseY + anchorTop - anchorStartY;
        if (popupSide > 0 && y + height > bottom && anchorTop - popupGap - height >= top) {
            y = anchorTop - popupGap - height;
        } else if (popupSide < 0 && y < top && anchorBottom + popupGap + height <= bottom) {
            y = anchorBottom + popupGap;
        }
        x = Math.max(area[0], Math.min(x, area[0] + area[2] - width));
        y = Math.max(top, Math.min(y, bottom - height));
        popupShiftX = x - baseX;
        popupShiftY = y - baseY;
        if (x != open.getX()) open.setX(x);
        if (y != open.getY()) open.setY(y);
    }

    private void keepPopupInside() {
        if (popup == null) return;
        double[] area = getPopupArea();
        double x = Math.max(area[0], Math.min(popup.getX(), area[0] + area[2] - popup.getWidth()));
        double y = Math.max(area[1], Math.min(popup.getY(), area[1] + area[3] - popup.getHeight()));
        if (x != popup.getX()) popup.setX(x);
        if (y != popup.getY()) popup.setY(y);
    }

    /**
     * Whether {@code element} is still on this screen: in the container, enabled along with everything it is in.
     */
    private boolean isShowing(Element element) {
        Element node = element;
        while (node.owner != null) {
            Container owner = node.owner;
            if (!node.isEnabled() || !owner.getElements().containsValue(node)) return false;
            node = owner;
        }
        return node == root && root.isEnabled();
    }

    @Override
    public void close() {
        closePopup();
        root.setPopupHost(null);
        renderer.close();
    }
}
