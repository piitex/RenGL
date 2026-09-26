package me.piitex.engine.scheduler;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A set of delay and repeating timers advanced by whoever owns it, on whatever clock they choose. {@link Scheduler}
 * keeps one on the real frame clock, and every {@link me.piitex.engine.game.Scene} keeps one on the game clock, so
 * a scene's timers stop while the scene is paused and follow {@link me.piitex.engine.game.GameTime#setTimeScale}.
 * <p>
 * Timers can be added from any thread. They start counting on the next {@link #update}, and their actions run on
 * the thread that calls {@link #update}. An exception thrown by an action is logged and does not stop other timers.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Timers {
    private final Queue<Timer> incoming = new ConcurrentLinkedQueue<>();
    private final List<Timer> timers = new ArrayList<>(); // updating thread only

    /**
     * Runs {@code action} once after {@code seconds}.
     */
    public Task after(float seconds, Runnable action) {
        return schedule(seconds, 0f, action);
    }

    /**
     * Runs {@code action} every {@code seconds}, starting {@code seconds} from now, until cancelled.
     */
    public Task every(float seconds, Runnable action) {
        return every(seconds, seconds, action);
    }

    /**
     * As {@link #every(float, Runnable)}, but the first run comes after {@code initialDelay}.
     */
    public Task every(float initialDelay, float interval, Runnable action) {
        if (interval <= 0f) throw new IllegalArgumentException("interval must be positive: " + interval);
        return schedule(initialDelay, interval, action);
    }

    private Task schedule(float delay, float interval, Runnable action) {
        if (delay < 0f) throw new IllegalArgumentException("delay must not be negative: " + delay);
        Timer timer = new Timer(delay, interval, action);
        incoming.add(timer);
        return timer;
    }

    /**
     * Advances every timer by {@code delta} seconds and runs the ones that are due.
     */
    public void update(float delta) {
        Timer added;
        while ((added = incoming.poll()) != null) {
            timers.add(added);
        }

        Iterator<Timer> it = timers.iterator();
        while (it.hasNext()) {
            Timer timer = it.next();
            if (timer.isCancelled()) {
                it.remove();
                continue;
            }
            timer.remaining -= delta;
            if (timer.remaining > 0f) continue;

            if (timer.interval > 0f) {
                // Keep the overshoot so the rate holds steady, but never queue up a backlog of missed runs.
                timer.remaining = Math.max(timer.remaining + timer.interval, 0f);
            } else {
                timer.markDone();
                it.remove();
            }
            Scheduler.run(timer.action);
        }
    }

    /**
     * Cancels every timer in this set. Call it on the thread that calls {@link #update}; it is safe from inside a
     * timer's own action.
     */
    public void cancelAll() {
        Timer timer;
        while ((timer = incoming.poll()) != null) {
            timer.cancel();
        }
        // Not cleared here: this may run from inside update()'s loop. Cancelled timers are dropped on the next update.
        for (Timer pending : timers) {
            pending.cancel();
        }
    }

    private static final class Timer extends Task {
        final float interval; // 0 = one-shot
        final Runnable action;
        float remaining;

        Timer(float delay, float interval, Runnable action) {
            this.remaining = delay;
            this.interval = interval;
            this.action = action;
        }
    }
}
