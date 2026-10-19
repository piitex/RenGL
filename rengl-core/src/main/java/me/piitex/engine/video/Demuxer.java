package me.piitex.engine.video;

import java.io.IOException;

/**
 * Splits a container file into packets, one at a time, reading from disk as it goes. Used from the decode thread
 * only.
 */
interface Demuxer extends AutoCloseable {
    MediaInfo info();

    /**
     * Track index of the picture and audio streams, or -1 for none.
     */
    int videoStream();

    int audioStream();

    /**
     * Lets a source that produces finished pictures take its pixel arrays from the player's pool.
     */
    default void useBuffers(Buffers buffers) {
    }

    /**
     * A decoder for the picture track, or null when the file has none. Throws when the codec is not supported.
     */
    FrameDecoder videoDecoder();

    /**
     * A decoder for the soundtrack, or null when there is none or its codec is not supported.
     */
    AudioTrackDecoder audioDecoder();

    /**
     * The next packet in file order, or null at the end.
     */
    Packet next() throws IOException;

    /**
     * Moves to the last keyframe at or before {@code seconds}. Audio resumes from the same place in the file, so
     * it can start slightly before the picture; the player trims it.
     */
    void seek(double seconds) throws IOException;

    @Override
    void close();
}
