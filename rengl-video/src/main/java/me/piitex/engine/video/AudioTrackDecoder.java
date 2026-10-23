package me.piitex.engine.video;

import java.io.IOException;

/**
 * Turns the audio packets of one codec into 16-bit interleaved PCM, mono or stereo.
 */
interface AudioTrackDecoder extends AutoCloseable {
    int channels();

    int sampleRate();

    short[] decode(Packet packet) throws IOException;

    default void flush() {
    }

    @Override
    default void close() {
    }
}
