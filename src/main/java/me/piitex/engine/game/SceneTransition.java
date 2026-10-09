package me.piitex.engine.game;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.List;

/**
 * How a change to the {@link SceneManager} stack is shown on screen: a fade, a slide, a wipe, or anything a subclass
 * draws. Pass one to the stack methods that take it, such as {@link SceneManager#push(Scene, SceneTransition)}.
 * <pre>{@code
 * scenes.set(new Level(), SceneTransition.fadeThrough(Color.BLACK, 0.6f));
 * scenes.push(new PauseMenu(), SceneTransition.fade(0.2f));
 * scenes.pop(SceneTransition.slide(SceneTransition.Direction.DOWN, 0.3f));
 * }</pre>
 * The stack itself still changes at once; only the picture catches up over {@link #getDuration()} seconds of real
 * time, so a transition keeps going while game time is paused or slowed down. See {@link SceneManager} for what
 * happens to the scenes during it.
 * <p>
 * Each frame of a transition, {@link #render} draws the scenes, split into three groups by a {@link Frame}:
 * <ul>
 *   <li>the <b>base</b>: scenes that are visible before and after the change and don't move, such as the game
 *       under a pause menu being pushed;</li>
 *   <li>the <b>outgoing</b> scenes: visible before the change, not after;</li>
 *   <li>the <b>incoming</b> scenes: visible after the change, not before.</li>
 * </ul>
 * To write your own, extend this class and draw those groups however you like, with the renderer's opacity layers,
 * clips, translations and scales, and anything else on top.
 * <pre>{@code
 * class ZoomIn extends SceneTransition {
 *     ZoomIn(float duration) { super(duration, Easing.EASE_OUT); }
 *
 *     protected void render(Renderer renderer, Frame frame, float progress) {
 *         frame.drawBase();
 *         frame.drawOutgoing();
 *         renderer.pushScale(0.5f + progress / 2f, frame.getWidth() / 2f, frame.getHeight() / 2f);
 *         renderer.pushOpacityLayer(0, 0, frame.getWidth(), frame.getHeight(), progress);
 *         frame.drawIncoming();
 *         renderer.popOpacityLayer();
 *         renderer.popScale();
 *     }
 * }
 * }</pre>
 * A transition holds no state of its own between frames, so one instance can be used for any number of changes.
 * <p>
 * By default the scenes keep running and taking input while it plays. {@link #lock} freezes them and blocks input
 * until it is over, as a cutscene or a level intro wants, and {@link #skippable} lets a key press or click end it
 * early. {@link SceneManager#finishTransition()} ends it from code. These are settings of the instance, so a
 * shared one applies them to every change that uses it: {@code SceneTransition.fade(2f).lock().skippable()}.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public abstract class SceneTransition {

    /**
     * The way a {@link #slide} or {@link #wipe} moves across the window.
     */
    public enum Direction {
        /**
         * Toward the left edge: the incoming scenes come in from the right.
         */
        LEFT,
        /**
         * Toward the right edge: the incoming scenes come in from the left.
         */
        RIGHT,
        /**
         * Toward the top edge: the incoming scenes come in from the bottom.
         */
        UP,
        /**
         * Toward the bottom edge: the incoming scenes come in from the top.
         */
        DOWN
    }

    private final float duration;
    private final Easing easing;
    private boolean locking;
    private boolean skippable;

    /**
     * @param duration seconds of real time. A transition of 0 seconds or less changes the stack with no transition.
     * @param easing   the curve {@code progress} follows; null for {@link Easing#LINEAR}.
     */
    protected SceneTransition(float duration, Easing easing) {
        if (!Float.isFinite(duration)) throw new IllegalArgumentException("duration must be finite: " + duration);
        this.duration = duration;
        this.easing = easing == null ? Easing.LINEAR : easing;
    }

    public float getDuration() {
        return duration;
    }

    public Easing getEasing() {
        return easing;
    }

    /**
     * Locks the game while this transition plays: no scene gets {@link Scene#fixedUpdate}, {@link Scene#update}, its
     * timers or a key press, and the mouse, keyboard and scroll wheel do nothing in the window, until the transition
     * ends. Key and button releases still get through, so nothing is left stuck down. Combine with {@link #skippable}
     * to let the user cut it short. Returns this transition.
     */
    public SceneTransition lock() {
        return lock(true);
    }

    public SceneTransition lock(boolean locking) {
        this.locking = locking;
        return this;
    }

    /**
     * Whether the scenes are frozen and input blocked while this transition plays. Off by default.
     */
    public boolean isLocking() {
        return locking;
    }

    /**
     * Lets a key press or mouse click end this transition early, as {@link SceneManager#finishTransition()} does. The
     * press only skips; it does not reach the scenes or the UI. Only meaningful for a {@link #lock locking} transition,
     * since without the lock the input keeps working as usual and nothing is swallowed. Returns this transition.
     */
    public SceneTransition skippable() {
        return skippable(true);
    }

    public SceneTransition skippable(boolean skippable) {
        this.skippable = skippable;
        return this;
    }

    /**
     * Whether a key press or click ends this transition early. Off by default.
     */
    public boolean isSkippable() {
        return skippable;
    }

    /**
     * Draws one frame of the transition. {@code progress} runs from 0, where the scenes should look as they did
     * before the change, to 1, where they should look as they do after it, shaped by {@link #getEasing()}. Leave
     * the renderer as you found it: pop everything you push.
     */
    protected abstract void render(Renderer renderer, Frame frame, float progress);

    /**
     * Fades the outgoing scenes out and the incoming ones in, at the same time.
     */
    public static SceneTransition fade(float duration) {
        return fade(duration, Easing.EASE_IN_OUT);
    }

    public static SceneTransition fade(float duration, Easing easing) {
        return new Fade(duration, easing);
    }

    /**
     * Fades everything to {@code color} over the first half, then from it to the new scenes over the second: the
     * classic fade to black between levels. The base scenes are covered too.
     */
    public static SceneTransition fadeThrough(Color color, float duration) {
        return fadeThrough(color, duration, Easing.EASE_IN_OUT);
    }

    public static SceneTransition fadeThrough(Color color, float duration, Easing easing) {
        return new FadeThrough(color, duration, easing);
    }

    /**
     * Slides the incoming scenes in and the outgoing ones out, moving together in {@code direction}. Base scenes
     * stay where they are, so a pause menu slides over the game.
     */
    public static SceneTransition slide(Direction direction, float duration) {
        return slide(direction, duration, Easing.EASE_IN_OUT);
    }

    public static SceneTransition slide(Direction direction, float duration, Easing easing) {
        return new Slide(direction, duration, easing);
    }

    /**
     * Uncovers the incoming scenes behind an edge that sweeps across the window in {@code direction}. Nothing moves.
     */
    public static SceneTransition wipe(Direction direction, float duration) {
        return wipe(direction, duration, Easing.EASE_IN_OUT);
    }

    public static SceneTransition wipe(Direction direction, float duration, Easing easing) {
        return new Wipe(direction, duration, easing);
    }

    /**
     * The scenes a transition draws in one frame, and the size of the window. Every scene is drawn the way it
     * normally is: its {@link Scene#render} first, then the elements in its container. Only valid during the
     * {@link #render} call it was passed to.
     */
    public static final class Frame {
        private final Renderer renderer;
        private final float width, height;
        private final List<Scene> base, outgoing, incoming;

        Frame(Renderer renderer, float width, float height, List<Scene> base, List<Scene> outgoing, List<Scene> incoming) {
            this.renderer = renderer;
            this.width = width;
            this.height = height;
            this.base = base;
            this.outgoing = outgoing;
            this.incoming = incoming;
        }

        public float getWidth() {
            return width;
        }

        public float getHeight() {
            return height;
        }

        /**
         * Draws the base scenes, bottom first: visible before and after the change.
         */
        public void drawBase() {
            for (Scene scene : base) draw(scene);
        }

        /**
         * Draws the outgoing scenes, bottom first: visible before the change but not after.
         */
        public void drawOutgoing() {
            for (Scene scene : outgoing) draw(scene);
        }

        /**
         * Draws the incoming scenes, bottom first: visible after the change but not before.
         */
        public void drawIncoming() {
            for (Scene scene : incoming) draw(scene);
        }

        public List<Scene> getBase() {
            return base;
        }

        public List<Scene> getOutgoing() {
            return outgoing;
        }

        public List<Scene> getIncoming() {
            return incoming;
        }

        /**
         * Draws one scene, from any of the three groups, for a transition that treats them one at a time.
         */
        public void draw(Scene scene) {
            Container container = scene.getContainer();
            boolean enabled = container.isEnabled(); // an outgoing scene still on the stack is already hidden
            container.setEnabled(true);
            try {
                container.renderWithTransitions(renderer);
            } finally {
                container.setEnabled(enabled);
            }
        }
    }

    private static final class Fade extends SceneTransition {
        Fade(float duration, Easing easing) {
            super(duration, easing);
        }

        @Override
        protected void render(Renderer renderer, Frame frame, float progress) {
            frame.drawBase();
            drawFaded(renderer, frame, frame.getOutgoing(), 1f - progress);
            drawFaded(renderer, frame, frame.getIncoming(), progress);
        }

        private static void drawFaded(Renderer renderer, Frame frame, List<Scene> scenes, float opacity) {
            if (scenes.isEmpty() || opacity <= 0f) return;
            if (opacity >= 1f) {
                for (Scene scene : scenes) frame.draw(scene);
                return;
            }
            renderer.pushOpacityLayer(0f, 0f, frame.getWidth(), frame.getHeight(), opacity);
            for (Scene scene : scenes) frame.draw(scene);
            renderer.popOpacityLayer();
        }
    }

    private static final class FadeThrough extends SceneTransition {
        private final Color color;

        FadeThrough(Color color, float duration, Easing easing) {
            super(duration, easing);
            this.color = color == null ? Color.BLACK : color;
        }

        @Override
        protected void render(Renderer renderer, Frame frame, float progress) {
            frame.drawBase();
            float cover;
            if (progress < 0.5f) {
                frame.drawOutgoing();
                cover = progress * 2f;
            } else {
                frame.drawIncoming();
                cover = (1f - progress) * 2f;
            }
            float alpha = color.getA() * Math.clamp(cover, 0f, 1f);
            if (alpha > 0f) {
                renderer.drawQuad(0f, 0f, frame.getWidth(), frame.getHeight(),
                        new Color(color.getR(), color.getG(), color.getB(), alpha), 0f);
            }
        }
    }

    private static final class Slide extends SceneTransition {
        private final Direction direction;

        Slide(Direction direction, float duration, Easing easing) {
            super(duration, easing);
            this.direction = direction == null ? Direction.LEFT : direction;
        }

        @Override
        protected void render(Renderer renderer, Frame frame, float progress) {
            frame.drawBase();
            // Unit vector of the movement; everything travels one window width or height.
            float dx = direction == Direction.LEFT ? -1f : direction == Direction.RIGHT ? 1f : 0f;
            float dy = direction == Direction.UP ? -1f : direction == Direction.DOWN ? 1f : 0f;
            float w = frame.getWidth(), h = frame.getHeight();

            renderer.pushTranslate(dx * w * progress, dy * h * progress);
            frame.drawOutgoing();
            renderer.popTranslate();

            renderer.pushTranslate(-dx * w * (1f - progress), -dy * h * (1f - progress));
            frame.drawIncoming();
            renderer.popTranslate();
        }
    }

    private static final class Wipe extends SceneTransition {
        private final Direction direction;

        Wipe(Direction direction, float duration, Easing easing) {
            super(duration, easing);
            this.direction = direction == null ? Direction.LEFT : direction;
        }

        @Override
        protected void render(Renderer renderer, Frame frame, float progress) {
            frame.drawBase();
            float w = frame.getWidth(), h = frame.getHeight();
            // The edge sweeps in the direction of travel: the incoming scenes show on the side it has passed.
            float[] out, in;
            switch (direction) {
                case LEFT -> {
                    float edge = w * (1f - progress);
                    out = new float[]{0f, 0f, edge, h};
                    in = new float[]{edge, 0f, w - edge, h};
                }
                case RIGHT -> {
                    float edge = w * progress;
                    out = new float[]{edge, 0f, w - edge, h};
                    in = new float[]{0f, 0f, edge, h};
                }
                case UP -> {
                    float edge = h * (1f - progress);
                    out = new float[]{0f, 0f, w, edge};
                    in = new float[]{0f, edge, w, h - edge};
                }
                default -> {
                    float edge = h * progress;
                    out = new float[]{0f, edge, w, h - edge};
                    in = new float[]{0f, 0f, w, edge};
                }
            }
            drawClipped(renderer, out, frame::drawOutgoing);
            drawClipped(renderer, in, frame::drawIncoming);
        }

        private static void drawClipped(Renderer renderer, float[] rect, Runnable draw) {
            if (rect[2] <= 0f || rect[3] <= 0f) return;
            renderer.pushClip(rect[0], rect[1], rect[2], rect[3]);
            draw.run();
            renderer.popClip();
        }
    }
}
