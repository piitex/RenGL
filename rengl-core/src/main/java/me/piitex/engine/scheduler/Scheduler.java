package me.piitex.engine.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * Timers and background work for the UI, the equivalent of JavaFX's {@code PauseTransition}, {@code Timeline},
 * {@code Platform.runLater} and {@code Task}.
 * <ul>
 *   <li>{@link #runLater}: run something on the render thread on the next frame. Callable from any thread.</li>
 *   <li>{@link #after}, {@link #every}: timers. They are driven by the frame delta, the same clock the
 *       transitions use, so they stay in step with animations.</li>
 *   <li>{@link #play}: tweens and sequences, on the same clock.</li>
 *   <li>{@link #async}: run blocking work (I/O, decoding, network) on a worker thread and get the result back
 *       on the render thread.</li>
 * </ul>
 * Every callback runs on the render thread, so it can create, move and remove elements and call any
 * {@code Window} method directly. An exception thrown by a callback is logged and does not stop the engine
 * or other tasks. Nothing runs until {@link me.piitex.engine.Engine#start} is running, so tasks may be
 * scheduled before the window opens; a timer's delay starts counting from its first frame.
 * <p>
 * Because the engine caps a single frame's delta at 0.25 seconds, timers pause across long stalls (a debugger
 * break, a GC pause) instead of all firing at once afterwards.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Scheduler {
    private static final Logger log = LogManager.getLogger("Scheduler");

    private static final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private static final Timers timers = new Timers();

    private static volatile ExecutorService workers;
    private static volatile Thread renderThread;

    private Scheduler() {
    }

    /**
     * Runs {@code action} on the render thread at the start of the next frame. Safe from any thread.
     */
    public static Task runLater(Runnable action) {
        Task task = new Task();
        queue.add(() -> {
            if (task.isCancelled()) return;
            task.markDone();
            action.run();
        });
        return task;
    }

    /**
     * Runs {@code action} once on the render thread after {@code seconds}, for example to leave a splash screen.
     */
    public static Task after(float seconds, Runnable action) {
        return timers.after(seconds, action);
    }

    /**
     * Runs {@code action} on the render thread every {@code seconds}, starting {@code seconds} from now, until cancelled.
     */
    public static Task every(float seconds, Runnable action) {
        return every(seconds, seconds, action);
    }

    /**
     * As {@link #every(float, Runnable)}, but the first run comes after {@code initialDelay}.
     */
    public static Task every(float initialDelay, float interval, Runnable action) {
        return timers.every(initialDelay, interval, action);
    }

    /**
     * Plays a {@link Tween}, a {@link Sequence} or any other {@link Playable} on real time, on the render thread. It
     * keeps going while the game is paused; to play one on game time, use
     * {@link me.piitex.engine.game.Scene#play} instead. Played from the render thread it starts at once; from any other
     * thread, on the next frame. Returns the handle to cancel it with: the tween or sequence itself, or a new
     * {@link Task}.
     */
    public static Task play(Playable playable) {
        return timers.play(playable);
    }

    /**
     * Runs {@code work} on a worker thread. Chain {@link AsyncTask#onSuccess} / {@link AsyncTask#onError} to
     * receive the outcome on the render thread. Do not touch the UI from inside {@code work} itself.
     */
    public static <T> AsyncTask<T> async(Supplier<T> work) {
        AsyncTask<T> task = new AsyncTask<>();
        task.attach(executor().submit(() -> {
            try {
                task.complete(work.get(), null);
            } catch (Throwable t) {
                task.complete(null, t);
            }
        }));
        return task;
    }

    /**
     * As {@link #async(Supplier)} for work with no result. {@link AsyncTask#onSuccess} then receives null.
     */
    public static AsyncTask<Void> async(Runnable work) {
        return async(() -> {
            work.run();
            return null;
        });
    }

    static ExecutorService executor() {
        ExecutorService pool = workers;
        if (pool == null) {
            synchronized (Scheduler.class) {
                pool = workers;
                if (pool == null) {
                    pool = Executors.newCachedThreadPool(r -> {
                        Thread thread = new Thread(r, "Scheduler-Worker");
                        thread.setDaemon(true);
                        return thread;
                    });
                    workers = pool;
                }
            }
        }
        return pool;
    }

    /**
     * Render thread, once per frame. Runs queued callbacks, then advances the timers by {@code delta} seconds.
     */
    public static void update(float delta) {
        renderThread = Thread.currentThread();
        // Only what was queued when the frame began, so a callback that re-queues itself can't stall the frame.
        for (int n = queue.size(); n > 0; n--) {
            Runnable action = queue.poll();
            if (action == null) break;
            run(action);
        }

        timers.update(delta);
    }

    /**
     * Engine shutdown: drops everything pending and stops the worker threads.
     */
    public static void shutdown() {
        queue.clear();
        timers.cancelAll();
        synchronized (Scheduler.class) {
            if (workers != null) {
                workers.shutdownNow();
                workers = null;
            }
        }
    }

    /**
     * Whether this is the thread that runs {@link #update}, once it has run.
     */
    static boolean isRenderThread() {
        return Thread.currentThread() == renderThread;
    }

    static void run(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log.error("Scheduled task threw", t);
        }
    }
}
