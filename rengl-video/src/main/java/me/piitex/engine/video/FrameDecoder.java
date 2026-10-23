package me.piitex.engine.video;

import java.io.IOException;

/**
 * Turns the video packets of one codec into pictures. Used from the decode thread only.
 */
interface FrameDecoder extends AutoCloseable {
    /**
     * Decodes one packet. Returns null when it produced no picture (a codec may hold one back for reordering).
     */
    VideoFrame decode(Packet packet) throws IOException;

    /**
     * Lets the decoder reuse pixel arrays instead of allocating one per frame.
     */
    default void useBuffers(Buffers buffers) {
    }

    /**
     * Forgets reference frames, called after a seek.
     */
    default void flush() {
    }

    @Override
    default void close() {
    }
}
