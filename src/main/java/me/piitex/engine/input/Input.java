package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.game.GameTime;
import me.piitex.engine.game.Scene;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The game's keyboard and mouse, polled from game code: which keys and buttons are held, which were just pressed or
 * released, where the mouse is. One per window, from {@link Window#getInput()}. Named, rebindable actions are in
 * {@link InputContext}s, which are added here or to a {@link Scene}.
 * <pre>{@code
 * Input input = getWindow().getInput();
 * if (input.isKeyJustPressed(GLFW_KEY_SPACE)) jump();
 * if (input.isMouseDown(GLFW_MOUSE_BUTTON_LEFT)) aimAt(input.getMouseX(), input.getMouseY());
 * }</pre>
 * <h2>What counts as game input</h2>
 * The interface gets input first, as it always has, and this only sees what it leaves: no key presses while a text
 * field or other element has the keyboard or a popup or modal is open, and no clicks or wheel turns that went to an
 * element (a button, anything with a click listener, a text field, a scroll area, a draggable panel). A click on an
 * element that doesn't listen for clicks, such as a label or a plain panel, counts as game input; give the element a
 * click listener that does nothing to keep clicks on it from the game. Releases always come through, so nothing is left
 * held down. Presses a scene's {@link Scene#onKey} consumes are seen here but not by any {@link InputContext}.
 * <h2>Just pressed, in steps and frames</h2>
 * The "just" queries mean "since the last step" when called from {@link Scene#fixedUpdate} and "since the last frame"
 * everywhere else, so a press shows in exactly one fixed step, however many steps a frame runs, and in exactly one
 * frame. The mouse and wheel deltas work the same way. Everything here is for the render thread, where every scene
 * hook and element callback runs.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Input {
    private static final Logger log = LogManager.getLogger("Input");
    private static final int[] MODIFIER_KEYS = {GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
            GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER};

    private final Window window;
    private final PressState[] keys = new PressState[GLFW.GLFW_KEY_LAST + 1];
    private final PressState[] buttons = new PressState[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
    private final PressState any = new PressState();

    long frame; // counts the frames, from the start of each frame's input

    private float mouseX = -1f, mouseY = -1f;
    private boolean mouseKnown, mouseInWindow;
    private final Delta mouse = new Delta();
    private final Delta scroll = new Delta();
    private double wheelX, wheelY; // wheel travel not yet turned into whole notches for bindings

    private final List<InputContext> contexts = new ArrayList<>();
    private List<InputContext> active = List.of();
    private List<InputContext> muted = List.of(); // active, but in a scene whose input delay isn't over: presses dropped

    private Consumer<Binding> bindingListener;
    private int pendingModifier = -1;
    private boolean swallowChars;
    private final StringBuilder typed = new StringBuilder();

    public Input(Window window) {
        this.window = window;
        for (int i = 0; i < keys.length; i++) keys[i] = new PressState();
        for (int i = 0; i < buttons.length; i++) buttons[i] = new PressState();
    }

    // ---------------------------------------------------------------- keys

    /**
     * True while {@code key} (a {@code GLFW_KEY_*} code) is held.
     */
    public boolean isKeyDown(int key) {
        return key >= 0 && key < keys.length && keys[key].down;
    }

    /**
     * True if {@code key} was pressed since the last step (in a fixed step) or during this frame (anywhere else).
     */
    public boolean isKeyJustPressed(int key) {
        return key >= 0 && key < keys.length && keys[key].justPressed(frame);
    }

    /**
     * True if {@code key} was let go since the last step (in a fixed step) or during this frame (anywhere else).
     */
    public boolean isKeyJustReleased(int key) {
        return key >= 0 && key < keys.length && keys[key].justReleased(frame);
    }

    /**
     * True if the OS repeated held {@code key} since the last step or during this frame, as it does once a key has
     * been held for a moment.
     */
    public boolean isKeyRepeated(int key) {
        return key >= 0 && key < keys.length && keys[key].repeated(frame);
    }

    /**
     * Seconds of game time {@code key} has been held, counting the current step or frame, or 0 when it isn't held.
     */
    public float getKeyHeldTime(int key) {
        return key >= 0 && key < keys.length ? keys[key].heldTime() : 0f;
    }

    /**
     * The modifier keys held, as GLFW modifier bits ({@code GLFW_MOD_SHIFT} and so on), either side counting.
     */
    public int getModifiers() {
        int mods = 0;
        for (int key : MODIFIER_KEYS) {
            if (keys[key].down) mods |= modifierBit(key);
        }
        return mods;
    }

    // ---------------------------------------------------------------- mouse

    /**
     * True while {@code button} ({@code GLFW_MOUSE_BUTTON_*}) is held.
     */
    public boolean isMouseDown(int button) {
        return button >= 0 && button < buttons.length && buttons[button].down;
    }

    public boolean isMouseJustPressed(int button) {
        return button >= 0 && button < buttons.length && buttons[button].justPressed(frame);
    }

    public boolean isMouseJustReleased(int button) {
        return button >= 0 && button < buttons.length && buttons[button].justReleased(frame);
    }

    public float getMouseHeldTime(int button) {
        return button >= 0 && button < buttons.length ? buttons[button].heldTime() : 0f;
    }

    /**
     * The mouse position in window points, the space {@link me.piitex.engine.graphics.Camera#screenToWorld} takes.
     * While a button is held it keeps following the mouse outside the window. -1 before the mouse has been over the
     * window.
     */
    public float getMouseX() {
        return mouseX;
    }

    public float getMouseY() {
        return mouseY;
    }

    /**
     * How far the mouse moved since the last step (in a fixed step) or during this frame (anywhere else), in points.
     */
    public float getMouseDeltaX() {
        return mouse.x(frame);
    }

    public float getMouseDeltaY() {
        return mouse.y(frame);
    }

    public boolean isMouseInWindow() {
        return mouseInWindow;
    }

    /**
     * The text typed during this frame that no text field or other UI element took, already resolved against the
     * keyboard layout, shift state and dead keys (so {@code "A"} for Shift+a, and accents and non-Latin text work).
     * Empty when nothing was typed, which is almost always. Meant for a chat box or console you draw yourself; for the
     * keys as controls use {@link #isKeyJustPressed} and bindings instead.
     */
    public String getTypedText() {
        return typed.toString();
    }

    /**
     * A typed character that reached the game.
     */
    void charTyped(int codepoint) {
        typed.appendCodePoint(codepoint);
    }

    /**
     * How far the wheel turned since the last step or during this frame. Positive is up, away from the player. Notches
     * on a wheel are 1 each; a trackpad gives smaller amounts, more often.
     */
    public float getScrollY() {
        return scroll.y(frame);
    }

    /**
     * As {@link #getScrollY()}, sideways: positive is to the right.
     */
    public float getScrollX() {
        return scroll.x(frame);
    }

    /**
     * True if any key or mouse button was pressed since the last step or during this frame. For "press any key".
     */
    public boolean isAnyJustPressed() {
        return any.justPressed(frame);
    }

    // ---------------------------------------------------------------- contexts

    /**
     * Adds a context that is active whatever scene is showing, such as one for a screenshot or fullscreen key. The
     * window's contexts get presses before every scene's, the last added first.
     */
    public void addContext(InputContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (!contexts.contains(context)) contexts.add(context);
        refreshContexts();
    }

    public void removeContext(InputContext context) {
        contexts.remove(context);
        refreshContexts();
    }

    /**
     * The contexts added with {@link #addContext}, in the order they were added.
     */
    public List<InputContext> getContexts() {
        return List.copyOf(contexts);
    }

    /**
     * Every context receiving input, the window's and the running scenes', in the order a press is offered to them.
     * Those of a scene whose {@link Scene#setInputDelay input delay} isn't over are included: they take the presses
     * they bind, but drop them.
     */
    public List<InputContext> getActiveContexts() {
        refreshContexts();
        return active;
    }

    // ---------------------------------------------------------------- rebinding

    /**
     * Hands the next key, mouse button or wheel turn to {@code onBound} as a {@link Binding}, for a "press a key"
     * prompt in a controls menu. It comes before everything else, the interface included, so every key can be bound,
     * Escape and Tab too; the prompt decides whether one of them cancels instead. That press goes nowhere else.
     * <p>
     * A modifier key waits: held with another key or a button it becomes part of that binding (Ctrl+S), and released
     * on its own it is the binding (Left Ctrl). Calling this again replaces the waiting callback.
     */
    public void listenForBinding(Consumer<Binding> onBound) {
        if (onBound == null) throw new IllegalArgumentException("callback must not be null");
        bindingListener = onBound;
        pendingModifier = -1;
    }

    /**
     * Stops waiting for a binding, without calling the callback.
     */
    public void cancelListening() {
        bindingListener = null;
        pendingModifier = -1;
    }

    public boolean isListening() {
        return bindingListener != null;
    }

    // ---------------------------------------------------------------- fed by InputProcessor

    /**
     * The start of a frame's input.
     */
    void beginFrame() {
        frame++;
        swallowChars = false;
        typed.setLength(0);
        refreshContexts();
    }

    /**
     * A key press that reached the game. Returns true if a context took it.
     */
    boolean keyPressed(int key, int mods, boolean consumed) {
        if (key < 0 || key >= keys.length || keys[key].down) return false; // unknown key, or a press without a release
        keys[key].press(frame);
        any.press(frame);
        if (consumed) return false;
        return pressContexts(Binding.Device.KEY, key, mods & Binding.MODIFIER_MASK);
    }

    /**
     * A key repeat that reached the game. Returns true if a context holds the key.
     */
    boolean keyRepeated(int key, boolean consumed) {
        if (key < 0 || key >= keys.length || !keys[key].down) return false;
        keys[key].repeat(frame);
        if (consumed) return false;
        boolean held = false;
        for (InputContext context : active) held |= context.repeat(key, frame);
        return held;
    }

    void keyReleased(int key) {
        if (key < 0 || key >= keys.length) return;
        if (keys[key].down) keys[key].release(frame);
        refreshContexts();
        for (InputContext context : active) context.release(key, frame);
    }

    void mousePressed(int button, int mods) {
        if (button < 0 || button >= buttons.length || buttons[button].down) return;
        buttons[button].press(frame);
        any.press(frame);
        pressContexts(Binding.Device.MOUSE, button, mods & Binding.MODIFIER_MASK);
    }

    void mouseReleased(int button) {
        if (button < 0 || button >= buttons.length) return;
        if (buttons[button].down) buttons[button].release(frame);
        refreshContexts();
        int source = Binding.source(Binding.Device.MOUSE, button);
        for (InputContext context : active) context.release(source, frame);
    }

    void mouseMoved(double x, double y, boolean inside) {
        float nx = (float) x, ny = (float) y;
        if (mouseKnown) mouse.add(frame, nx - mouseX, ny - mouseY);
        mouseX = nx;
        mouseY = ny;
        mouseKnown = true;
        if (inside) mouseInWindow = true; // GLFW's enter event can go missing; see InputProcessor
    }

    void mouseEntered(boolean entered) {
        mouseInWindow = entered;
        if (!entered) mouseKnown = false; // coming back in somewhere else isn't a movement
    }

    /**
     * A wheel turn that reached the game.
     */
    void scrolled(double dx, double dy) {
        scroll.add(frame, (float) dx, (float) dy);
        // Wheel travel in each direction adds up into whole notches, so a trackpad's small steps trigger a binding
        // at about the rate a wheel does. Turning the other way starts over.
        wheelX = Math.signum(dx) == -Math.signum(wheelX) ? dx : wheelX + dx;
        wheelY = Math.signum(dy) == -Math.signum(wheelY) ? dy : wheelY + dy;
        int mods = getModifiers();
        for (; wheelY >= 1; wheelY -= 1) tapContexts(Binding.WHEEL_UP, mods);
        for (; wheelY <= -1; wheelY += 1) tapContexts(Binding.WHEEL_DOWN, mods);
        for (; wheelX >= 1; wheelX -= 1) tapContexts(Binding.WHEEL_RIGHT, mods);
        for (; wheelX <= -1; wheelX += 1) tapContexts(Binding.WHEEL_LEFT, mods);
    }

    /**
     * Offers a key event to a {@link #listenForBinding} prompt. Returns true if the prompt took it, so it goes nowhere
     * else.
     */
    boolean captureKey(int key, int action, int mods) {
        if (bindingListener == null) return false;
        if (key < 0 || KeyNames.name(key) == null) return action != GLFW.GLFW_RELEASE; // a key with no GLFW code
        if (action == GLFW.GLFW_REPEAT) return true;
        if (action == GLFW.GLFW_PRESS) {
            if (Binding.isModifierKey(key)) {
                pendingModifier = key;
            } else {
                finishListening(Binding.key(key, mods & Binding.MODIFIER_MASK));
            }
            return true;
        }
        if (key == pendingModifier) finishListening(Binding.key(key)); // a modifier pressed and let go on its own
        return false; // releases pass on: the press never went anywhere, so they change nothing
    }

    boolean captureMouse(int button, int mods) {
        if (bindingListener == null) return false;
        finishListening(Binding.mouse(button, mods & Binding.MODIFIER_MASK));
        return true;
    }

    boolean captureScroll(double dx, double dy) {
        if (bindingListener == null) return false;
        if (dx == 0 && dy == 0) return true;
        int direction = Math.abs(dy) >= Math.abs(dx)
                ? (dy > 0 ? Binding.WHEEL_UP : Binding.WHEEL_DOWN)
                : (dx > 0 ? Binding.WHEEL_RIGHT : Binding.WHEEL_LEFT);
        int mods = pendingModifier >= 0 ? modifierBit(pendingModifier) : 0;
        finishListening(Binding.wheel(direction, mods));
        return true;
    }

    /**
     * Whether typed text should be dropped: while a prompt waits for a binding, and for the rest of the frame it got
     * one in, since the key that was bound also types a character.
     */
    boolean swallowsChars() {
        return bindingListener != null || swallowChars;
    }

    private void finishListening(Binding binding) {
        Consumer<Binding> listener = bindingListener;
        bindingListener = null;
        pendingModifier = -1;
        swallowChars = true;
        try {
            listener.accept(binding);
        } catch (Throwable t) {
            log.error("Binding listener threw", t);
        }
    }

    private boolean pressContexts(Binding.Device device, int code, int mods) {
        refreshContexts();
        int source = Binding.source(device, code);
        for (InputContext context : active) {
            List<InputContext.Action> matched = context.match(device, code, mods);
            if (!matched.isEmpty()) {
                if (!containsSame(muted, context)) context.press(source, matched, frame);
                return true;
            }
            if (context.isBlocking()) return true;
        }
        return false;
    }

    private void tapContexts(int direction, int mods) {
        refreshContexts();
        for (InputContext context : active) {
            List<InputContext.Action> matched = context.match(Binding.Device.WHEEL, direction, mods);
            if (!matched.isEmpty()) {
                if (!containsSame(muted, context)) context.tap(matched, frame);
                return;
            }
            if (context.isBlocking()) return;
        }
    }

    /**
     * Works out which contexts are active, lets go of everything held in the ones that stopped being active, and
     * points the new ones at this input.
     */
    private void refreshContexts() {
        List<InputContext> next = new ArrayList<>();
        List<InputContext> nextMuted = new ArrayList<>();
        boolean blocked = collect(contexts, next);
        if (!blocked) {
            List<Scene> scenes = window.getSceneManager().getScenes();
            for (int i = scenes.size() - 1; i >= 0 && !blocked; i--) {
                Scene scene = scenes.get(i);
                if (!scene.isRunning()) continue;
                int first = next.size();
                blocked = collect(scene.getInputContexts(), next);
                if (!scene.isReady()) nextMuted.addAll(next.subList(first, next.size()));
            }
        }
        muted = List.copyOf(nextMuted);
        for (InputContext old : active) {
            if (!containsSame(next, old)) old.deactivate(frame);
        }
        for (InputContext context : next) {
            context.input = this;
            context.active = true;
        }
        active = List.copyOf(next);
    }

    /**
     * Adds the enabled contexts of {@code from} to {@code into}, the last one first. Returns true if one of them blocks.
     */
    private static boolean collect(List<InputContext> from, List<InputContext> into) {
        for (int i = from.size() - 1; i >= 0; i--) {
            InputContext context = from.get(i);
            if (!context.isEnabled() || containsSame(into, context)) continue;
            into.add(context);
            if (context.isBlocking()) return true;
        }
        return false;
    }

    private static boolean containsSame(List<InputContext> list, InputContext context) {
        for (InputContext c : list) {
            if (c == context) return true;
        }
        return false;
    }

    private static int modifierBit(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_MOD_SHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> GLFW.GLFW_MOD_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_MOD_ALT;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> GLFW.GLFW_MOD_SUPER;
            default -> 0;
        };
    }

    /**
     * An amount that adds up over a frame and over a step, such as mouse movement: each total starts over at the first
     * addition of a new frame or step, and reads as 0 once its frame or step is over.
     */
    private static final class Delta {
        private long frame = -1, tick = -1;
        private float frameX, frameY, stepX, stepY;

        void add(long currentFrame, float dx, float dy) {
            if (frame != currentFrame) {
                frame = currentFrame;
                frameX = frameY = 0f;
            }
            long currentTick = GameTime.getTicks();
            if (tick != currentTick) {
                tick = currentTick;
                stepX = stepY = 0f;
            }
            frameX += dx;
            frameY += dy;
            stepX += dx;
            stepY += dy;
        }

        float x(long currentFrame) {
            if (GameTime.isInFixedStep()) return tick == GameTime.getTicks() ? stepX : 0f;
            return frame == currentFrame ? frameX : 0f;
        }

        float y(long currentFrame) {
            if (GameTime.isInFixedStep()) return tick == GameTime.getTicks() ? stepY : 0f;
            return frame == currentFrame ? frameY : 0f;
        }
    }
}
