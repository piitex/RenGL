package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.List;

/**
 * How far the {@linkplain WarmUp warm-ups} of a window have got, for a {@link Splash} to show and to wait for. Read it
 * from {@link Window#getLoading()}; the engine runs the work.
 * <p>
 * A window with no warm-ups, or whose splash is switched off, is done from the start.
 */
public final class Loading {
    private static final Logger log = LogManager.getLogger("Splash");

    private final ArrayDeque<WarmUp> pending = new ArrayDeque<>();
    private volatile int total;
    private volatile int finished;
    private volatile String current;
    private volatile boolean done = true;
    /**
     * When the first step ran, in System.nanoTime units; 0 before it.
     */
    private long startedNanos;
    private long limitNanos;

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
     * Queues {@code warmUps} to run, a limit of {@code limitSeconds} for all of them together. Called by the engine
     * before the splash starts.
     */
    void begin(List<WarmUp> warmUps, float limitSeconds) {
        pending.clear();
        pending.addAll(warmUps);
        total = pending.size();
        finished = 0;
        startedNanos = 0L;
        limitNanos = (long) (Math.max(limitSeconds, 0f) * 1_000_000_000d);
        done = pending.isEmpty();
        current = done ? null : pending.peek().getName();
    }

    /**
     * Runs one step of the warm-up at the front of the queue. A warm-up that throws is logged and dropped, and the rest
     * still run. Once the time limit has passed, what is left is abandoned and the application starts. Render thread.
     */
    void step(Window window, Renderer renderer) {
        if (done) return;
        long now = System.nanoTime();
        if (startedNanos == 0L) startedNanos = now;
        if (now - startedNanos > limitNanos) {
            log.warn("The warm-up {} did not finish within {} s; the application starts without waiting for it and the {} after it",
                    pending.peek().getName(), limitNanos / 1_000_000_000L, pending.size() - 1);
            pending.clear();
            finish();
            return;
        }
        WarmUp front = pending.peek();
        boolean over;
        try {
            over = front.step(window, renderer);
        } catch (RuntimeException | LinkageError e) {
            log.error("The warm-up {} threw and is dropped; the application carries on", front.getName(), e);
            over = true;
        }
        if (!over) return;
        pending.poll();
        finished++;
        if (pending.isEmpty()) finish();
        else current = pending.peek().getName();
    }

    private void finish() {
        current = null;
        done = true;
    }
}
