package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * How far the {@linkplain WarmUp warm-ups} of a window have got, for a {@link Splash} to show and to wait for. Read it
 * from {@link Window#getLoading()}; the engine runs the work.
 * <p>
 * A window with no warm-ups, or whose splash is switched off, is done from the start.
 */
public final class Loading {
    private static final Logger log = LogManager.getLogger("Splash");

    /**
     * How long one frame spends on warm-up steps before it draws the splash again: a step that is under this leaves room
     * for the next in the same frame, so many small ones do not take a frame each. A step that is longer than this ends
     * the frame's work by itself.
     */
    private static final long FRAME_BUDGET_NANOS = 8_000_000L;

    private final ArrayDeque<WarmUp> pending = new ArrayDeque<>();
    /**
     * The warm-ups that ran at least one step, to tell once the application has started.
     */
    private final List<WarmUp> started = new ArrayList<>();
    /**
     * The warm-ups whose {@link WarmUp#prepare()} has returned (or failed).
     */
    private final java.util.Set<WarmUp> prepared = ConcurrentHashMap.newKeySet();
    private volatile CountDownLatch preparing = new CountDownLatch(0);
    private volatile int total;
    private volatile int finished;
    private volatile String current;
    private volatile boolean done = true;
    /**
     * When the first call came, once the splash had been drawn, in System.nanoTime units; 0 before it.
     */
    private long shownNanos;
    /**
     * When the first step ran, in System.nanoTime units; 0 before it.
     */
    private long startedNanos;
    private long limitNanos;
    private long delayNanos;

    /**
     * True when every warm-up has finished (or failed, or run out of time), and also when there were none.
     */
    public boolean isDone() {
        return done;
    }

    /**
     * How much of the work is finished, from 0 to 1: the warm-ups that are over, out of all of them. 1 when there are none.
     * It moves in whole steps, so a splash that wants a smooth bar eases towards it.
     */
    public float getProgress() {
        int all = total;
        return all == 0 ? 1f : (float) finished / all;
    }

    /**
     * The name of the warm-up running now, or the next to run; null when done.
     */
    public String getCurrent() {
        return current;
    }

    /**
     * Queues {@code warmUps} to run, a limit of {@code limitSeconds} for all of them together, and starts their
     * {@linkplain WarmUp#prepare background work}. Called by the engine before the splash starts.
     */
    void begin(List<WarmUp> warmUps, float limitSeconds) {
        begin(warmUps, limitSeconds, 0f);
    }

    /**
     * As {@link #begin(List, float)}, with the steps held back until the splash has been drawn for {@code delaySeconds}.
     */
    void begin(List<WarmUp> warmUps, float limitSeconds, float delaySeconds) {
        pending.clear();
        started.clear();
        prepared.clear();
        pending.addAll(warmUps);
        total = pending.size();
        finished = 0;
        shownNanos = 0L;
        startedNanos = 0L;
        limitNanos = (long) (Math.max(limitSeconds, 0f) * 1_000_000_000d);
        delayNanos = (long) (Math.max(delaySeconds, 0f) * 1_000_000_000d);
        done = pending.isEmpty();
        current = done ? null : pending.peek().getName();
        if (done) {
            preparing = new CountDownLatch(0);
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        preparing = latch;
        List<WarmUp> toPrepare = List.copyOf(warmUps);
        Thread worker = new Thread(() -> {
            try {
                for (WarmUp warmUp : toPrepare) {
                    try {
                        warmUp.prepare();
                    } catch (RuntimeException | LinkageError e) {
                        log.error("The warm-up {} threw while it was preparing; it carries on to its steps", warmUp.getName(), e);
                    }
                    prepared.add(warmUp);
                }
            } finally {
                latch.countDown();
            }
        }, "RenGL-warm-up-prepare");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Waits for the background work of every warm-up to return, for tests.
     */
    boolean awaitPrepared(long milliseconds) throws InterruptedException {
        return preparing.await(milliseconds, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs warm-up steps for this frame: as many as fit the frame's budget, from the warm-up at the front of the queue. A
     * warm-up whose background work has not returned yet is waited for, and nothing runs until the splash has played for
     * the configured delay. A warm-up that throws is logged and dropped, and the rest still run. Once the time limit has
     * passed, what is left is abandoned and the application starts. Render thread.
     */
    void step(Window window, Renderer renderer) {
        if (done) return;
        long now = System.nanoTime();
        if (shownNanos == 0L) shownNanos = now;
        if (now - shownNanos < delayNanos) return;
        if (startedNanos == 0L) startedNanos = now;
        if (now - startedNanos > limitNanos) {
            log.warn("The warm-up {} did not finish within {} s; the application starts without waiting for it and the {} after it",
                    pending.peek().getName(), limitNanos / 1_000_000_000L, pending.size() - 1);
            pending.clear();
            finish();
            return;
        }
        long frameEnd = now + FRAME_BUDGET_NANOS;
        do {
            WarmUp front = pending.peek();
            if (!prepared.contains(front)) return; // its background work is still going; the splash carries on meanwhile
            if (!started.contains(front)) started.add(front);
            boolean over;
            FrameTrace.begin();
            try {
                over = front.step(window, renderer);
            } catch (RuntimeException | LinkageError e) {
                log.error("The warm-up {} threw and is dropped; the application carries on", front.getName(), e);
                over = true;
            }
            FrameTrace.end("warm-up '" + front.getName() + "'");
            if (!over) return; // one slice per warm-up per frame: the splash gets a frame before the next slice
            pending.poll();
            finished++;
            if (pending.isEmpty()) finish();
            else current = pending.peek().getName();
        } while (!done && System.nanoTime() < frameEnd);
    }

    /**
     * Tells every warm-up that ran that the application has started. Render thread, once, after the application's first
     * frames.
     */
    void applicationStarted(Window window) {
        List<WarmUp> toTell = List.copyOf(started);
        started.clear();
        for (WarmUp warmUp : toTell) {
            try {
                warmUp.onApplicationStarted(window);
            } catch (RuntimeException | LinkageError e) {
                log.error("The warm-up {} threw while it was told the application started", warmUp.getName(), e);
            }
        }
    }

    private void finish() {
        current = null;
        done = true;
    }
}
