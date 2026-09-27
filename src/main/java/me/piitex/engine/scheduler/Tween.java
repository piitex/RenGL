package me.piitex.engine.scheduler;

import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Moves any value from one number, point or color to another over time: a camera's zoom, a sprite's position, the
 * alpha of a flash, a score counting up. It calls your setter with the new value on every update.
 * <pre>{@code
 * play(Tween.of(0f, 1f, 0.4f, v -> flash = v).easing(Easing.EASE_OUT));      // in a Scene: game time
 * play(Tween.to(camera::getZoom, camera::setZoom, 2f, 0.5f));                // from wherever the zoom is now
 * Scheduler.play(Tween.of(Color.WHITE, Color.RED, 1f, Lerp.COLOR, c -> tint = c).repeat(4).yoyo()); // real time
 * }</pre>
 * Configure it before playing it. A tween applies its starting value as soon as it starts, which is at once when it is
 * played from the render thread, and its end value on the update it finishes. {@link #cancel()} stops it where it is,
 * without calling {@link #onComplete}. A tween that has finished can be played again, and it starts over.
 * <p>
 * For {@link me.piitex.engine.ui.Element elements}, the {@link me.piitex.engine.ui.animation.Transition transitions}
 * are usually the better fit; a tween is for any other value.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Tween extends Task implements Playable {

    /**
     * Receives a tweened number: {@code v -> x = v}, or a setter such as {@code camera::setZoom}.
     */
    @FunctionalInterface
    public interface FloatSetter {
        void set(float value);
    }

    /**
     * Reads the current value of a number, such as {@code camera::getZoom}.
     */
    @FunctionalInterface
    public interface FloatGetter {
        float get();
    }

    private interface Target {
        void begin();

        void apply(float t);
    }

    private final float seconds;
    private final Target target;
    private Easing easing = Easing.LINEAR;
    private float delay;
    private int plays = 1; // 0 = forever
    private boolean yoyo;
    private Runnable onComplete;

    private float delayLeft;
    private boolean begun;
    private int played;
    private float elapsed; // in the current play

    private Tween(float seconds, Target target) {
        if (!(seconds >= 0f)) throw new IllegalArgumentException("seconds must not be negative: " + seconds);
        this.seconds = seconds;
        this.target = target;
    }

    /**
     * A tween of a number from {@code from} to {@code to} over {@code seconds}.
     */
    public static Tween of(float from, float to, float seconds, FloatSetter setter) {
        requireNonNull(setter, "setter");
        return new Tween(seconds, new Target() {
            @Override
            public void begin() {
            }

            @Override
            public void apply(float t) {
                setter.set(from + (to - from) * t);
            }
        });
    }

    /**
     * A tween of a number from whatever {@code getter} reads when the tween begins, after any {@link #delay}, to
     * {@code to}. In a {@link Sequence} it picks up from where the steps before it left the value.
     */
    public static Tween to(FloatGetter getter, FloatSetter setter, float to, float seconds) {
        requireNonNull(getter, "getter");
        requireNonNull(setter, "setter");
        return new Tween(seconds, new Target() {
            private float from;

            @Override
            public void begin() {
                from = getter.get();
            }

            @Override
            public void apply(float t) {
                setter.set(from + (to - from) * t);
            }
        });
    }

    /**
     * A tween of any type, blended by {@code lerp}: {@link Lerp#VEC2} for points, {@link Lerp#COLOR} for colors, or
     * your own.
     */
    public static <T> Tween of(T from, T to, float seconds, Lerp<T> lerp, Consumer<? super T> setter) {
        requireNonNull(lerp, "lerp");
        requireNonNull(setter, "setter");
        return new Tween(seconds, new Target() {
            @Override
            public void begin() {
            }

            @Override
            public void apply(float t) {
                setter.accept(lerp.lerp(from, to, t));
            }
        });
    }

    /**
     * As {@link #to(FloatGetter, FloatSetter, float, float)}, for any type blended by {@code lerp}.
     */
    public static <T> Tween to(Supplier<? extends T> getter, Consumer<? super T> setter, T to, float seconds, Lerp<T> lerp) {
        requireNonNull(getter, "getter");
        requireNonNull(setter, "setter");
        requireNonNull(lerp, "lerp");
        return new Tween(seconds, new Target() {
            private T from;

            @Override
            public void begin() {
                from = getter.get();
            }

            @Override
            public void apply(float t) {
                setter.accept(lerp.lerp(from, to, t));
            }
        });
    }

    /**
     * The curve the value follows, {@link Easing#LINEAR} by default.
     */
    public Tween easing(Easing easing) {
        this.easing = easing == null ? Easing.LINEAR : easing;
        return this;
    }

    /**
     * Waits {@code seconds} before the tween begins. Nothing is set during the wait. Only the first play waits, not
     * each {@link #repeat}.
     */
    public Tween delay(float seconds) {
        if (!(seconds >= 0f)) throw new IllegalArgumentException("delay must not be negative: " + seconds);
        this.delay = seconds;
        return this;
    }

    /**
     * Plays the tween {@code times} times in all, 1 by default.
     */
    public Tween repeat(int times) {
        if (times < 1) throw new IllegalArgumentException("times must be at least 1: " + times);
        this.plays = times;
        return this;
    }

    /**
     * Plays the tween over and over until it is cancelled. {@link #onComplete} is never called.
     */
    public Tween loop() {
        this.plays = 0;
        return this;
    }

    /**
     * Plays every second repeat backwards, so the value goes there and back: a pulse, a bob, a blink. Use it with
     * {@link #repeat} or {@link #loop}; {@code repeat(2).yoyo()} is one trip there and back.
     */
    public Tween yoyo() {
        this.yoyo = true;
        return this;
    }

    /**
     * Runs {@code action} on the update the tween finishes, after its end value is set. Not called if it is cancelled.
     */
    public Tween onComplete(Runnable action) {
        this.onComplete = action;
        return this;
    }

    public float getSeconds() {
        return seconds;
    }

    @Override
    public void start() {
        clearDone();
        delayLeft = delay;
        begun = false;
        played = 0;
        elapsed = 0f;
    }

    @Override
    public float update(float delta) {
        if (isCancelled()) return delta; // in a sequence, a cancelled tween is skipped
        if (isDone()) return delta;
        float left = delta;
        if (!begun) {
            if (left < delayLeft) {
                delayLeft -= left;
                return RUNNING;
            }
            left -= delayLeft;
            delayLeft = 0f;
            begun = true;
            target.begin();
        }

        elapsed += left;
        while (elapsed >= seconds) {
            played++;
            if (plays != 0 && played >= plays) {
                float unused = elapsed - seconds;
                apply(played - 1, 1f);
                markDone();
                if (onComplete != null) Scheduler.run(onComplete);
                return unused;
            }
            if (seconds == 0f && plays == 0) {
                // A looping tween that takes no time plays once per update rather than forever.
                apply(played - 1, 1f);
                elapsed = 0f;
                return RUNNING;
            }
            elapsed -= seconds;
        }
        apply(played, elapsed / seconds);
        return RUNNING;
    }

    private void apply(int play, float t) {
        boolean backwards = yoyo && play % 2 == 1;
        target.apply(easing.apply(backwards ? 1f - t : t));
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
    }
}
