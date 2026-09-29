package me.piitex.engine.scheduler;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.function.BooleanSupplier;

/**
 * Something that plays out over time on a clock: a {@link Tween}, a {@link Sequence}, a delay, or your own. Hand it to
 * {@link me.piitex.engine.game.Scene#play} to run on the scene's game time, or to {@link Scheduler#play} to run on
 * real time, or add it to a {@link Sequence}.
 * <p>
 * Most code never implements this: the factories below, {@link Tween} and {@link Sequence} cover the usual needs. To
 * write one, reset its state in {@link #start()} and advance it in {@link #update(float)}:
 * <pre>{@code
 * Playable waitForAnimation = delta -> player.isFinished() ? delta : Playable.RUNNING;
 * }</pre>
 * A playable is played in one place at a time. Everything it does runs on the thread of the clock playing it, which
 * for a scene or the {@link Scheduler} is the render thread.
 */
@FunctionalInterface
public interface Playable {

    /**
     * What {@link #update} returns while the playable still has more to do.
     */
    float RUNNING = -1f;

    /**
     * Called when the playable begins, before its first {@link #update}, and again each time a repeating
     * {@link Sequence} comes back round to it. Reset any state here so it can play again.
     */
    default void start() {
    }

    /**
     * Advances by {@code delta} seconds, which may be 0. Returns {@link #RUNNING} (any negative number) while there is
     * more to do. Once it finishes, it returns the part of {@code delta} it did not need, 0 or more, which a
     * {@link Sequence} hands on to the next step, so the timing stays exact at any frame rate. A playable that finishes
     * at once returns {@code delta}.
     */
    float update(float delta);

    /**
     * Waits {@code seconds}.
     */
    static Playable delay(float seconds) {
        if (!(seconds >= 0f)) throw new IllegalArgumentException("seconds must not be negative: " + seconds);
        return new Playable() {
            private float left;

            @Override
            public void start() {
                left = seconds;
            }

            @Override
            public float update(float delta) {
                left -= delta;
                return left > 0f ? RUNNING : -left;
            }
        };
    }

    /**
     * Runs {@code action} and finishes at once. An exception it throws is logged and the playable still finishes, so a
     * {@link Sequence} carries on.
     */
    static Playable run(Runnable action) {
        if (action == null) throw new IllegalArgumentException("action must not be null");
        return delta -> {
            Scheduler.run(action);
            return delta;
        };
    }

    /**
     * Waits until {@code condition} is true. It is checked when the playable is reached and then once per update, so
     * the rest of that update's time carries on to what follows.
     */
    static Playable waitUntil(BooleanSupplier condition) {
        if (condition == null) throw new IllegalArgumentException("condition must not be null");
        return delta -> condition.getAsBoolean() ? delta : RUNNING;
    }

    /**
     * Plays all of {@code playables} at once, and finishes when the last of them does.
     */
    static Playable parallel(Playable... playables) {
        Playable[] all = playables.clone();
        for (Playable playable : all) {
            if (playable == null) throw new IllegalArgumentException("playables must not contain null");
        }
        return new Playable() {
            private final boolean[] finished = new boolean[all.length];

            @Override
            public void start() {
                for (int i = 0; i < all.length; i++) {
                    finished[i] = false;
                    all[i].start();
                }
            }

            @Override
            public float update(float delta) {
                // What is left over is what the last to finish didn't need; those that finished earlier used none of it.
                float unused = delta;
                boolean running = false;
                for (int i = 0; i < all.length; i++) {
                    if (finished[i]) continue;
                    float left = all[i].update(delta);
                    if (left < 0f) {
                        running = true;
                    } else {
                        finished[i] = true;
                        unused = Math.min(unused, left);
                    }
                }
                return running ? RUNNING : unused;
            }
        };
    }
}
