package me.piitex.engine.scheduler;

/**
 * Handle to something scheduled through {@link Scheduler}: a one-shot delay, a repeating timer, or a queued
 * callback. Keep it if you might need to {@link #cancel()} before it fires, for example when the user skips a
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
     * True once a one-shot task has run (or been cancelled). A repeating timer is done only once cancelled.
     */
    public boolean isDone() {
        return done || cancelled;
    }

    void markDone() {
        done = true;
    }
}
