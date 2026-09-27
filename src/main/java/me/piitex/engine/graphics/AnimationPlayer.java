package me.piitex.engine.graphics;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plays {@link Animation}s for one thing on screen: keeps its place in the current animation, moves it on in
 * {@link #update}, and reports frame events and the end of the animation.
 * <pre>{@code
 * AnimationPlayer anim = new AnimationPlayer();
 * anim.onEvent(event -> { if (event.equals("footstep")) footstep.play(); });
 *
 * // in Scene.update:
 * anim.play(moving ? walk : idle); // switches only when the animation changes
 * anim.update(delta);
 * sprite.setRegion(anim.getFrame());
 * }</pre>
 * It runs on whatever time it is given. Pass the scene's {@code delta} to follow game time, so animations slow down
 * with the time scale and stop while the game is paused.
 * <p>
 * Callbacks run inside {@link #update}, in the order things happened, even when one update covers several frames.
 * Not thread-safe; use it on the render thread.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class AnimationPlayer {
    private final List<Consumer<String>> eventListeners = new ArrayList<>();
    private final List<Runnable> finishListeners = new ArrayList<>();

    private Animation animation;
    private int step;          // position in the animation's sequence
    private float frameTime;   // seconds into the current frame
    private boolean started;   // the current frame's start has been reported
    private boolean finished;
    private boolean paused;
    private float speed = 1f;

    public AnimationPlayer() {
    }

    public AnimationPlayer(Animation animation) {
        play(animation);
    }

    /**
     * Switches to {@code animation} from its first frame. Does nothing if it is already the current animation,
     * even if it has finished, so it can be called every frame with whichever animation should be showing; use
     * {@link #restart()} to play the current one again. Null stops playing, leaving no frame.
     */
    public void play(Animation animation) {
        if (animation == this.animation) return;
        this.animation = animation;
        restart();
    }

    /**
     * Plays the current animation again from its first frame.
     */
    public void restart() {
        step = 0;
        frameTime = 0f;
        started = false;
        finished = false;
    }

    /**
     * Moves the animation on by {@code delta} seconds, times the {@link #setSpeed speed}. The first update after
     * {@link #play} reports the first frame's event before moving on.
     */
    public void update(float delta) {
        if (animation == null) return;
        if (!started) {
            started = true;
            fire(animation.getFrame(animation.sequenceFrame(step)).event());
        }
        if (paused || finished || !(delta > 0f) || !Float.isFinite(delta)) return;
        frameTime += delta * speed;

        // Whole passes beyond the next one would only repeat the same events; skip them.
        float pass = animation.getDuration();
        if (animation.getMode() != Animation.PlayMode.ONCE && frameTime > pass * 2f) {
            frameTime -= (float) Math.floor(frameTime / pass - 1f) * pass;
        }

        Animation playing = animation;
        while (playing == animation && !finished) {
            float shown = animation.getFrame(animation.sequenceFrame(step)).duration();
            if (frameTime < shown) break;
            if (step == animation.sequenceLength() - 1 && animation.getMode() == Animation.PlayMode.ONCE) {
                frameTime = shown;
                finished = true;
                for (Runnable listener : List.copyOf(finishListeners)) listener.run();
                break;
            }
            frameTime -= shown;
            step = (step + 1) % animation.sequenceLength();
            fire(animation.getFrame(animation.sequenceFrame(step)).event());
        }
    }

    private void fire(String event) {
        if (event == null) return;
        for (Consumer<String> listener : List.copyOf(eventListeners)) listener.accept(event);
    }

    /**
     * Calls {@code listener} with a frame's event each time that frame starts. A listener may switch animations.
     */
    public AnimationPlayer onEvent(Consumer<String> listener) {
        eventListeners.add(listener);
        return this;
    }

    /**
     * Calls {@code listener} when a {@link Animation.PlayMode#ONCE} animation has shown its last frame for its full
     * duration. A looping animation never finishes. A listener may switch animations.
     */
    public AnimationPlayer onFinish(Runnable listener) {
        finishListeners.add(listener);
        return this;
    }

    public Animation getAnimation() {
        return animation;
    }

    /**
     * The picture to show now, or null without an animation.
     */
    public TextureRegion getFrame() {
        return animation == null ? null : animation.getFrame(animation.sequenceFrame(step)).region();
    }

    /**
     * The index of the frame showing now in the animation's frame list, or -1 without an animation.
     */
    public int getFrameIndex() {
        return animation == null ? -1 : animation.sequenceFrame(step);
    }

    /**
     * True once a {@link Animation.PlayMode#ONCE} animation has finished, until the next {@link #play} of another
     * animation or {@link #restart()}.
     */
    public boolean isFinished() {
        return finished;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Holds the animation on its current frame while true.
     */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public float getSpeed() {
        return speed;
    }

    /**
     * How fast the animation plays, 1 by default: 2 plays it twice as fast, 0.5 at half speed.
     */
    public void setSpeed(float speed) {
        if (!(speed >= 0f) || !Float.isFinite(speed)) throw new IllegalArgumentException("speed must be 0 or more: " + speed);
        this.speed = speed;
    }
}
