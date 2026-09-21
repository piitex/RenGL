package me.piitex.engine.audio;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Streams an Ogg Vorbis file out of memory with stb_vorbis. */
final class VorbisStream implements PcmStream {
    private static final int CHUNK = 8192;

    private final ByteBuffer data; // must outlive the decoder handle
    private final long handle;
    private final int channels, rate;
    private final ShortBuffer chunk;

    VorbisStream(String name, byte[] bytes) {
        data = memAlloc(bytes.length);
        data.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var error = stack.mallocInt(1);
            handle = stb_vorbis_open_memory(data, error, null);
            if (handle == NULL) {
                memFree(data);
                throw new IllegalArgumentException("Could not decode Ogg Vorbis file " + name + " (stb_vorbis error " + error.get(0) + ")");
            }
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            stb_vorbis_get_info(handle, info);
            channels = info.channels();
            rate = info.sample_rate();
        }
        if (channels > 2) {
            stb_vorbis_close(handle);
            memFree(data);
            throw AudioDecoder.tooManyChannels(name, channels);
        }
        chunk = memAllocShort(CHUNK * channels);
    }

    @Override public int channels() { return channels; }
    @Override public int sampleRate() { return rate; }
    @Override public long totalFrames() { return stb_vorbis_stream_length_in_samples(handle); }

    @Override
    public int read(short[] dst, int off, int len) {
        chunk.clear().limit(Math.min(len, chunk.capacity()));
        int frames = stb_vorbis_get_samples_short_interleaved(handle, channels, chunk);
        int shorts = frames * channels;
        chunk.get(0, dst, off, shorts);
        return shorts;
    }

    @Override
    public void seek(long frame) {
        if (frame <= 0) stb_vorbis_seek_start(handle);
        else stb_vorbis_seek(handle, (int) frame);
    }

    @Override
    public void close() {
        stb_vorbis_close(handle);
        memFree(data);
        memFree(chunk);
    }
}
