package me.piitex.engine.scheduler;

/**
 * Handle to something scheduled through {@link Scheduler}: a one-shot delay, a repeating timer, a queued callback,
 * or something {@link Scheduler#play played} such as a {@link Tween} or a {@link Sequence}. Keep it if you might need to {@link #cancel()} before it fires, for example when the user skips a
 * splash screen. Safe to use from any thread.
 */
public class Task {
    private volatile boolean cancelled;
    private volatile boolean done;

    /**
     * Stops the task from running again. Has no effect on a run already in progress.
     */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * True once a one-shot task has run, or a played tween or sequence has finished, or it has been cancelled. A
     * repeating timer, or a tween or sequence that loops, is done only once cancelled.
     */
    public boolean isDone() {
        return done || cancelled;
    }

    void markDone() {
        done = true;
    }

    /**
     * For a {@link Playable} that is started again, such as a tween in a repeating {@link Sequence}.
     */
    void clearDone() {
        done = false;
    }
}
