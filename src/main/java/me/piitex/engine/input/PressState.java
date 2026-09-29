package me.piitex.engine.input;

import me.piitex.engine.game.GameTime;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * Whether something is held, and when it was last pressed, released and repeated, for a key, a mouse button or an
 * action. Each moment is stamped twice: with the {@link Input} frame, for code running once per frame, and with the
 * fixed step it comes before ({@link GameTime#getTicks()} at the time), for code in a fixed step. The queries pick the
 * one that matches where they are called from, so "just pressed" means "since the last frame" in
 * {@code Scene.update} and "since the last step" in {@code Scene.fixedUpdate}. Render thread only.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
final class PressState {
    boolean down;
    private long pressFrame = -1, pressTick = -1;
    private long releaseFrame = -1, releaseTick = -1;
    private long repeatFrame = -1, repeatTick = -1;
    private double pressTime;

    void press(long frame) {
        down = true;
        pressFrame = frame;
        pressTick = GameTime.getTicks();
        pressTime = GameTime.getTime();
    }

    void release(long frame) {
        down = false;
        releaseFrame = frame;
        releaseTick = GameTime.getTicks();
    }

    void repeat(long frame) {
        repeatFrame = frame;
        repeatTick = GameTime.getTicks();
    }

    /**
     * A press and release at once, such as a wheel notch. Held state is left as it is.
     */
    void tap(long frame) {
        boolean wasDown = down;
        press(frame);
        release(frame);
        down = wasDown;
    }

    boolean justPressed(long frame) {
        return GameTime.isInFixedStep() ? pressTick == GameTime.getTicks() : pressFrame == frame;
    }

    boolean justReleased(long frame) {
        return GameTime.isInFixedStep() ? releaseTick == GameTime.getTicks() : releaseFrame == frame;
    }

    boolean repeated(long frame) {
        return GameTime.isInFixedStep() ? repeatTick == GameTime.getTicks() : repeatFrame == frame;
    }

    /**
     * Seconds of game time held, counting the current step or frame. 0 when not held.
     */
    float heldTime() {
        if (!down) return 0f;
        if (GameTime.isInFixedStep()) return (GameTime.getTicks() - pressTick + 1) * GameTime.getFixedDelta();
        return (float) Math.max(0.0, GameTime.getTime() - pressTime);
    }

    void clear() {
        down = false;
        pressFrame = pressTick = releaseFrame = releaseTick = repeatFrame = repeatTick = -1;
    }
}
