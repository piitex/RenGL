package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.FileDraggable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.PointerInput;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
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
    /**
     * The Elements a left press started on whose click waits for the release (click-on-release ones, and ones that
     * {@link Element#setDragsContainer drag their container}), empty between presses. Each click fires if the release
     * lands on it.
     */
    private final List<Element> clickTargets = new ArrayList<>();
    /**
     * The {@link PointerInput} element the pointer is over with no button held, or null.
     */
    private PointerInput pointerHover;
    /**
     * The {@link PointerInput} element that took a press and so keeps the pointer until every button is released, or
     * null, and the buttons held (bit n for button n).
     */
    private PointerInput pointerCapture;
    private int pointerButtons;
    /**
     * The disabled Element a left press landed on, whose {@link Element#onDisabledClick} runs if the release lands on
     * it too, or null.
     */
    private Element disabledTarget;
    /**
     * The Draggable a press on a drag-through Element will start dragging once the mouse has moved
     * {@link #DRAG_THRESHOLD} points, or null. See {@link Element#setDragsContainer}.
     */
    private Element pendingDrag;
    private double pendingDragX, pendingDragY;
    private static final double DRAG_THRESHOLD = 4.0;

    /**
     * A held D-pad or stick direction repeats as an arrow key after this long, and then at the interval, like a held key.
     */
    private static final long PAD_REPEAT_DELAY_NANOS = 400_000_000L;
    private static final long PAD_REPEAT_NANOS = 100_000_000L;
    /**
     * The arrow key a held controller direction is repeating as, or -1, and the controller input that holds it.
     */
    private int padRepeatKey = -1;
    private int padRepeatSource = -1;
    private long padRepeatNext;

    public void processEvents(Window window) {
        InputManager manager = window.getInputManager();
        window.getInput().beginFrame();
        InputEvent event;

        while ((event = manager.getEventQueue().poll()) != null) {
            switch (event.type) {
                case MOUSE_MOVE:
                    // The window keeps the pointer while a button is held, so a drag that goes past its edge still reports
                    // positions there, outside it.
                    if (dragTarget != null && !isInsideWindow(window, event.x, event.y)) handOffDragToSystem(window);
                    window.getInput().mouseMoved(event.x, event.y, isInsideWindow(window, event.x, event.y));
                    // GLFW's enter callback is edge-triggered, and on macOS a live window resize sends a
                    // "left" without a matching "entered" once the drag ends (the tracking area is rebuilt
                    // under the cursor). A move that lands inside the window is proof the cursor is back.
                    if (!mouseInWindow && isInsideWindow(window, event.x, event.y)) {
                        mouseInWindow = true;
                        MouseTracker.setInWindow(true);
                    }
                    if (mouseInWindow && !window.isInputLocked()) handleMouseMove(window, event);
                    break;
                case MOUSE_ENTER:
                    mouseInWindow = event.action == 1;
                    if (!mouseInWindow && dragTarget != null) handOffDragToSystem(window);
                    MouseTracker.setInWindow(mouseInWindow);
                    window.getInput().mouseEntered(mouseInWindow);
                    if (!mouseInWindow) clearAllHover(window);
                    break;
                case MOUSE_BUTTON:
                    // A release still goes through, so a drag or held button that began before the lock can end.
                    if (event.action != GLFW.GLFW_RELEASE && window.swallowInput(true)) break;
                    handleMouseButton(window, event);
                    break;
                case KEY:
                    // Only a fresh press skips, so the key that started the transition, held down, cannot end it.
                    if (event.action != GLFW.GLFW_RELEASE && window.swallowInput(event.action == GLFW.GLFW_PRESS)) break;
                    handleKey(window, event);
                    break;
                case CHAR:
                    if (window.swallowInput(false)) break;
                    handleChar(window, event);
                    break;
                case SCROLL:
                    if (window.swallowInput(false)) break;
                    handleScroll(window, event);
                    break;
                case PREEDIT:
                    if (usableFocus(window) instanceof Focusable focusable && focusable.acceptsTextInput()) focusable.onPreedit(event.preedit);
                    break;
            }
        }
        updateIme(window);
        handleGamepads(window, window.isFocused(), System.nanoTime());
    }

    /**
     * Takes this frame's controller changes and sends each press where a key would go: to a prompt waiting for a binding,
     * else to the interface if it has the keyboard (as the arrow keys, Enter, Escape and Tab), else to the game. Releases
     * always reach the game, so nothing is left held.
     */
    void handleGamepads(Window window, boolean focused, long now) {
        Input input = window.getInput();
        for (Input.GamepadEdge edge : input.pollGamepads(focused, uiOwnsGamepad(window))) {
            if (!edge.pressed()) {
                input.gamepadReleased(edge);
                if (Binding.padSource(edge.pad().getIndex(), edge.device(), edge.code()) == padRepeatSource) padRepeatKey = -1;
                continue;
            }
            if (window.swallowInput(true)) continue;
            if (input.captureGamepad(edge)) continue;
            if (uiOwnsGamepad(window)) {
                uiGamepadPress(window, edge, now);
            } else {
                input.gamepadPressed(edge);
            }
        }
        if (padRepeatKey >= 0) {
            if (!uiOwnsGamepad(window)) {
                padRepeatKey = -1;
            } else if (now >= padRepeatNext) {
                padRepeatNext = now + PAD_REPEAT_NANOS;
                handleKey(window, new InputEvent(InputEvent.Type.KEY, now, 0, 0, padRepeatKey, GLFW.GLFW_REPEAT, 0));
            }
        }
    }

    /**
     * Whether the interface has the controller: a popup or modal is open, or an element has the keyboard. The same
     * condition keeps keys from the game.
     */
    private boolean uiOwnsGamepad(Window window) {
        return window.getPopup() != null || window.getModal() != null || usableFocus(window) != null;
    }

    /**
     * A controller press for the interface, as the key it stands for: the D-pad and the left stick are the arrow keys, A is
     * Enter, B is Escape, and the bumpers are Tab and Shift+Tab. A held direction repeats. Anything else does nothing.
     */
    private void uiGamepadPress(Window window, Input.GamepadEdge edge, long now) {
        int key = uiKeyFor(edge);
        if (key < 0) return;
        boolean previous = edge.device() == Binding.Device.GAMEPAD_BUTTON && edge.code() == Gamepad.Button.LEFT_BUMPER.code();
        handleKey(window, new InputEvent(InputEvent.Type.KEY, now, 0, 0, key, GLFW.GLFW_PRESS, previous ? GLFW.GLFW_MOD_SHIFT : 0));
        if (key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_UP) {
            padRepeatKey = key;
            padRepeatSource = Binding.padSource(edge.pad().getIndex(), edge.device(), edge.code());
            padRepeatNext = now + PAD_REPEAT_DELAY_NANOS;
        }
    }

    private static int uiKeyFor(Input.GamepadEdge edge) {
        if (edge.device() == Binding.Device.GAMEPAD_BUTTON) {
            Gamepad.Button button = Gamepad.Button.of(edge.code());
            return button == null ? -1 : switch (button) {
                case A -> GLFW.GLFW_KEY_ENTER;
                case B -> GLFW.GLFW_KEY_ESCAPE;
                case DPAD_UP -> GLFW.GLFW_KEY_UP;
                case DPAD_DOWN -> GLFW.GLFW_KEY_DOWN;
                case DPAD_LEFT -> GLFW.GLFW_KEY_LEFT;
                case DPAD_RIGHT -> GLFW.GLFW_KEY_RIGHT;
                case LEFT_BUMPER, RIGHT_BUMPER -> GLFW.GLFW_KEY_TAB;
                default -> -1;
            };
        }
        // Stick directions are twice the axis, plus one for the right or down side
        return switch (edge.code()) {
            case 0 -> GLFW.GLFW_KEY_LEFT;
            case 1 -> GLFW.GLFW_KEY_RIGHT;
            case 2 -> GLFW.GLFW_KEY_UP;
            case 3 -> GLFW.GLFW_KEY_DOWN;
            default -> -1;
        };
    }

    private static boolean isInsideWindow(Window window, double x, double y) {
        return x >= 0 && y >= 0
                && x < window.getWindowOptions().getWidth()
                && y < window.getWindowOptions().getHeight();
    }

    /**
     * The pointer left the window while an Element was being dragged. If that Element can carry on as a drag of files
     * (see {@link FileDraggable}), its own drag is cancelled and the operating system's drag out of the window starts,
     * so what it carries can be dropped on Explorer or the desktop. Otherwise nothing changes.
     */
    private void handOffDragToSystem(Window window) {
        if (!(dragTarget instanceof FileDraggable out) || !(dragTarget instanceof Draggable draggable) || !FileDrag.isSupported()) return;
        FileDrag.Source source = out.fileDragOut();
        if (source == null) return;
        dragTarget = null;
        pendingDrag = null;
        clickTargets.clear();
        draggable.onDragCancel();
        clearAllHover(window);
        // Asks for the OS drag; the button release it ends with is put back into the queue by the window.
        window.startFileDrag(source, null);
    }

    private void clearAllHover(Window window) {
        if (pointerCapture == null && pointerHover != null) {
            pointerHover.onPointerLeave();
            pointerHover = null;
        }
        for (Container container : window.getContainers().values()) {
            UiHitTest.applyHover(container, null);
        }
        CursorManager.request(null); // nothing is hovered anymore, so back to the default cursor
    }

    private void handleMouseMove(Window window, InputEvent event) {
        absoluteMouseX = event.x;
        absoluteMouseY = event.y;

        absoluteMouseX = Math.max(0, Math.min(absoluteMouseX, window.getWindowOptions().getWidth()));
        absoluteMouseY = Math.max(0, Math.min(absoluteMouseY, window.getWindowOptions().getHeight()));
        MouseTracker.update(absoluteMouseX, absoluteMouseY);

        // A press on a clickable Element that drags its container turns into that drag once the mouse has moved a few
        // points; until then it is still a click.
        if (pendingDrag != null && Math.hypot(absoluteMouseX - pendingDragX, absoluteMouseY - pendingDragY) >= DRAG_THRESHOLD) {
            Element started = pendingDrag;
            pendingDrag = null;
            clickTargets.clear();
            dragTarget = started;
            ((Draggable) started).onDragStart(pendingDragX, pendingDragY);
        }

        // Find the single topmost (highest z-index) thing under the cursor across all
        // top-level containers. The first container whose bounds contain the point
        // wins, and UiHitTest.findTopmostHit descends into it as deep as the nesting goes.
        Element hit = null;
        for (Map.Entry<Integer, Container> entry : window.getContainers().descendingMap().entrySet()) {
            Container container = entry.getValue();
            if (!container.isEnabled()) continue;
            if (hit == null) {
                hit = UiHitTest.findTopmostHit(container, absoluteMouseX, absoluteMouseY);
            }
        }

        // Mark hovered=true on `hit` and every Container containing it, false on
        // everything else. This is a second, separate pass so stale hover deep in a
        // subtree the cursor has left is cleared even though it is not on the path
        // to `hit`.
        for (Container container : window.getContainers().values()) {
            UiHitTest.applyHover(container, hit);
        }

        updatePointer(window, hit);

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

    private void handleMouseButton(Window window, InputEvent event) {
        Input input = window.getInput();
        if (event.action == GLFW.GLFW_RELEASE) {
            input.mouseReleased(event.buttonOrKey);
            if (pointerCapture != null) {
                PointerInput holder = pointerCapture;
                pointerButtons &= ~pointerBit(event.buttonOrKey);
                if (pointerButtons == 0) pointerCapture = null;
                Element holderElement = (Element) holder;
                holder.onPointerButton(absoluteMouseX - holderElement.getAbsoluteX(), absoluteMouseY - holderElement.getAbsoluteY(),
                        event.buttonOrKey, false, input.getModifiers());
            }
            // Only the button that began a drag (always the left one) ends it; releasing a right-click mid-drag must not.
            if (event.buttonOrKey != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            if (dragTarget instanceof Draggable draggable) {
                draggable.onDragEnd(absoluteMouseX, absoluteMouseY);
            }
            dragTarget = null;
            pendingDrag = null;
            List<Element> targets = List.copyOf(clickTargets);
            clickTargets.clear();
            Element disabled = disabledTarget;
            disabledTarget = null;
            if (disabled != null && isUnderPointer(window, disabled)) disabled.fireDisabledClick();
            for (Element target : targets) {
                if (target.getMouseClickListenerConsumer() != null && !target.isEffectivelyDisabled() && isUnderPointer(window, target)) {
                    target.getMouseClickListenerConsumer().accept(new MouseClickEvent(absoluteMouseX, absoluteMouseY, event.buttonOrKey, event.action));
                }
            }
            return;
        }

        if (event.action != GLFW.GLFW_PRESS) {
            return; // ignore anything that isn't a fresh press/release (mouse buttons don't repeat)
        }
        if (event.buttonOrKey == GLFW.GLFW_MOUSE_BUTTON_LEFT) { // a press whose release was never seen
            clickTargets.clear();
            disabledTarget = null;
            pendingDrag = null;
        }

        if (input.captureMouse(event.buttonOrKey, event.mods)) return; // a controls menu waiting for a binding

        // A button pressed while another is held belongs to the element that holds the pointer.
        if (pointerCapture != null) {
            pointerButtons |= pointerBit(event.buttonOrKey);
            Element holder = (Element) pointerCapture;
            pointerCapture.onPointerButton(absoluteMouseX - holder.getAbsoluteX(), absoluteMouseY - holder.getAbsoluteY(),
                    event.buttonOrKey, true, input.getModifiers());
            return;
        }

        // An open popup (dropdown list, ...) is modal-ish: a press outside it just dismisses it.
        if (window.getPopup() != null && !window.getPopup().contains(absoluteMouseX, absoluteMouseY)) {
            window.closePopup();
            return;
        }

        MouseClickEvent clickEvent = new MouseClickEvent(absoluteMouseX, absoluteMouseY, event.buttonOrKey, event.action);
        Element focusBefore = window.getFocusedElement();
        Element hitElement = null;
        // Whether the interface used the press. If nothing did, it is game input.
        boolean taken = window.getPopup() != null || window.getModal() != null;

        for (Map.Entry<Integer, Container> entry : window.getContainers().descendingMap().entrySet()) {
            Container container = entry.getValue();
            if (!container.isEnabled()) continue;

            hitElement = UiHitTest.findTopmostHit(container, absoluteMouseX, absoluteMouseY);
            if (hitElement == null) continue;
            // A disabled Element, or anything inside a disabled container, takes the press and does nothing with it:
            // no click, no focus, no drag. A left press may still end as a click on its disabled handler.
            Element disabler = hitElement.getDisablingElement();
            if (disabler != null) {
                if (event.buttonOrKey == GLFW.GLFW_MOUSE_BUTTON_LEFT) disabledTarget = disabler;
                return;
            }
            // The click goes to the nearest Element at or above the hit one that listens for it, so pressing on the
            // label or icon inside a clickable row or button counts as pressing the row or button.
            boolean left = event.buttonOrKey == GLFW.GLFW_MOUSE_BUTTON_LEFT;
            for (Element e = hitElement; e != null; e = e.owner) {
                if (e.getMouseClickListenerConsumer() == null) continue;
                // A button-like Element fires once the left button is released over it, not now. So does one that
                // may turn the press into a drag of its container, which can only be known once the mouse moves.
                if (!e.isClickOnRelease() && !(left && e.isDragsContainer())) {
                    e.getMouseClickListenerConsumer().accept(clickEvent);
                } else if (left) {
                    clickTargets.add(e);
                }
                taken = true;
                if (!e.isPropagateClicks()) break;
            }
            break;
        }

        // An element that wants the raw pointer takes the press whole: no context menu, no drag of what it sits in, and
        // the pointer stays with it until every button is released.
        PointerInput pointer = hitElement == null || window.getPopup() != null ? null : pointerAt(hitElement);
        if (pointer != null) {
            Element target = (Element) pointer;
            Element focusNow = window.getFocusedElement();
            if (focusNow == focusBefore || focusNow == null) {
                window.requestFocus(target instanceof Focusable ? target : null);
            }
            pointerCapture = pointer;
            pointerButtons |= pointerBit(event.buttonOrKey);
            pointer.onPointerButton(absoluteMouseX - target.getAbsoluteX(), absoluteMouseY - target.getAbsoluteY(),
                    event.buttonOrKey, true, input.getModifiers());
            return;
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
        // clicking anything else, including empty space, clears focus. A click listener that moved the focus itself,
        // such as a menu item that opens a field to type in, keeps it there.
        Element focusNow = window.getFocusedElement();
        if (focusNow == focusBefore || focusNow == null) {
            window.requestFocus(hitElement instanceof Focusable ? hitElement : null);
        }
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
        boolean throughClickable = false; // passed up through a clickable Element that drags its container
        while (dragCandidate != null && !acceptsDrag(dragCandidate)) {
            if (dragCandidate.getMouseClickListenerConsumer() != null) {
                if (!dragCandidate.isDragsContainer()) {
                    dragCandidate = null;
                    break;
                }
                throughClickable = true;
            }
            dragCandidate = dragCandidate.owner;
        }

        if (dragCandidate instanceof Draggable draggable) {
            if (throughClickable) {
                pendingDrag = dragCandidate;
                pendingDragX = absoluteMouseX;
                pendingDragY = absoluteMouseY;
            } else {
                dragTarget = dragCandidate;
                draggable.onDragStart(absoluteMouseX, absoluteMouseY);
            }
            taken = true;
        }
        if (!taken) input.mousePressed(event.buttonOrKey, event.mods);
    }

    private static int pointerBit(int button) {
        return button >= 0 && button < 31 ? 1 << button : 0;
    }

    /**
     * The nearest element at or above {@code hit} that implements {@link PointerInput} and is not disabled, or null.
     */
    private static PointerInput pointerAt(Element hit) {
        for (Element e = hit; e != null; e = e.owner) {
            if (e instanceof PointerInput pointer && !e.isEffectivelyDisabled()) return pointer;
        }
        return null;
    }

    /**
     * Sends a move to the {@link PointerInput} element that holds the pointer, or else to the one under it, and tells the
     * one it left that it did.
     */
    private void updatePointer(Window window, Element hit) {
        int mods = window.getInput().getModifiers();
        if (pointerCapture != null) {
            Element holder = (Element) pointerCapture;
            pointerCapture.onPointerMove(absoluteMouseX - holder.getAbsoluteX(), absoluteMouseY - holder.getAbsoluteY(), pointerButtons, mods);
            return;
        }
        PointerInput target = dragTarget != null || window.getPopup() != null || hit == null ? null : pointerAt(hit);
        if (target != pointerHover) {
            if (pointerHover != null) pointerHover.onPointerLeave();
            pointerHover = target;
        }
        if (target != null) {
            Element element = (Element) target;
            target.onPointerMove(absoluteMouseX - element.getAbsoluteX(), absoluteMouseY - element.getAbsoluteY(), 0, mods);
        }
    }

    /**
     * Whether {@code element} is, or contains, the topmost thing under the cursor, found the same way a press finds it.
     */
    private boolean isUnderPointer(Window window, Element element) {
        for (Container container : window.getContainers().descendingMap().values()) {
            if (!container.isEnabled()) continue;
            Element hit = UiHitTest.findTopmostHit(container, absoluteMouseX, absoluteMouseY);
            if (hit == null) continue;
            for (Element e = hit; e != null; e = e.owner) {
                if (e == element) return true;
            }
            return false;
        }
        return false;
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
     * claims it. {@link UiHitTest#findScrollTarget} then descends, preferring the innermost
     * Scrollable. Unlike {@link UiHitTest#findTopmostHit}, it does not stop at the first Element
     * hit, so scrolling works anywhere inside the Scrollable's area rather than only
     * over its empty background.
     */
    private void handleScroll(Window window, InputEvent event) {
        Input input = window.getInput();
        if (input.captureScroll(event.x, event.y)) return;
        PointerInput holder = pointerCapture;
        if (holder == null && window.getPopup() == null) {
            for (Container container : window.getContainers().descendingMap().values()) {
                if (!container.isEnabled()) continue;
                Element hit = UiHitTest.findTopmostHit(container, absoluteMouseX, absoluteMouseY);
                if (hit == null) continue;
                holder = pointerAt(hit);
                break;
            }
        }
        if (holder != null) {
            Element target = (Element) holder;
            holder.onPointerScroll(absoluteMouseX - target.getAbsoluteX(), absoluteMouseY - target.getAbsoluteY(),
                    event.x, event.y, input.getModifiers());
            return;
        }
        boolean taken = window.getPopup() != null || window.getModal() != null;
        for (Map.Entry<Integer, Container> entry : window.getContainers().descendingMap().entrySet()) {
            Container container = entry.getValue();
            if (!container.isEnabled()) continue;
            if (UiHitTest.findTopmostHit(container, absoluteMouseX, absoluteMouseY) == null) continue;

            Element target = UiHitTest.findScrollTarget(container, absoluteMouseX, absoluteMouseY, event.x, event.y);
            if (target instanceof Scrollable scrollable) {
                scrollable.onScroll(event.x, event.y);
                taken = true;
            }
            break;
        }
        if (!taken) input.scrolled(event.x, event.y); // nothing scrolled: the wheel is game input
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
        if (event.action != GLFW.GLFW_REPEAT) input.trackModifier(event.buttonOrKey, event.action == GLFW.GLFW_PRESS);
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
            if (usableFocus(window) instanceof Focusable focusable && focusable.wantsTab()
                    && window.getPopup() == null && window.getModal() == null) {
                focusable.onKeyPressed(event.buttonOrKey, event.action, event.mods);
                return;
            }
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

        // Shortcuts such as a menu's Ctrl+S, unless a text field is being edited and the keys are its own editing ones.
        if (event.action == GLFW.GLFW_PRESS && window.getModal() == null && !editingKey(window, event)
                && window.dispatchShortcut(event.buttonOrKey, event.mods)) {
            return;
        }

        if (usableFocus(window) instanceof Focusable focusable) {
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

    /**
     * Whether the key is one a focused text field uses to edit (select all, copy, cut, paste, undo, redo), which
     * a shortcut does not take from it.
     */
    private boolean editingKey(Window window, InputEvent event) {
        if (!(window.getFocusedElement() instanceof TextFieldOverlay) && !(window.getFocusedElement() instanceof TextAreaOverlay)) {
            return false;
        }
        if ((event.mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) == 0) return false;
        return switch (event.buttonOrKey) {
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_Z -> true;
            default -> false;
        };
    }

    /**
     * The focused Element, or null if nothing has focus. One that was disabled while it held focus loses it here.
     */
    private Element usableFocus(Window window) {
        Element focused = window.getFocusedElement();
        if (focused != null && focused.isEffectivelyDisabled()) {
            window.requestFocus(null);
            return null;
        }
        return focused;
    }

    /**
     * Turns the system's input method on while a text field has focus, and tells it where the caret is.
     */
    private void updateIme(Window window) {
        Element focused = usableFocus(window);
        if (focused instanceof Focusable focusable && focusable.acceptsTextInput() && window.isFocused()) {
            float[] rect = focusable.imeCaretRect();
            if (rect == null) rect = new float[]{(float) focused.getAbsoluteX(), (float) (focused.getAbsoluteY() + focused.getHeight()), 1f, 1f};
            ImeManager.request(true, rect[0], rect[1], rect[2], rect[3]);
        } else {
            ImeManager.request(false, 0f, 0f, 1f, 1f);
        }
    }

    private void handleChar(Window window, InputEvent event) {
        if (window.getInput().swallowsChars()) return; // the key that typed it was just bound in a controls menu
        if (usableFocus(window) instanceof Focusable focusable) {
            focusable.onCharTyped(event.buttonOrKey);
        } else {
            window.getInput().charTyped(event.buttonOrKey);
        }
    }
}