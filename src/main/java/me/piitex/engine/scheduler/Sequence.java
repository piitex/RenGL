package me.piitex.engine.scheduler;

import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Steps played one after another: "wait 2 seconds, open the door, zoom the camera in, wait until the player walks
 * through, close it". Each step is a {@link Playable}, such as a {@link Tween}, a delay, an action or another sequence.
 * <pre>{@code
 * play(new Sequence()
 *         .delay(2f)
 *         .run(door::open)
 *         .then(Tween.to(camera::getZoom, camera::setZoom, 2f, 1f).easing(Easing.EASE_IN_OUT))
 *         .waitUntil(() -> player.x > door.x)
 *         .run(door::close));
 * }</pre>
 * Time a step doesn't need carries on to the next, so a sequence keeps exact time at any frame rate, and steps that
 * take no time, such as {@link #run}, all happen on the same update. Build the sequence before playing it. {@link
 * #cancel()} stops it where it is, without calling {@link #onComplete}. A sequence that has finished can be played
 * again, and it starts over.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Sequence extends Task implements Playable {
    private final List<Playable> steps = new ArrayList<>();
    private int plays = 1; // 0 = forever
    private Runnable onComplete;

    private int index;
    private int played;

    public Sequence() {
    }

    /**
     * A sequence of the given steps.
     */
    public Sequence(Playable... steps) {
        for (Playable step : steps) then(step);
    }

    /**
     * Adds a step: a {@link Tween}, another sequence, {@link Playable#parallel}, or your own {@link Playable}.
     */
    public Sequence then(Playable step) {
        if (step == null) throw new IllegalArgumentException("step must not be null");
        if (step == this) throw new IllegalArgumentException("a sequence cannot contain itself");
        steps.add(step);
        return this;
    }

    /**
     * Adds a wait of {@code seconds}.
     */
    public Sequence delay(float seconds) {
        return then(Playable.delay(seconds));
    }

    /**
     * Adds an action that runs and moves straight on. An exception it throws is logged, and the sequence carries on.
     */
    public Sequence run(Runnable action) {
        return then(Playable.run(action));
    }

    /**
     * Adds a wait until {@code condition} is true, checked once per update.
     */
    public Sequence waitUntil(BooleanSupplier condition) {
        return then(Playable.waitUntil(condition));
    }

    /**
     * Adds a step that plays all of {@code playables} at once, and moves on when the last of them finishes.
     */
    public Sequence parallel(Playable... playables) {
        return then(Playable.parallel(playables));
    }

    /**
     * Plays the whole sequence {@code times} times in all, 1 by default.
     */
    public Sequence repeat(int times) {
        if (times < 1) throw new IllegalArgumentException("times must be at least 1: " + times);
        this.plays = times;
        return this;
    }

    /**
     * Plays the sequence over and over until it is cancelled. A pass whose steps take no time at all happens at most
     * once per update. {@link #onComplete} is never called.
     */
    public Sequence loop() {
        this.plays = 0;
        return this;
    }

    /**
     * Runs {@code action} on the update the last step finishes. Not called if the sequence is cancelled.
     */
    public Sequence onComplete(Runnable action) {
        this.onComplete = action;
        return this;
    }

    @Override
    public void start() {
        clearDone();
        index = 0;
        played = 0;
        if (!steps.isEmpty()) steps.getFirst().start();
    }

    @Override
    public float update(float delta) {
        if (isCancelled() || isDone()) return delta;
        float left = delta;
        float passStart = left;
        while (true) {
            if (index < steps.size()) {
                float unused = steps.get(index).update(left);
                if (unused < 0f) return RUNNING;
                left = Math.min(unused, left);
                index++;
                if (index < steps.size()) {
                    steps.get(index).start();
                    continue;
                }
            }

            played++;
            if (plays != 0 && played >= plays) {
                markDone();
                if (onComplete != null) Scheduler.run(onComplete);
                return left;
            }
            boolean tookNoTime = left >= passStart;
            index = 0;
            if (!steps.isEmpty()) steps.getFirst().start();
            if (tookNoTime && plays == 0) return RUNNING; // go round again on the next update, not forever now
            passStart = left;
        }
    }
}
