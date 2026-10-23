package me.piitex.engine.scheduler;

import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A set of delay and repeating timers, {@link Tween tweens} and {@link Sequence sequences}, advanced by whoever owns
 * it, on whatever clock they choose. {@link Scheduler} keeps one on the real frame clock, and every
 * {@link me.piitex.engine.game.Scene} keeps one on the game clock, so a scene's timers stop while the scene is paused
 * and follow {@link me.piitex.engine.game.GameTime#setTimeScale}.
 * <p>
 * Timers and playables can be added from any thread. Timers start counting on the next {@link #update}. A
 * {@link Playable} played from the thread that calls {@link #update} starts at once, so a tween sets its starting value
 * before the frame is drawn; from any other thread it starts on the next update. Everything runs on the thread that
 * calls {@link #update}. An exception thrown by a timer's action is logged and does not stop other timers; a playable
 * that throws is logged and dropped.
 */
public final class Timers {
    private static final Logger log = LogManager.getLogger("Scheduler");

    private final Queue<Entry> incoming = new ConcurrentLinkedQueue<>();
    private final List<Entry> entries = new ArrayList<>(); // updating thread only
    private volatile Thread updater;

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
        Entry entry = new Entry(timer, timer);
        entry.started = true;
        incoming.add(entry);
        return timer;
    }

    /**
     * Plays {@code playable} until it finishes or is cancelled. Returns the handle to cancel it with: the tween or
     * sequence itself, or a new {@link Task} for any other playable.
     */
    public Task play(Playable playable) {
        if (playable == null) throw new IllegalArgumentException("playable must not be null");
        Task handle = playable instanceof Task task ? task : new Task();
        Entry entry = new Entry(playable, handle);
        Thread owner = updater;
        if (owner == null ? Scheduler.isRenderThread() : owner == Thread.currentThread()) {
            if (!begin(entry)) return handle; // finished, or threw, at once
        }
        incoming.add(entry);
        return handle;
    }

    /**
     * Advances every timer and playable by {@code delta} seconds, and runs the timers that are due.
     */
    public void update(float delta) {
        updater = Thread.currentThread();
        Entry added;
        while ((added = incoming.poll()) != null) {
            if (added.handle.isCancelled()) continue;
            if (!added.started) {
                added.started = true;
                try {
                    added.playable.start();
                } catch (Throwable t) {
                    log.error("Playable threw; it has been stopped", t);
                    added.handle.cancel();
                    continue;
                }
            }
            entries.add(added);
        }

        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.handle.isCancelled()) {
                it.remove();
                continue;
            }
            float left;
            try {
                left = entry.playable.update(delta);
            } catch (Throwable t) {
                log.error("Playable threw; it has been stopped", t);
                entry.handle.cancel();
                it.remove();
                continue;
            }
            if (left >= 0f) {
                entry.handle.markDone();
                it.remove();
            }
        }
    }

    /**
     * Starts the entry and applies its starting state. False if it already finished or threw.
     */
    private boolean begin(Entry entry) {
        entry.started = true;
        try {
            entry.playable.start();
            if (entry.playable.update(0f) < 0f) return true;
            entry.handle.markDone();
        } catch (Throwable t) {
            log.error("Playable threw; it has been stopped", t);
            entry.handle.cancel();
        }
        return false;
    }

    /**
     * Cancels every timer and playable in this set. Call it on the thread that calls {@link #update}; it is safe from
     * inside a timer's own action.
     */
    public void cancelAll() {
        Entry entry;
        while ((entry = incoming.poll()) != null) {
            entry.handle.cancel();
        }
        // Not cleared here: this may run from inside update()'s loop. Cancelled entries are dropped on the next update.
        for (Entry pending : entries) {
            pending.handle.cancel();
        }
    }

    private static final class Entry {
        final Playable playable;
        final Task handle;
        boolean started;

        Entry(Playable playable, Task handle) {
            this.playable = playable;
            this.handle = handle;
        }
    }

    private static final class Timer extends Task implements Playable {
        final float interval; // 0 = one-shot
        final Runnable action;
        float remaining;

        Timer(float delay, float interval, Runnable action) {
            this.remaining = delay;
            this.interval = interval;
            this.action = action;
        }

        @Override
        public float update(float delta) {
            remaining -= delta;
            if (remaining > 0f) return RUNNING;
            if (interval > 0f) {
                // Keep the overshoot so the rate holds steady, but never queue up a backlog of missed runs.
                remaining = Math.max(remaining + interval, 0f);
                Scheduler.run(action);
                return RUNNING;
            }
            markDone();
            Scheduler.run(action);
            return 0f;
        }
    }
}
