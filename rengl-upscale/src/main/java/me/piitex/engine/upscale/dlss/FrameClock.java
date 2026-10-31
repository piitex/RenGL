package me.piitex.engine.upscale.dlss;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Streamline's frame token for the window frame being drawn. Everything Streamline is told in a frame (the camera's
 * constants, the tags, the latency markers, the present) has to name the same frame, so the token is made once when the window
 * frame starts and the DLSS upscaler of every viewport uses it. Outside a window frame (a test driving the upscaler by hand) each
 * caller gets a token of its own.
 */
final class FrameClock {
    private FrameClock() {
    }

    private static int index;
    private static MemorySegment token;
    private static boolean open;

    /** A window frame starts: a new token. */
    static synchronized MemorySegment begin(Sl sl) {
        try (Arena arena = Arena.ofConfined()) {
            token = sl.newFrameToken(arena, ++index);
        }
        open = true;
        return token;
    }

    /** The token of the frame being drawn. */
    static synchronized MemorySegment current(Sl sl) {
        if (open) return token;
        try (Arena arena = Arena.ofConfined()) {
            return sl.newFrameToken(arena, ++index);
        }
    }

    /** The window frame was presented. */
    static synchronized void end() {
        open = false;
    }
}
