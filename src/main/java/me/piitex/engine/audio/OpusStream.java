package me.piitex.engine.audio;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.opus.OpusFile.*;

/**
 * Streams an Ogg Opus file with libopusfile. Opus always decodes at 48 kHz. Files with more than two channels are
 * mixed down to stereo by the decoder.
 */
final class OpusStream implements PcmStream {
    private static final int OP_HOLE = -3; // a gap in the stream; decoding can carry on
    private static final int CHUNK_FRAMES = 5760; // 120 ms, the longest Opus packet

    private final ByteBuffer data; // must outlive the decoder handle
    private final long handle;
    private final int channels;
    private final ShortBuffer chunk;

    OpusStream(String name, byte[] bytes) {
        data = memAlloc(bytes.length);
        data.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var error = stack.mallocInt(1);
            handle = op_open_memory(data, error);
            if (handle == NULL) {
                memFree(data);
                throw new IllegalArgumentException("Could not decode Opus file " + name + " (opusfile error " + error.get(0) + ")");
            }
        }
        channels = op_channel_count(handle, -1) == 1 ? 1 : 2;
        chunk = memAllocShort(CHUNK_FRAMES * channels);
    }

    @Override public int channels() { return channels; }
    @Override public int sampleRate() { return 48000; }
    @Override public long totalFrames() { return op_pcm_total(handle, -1); }

    @Override
    public int read(short[] dst, int off, int len) {
        chunk.clear().limit(Math.min(len, chunk.capacity()));
        int frames;
        do {
            frames = channels == 1 ? op_read(handle, chunk, null) : op_read_stereo(handle, chunk);
        } while (frames == OP_HOLE);
        if (frames <= 0) return 0; // end of stream, or a decode error, which ends playback rather than throwing mid-song
        chunk.get(0, dst, off, frames * channels);
        return frames * channels;
    }

    @Override
    public void seek(long frame) {
        op_pcm_seek(handle, Math.max(0, frame));
    }

    @Override
    public void close() {
        op_free(handle);
        memFree(data);
        memFree(chunk);
    }
}
