package me.piitex.engine.scheduler;

import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Loads things in the background in named steps and reports how far it has got, for a loading screen, a progress bar
 * or a splash screen. What the screen looks like is up to you: read {@link #getProgress()} and {@link #getCurrentStep()}
 * each frame and draw them however you like.
 * <pre>{@code
 * Loader loader = new Loader()
 *         .add("Map", progress -> map = Map.read(path))                 // worker thread
 *         .add("Sounds", 3f, progress -> {                              // weighs 3 times as much as the others
 *             for (int i = 0; i < files.size(); i++) {
 *                 sounds.add(Sound.load(files.get(i)));
 *                 progress.set(i + 1, files.size());                    // progress within the step
 *             }
 *         })
 *         .addOnRenderThread("Scene", progress -> level = new Level(map, sounds))
 *         .onComplete(() -> scenes.set(level, SceneTransition.fade(0.4f)))
 *         .onError(error -> status.setText("Couldn't load: " + error.getMessage()))
 *         .start();
 * }</pre>
 * Steps run one at a time, in the order they were added. A step added with {@link #add} runs on a worker thread and
 * must not touch the UI. One added with {@link #addOnRenderThread} runs on the render thread, one per frame so the
 * loading screen keeps drawing between them, for work that needs it: building elements, or anything that must run
 * where the renderer is. Whatever a step stores in a field is visible to the steps after it and to
 * {@link #onComplete} callbacks without further synchronization.
 * <p>
 * If a step throws, loading stops there and the {@link #onError} callbacks run; with none registered the error is
 * logged. {@link #cancel()} stops loading after the current step and interrupts it if it is on a worker thread;
 * neither callback runs. Callbacks always run on the render thread, including ones added after loading has ended.
 * <p>
 * Everything except the step bodies can be called from any thread. A loader runs once.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Loader extends Task {
    private static final Logger log = LogManager.getLogger("Loader");

    /**
     * The work of one step. It may throw anything, which stops loading and reports the error.
     */
    @FunctionalInterface
    public interface Step {
        void run(Progress progress) throws Exception;
    }

    /**
     * Passed to each step, to report how far through it is.
     */
    public interface Progress {
        /**
         * How far through the current step, from 0 to 1. Overall progress never goes backwards, so setting a
         * lower value than before has no visible effect.
         */
        void set(float fraction);

        /**
         * {@code done} of {@code total} parts of the current step are finished.
         */
        default void set(int done, int total) {
            set(total <= 0 ? 1f : (float) done / total);
        }

        /**
         * True once the loader has been cancelled. A long step should check it and return early.
         */
        boolean isCancelled();
    }

    private record Entry(String name, float weight, boolean renderThread, Step step) {
    }

    private final List<Entry> steps = new ArrayList<>(); // fixed once started
    private float totalWeight;                            // fixed once started
    private final List<Runnable> completeListeners = new ArrayList<>();         // guarded by this
    private final List<Consumer<Throwable>> errorListeners = new ArrayList<>(); // guarded by this
    private boolean started;  // guarded by this
    private boolean finished; // guarded by this: every step ran, or one failed
    private volatile Throwable error;

    // Written only by the step running at the time: steps run one after another, never at once.
    private volatile float progress;
    private volatile float completedWeight;
    private volatile int completedSteps;
    private volatile String currentStep;
    private volatile Future<?> worker;

    private final Progress reporter = new Progress() {
        @Override
        public void set(float fraction) {
            int index = completedSteps;
            if (index >= steps.size()) return;
            float within = Math.clamp(Float.isNaN(fraction) ? 0f : fraction, 0f, 1f);
            report(completedWeight + steps.get(index).weight * within);
        }

        @Override
        public boolean isCancelled() {
            return Loader.this.isCancelled();
        }
    };

    /**
     * Adds a step that runs on a worker thread, weighing 1.
     */
    public Loader add(String name, Step step) {
        return add(name, 1f, step);
    }

    /**
     * Adds a step that runs on a worker thread. {@code weight} is its share of the progress bar relative to the other
     * steps: give a step that takes three times as long a weight of 3.
     */
    public Loader add(String name, float weight, Step step) {
        return addStep(name, weight, false, step);
    }

    /**
     * Adds a step that runs on the render thread, weighing 1.
     */
    public Loader addOnRenderThread(String name, Step step) {
        return addOnRenderThread(name, 1f, step);
    }

    /**
     * Adds a step that runs on the render thread, on a frame of its own. See {@link #add(String, float, Step)} for
     * {@code weight}.
     */
    public Loader addOnRenderThread(String name, float weight, Step step) {
        return addStep(name, weight, true, step);
    }

    private synchronized Loader addStep(String name, float weight, boolean renderThread, Step step) {
        if (started) throw new IllegalStateException("steps can't be added after the loader has started");
        if (step == null) throw new IllegalArgumentException("step must not be null");
        if (!(weight >= 0f) || !Float.isFinite(weight)) throw new IllegalArgumentException("weight must be 0 or more: " + weight);
        steps.add(new Entry(name == null ? "" : name, weight, renderThread, step));
        totalWeight += weight;
        return this;
    }

    /**
     * Runs {@code listener} on the render thread once every step has finished. Not called if a step failed or the
     * loader was cancelled before finishing.
     */
    public Loader onComplete(Runnable listener) {
        synchronized (this) {
            if (!finished) {
                completeListeners.add(listener);
                return this;
            }
        }
        if (error == null) Scheduler.runLater(listener);
        return this;
    }

    /**
     * Runs {@code listener} on the render thread with the error if a step throws. Not called if the loader was
     * cancelled.
     */
    public Loader onError(Consumer<Throwable> listener) {
        Throwable failure;
        synchronized (this) {
            if (!finished) {
                errorListeners.add(listener);
                return this;
            }
            failure = error;
        }
        if (failure != null) Scheduler.runLater(() -> listener.accept(failure));
        return this;
    }

    /**
     * Starts loading. The first step begins right away if it runs on a worker thread, or on the next frame if it
     * runs on the render thread. Throws if the loader has already been started.
     */
    public Loader start() {
        synchronized (this) {
            if (started) throw new IllegalStateException("the loader has already been started");
            started = true;
        }
        continueFrom(0);
        return this;
    }

    /**
     * Stops loading after the step that is running, and interrupts that step if it is on a worker thread. Neither
     * {@link #onComplete} nor {@link #onError} callbacks run.
     */
    @Override
    public void cancel() {
        super.cancel();
        Future<?> running = worker;
        if (running != null) running.cancel(true);
    }

    /**
     * Overall progress from 0 to 1, weighted by the steps' weights. Never goes backwards; 1 once every step has
     * finished.
     */
    public float getProgress() {
        return progress;
    }

    /**
     * The name of the step running now, or of the next one while it waits for its thread. Null before
     * {@link #start()} and once loading has ended.
     */
    public String getCurrentStep() {
        return currentStep;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public synchronized int getStepCount() {
        return steps.size();
    }

    public synchronized boolean isStarted() {
        return started;
    }

    /**
     * True once every step has finished. Stays false after a failure or a cancellation.
     */
    public synchronized boolean isComplete() {
        return finished && error == null;
    }

    /**
     * The error a step threw, or null.
     */
    public Throwable getError() {
        return error;
    }

    /**
     * Runs step {@code index} and those after it: on the thread each one needs, as many worker steps in a row as
     * there are, but only one render-thread step per frame.
     */
    private void continueFrom(int index) {
        if (isCancelled()) return;
        if (index >= steps.size()) {
            succeed();
            return;
        }
        Entry next = steps.get(index);
        currentStep = next.name;
        if (next.renderThread) {
            Scheduler.runLater(() -> runFrom(index));
        } else {
            worker = Scheduler.executor().submit(() -> runFrom(index));
            if (isCancelled()) worker.cancel(true); // cancelled while submitting
        }
    }

    private void runFrom(int index) {
        boolean renderThread = steps.get(index).renderThread;
        while (index < steps.size() && steps.get(index).renderThread == renderThread) {
            if (isCancelled()) return;
            Entry entry = steps.get(index);
            currentStep = entry.name;
            try {
                entry.step.run(reporter);
            } catch (Throwable t) {
                if (!isCancelled()) fail(entry, t);
                return;
            }
            if (isCancelled()) return;
            completedWeight += entry.weight;
            completedSteps = ++index;
            report(completedWeight);
            if (renderThread) break; // let a frame draw before the next one
        }
        continueFrom(index);
    }

    private synchronized void report(float weightDone) { // a step may report from threads of its own
        float value = totalWeight > 0f ? Math.min(1f, weightDone / totalWeight) : 0f;
        if (value > progress) progress = value;
    }

    private void succeed() {
        List<Runnable> listeners;
        synchronized (this) {
            finished = true;
            listeners = List.copyOf(completeListeners);
            completeListeners.clear();
            errorListeners.clear();
        }
        progress = 1f;
        currentStep = null;
        worker = null;
        markDone();
        for (Runnable listener : listeners) Scheduler.runLater(listener);
    }

    private void fail(Entry entry, Throwable t) {
        List<Consumer<Throwable>> listeners;
        synchronized (this) {
            error = t;
            finished = true;
            listeners = List.copyOf(errorListeners);
            completeListeners.clear();
            errorListeners.clear();
        }
        currentStep = null;
        worker = null;
        markDone();
        if (listeners.isEmpty()) {
            log.error("Loading step '{}' failed", entry.name, t);
            return;
        }
        for (Consumer<Throwable> listener : listeners) Scheduler.runLater(() -> listener.accept(t));
    }
}
