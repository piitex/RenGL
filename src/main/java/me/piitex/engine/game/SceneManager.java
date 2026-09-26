package me.piitex.engine.game;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

    private final Window window;
    private final List<Scene> stack = new ArrayList<>();

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
        if (scene.manager != null) throw new IllegalStateException("scene is already on a stack: " + scene);
        stack.add(scene);
        enter(scene);
    }

    /**
     * Removes the top scene, resuming and showing the one below. Returns the removed scene, or null if the stack
     * was empty.
     */
    public Scene pop() {
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
        clearStack(null);
        refresh();
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
        window.removeContainer(scene.getContainer());
        call(scene, Scene::onExit, "onExit");
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
}
