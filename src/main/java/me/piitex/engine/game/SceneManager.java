package me.piitex.engine.game;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The window's stack of {@link Scene}s, reached through {@link Window#getSceneManager()}. The top scene is the one
 * being played; each scene below it is drawn only while the ones above are {@link Scene#setOverlay overlays}, and
 * runs only while none above {@link Scene#setPausesBelow pauses it}.
 * <pre>{@code
 * SceneManager scenes = window.getSceneManager();
 * scenes.set(new MainMenu());        // replace everything: the start of the game
 * scenes.push(new PauseMenu());      // over the gameplay, which pauses
 * scenes.pop();                      // back to the gameplay, which resumes
 * }</pre>
 * Changes apply immediately: after {@link #push} returns, the new scene has entered and is {@link #getTop()}. They
 * are safe from inside any scene hook or element callback, including ones on the scene being removed. Call these
 * methods on the render thread (every hook and element callback runs there), or before {@link me.piitex.engine.Engine#start};
 * from another thread, go through {@link me.piitex.engine.scheduler.Scheduler#runLater}.
 * <p>
 * An exception thrown by {@link Scene#onEnter}, {@link Scene#onExit}, {@link Scene#onPause}, {@link Scene#onResume},
 * {@link Scene#onKey} or {@link Scene#onResize} is logged and does not stop the change that triggered it.
 *
 * <h2>Transitions</h2>
 * {@link #push(Scene, SceneTransition)}, {@link #pop(SceneTransition)}, {@link #replace(Scene, SceneTransition)},
 * {@link #set(Scene, SceneTransition)} and {@link #clear(SceneTransition)} show the change with a
 * {@link SceneTransition}, such as a fade. The stack still changes at once: the new scenes enter and run, and the
 * ones below are paused or resumed, as without a transition. Only the picture takes time to catch up. Scenes on their
 * way out leave the stack, the window and the input at once too, but are still drawn until the transition ends, and
 * only then get {@link Scene#onExit}.
 * <p>
 * Any other change to the stack while a transition plays, with or without a transition of its own, first finishes
 * the one that is playing, as {@link #finishTransition()} does.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class SceneManager {
    private static final Logger log = LogManager.getLogger("SceneManager");

    /**
     * Window container index of the bottom scene; the rest follow in stack order. Far below where
     * {@link Window#addContainer(Container)} puts containers, so everything added to the window directly is drawn
     * above the scenes and gets the mouse first.
     */
    static final int BASE_INDEX = Integer.MIN_VALUE / 2;

    /**
     * Window container index of the layer that draws a running transition, just below the scenes.
     */
    static final int TRANSITION_INDEX = BASE_INDEX - 1;

    private final Window window;
    private final List<Scene> stack = new ArrayList<>();
    private final Container transitionLayer = new TransitionLayer(this);

    private ActiveTransition active;
    private List<Scene> pendingExits; // while a change with a transition is applied: removed scenes, onExit not yet called
    private boolean drawingTransition;

    public SceneManager(Window window) {
        this.window = window;
    }

    public Window getWindow() {
        return window;
    }

    /**
     * Adds {@code scene} on top of the stack. The scene that was on top is paused, unless {@code scene} doesn't
     * {@link Scene#setPausesBelow pause the scenes below}, and is hidden, unless {@code scene} is an
     * {@link Scene#setOverlay overlay}.
     */
    public void push(Scene scene) {
        if (scene == null) throw new IllegalArgumentException("scene must not be null");
        settle(scene);
        if (scene.manager != null) throw new IllegalStateException("scene is already on a stack: " + scene);
        stack.add(scene);
        enter(scene);
    }

    /**
     * Removes the top scene, resuming and showing the one below. Returns the removed scene, or null if the stack
     * was empty.
     */
    public Scene pop() {
        finishTransition();
        if (stack.isEmpty()) return null;
        Scene top = stack.removeLast();
        exit(top);
        refresh();
        return top;
    }

    /**
     * Removes {@code scene} from wherever it is in the stack, not only the top. Returns false if it is not on this
     * stack.
     */
    public boolean remove(Scene scene) {
        finishTransition();
        if (scene == null || scene.manager != this) return false;
        stack.remove(scene);
        exit(scene);
        refresh();
        return true;
    }

    /**
     * Swaps the top scene for {@code scene}: the old one exits and the new one enters in its place, with the scenes
     * below left as they are (they are not resumed in between). The same as {@link #push} on an empty stack.
     */
    public void replace(Scene scene) {
        if (scene == null) throw new IllegalArgumentException("scene must not be null");
        settle(scene);
        if (scene.manager != null) throw new IllegalStateException("scene is already on a stack: " + scene);
        if (!stack.isEmpty()) {
            Scene old = stack.removeLast();
            exit(old);
        }
        stack.add(scene);
        enter(scene);
    }

    /**
     * Removes every scene, top first, then pushes {@code scene}: for moving between parts of the game, such as from
     * the main menu into a level. None of the removed scenes are resumed on the way out. If {@code scene} is already
     * on this stack it stays, resumed if it was paused, and only the others are removed.
     */
    public void set(Scene scene) {
        if (scene == null) throw new IllegalArgumentException("scene must not be null");
        settle(scene);
        if (scene.manager != null && scene.manager != this) throw new IllegalStateException("scene is on another stack: " + scene);
        clearStack(scene);
        if (scene.manager == this) {
            refresh(); // it was already here: now the only one left
        } else {
            stack.add(scene);
            enter(scene);
        }
    }

    /**
     * Removes every scene, top first.
     */
    public void clear() {
        finishTransition();
        clearStack(null);
        refresh();
    }

    /**
     * As {@link #push(Scene)}, shown with {@code transition}; null for none.
     */
    public void push(Scene scene, SceneTransition transition) {
        change(transition, () -> {
            push(scene);
            return null;
        });
    }

    /**
     * As {@link #pop()}, shown with {@code transition}; null for none.
     */
    public Scene pop(SceneTransition transition) {
        return change(transition, this::pop);
    }

    /**
     * As {@link #replace(Scene)}, shown with {@code transition}; null for none.
     */
    public void replace(Scene scene, SceneTransition transition) {
        change(transition, () -> {
            replace(scene);
            return null;
        });
    }

    /**
     * As {@link #set(Scene)}, shown with {@code transition}; null for none.
     */
    public void set(Scene scene, SceneTransition transition) {
        change(transition, () -> {
            set(scene);
            return null;
        });
    }

    /**
     * As {@link #clear()}, shown with {@code transition}; null for none. The scenes are drawn going out over the
     * window's own background and whatever containers are below them.
     */
    public void clear(SceneTransition transition) {
        change(transition, () -> {
            clear();
            return null;
        });
    }

    /**
     * True while a {@link SceneTransition} is playing.
     */
    public boolean isTransitioning() {
        return active != null;
    }

    /**
     * Ends the transition that is playing, if any, as if its time were up: the scenes are drawn as usual from the
     * next frame on, and those that went out get {@link Scene#onExit} now.
     */
    public void finishTransition() {
        ActiveTransition finished = active;
        if (finished == null) return;
        active = null;
        window.removeContainer(transitionLayer);
        runExits(finished.exits);
    }

    /**
     * The scene on top of the stack, or null if there is none.
     */
    public Scene getTop() {
        return stack.isEmpty() ? null : stack.getLast();
    }

    /**
     * The scenes on the stack, bottom first. A copy, so changing the stack doesn't change it.
     */
    public List<Scene> getScenes() {
        return List.copyOf(stack);
    }

    public int size() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    /**
     * Removes every scene except {@code keep} (which may be null), top first, without resuming any of them.
     */
    private void clearStack(Scene keep) {
        for (Scene scene : List.copyOf(stack).reversed()) {
            if (scene == keep) continue;
            stack.remove(scene);
            exit(scene);
        }
    }

    private void enter(Scene scene) {
        scene.manager = this;
        Container container = scene.getContainer();
        container.setX(0);
        container.setY(0);
        container.setWidth(window.getWindowOptions().getWidth());
        container.setHeight(window.getWindowOptions().getHeight());
        scene.visible = false;
        scene.running = true; // it gets onEnter, not onResume
        refresh(); // pauses and hides the scenes below, and places the container
        call(scene, Scene::onEnter, "onEnter");
    }

    private void exit(Scene scene) {
        scene.manager = null;
        scene.visible = false;
        scene.running = false;
        scene.getTimers().cancelAll();
        scene.cancelSubscriptions();
        window.removeContainer(scene.getContainer());
        if (pendingExits != null) {
            scene.leaving = this; // onExit once the transition has finished drawing it
            pendingExits.add(scene);
        } else {
            call(scene, Scene::onExit, "onExit");
        }
    }

    private static void runExits(List<Scene> scenes) {
        for (Scene scene : scenes) {
            scene.leaving = null;
            call(scene, Scene::onExit, "onExit");
        }
    }

    /**
     * Finishes the transition {@code scene} is still going out in, on whichever manager that is, so it gets its
     * {@link Scene#onExit} before it enters again. Also finishes the transition on this manager: any change does.
     */
    private void settle(Scene scene) {
        SceneManager leaving = scene.leaving;
        if (leaving != null) leaving.finishTransition();
        finishTransition();
    }

    /**
     * Applies {@code change} to the stack and, if it changed what is on screen, starts showing it with
     * {@code transition}.
     */
    private <T> T change(SceneTransition transition, Supplier<T> change) {
        finishTransition();
        // Inside another change (a hook of a scene entering with a transition), the outer transition shows this one too.
        if (transition == null || !(transition.getDuration() > 0f) || pendingExits != null) return change.get();

        List<Scene> before = visibleScenes();
        List<Scene> exits = new ArrayList<>();
        pendingExits = exits;
        T result = null;
        boolean applied = false;
        try {
            result = change.get();
            applied = true;
        } finally {
            pendingExits = null;
            if (!applied) runExits(exits);
        }
        begin(transition, before, visibleScenes(), exits);
        return result;
    }

    private void begin(SceneTransition transition, List<Scene> before, List<Scene> after, List<Scene> exits) {
        // The base is what stays put at the bottom; everything above it on either side goes out or comes in.
        int shared = 0;
        while (shared < before.size() && shared < after.size() && before.get(shared) == after.get(shared)) shared++;
        List<Scene> outgoing = before.subList(shared, before.size());
        List<Scene> incoming = after.subList(shared, after.size());
        if (outgoing.isEmpty() && incoming.isEmpty()) {
            runExits(exits); // nothing on screen changed
            return;
        }
        active = new ActiveTransition(transition, List.copyOf(after.subList(0, shared)), List.copyOf(outgoing),
                List.copyOf(incoming), exits);
        window.removeContainer(transitionLayer);
        window.addContainer(transitionLayer, TRANSITION_INDEX);
    }

    private List<Scene> visibleScenes() {
        List<Scene> visible = new ArrayList<>();
        for (Scene scene : stack) {
            if (scene.visible) visible.add(scene);
        }
        return visible;
    }

    /**
     * Whether scene containers should skip drawing themselves because the running transition draws them.
     */
    boolean hidesScenes() {
        return active != null && !drawingTransition;
    }

    /**
     * Render thread: draws the running transition, from the layer just below the scenes.
     */
    private void renderTransition(Renderer renderer) {
        ActiveTransition current = active;
        if (current == null) return;
        WindowOptions options = window.getWindowOptions();
        SceneTransition.Frame frame = new SceneTransition.Frame(renderer, options.getWidth(), options.getHeight(),
                current.base, current.outgoing, current.incoming);
        drawingTransition = true;
        try {
            if (current.failed) {
                frame.drawBase();
                frame.drawIncoming();
            } else {
                float duration = current.transition.getDuration();
                float progress = current.transition.getEasing().apply(Math.clamp(current.elapsed / duration, 0f, 1f));
                current.transition.render(renderer, frame, progress);
            }
        } catch (Throwable t) {
            log.error("{}.render threw; the transition is skipped", current.transition.getClass().getName(), t);
            current.failed = true;
            current.elapsed = current.transition.getDuration(); // ends on the next update
        } finally {
            drawingTransition = false;
        }
    }

    /**
     * Works out which scenes are visible and running, places their containers in the window, and fires
     * {@link Scene#onPause}/{@link Scene#onResume} for those whose running state changed.
     */
    void refresh() {
        // Bottom first: the stack only ever shrinks from under a scene, so each container moves down into a free index.
        for (int i = 0; i < stack.size(); i++) {
            Container container = stack.get(i).getContainer();
            int index = BASE_INDEX + i;
            if (window.getContainers().get(index) != container) {
                window.removeContainer(container);
                window.addContainer(container, index);
            }
        }

        boolean visible = true;
        boolean running = true;
        List<Scene> paused = new ArrayList<>();
        List<Scene> resumed = new ArrayList<>();
        for (Scene scene : stack.reversed()) {
            scene.visible = visible;
            scene.getContainer().setEnabled(visible);
            if (scene.running != running) {
                scene.running = running;
                (running ? resumed : paused).add(scene);
            }
            visible = visible && scene.isOverlay();
            running = running && !scene.isPausingBelow();
        }
        for (Scene scene : paused) call(scene, Scene::onPause, "onPause");
        for (Scene scene : resumed) call(scene, Scene::onResume, "onResume");
    }

    /**
     * Render thread, called by the engine for each fixed step.
     */
    public void fixedUpdate() {
        float step = GameTime.getFixedDelta();
        for (Scene scene : List.copyOf(stack)) {
            if (scene.running && scene.manager == this) scene.fixedUpdate(step);
        }
    }

    /**
     * Render thread, called by the engine once per frame after the fixed steps. Follows the window size, then runs
     * each running scene's timers and {@link Scene#update}, bottom first.
     */
    public void update(float delta) {
        advanceTransition(GameTime.getRealDelta());
        followWindowSize();
        for (Scene scene : List.copyOf(stack)) {
            if (!scene.running || scene.manager != this) continue;
            scene.getTimers().update(delta);
            if (scene.running && scene.manager == this) scene.update(delta);
        }
    }

    /**
     * Render thread, called by the input processor. Offers a key press or repeat to the running scenes, top first,
     * until one consumes it. A release goes to every scene on the stack, running or paused, so none is left thinking
     * a key is still held. Returns whether a scene consumed the event.
     */
    public boolean dispatchKey(int key, int action, int mods) {
        boolean release = action == GLFW.GLFW_RELEASE;
        boolean consumed = false;
        for (Scene scene : List.copyOf(stack).reversed()) {
            if (scene.manager != this || (!release && !scene.running)) continue;
            try {
                consumed |= scene.onKey(key, action, mods);
            } catch (Throwable t) {
                log.error("{}.onKey threw", scene.getClass().getName(), t);
            }
            if (consumed && !release) return true;
        }
        return consumed;
    }

    /**
     * Transitions run on real time, so they play out while game time is paused or slowed down.
     */
    private void advanceTransition(float realDelta) {
        ActiveTransition current = active;
        if (current == null) return;
        current.elapsed += realDelta;
        if (current.elapsed >= current.transition.getDuration()) finishTransition();
    }

    private void followWindowSize() {
        WindowOptions options = window.getWindowOptions();
        int w = options.getWidth(), h = options.getHeight();
        for (Scene scene : List.copyOf(stack)) {
            Container container = scene.getContainer();
            if (scene.manager != this || (container.getWidth() == w && container.getHeight() == h)) continue;
            container.setWidth(w);
            container.setHeight(h);
            call(scene, s -> s.onResize(w, h), "onResize");
        }
    }

    private static void call(Scene scene, Consumer<Scene> hook, String name) {
        try {
            hook.accept(scene);
        } catch (Throwable t) {
            log.error("{}.{} threw", scene.getClass().getName(), name, t);
        }
    }

    private static final class ActiveTransition {
        final SceneTransition transition;
        final List<Scene> base, outgoing, incoming;
        final List<Scene> exits;
        float elapsed;
        boolean failed;

        ActiveTransition(SceneTransition transition, List<Scene> base, List<Scene> outgoing, List<Scene> incoming,
                         List<Scene> exits) {
            this.transition = transition;
            this.base = base;
            this.outgoing = outgoing;
            this.incoming = incoming;
            this.exits = exits;
        }
    }

    /**
     * Sits in the window just below the scenes while a transition plays, and draws it. Takes no input.
     */
    private static final class TransitionLayer extends Container {
        private final SceneManager manager;

        TransitionLayer(SceneManager manager) {
            super(0, 0);
            this.manager = manager;
        }

        @Override
        public void renderWithTransitions(Renderer renderer) {
            manager.renderTransition(renderer);
        }

        @Override
        public boolean contains(double px, double py) {
            return false;
        }
    }
}
