package me.piitex.engine.game;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.scheduler.Task;
import me.piitex.engine.scheduler.Timers;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One screen or state of a game: a main menu, the gameplay, a pause menu, a loading screen. Scenes live on the
 * window's {@link SceneManager}, which keeps them in a stack: the top one is the one being played, and the ones below
 * it wait, paused, until it is removed. Pushing a pause menu over the gameplay and popping it again is the typical use.
 * <p>
 * Extend this class and override the hooks you need. Every hook runs on the render thread.
 * <ul>
 *   <li>{@link #onEnter()} when the scene is added, {@link #onExit()} when it is removed. Build the scene's UI in
 *       {@code onEnter}.</li>
 *   <li>{@link #onPause()} and {@link #onResume()} when a scene above it starts and stops pausing it.</li>
 *   <li>{@link #fixedUpdate(float)} at the fixed tick rate, for game logic and physics, and {@link #update(float)}
 *       once per frame. Both run on game time (see {@link GameTime}) and only while the scene is not paused.</li>
 *   <li>{@link #render(Renderer, float)} to draw the game world with the {@link Renderer} directly.</li>
 *   <li>{@link #onKey(int, int, int)} for keyboard input no text field or other focused element has taken.</li>
 * </ul>
 * For controls, polling is usually simpler than {@code onKey}: add an {@link InputContext} with the scene's actions
 * with {@link #addInputContext}, and ask it in {@link #fixedUpdate} or {@link #update} whether an action is held or was
 * just pressed. The window's {@link me.piitex.engine.input.Input} has the raw keys and mouse.
 * <p>
 * Every scene has a {@link #getContainer() container} that fills the window. Add buttons, text and other elements
 * to it as with any container. They are drawn above what {@link #render} draws, work with the mouse as usual, and
 * keep running on real time even while the scene is paused. Scenes are drawn below every container added to the
 * window directly, so those (a debug overlay, say) stay on top of whatever scene is showing, as do popups and modals.
 * <p>
 * A scene can be on at most one stack at a time, and can be pushed again after it has been removed.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public abstract class Scene {
    private final Container container = new SceneContainer(this);
    private final Timers timers = new Timers();
    private boolean overlay;
    private boolean pausesBelow = true;
    private final List<InputContext> inputContexts = new ArrayList<>();

    SceneManager manager; // non-null while on a stack
    SceneManager leaving; // non-null while drawn going out in a transition, until onExit
    boolean visible;
    boolean running;

    /**
     * Called when the scene is added to the stack, after the scene below it (if any) has been paused. The container
     * already fills the window.
     */
    protected void onEnter() {
    }

    /**
     * Called when the scene is removed from the stack. Its container is already off the window, and its timers are
     * cancelled. When the removal plays a {@link SceneTransition}, this comes at the end of the transition, once the
     * scene has stopped being drawn, so it is the place to release anything {@link #render} uses. Not called when
     * the application closes; use {@link Window#onClose} for that.
     */
    protected void onExit() {
    }

    /**
     * Called when a scene pushed above this one pauses it. From now on {@link #update}, {@link #fixedUpdate} and
     * the scene's timers stop until {@link #onResume()}.
     */
    protected void onPause() {
    }

    /**
     * Called when this scene runs again after {@link #onPause()}, typically because the scene above it was popped.
     */
    protected void onResume() {
    }

    /**
     * Game logic at the fixed tick rate, {@link GameTime#getTickRate()} times per second of game time. {@code step}
     * is always {@link GameTime#getFixedDelta()}, so movement written as {@code position += speed * step} plays out
     * the same at any frame rate. Only called while this scene is running (see {@link #isRunning()}).
     */
    protected void fixedUpdate(float step) {
    }

    /**
     * Called once per frame while this scene is running, after the fixed steps for the frame. {@code delta} is the
     * frame's game time in seconds ({@link GameTime#getDelta()}): scaled by the time scale, and 0 while
     * {@link GameTime#setPaused game time is paused}.
     */
    protected void update(float delta) {
    }

    /**
     * Draws the scene's world, in window coordinates, below the elements in {@link #getContainer()}. Called every
     * frame the scene is visible, including while it is paused under an overlay, and while a
     * {@link SceneTransition} draws it on its way out, after it has left the stack. Only draw here; change state in
     * {@link #update} or {@link #fixedUpdate}. {@code alpha} is {@link GameTime#getAlpha()}, for drawing things
     * moved in {@link #fixedUpdate} between their last two positions.
     */
    protected void render(Renderer renderer, float alpha) {
    }

    /**
     * A key press or repeat that no focused element (a text field, say), popup or modal has taken, while this scene
     * is running. Return true to consume it; otherwise it goes on to the scene below, if that one is running too.
     * <p>
     * Key releases are different: every scene on the stack gets every release, paused or not, even while a text field
     * has the keyboard, and consuming one doesn't stop it. So a scene tracking held keys never misses one being let
     * go. {@code key}, {@code action} and {@code mods} are GLFW values: {@code GLFW_KEY_*}, {@code GLFW_PRESS}/
     * {@code GLFW_REPEAT}/{@code GLFW_RELEASE}, {@code GLFW_MOD_*}. Tab presses are kept for moving focus between
     * elements while an element has focus or a popup or modal is open; otherwise they come here first, and move focus
     * only if no scene consumes them and no {@link InputContext} binds Tab.
     */
    protected boolean onKey(int key, int action, int mods) {
        return false;
    }

    /**
     * Adds an {@link InputContext} that receives input while this scene is running. A scene's contexts get presses
     * before those of the scenes below it, and after the window's own; among the scene's contexts, the last added comes
     * first. A context can belong to several scenes, such as shared menu controls.
     */
    public void addInputContext(InputContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (!inputContexts.contains(context)) inputContexts.add(context);
    }

    public void removeInputContext(InputContext context) {
        inputContexts.remove(context);
    }

    /**
     * The contexts added with {@link #addInputContext}, in the order they were added.
     */
    public List<InputContext> getInputContexts() {
        return Collections.unmodifiableList(inputContexts);
    }

    /**
     * Called when the window changes size, after the container has been resized to match. Also called on
     * scenes that are paused or hidden, so they are laid out correctly when they show again.
     */
    protected void onResize(int width, int height) {
    }

    /**
     * The container holding this scene's elements. It covers the whole window, follows its size, and has no
     * background or border unless you give it one.
     */
    public Container getContainer() {
        return container;
    }

    /**
     * The manager whose stack this scene is on, or null when it is not on one.
     */
    public SceneManager getSceneManager() {
        return manager;
    }

    /**
     * The window this scene is shown in, or null when it is not on a stack.
     */
    public Window getWindow() {
        SceneManager current = manager;
        return current == null ? null : current.getWindow();
    }

    /**
     * True while the scene is on a stack, from just before {@link #onEnter()} until it is removed. That is just
     * before {@link #onExit()}, or when a {@link SceneTransition} plays, at the start of the transition.
     */
    public boolean isActive() {
        return manager != null;
    }

    /**
     * True while the scene is drawn: it is the top scene, or every scene above it is an {@link #setOverlay overlay}.
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * True while the scene's updates and timers run: it is the top scene, or no scene above it
     * {@link #setPausesBelow pauses the ones below}. False between {@link #onPause()} and {@link #onResume()}.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Whether the scenes below this one stay visible behind it, false by default. Make a pause menu or a dialog an
     * overlay so the paused game shows through; give its container a translucent background to dim it.
     */
    public void setOverlay(boolean overlay) {
        this.overlay = overlay;
        refresh();
    }

    public boolean isOverlay() {
        return overlay;
    }

    /**
     * Whether the scenes below this one are paused while it is on top of them, true by default. Turn it off for a
     * scene that should run alongside the one below, such as a HUD or a chat box over the gameplay. The scene
     * below then keeps receiving the keys this one does not consume.
     */
    public void setPausesBelow(boolean pausesBelow) {
        this.pausesBelow = pausesBelow;
        refresh();
    }

    public boolean isPausingBelow() {
        return pausesBelow;
    }

    /**
     * Runs {@code action} after {@code seconds} of this scene's game time: the timer is scaled by
     * {@link GameTime#setTimeScale}, stops while the scene is paused, and is cancelled if the scene is removed first.
     * Use {@link me.piitex.engine.scheduler.Scheduler#after} for a timer on real time instead.
     */
    public Task after(float seconds, Runnable action) {
        return timers.after(seconds, action);
    }

    /**
     * As {@link #after}, but runs {@code action} every {@code seconds} until cancelled or the scene is removed.
     */
    public Task every(float seconds, Runnable action) {
        return timers.every(seconds, action);
    }

    /**
     * As {@link #every(float, Runnable)}, but the first run comes after {@code initialDelay}.
     */
    public Task every(float initialDelay, float interval, Runnable action) {
        return timers.every(initialDelay, interval, action);
    }

    Timers getTimers() {
        return timers;
    }

    private void refresh() {
        SceneManager current = manager;
        if (current != null) current.refresh();
    }

    /**
     * The scene's root container. Draws the scene's world after its own background and before its children.
     */
    private static final class SceneContainer extends Container {
        private final Scene scene;

        SceneContainer(Scene scene) {
            super(0, 0);
            this.scene = scene;
        }

        @Override
        public void renderWithTransitions(Renderer renderer) {
            SceneManager current = scene.manager;
            if (current != null && current.hidesScenes()) return; // the running scene transition draws it instead
            super.renderWithTransitions(renderer);
        }

        @Override
        protected void renderBackground(Renderer renderer) {
            super.renderBackground(renderer);
            scene.render(renderer, GameTime.getAlpha());
        }
    }
}
