package me.piitex.engine.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Background work started by {@link Scheduler#async}. The work itself runs on a worker thread, but
 * {@link #onSuccess} and {@link #onError} callbacks are always delivered on the render thread, so they can
 * touch the UI directly. Callbacks added after the work has finished still fire (on the next frame).
 *
 * @param <T> the type the work produces
 */
public class AsyncTask<T> extends Task {
    private final List<Consumer<T>> successListeners = new ArrayList<>();
    private final List<Consumer<Throwable>> errorListeners = new ArrayList<>();
    private volatile Future<?> future;
    private boolean finished; // guarded by this
    private T result;         // guarded by this
    private Throwable error;  // guarded by this

    /** Runs {@code listener} on the render thread with the work's result. Not called if the work failed or was cancelled. */
    public AsyncTask<T> onSuccess(Consumer<T> listener) {
        synchronized (this) {
            if (!finished) {
                successListeners.add(listener);
                return this;
            }
            if (error != null || isCancelled()) return this;
            T value = result;
            Scheduler.runLater(() -> listener.accept(value));
        }
        return this;
    }

    /** Runs {@code listener} on the render thread if the work threw. Not called if it was cancelled. */
    public AsyncTask<T> onError(Consumer<Throwable> listener) {
        synchronized (this) {
            if (!finished) {
                errorListeners.add(listener);
                return this;
            }
            if (error == null || isCancelled()) return this;
            Throwable failure = error;
            Scheduler.runLater(() -> listener.accept(failure));
        }
        return this;
    }

    /** Cancels the work: interrupts it if it is running, and drops its callbacks. */
    @Override
    public void cancel() {
        super.cancel();
        Future<?> f = future;
        if (f != null) f.cancel(true);
    }

    void attach(Future<?> future) {
        this.future = future;
        if (isCancelled()) future.cancel(true);
    }

    /** Worker thread. Records the outcome and queues the matching callbacks for the render thread. */
    void complete(T value, Throwable failure) {
        List<Runnable> deliveries = new ArrayList<>();
        synchronized (this) {
            finished = true;
            result = value;
            error = failure;
            markDone();
            if (!isCancelled()) {
                if (failure == null) {
                    for (Consumer<T> l : successListeners) deliveries.add(() -> l.accept(value));
                } else {
                    for (Consumer<Throwable> l : errorListeners) deliveries.add(() -> l.accept(failure));
                }
            }
            successListeners.clear();
            errorListeners.clear();
        }
        for (Runnable delivery : deliveries) {
            Scheduler.runLater(delivery);
        }
    }
}
