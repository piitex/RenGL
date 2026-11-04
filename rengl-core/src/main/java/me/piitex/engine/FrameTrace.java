package me.piitex.engine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An opt-in log of the frames that take long while a window is starting up (the splash playing, warm-ups running, the
 * application taking over), saying where the time went. It is how a stall under the splash is found: start the program
 * with {@code -Drengl.trace.frames=true}, optionally {@code -Drengl.trace.frames.ms=N} to change what counts as long
 * (40 by default), and read the {@code FrameTrace} lines.
 * <p>
 * Render thread only. It costs a few clock reads per frame when on and one boolean test when off.
 */
final class FrameTrace {
    private static final Logger log = LogManager.getLogger("FrameTrace");
    private static final boolean ENABLED = Boolean.getBoolean("rengl.trace.frames");
    private static final long LONG_FRAME_NANOS = Long.getLong("rengl.trace.frames.ms", 40L) * 1_000_000L;

    private static final StringBuilder parts = new StringBuilder();
    private static long frame;
    private static long partStart;

    private FrameTrace() {
    }

    static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Starts timing a part of the frame. Pair it with {@link #end}.
     */
    static void begin() {
        if (ENABLED) partStart = System.nanoTime();
    }

    /**
     * Notes that the part begun last took until now, under {@code name}.
     */
    static void end(String name) {
        if (!ENABLED) return;
        long took = System.nanoTime() - partStart;
        // Parts under a millisecond are not what a person sees as a stall.
        if (took >= 1_000_000L) parts.append(' ').append(name).append('=').append(took / 1_000_000L).append("ms");
    }

    /**
     * Logs a moment worth knowing about, such as the application being let go of, as it happens.
     */
    static void note(String message) {
        if (ENABLED) log.info("frame {}: {}", frame, message);
    }

    /**
     * Called at the top of a frame with the length of the one before and a note of what state the window was in; logs it
     * if it was long, along with the parts that were timed, and starts a fresh frame.
     */
    static void nextFrame(long previousFrameNanos, String state) {
        if (!ENABLED) return;
        if (previousFrameNanos >= LONG_FRAME_NANOS) {
            log.info("frame {} took {} ms [{}]{}", frame, previousFrameNanos / 1_000_000L, state, parts);
        }
        parts.setLength(0);
        frame++;
    }
}
