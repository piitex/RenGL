package me.piitex.engine.input;

import me.piitex.engine.config.Config;
import me.piitex.engine.game.Scene;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A set of named actions, such as "jump", "move_left" or "confirm", each triggered by one or more {@link Binding}s.
 * Game code asks about the action, and the player can rebind it:
 * <pre>{@code
 * InputContext controls = new InputContext("gameplay")
 *         .bind("move_left", Binding.key(GLFW_KEY_A), Binding.key(GLFW_KEY_LEFT))
 *         .bind("move_right", Binding.key(GLFW_KEY_D), Binding.key(GLFW_KEY_RIGHT))
 *         .bind("jump", Binding.key(GLFW_KEY_SPACE));
 * scene.addInputContext(controls);     // active while the scene runs
 *
 * // Scene.fixedUpdate
 * velocityX = controls.getAxis("move_left", "move_right") * speed;
 * if (controls.isJustPressed("jump")) jump();
 * }</pre>
 * <h2>Which context gets a press</h2>
 * A context only receives input while it is active: added to the window's {@link Input} or to a {@link Scene} that is
 * running, and {@link #setEnabled enabled}. Active contexts are ordered like the things they belong to: the window's
 * own contexts first (the last added on top), then the scenes', from the top of the stack down. A press goes to the
 * first active context that binds it, and no other; a {@link #setBlocking blocking} context takes every press, bound or
 * not, so the contexts below it get nothing. Only that context sees the press as held, until it is released.
 * <p>
 * When a context stops being active, everything held in it is released, and it doesn't see keys that are already down
 * when it becomes active again until they are pressed anew. So closing a menu with Escape can't also reach the game
 * underneath, and a key held when a dialogue box opens doesn't keep the player walking.
 * <p>
 * Only presses no element took reach contexts: none while a text field has the keyboard, a popup or modal is open, or
 * a scene's {@link Scene#onKey} consumed the key, and no clicks on buttons or other elements that listen for them.
 * <h2>Just pressed, in steps and frames</h2>
 * {@link #isJustPressed}, {@link #isJustReleased} and {@link #isRepeated} mean "since the last step" when called from
 * {@link Scene#fixedUpdate} and "since the last frame" everywhere else. So a press shows in exactly one fixed step, even
 * when a frame runs several or none, and in exactly one frame.
 * <p>
 * Render thread only, like the rest of the engine's game code. Action names use letters, digits, {@code _} and
 * {@code -}, so they can be {@link #save saved} as config keys; asking about an action the context doesn't have throws.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class InputContext {
    private static final Logger log = LogManager.getLogger("InputContext");
    private static final Pattern ACTION_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_\\-]*");

    private final String name;
    private final Map<String, Action> actions = new LinkedHashMap<>();
    /**
     * The actions each held key or button pressed here, by {@link Binding#source()}, until it is released.
     */
    private final Map<Integer, List<Action>> claims = new HashMap<>();
    private boolean enabled = true;
    private boolean blocking;

    Input input; // the Input this context was last active in, whose frame count its queries use; null before that
    boolean active;

    public InputContext(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("context name must not be blank");
        this.name = name;
    }

    public String getName() {
        return name;
    }

    // ---------------------------------------------------------------- actions and bindings

    /**
     * Adds an action with its default bindings, which are also its bindings until changed. An action with no bindings
     * is allowed; the player can bind it later.
     *
     * @throws IllegalArgumentException if the name isn't letters, digits, {@code _} and {@code -}
     * @throws IllegalStateException    if the context already has this action
     */
    public InputContext bind(String action, Binding... defaults) {
        if (action == null || !ACTION_NAME.matcher(action).matches()) {
            throw new IllegalArgumentException("action names use letters, digits, _ and -: \"" + action + "\"");
        }
        if (actions.containsKey(action)) {
            throw new IllegalStateException("\"" + action + "\" is already an action of " + this + "; use setBindings to change it");
        }
        List<Binding> list = checked(action, Arrays.asList(defaults));
        actions.put(action, new Action(action, list));
        return this;
    }

    public boolean hasAction(String action) {
        return actions.containsKey(action);
    }

    /**
     * The actions, in the order they were added.
     */
    public Set<String> getActions() {
        return Collections.unmodifiableSet(actions.keySet());
    }

    /**
     * The action's current bindings.
     */
    public List<Binding> getBindings(String action) {
        return List.copyOf(get(action).bindings);
    }

    /**
     * The bindings the action was added with.
     */
    public List<Binding> getDefaultBindings(String action) {
        return get(action).defaults;
    }

    /**
     * Replaces the action's bindings. A key held down through an old binding still releases the action when it is let go.
     */
    public InputContext setBindings(String action, Binding... bindings) {
        return setBindings(action, Arrays.asList(bindings));
    }

    public InputContext setBindings(String action, List<Binding> bindings) {
        Action a = get(action);
        a.bindings = new ArrayList<>(checked(action, bindings));
        return this;
    }

    /**
     * Adds a binding to the action, unless it already has it.
     */
    public InputContext addBinding(String action, Binding binding) {
        Action a = get(action);
        if (binding == null) throw new IllegalArgumentException("binding must not be null");
        if (!a.bindings.contains(binding)) a.bindings.add(binding);
        return this;
    }

    /**
     * Removes a binding from the action. Returns false if the action didn't have it.
     */
    public boolean removeBinding(String action, Binding binding) {
        return get(action).bindings.remove(binding);
    }

    /**
     * The actions in this context that {@code binding} triggers, for warning a player who rebinds a key that is
     * already in use. Modifiers must match exactly.
     */
    public List<String> getActionsBoundTo(Binding binding) {
        List<String> found = new ArrayList<>();
        for (Action a : actions.values()) {
            if (a.bindings.contains(binding)) found.add(a.name);
        }
        return found;
    }

    /**
     * Puts the action's default bindings back.
     */
    public InputContext resetBindings(String action) {
        Action a = get(action);
        a.bindings = new ArrayList<>(a.defaults);
        return this;
    }

    /**
     * Puts every action's default bindings back.
     */
    public InputContext resetBindings() {
        for (Action a : actions.values()) a.bindings = new ArrayList<>(a.defaults);
        return this;
    }

    // ---------------------------------------------------------------- saving

    /**
     * Stores the bindings the player changed under {@code path}, one list per action, such as
     * {@code jump: [SPACE, MOUSE_RIGHT]}. Actions left at their defaults are removed from the config, so a game update
     * that changes a default reaches every player who hasn't rebound that action. Sections left empty are removed too.
     * Call {@link Config#save()} afterwards.
     */
    public void save(Config config, String path) {
        for (Action a : actions.values()) {
            String key = path + "." + a.name;
            if (a.bindings.equals(a.defaults)) {
                config.remove(key);
            } else {
                List<String> texts = new ArrayList<>(a.bindings.size());
                for (Binding b : a.bindings) texts.add(b.toString());
                config.set(key, texts);
            }
        }
        // Drop the section, and any parents it leaves empty, when every action is back to its default
        for (String p = path; config.getSection(p).map(Config::isEmpty).orElse(false); ) {
            config.remove(p);
            int dot = p.lastIndexOf('.');
            if (dot < 0) break;
            p = p.substring(0, dot);
        }
    }

    /**
     * Reads bindings stored by {@link #save}. Actions not in the config get their defaults, entries for actions this
     * context doesn't have are ignored, and a binding that can't be read is skipped with a warning in the log. An empty
     * list unbinds the action.
     */
    public void load(Config config, String path) {
        for (Action a : actions.values()) {
            String key = path + "." + a.name;
            if (!config.has(key)) {
                a.bindings = new ArrayList<>(a.defaults);
                continue;
            }
            List<Binding> loaded = new ArrayList<>();
            for (String value : config.getStringList(key)) { // a single value, "jump: SPACE", reads as a list of one
                try {
                    Binding b = Binding.parse(value);
                    if (!loaded.contains(b)) loaded.add(b);
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping binding for {} in {}: {}", a.name, name, e.getMessage());
                }
            }
            a.bindings = loaded;
        }
    }

    // ---------------------------------------------------------------- state

    /**
     * True while the action is held through this context.
     */
    public boolean isDown(String action) {
        return get(action).state.down;
    }

    /**
     * True if the action was pressed since the last step (in a fixed step) or during this frame (anywhere else). A
     * press counts once however many of the action's bindings are held.
     */
    public boolean isJustPressed(String action) {
        Action a = get(action);
        return input != null && a.state.justPressed(input.frame);
    }

    /**
     * How many times the action was pressed since the last step (in a fixed step) or during this frame (anywhere
     * else). It is 0 when {@link #isJustPressed} is false. It differs from 1 for a {@link Binding#wheel wheel} turned
     * several notches at once, which presses the action once per notch.
     */
    public int getPressCount(String action) {
        Action a = get(action);
        return input == null ? 0 : a.state.pressCount(input.frame);
    }

    /**
     * True if the action was let go since the last step (in a fixed step) or during this frame (anywhere else),
     * including by this context stopping being active. A quick tap can be just pressed and just released at once.
     */
    public boolean isJustReleased(String action) {
        Action a = get(action);
        return input != null && a.state.justReleased(input.frame);
    }

    /**
     * True if the OS repeated a held key of the action since the last step or during this frame, as it does after a
     * key has been held for a moment. For menus: move on {@code isJustPressed(a) || isRepeated(a)}.
     */
    public boolean isRepeated(String action) {
        Action a = get(action);
        return input != null && a.state.repeated(input.frame);
    }

    /**
     * Seconds of game time the action has been held, counting the current step or frame, or 0 when it isn't held. In
     * a fixed step it is a whole number of steps.
     */
    public float getHeldTime(String action) {
        return get(action).state.heldTime();
    }

    /**
     * How strongly the action is pressed, from 0 to 1. Keys and buttons give 0 or 1.
     */
    public float getValue(String action) {
        return get(action).state.down ? 1f : 0f;
    }

    /**
     * {@code getValue(positive) - getValue(negative)}: -1, 0 or 1 with keys. For movement on one axis:
     * {@code getAxis("move_left", "move_right")}.
     */
    public float getAxis(String negative, String positive) {
        return getValue(positive) - getValue(negative);
    }

    /**
     * A direction from four actions, in screen orientation: x grows to the right and y downwards, so "up" gives a
     * negative y. It is never longer than 1, so moving diagonally isn't faster than moving straight.
     */
    public Vec2 getVector(String left, String right, String up, String down) {
        float x = getAxis(left, right);
        float y = getAxis(up, down);
        float length = (float) Math.sqrt(x * x + y * y);
        if (length > 1f) {
            x /= length;
            y /= length;
        }
        return x == 0f && y == 0f ? Vec2.ZERO : new Vec2(x, y);
    }

    /**
     * Whether this context is receiving input: added to the window's {@link Input} or a running {@link Scene},
     * enabled, and not under a {@link #setBlocking blocking} one. Updated as input comes in and at the start of each
     * frame.
     */
    public boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------------- settings

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Turns the context off or back on without removing it. While off it receives nothing, and presses go on to the
     * contexts below. Useful for switching controls off during a cutscene. Takes effect with the next input or frame.
     */
    public InputContext setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public boolean isBlocking() {
        return blocking;
    }

    /**
     * A blocking context takes every press while it is active, bound or not, so no context below it gets any: for a
     * menu or dialogue box shown over a game that keeps running. Off by default.
     */
    public InputContext setBlocking(boolean blocking) {
        this.blocking = blocking;
        return this;
    }

    @Override
    public String toString() {
        return "InputContext[" + name + "]";
    }

    // ---------------------------------------------------------------- called by Input

    /**
     * The actions a press of {@code device}/{@code code} with {@code mods} held triggers here: those bound to it whose
     * modifiers are all held, keeping only the ones requiring the most modifiers. So with S and Ctrl+S both bound,
     * Ctrl+S triggers only the second, while S with Ctrl held (and no Ctrl+S binding) still triggers the first.
     */
    List<Action> match(Binding.Device device, int code, int mods) {
        List<Action> matched = null;
        int best = -1;
        for (Action a : actions.values()) {
            for (Binding b : a.bindings) {
                if (b.device() != device || b.code() != code || (b.modifiers() & ~mods) != 0) continue;
                int count = Integer.bitCount(b.modifiers());
                if (count > best) {
                    best = count;
                    if (matched != null) matched.clear();
                }
                if (count == best) {
                    if (matched == null) matched = new ArrayList<>(2);
                    if (!matched.contains(a)) matched.add(a);
                }
            }
        }
        return matched == null ? List.of() : matched;
    }

    void press(int source, List<Action> matched, long frame) {
        claims.put(source, matched);
        for (Action a : matched) {
            if (a.held++ == 0) a.state.press(frame);
        }
    }

    boolean repeat(int source, long frame) {
        List<Action> held = claims.get(source);
        if (held == null) return false;
        for (Action a : held) a.state.repeat(frame);
        return true;
    }

    void release(int source, long frame) {
        List<Action> held = claims.remove(source);
        if (held == null) return;
        for (Action a : held) {
            if (a.held > 0 && --a.held == 0) a.state.release(frame);
        }
    }

    void tap(List<Action> matched, long frame) {
        for (Action a : matched) {
            if (a.held == 0) a.state.tap(frame);
        }
    }

    /**
     * Stopped being active: everything held is let go.
     */
    void deactivate(long frame) {
        claims.clear();
        for (Action a : actions.values()) {
            if (a.held > 0) {
                a.held = 0;
                a.state.release(frame);
            }
        }
        active = false;
    }

    private Action get(String action) {
        Action a = actions.get(action);
        if (a == null) {
            throw new IllegalArgumentException("\"" + action + "\" is not an action of " + this + "; it has " + actions.keySet());
        }
        return a;
    }

    private static List<Binding> checked(String action, List<Binding> bindings) {
        if (bindings == null) throw new IllegalArgumentException("bindings must not be null");
        List<Binding> list = new ArrayList<>(bindings.size());
        for (Binding b : bindings) {
            if (b == null) throw new IllegalArgumentException("a binding of \"" + action + "\" is null");
            if (!list.contains(b)) list.add(b);
        }
        return List.copyOf(list);
    }

    static final class Action {
        final String name;
        final List<Binding> defaults;
        List<Binding> bindings;
        final PressState state = new PressState();
        int held; // how many held keys or buttons are pressing it

        Action(String name, List<Binding> defaults) {
            this.name = name;
            this.defaults = defaults;
            this.bindings = new ArrayList<>(defaults);
        }
    }
}
