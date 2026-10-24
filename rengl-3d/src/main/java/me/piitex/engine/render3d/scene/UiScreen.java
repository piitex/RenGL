package me.piitex.engine.render3d.scene;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.input.UiHitTest;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
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
 * Not done: dragging anything but text selection, popups such as a dropdown's list, and context menus.
 * <p>
 * Not thread-safe: draw and point from one thread, the way a window's interface is used. Close the screen when it is no
 * longer shown; the texture it made stays valid.
 */
public final class UiScreen implements AutoCloseable {
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
        renderer.begin();
        // The container draws itself at its own x/y, so shift that to the corner of the texture.
        renderer.pushTranslate((float) -root.getX(), (float) -root.getY());
        root.renderWithTransitions(renderer);
        renderer.popTranslate();
        renderer.end();
        texture.update(renderer.readPixels());
    }

    /**
     * As {@link #refresh()}, first advancing the container's own animations (fades, animated backgrounds, scrolling) by
     * {@code delta} seconds, as a window does every frame.
     */
    public void refresh(float delta) {
        root.update(delta);
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
        UiHitTest.applyHover(root, UiHitTest.findTopmostHit(root, pointerX, pointerY));
        if (dragTarget instanceof Draggable draggable) draggable.onDragUpdate(pointerX, pointerY);
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
        UiHitTest.applyHover(root, null);
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
        return pointing ? UiHitTest.findTopmostHit(root, pointerX, pointerY) : null;
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
        }
        // A press anywhere but on a field takes the keyboard away from it, as clicking away does in a window.
        if (!pointing) {
            clearFocus();
            return false;
        }

        Element hit = UiHitTest.findTopmostHit(root, pointerX, pointerY);
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

        // A field takes the keyboard when pressed; anything else lets it go. A click listener that moved the focus
        // itself keeps it there.
        if (focused == focusBefore || focused == null) {
            focus(hit instanceof Focusable ? hit : null);
        }
        if (hit instanceof Focusable) taken = true;
        // The text field puts its caret where it was pressed and selects text as the pointer moves until the release.
        if (left && hit instanceof Focusable && hit instanceof Draggable draggable) {
            dragTarget = hit;
            draggable.onDragStart(pointerX, pointerY);
        }
        return taken;
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
        Element target = UiHitTest.findScrollTarget(root, pointerX, pointerY, dx, dy);
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
        return focused() != null;
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
        if (focused() instanceof Focusable field) {
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
        if (!(focused() instanceof Focusable field)) return false;
        boolean released = action == GLFW.GLFW_RELEASE;
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

    @Override
    public void close() {
        renderer.close();
    }
}
