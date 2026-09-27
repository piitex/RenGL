package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import me.piitex.engine.utils.flags.GeneratedFragments;

@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class InputProcessor {
    private double absoluteMouseX = 0.0;
    private double absoluteMouseY = 0.0;
    private boolean mouseInWindow = true;
    /**
     * The Element a Draggable press started on, or null between drags. See {@link Draggable}.
     */
    private Element dragTarget;

    public void processEvents(Window window) {
        InputManager manager = window.getInputManager();
        window.getInput().beginFrame();
        InputEvent event;

        while ((event = manager.getEventQueue().poll()) != null) {
            switch (event.type) {
                case MOUSE_MOVE:
                    window.getInput().mouseMoved(event.x, event.y, isInsideWindow(window, event.x, event.y));
                    // GLFW's enter callback is edge-triggered, and on macOS a live window resize sends a
                    // "left" without a matching "entered" once the drag ends (the tracking area is rebuilt
                    // under the cursor). A move that lands inside the window is proof the cursor is back.
                    if (!mouseInWindow && isInsideWindow(window, event.x, event.y)) {
                        mouseInWindow = true;
                        MouseTracker.setInWindow(true);
                    }
                    if (mouseInWindow) handleMouseMove(window, event);
                    break;
                case MOUSE_ENTER:
                    mouseInWindow = event.action == 1;
                    MouseTracker.setInWindow(mouseInWindow);
                    window.getInput().mouseEntered(mouseInWindow);
                    if (!mouseInWindow) clearAllHover(window);
                    break;
                case MOUSE_BUTTON:
                    handleMouseButton(window, event);
                    break;
                case KEY:
                    handleKey(window, event);
                    break;
                case CHAR:
                    handleChar(window, event);
                    break;
                case SCROLL:
                    handleScroll(window, event);
                    break;
            }
        }
    }

    private static boolean isInsideWindow(Window window, double x, double y) {
        return x >= 0 && y >= 0
                && x < window.getWindowOptions().getWidth()
                && y < window.getWindowOptions().getHeight();
    }

    private void clearAllHover(Window window) {
        for (Container container : window.getContainers().values()) {
            applyHover(container, null);
        }
        CursorManager.request(null); // nothing is hovered anymore, so back to the default cursor
    }

    private void handleMouseMove(Window window, InputEvent event) {
        absoluteMouseX = event.x;
        absoluteMouseY = event.y;

        absoluteMouseX = Math.max(0, Math.min(absoluteMouseX, window.getWindowOptions().getWidth()));
        absoluteMouseY = Math.max(0, Math.min(absoluteMouseY, window.getWindowOptions().getHeight()));
        MouseTracker.update(absoluteMouseX, absoluteMouseY);

        // Find the single topmost (highest z-index) thing under the cursor across all
        // top-level containers. The first container whose bounds contain the point
        // wins, and findTopmostHit descends into it as deep as the nesting goes.
        Element hit = null;
        for (Map.Entry<Integer, Container> entry : window.getContainers().descendingMap().entrySet()) {
            Container container = entry.getValue();
            if (!container.isEnabled()) continue;
            if (hit == null) {
                hit = findTopmostHit(container, absoluteMouseX, absoluteMouseY);
            }
        }

        // Mark hovered=true on `hit` and every Container containing it, false on
        // everything else. This is a second, separate pass so stale hover deep in a
        // subtree the cursor has left is cleared even though it is not on the path
        // to `hit`.
        for (Container container : window.getContainers().values()) {
            applyHover(container, hit);
        }

        // Whatever is now hovered decides the OS cursor. Null, meaning nothing hovered
        // or something hovered that specifies no mode, falls back to CursorMode.DEFAULT.
        // While a drag is in progress the dragged Element keeps the cursor, even once
        // the mouse has left it, as when a container is resized past its own edge.
        // An Element with no cursor of its own takes the one of the nearest ancestor that has one, so hovering a
        // label inside a pointer-cursor row still shows the pointer.
        CursorMode cursor = null;
        for (Element e = dragTarget != null ? dragTarget : hit; e != null && cursor == null; e = e.owner) {
            cursor = e.getCursorMode();
        }
        CursorManager.request(cursor);

        if (dragTarget instanceof Draggable draggable) {
            draggable.onDragUpdate(absoluteMouseX, absoluteMouseY);
        }
    }

    /**
     * Sets hovered on {@code container} and its whole subtree: true for {@code hit} and every Container on the path to it, false for everything else. Returns whether {@code container} itself is on that path.
     */
    private boolean applyHover(Container container, Element hit) {
        boolean onPath = container == hit;
        for (Element child : container.getElements().values()) {
            if (child instanceof Container childContainer) {
                if (applyHover(childContainer, hit)) {
                    onPath = true;
                }
            } else {
                boolean hovered = child == hit;
                child.setHovered(hovered);
                if (hovered) onPath = true;
            }
        }
        container.setHovered(onPath);
        return onPath;
    }

    /**
     * Recursively finds the topmost (highest z-index) Element at (px, py) within
     * container's subtree, descending into any child that is itself a Container. The
     * recursion is what lets a Focusable or Draggable nested inside a Layout inside a
     * plain Container be found; that nesting is ordinary, since Layout extends Container,
     * and a search that stopped at the immediate parent would never reach the Element
     * itself. Returns {@code container} if it contains the point but nothing inside it,
     * at any depth, does, or null if the point is not in {@code container} at all.
     */
    private Element findTopmostHit(Container container, double px, double py) {
        if (!container.isEnabled() || !container.contains(px, py)) return null;
        if (container.interceptsPointer(px, py)) return container;

        for (Map.Entry<Integer, Element> entry : container.getElements().descendingMap().entrySet()) {
            Element child = entry.getValue();
            if (!child.isEnabled() || !child.contains(px, py)) continue;

            if (child instanceof Container childContainer) {
                return findTopmostHit(childContainer, px, py);
            }
            return child;
        }
        return container;
    }

    private void handleMouseButton(Window window, InputEvent event) {
        Input input = window.getInput();
        if (event.action == GLFW.GLFW_RELEASE) {
            input.mouseReleased(event.buttonOrKey);
            // Only the button that began a drag (always the left one) ends it; releasing a right-click mid-drag must not.
            if (event.buttonOrKey != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (dragTarget instanceof Draggable draggable) {
                draggable.onDragEnd(absoluteMouseX, absoluteMouseY);
            }
            dragTarget = null;
            return;
        }

        if (event.action != GLFW.GLFW_PRESS) {
            return; // ignore anything that isn't a fresh press/release (mouse buttons don't repeat)
        }

        if (input.captureMouse(event.buttonOrKey, event.mods)) return; // a controls menu waiting for a binding

        // An open popup (dropdown list, ...) is modal-ish: a press outside it just dismisses it.
        if (window.getPopup() != null && !window.getPopup().contains(absoluteMouseX, absoluteMouseY)) {
            window.closePopup();
            return;
        }

        MouseClickEvent clickEvent = new MouseClickEvent(absoluteMouseX, absoluteMouseY, event.buttonOrKey, event.action);
        Element hitElement = null;
        // Whether the interface used the press. If nothing did, it is game input.
        boolean taken = window.getPopup() != null || window.getModal() != null;

        for (Map.Entry<Integer, Container> entry : window.getContainers().descendingMap().entrySet()) {
            Container container = entry.getValue();
            if (!container.isEnabled() || !container.contains(absoluteMouseX, absoluteMouseY)) continue;

            hitElement = findTopmostHit(container, absoluteMouseX, absoluteMouseY);
            // The click goes to the nearest Element at or above the hit one that listens for it, so pressing on the
            // label or icon inside a clickable row or button counts as pressing the row or button.
            for (Element e = hitElement; e != null; e = e.owner) {
                if (e.getMouseClickListenerConsumer() != null) {
                    e.getMouseClickListenerConsumer().accept(clickEvent);
                    taken = true;
                    break;
                }
            }
            break;
        }

        // A right-click opens the context menu of the hit Element, or of the nearest ancestor that has one.
        if (event.buttonOrKey == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            for (Element e = hitElement; e != null; e = e.owner) {
                if (e.getContextMenu() != null) {
                    e.getContextMenu().show(e, absoluteMouseX, absoluteMouseY);
                    taken = true;
                    break;
                }
            }
        }

        // Clicking a Focusable element (e.g. a text field) grants it keyboard focus;
        // clicking anything else, including empty space, clears focus.
        window.requestFocus(hitElement instanceof Focusable ? hitElement : null);
        if (hitElement instanceof Focusable) taken = true;

        // Only the left button drags; a right-click opens a context menu and must not start a drag or a text selection.
        if (event.buttonOrKey != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!taken) input.mousePressed(event.buttonOrKey, event.mods);
            return;
        }

        // The press goes to the nearest Draggable at or above the hit Element. A container that doesn't want the
        // drag (typically a layout filling a draggable/resizable parent) and a plain child that is not Draggable at
        // all (a label or image sitting on a draggable card) both hand it up to their owner. An Element that listens
        // for clicks is a control (a button, a clickable row) and keeps its press: nothing above it is dragged.
        Element dragCandidate = hitElement;
        while (dragCandidate != null && !acceptsDrag(dragCandidate)) {
            dragCandidate = dragCandidate.getMouseClickListenerConsumer() != null ? null : dragCandidate.owner;
        }

        if (dragCandidate instanceof Draggable draggable) {
            dragTarget = dragCandidate;
            draggable.onDragStart(absoluteMouseX, absoluteMouseY);
            taken = true;
        }
        if (!taken) input.mousePressed(event.buttonOrKey, event.mods);
    }

    private boolean acceptsDrag(Element element) {
        if (!(element instanceof Draggable)) return false;
        return !(element instanceof Container container) || container.canDrag(absoluteMouseX, absoluteMouseY);
    }

    /**
     * Routes a wheel event to whatever {@link Scrollable} is under the cursor's last
     * known position, since scroll events carry no position of their own and GLFW
     * reports only the offsets. This uses the same top-level-container z-order priority
     * as {@link #handleMouseButton}, where the topmost container containing the point
     * claims it. {@link #findScrollTarget} then descends, preferring the innermost
     * Scrollable. Unlike {@link #findTopmostHit}, it does not stop at the first Element
     * hit, so scrolling works anywhere inside the Scrollable's area rather than only
     * over its empty background.
     */
    private void handleScroll(Window window, InputEvent event) {
        Input input = window.getInput();
        if (input.captureScroll(event.x, event.y)) return;
        boolean taken = window.getPopup() != null || window.getModal() != null;
        for (Map.Entry<Integer, Container> entry : window.getContainers().descendingMap().entrySet()) {
            Container container = entry.getValue();
            if (!container.isEnabled() || !container.contains(absoluteMouseX, absoluteMouseY)) continue;

            Element target = findScrollTarget(container, absoluteMouseX, absoluteMouseY, event.x, event.y);
            if (target instanceof Scrollable scrollable) {
                scrollable.onScroll(event.x, event.y);
                taken = true;
            }
            break;
        }
        if (!taken) input.scrolled(event.x, event.y); // nothing scrolled: the wheel is game input
    }

    /**
     * Recursively finds the innermost enabled {@link Scrollable} Element whose bounds
     * contain (px, py), preferring a nested one over an outer one it's inside of.
     * Returns null if nothing in this subtree is Scrollable at that point. A Scrollable that
     * reports {@link Scrollable#canScroll} false for this event is skipped, so the wheel falls
     * through to the enclosing one (e.g. an auto-height text block inside a ScrollContainer).
     * <p>
     * A plain, non-Container child that implements {@link Scrollable}, such as
     * {@link me.piitex.engine.ui.overlays.TextAreaOverlay} with its built-in scrolling,
     * is checked directly, the same way {@link #findTopmostHit} treats any Element,
     * Container or not, as a valid hit. Only a {@link Container} recurses further first,
     * since a plain Element has no children to descend into.
     */
    private Element findScrollTarget(Container container, double px, double py, double dx, double dy) {
        if (!container.isEnabled() || !container.contains(px, py)) return null;

        for (Map.Entry<Integer, Element> entry : container.getElements().descendingMap().entrySet()) {
            Element child = entry.getValue();
            if (!child.isEnabled() || !child.contains(px, py)) continue;
            if (child instanceof Container childContainer) {
                Element nested = findScrollTarget(childContainer, px, py, dx, dy);
                if (nested != null) return nested;
            } else if (child instanceof Scrollable scrollable && scrollable.canScroll(dx, dy)) {
                return child;
            }
        }

        return container instanceof Scrollable scrollable && scrollable.canScroll(dx, dy) ? container : null;
    }

    /**
     * Tab/Shift+Tab moves focus (see {@link Window#focusNext()}/{@link Window#focusPrevious()})
     * before it ever reaches the focused Element's own {@link Focusable#onKeyPressed}. None of
     * this engine's built-in Focusables, such as the single-line
     * {@link me.piitex.engine.ui.overlays.TextFieldOverlay}, act on a raw Tab keystroke, so
     * nothing is taken away from them. While nothing has focus and no popup or modal is open,
     * the game gets Tab first, and it moves focus only if no scene or input context uses it.
     */
    private void handleKey(Window window, InputEvent event) {
        Input input = window.getInput();
        if (input.captureKey(event.buttonOrKey, event.action, event.mods)) return; // a controls menu waiting for a binding

        // A release always reaches the game as well, so a key held down in the game and let go while a text field
        // or menu has the keyboard isn't left stuck down there.
        if (event.action == GLFW.GLFW_RELEASE) {
            window.getSceneManager().dispatchKey(event.buttonOrKey, event.action, event.mods);
            input.keyReleased(event.buttonOrKey);
        }

        // An open popup that wants the keyboard (a menu) gets every key first and keeps it.
        if (window.getPopup() instanceof PopupKeyListener listener) {
            if (event.action != GLFW.GLFW_RELEASE) listener.onPopupKey(event.buttonOrKey, event.mods);
            return;
        }

        if (event.buttonOrKey == GLFW.GLFW_KEY_TAB && event.action != GLFW.GLFW_RELEASE) {
            boolean uiHasKeyboard = window.getFocusedElement() != null || window.getPopup() != null || window.getModal() != null;
            if (uiHasKeyboard || !sendKeyToGame(window, event)) {
                if ((event.mods & GLFW.GLFW_MOD_SHIFT) != 0) {
                    window.focusPrevious();
                } else {
                    window.focusNext();
                }
            }
            return;
        }

        if (event.buttonOrKey == GLFW.GLFW_KEY_ESCAPE && event.action == GLFW.GLFW_PRESS && window.getPopup() != null) {
            window.closePopup();
            return;
        }

        if (event.buttonOrKey == GLFW.GLFW_KEY_ESCAPE && event.action == GLFW.GLFW_PRESS
                && window.getModal() != null && window.getModal().isDismissOnEscape()) {
            window.getModal().close();
            return;
        }

        if (window.getFocusedElement() instanceof Focusable focusable) {
            focusable.onKeyPressed(event.buttonOrKey, event.action, event.mods);
            return;
        }

        // Nothing in the UI wants the key, so it is game input. A popup or modal keeps it from the game underneath.
        if (event.action != GLFW.GLFW_RELEASE && window.getPopup() == null && window.getModal() == null) {
            sendKeyToGame(window, event);
        }
    }

    /**
     * A key press or repeat for the game: the scenes' {@link me.piitex.engine.game.Scene#onKey} first, then the
     * polled {@link Input} state and its contexts. Returns whether a scene consumed it or a context uses it.
     */
    private boolean sendKeyToGame(Window window, InputEvent event) {
        boolean consumed = window.getSceneManager().dispatchKey(event.buttonOrKey, event.action, event.mods);
        Input input = window.getInput();
        if (event.action == GLFW.GLFW_REPEAT) {
            return input.keyRepeated(event.buttonOrKey, consumed) || consumed;
        }
        return input.keyPressed(event.buttonOrKey, event.mods, consumed) || consumed;
    }

    private void handleChar(Window window, InputEvent event) {
        if (window.getInput().swallowsChars()) return; // the key that typed it was just bound in a controls menu
        if (window.getFocusedElement() instanceof Focusable focusable) {
            focusable.onCharTyped(event.buttonOrKey);
        }
    }
}