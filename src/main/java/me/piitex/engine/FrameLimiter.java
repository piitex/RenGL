package me.piitex.engine;

import java.util.concurrent.locks.LockSupport;

/**
 * Caps the render loop to a target frame rate by sleeping out the rest of each frame's time slot.
 * <p>
 * The deadline advances by exactly one frame period per call rather than being measured from "now", so a
 * frame that finishes a little early or late doesn't shift every later frame (no drift). If the loop has
 * fallen more than a period behind (a slow frame, a debugger pause, or a long gap since the last call),
 * the deadline is reset to now instead of being chased with a burst of un-throttled frames.
 * <p>
 * Sleeping alone is too coarse (OS timers wake up ms late), so it parks until shortly before the deadline
 * and spins for the remainder.
 * <p>
 * Render thread only.
 */
final class FrameLimiter {
    /** How long before the deadline to stop sleeping and start spinning. */
    private static final long SPIN_NANOS = 2_000_000L;

    private long deadline = 0L;

    /**
     * Blocks until the current frame's time slot ends. A {@code maxFps} of 0 or less means unlimited, and
     * returns immediately.
     */
    void limit(int maxFps) {
        if (maxFps <= 0) {
            deadline = 0L;
            return;
        }

        long period = 1_000_000_000L / maxFps;
        long now = System.nanoTime();

        if (deadline == 0L || now - deadline > period) {
            deadline = now; // first frame, or we're behind: don't try to catch up
        }
        deadline += period;

        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            if (remaining > SPIN_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    /** Forgets the current deadline, so the next {@link #limit} starts a fresh time slot. */
    void reset() {
        deadline = 0L;
    }
}
