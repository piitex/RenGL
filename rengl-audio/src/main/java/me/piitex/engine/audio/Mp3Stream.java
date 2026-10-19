package me.piitex.engine.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;

/**
 * Decodes an MP3 frame by frame with JLayer. When the file starts with an encoder's Xing / Info tag, the tag frame
 * and the silence the encoder and the decoder add at the start are dropped, and the end is cut where the tag says the
 * music stops (see {@link Mp3Gapless}), so the stream is the track's true length and loops without a gap.
 */
final class Mp3Stream implements PcmStream {
    private final String name;
    private final byte[] bytes;
    private Bitstream bitstream;
    private Decoder decoder;
    private int channels, rate;
    private short[] pending;
    private int pendingPos, pendingLen;
    private final Mp3Gapless gapless;
    private long remaining; // shorts left to hand out before the encoder's end padding

    Mp3Stream(String name, byte[] bytes) {
        this.name = name;
        this.bytes = bytes;
        this.gapless = Mp3Gapless.parse(bytes);
        open();
        if (channels == 0) throw new IllegalArgumentException("No audio frames found in MP3 file " + name);
        if (channels > 2) throw AudioDecoder.tooManyChannels(name, channels);
    }

    /**
     * Starts from the first sample of the music, with the first frame of it decoded and waiting.
     */
    private void open() {
        bitstream = new Bitstream(new ByteArrayInputStream(bytes));
        decoder = new Decoder();
        pendingPos = pendingLen = 0;
        remaining = Long.MAX_VALUE;
        if (gapless != null) {
            if (!decodeFrame()) return; // the tag's own frame, which holds no audio
            pendingPos = pendingLen = 0;
            long length = gapless.length();
            discard((long) gapless.skip() * channels);
            if (length >= 0) remaining = length * channels;
        }
        if (pendingPos >= pendingLen) decodeFrame();
    }

    /**
     * Throws away the next {@code shorts} of decoded audio.
     */
    private void discard(long shorts) {
        while (shorts > 0) {
            if (pendingPos >= pendingLen && !decodeFrame()) return;
            int take = (int) Math.min(shorts, pendingLen - pendingPos);
            pendingPos += take;
            shorts -= take;
        }
    }

    /**
     * Decodes the next frame into {@code pending}. Returns false at the end of the file.
     */
    private boolean decodeFrame() {
        try {
            Header header = bitstream.readFrame();
            if (header == null) return false;
            SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
            bitstream.closeFrame();
            channels = out.getChannelCount();
            rate = out.getSampleFrequency();
            pending = out.getBuffer();
            pendingLen = out.getBufferLength();
            pendingPos = 0;
            return true;
        } catch (JavaLayerException e) {
            throw new IllegalArgumentException("Could not decode MP3 file " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    public int read(short[] dst, int off, int len) {
        int n = 0;
        len = (int) Math.min(len, remaining);
        while (n < len) {
            if (pendingPos >= pendingLen && !decodeFrame()) break;
            int take = Math.min(len - n, pendingLen - pendingPos);
            System.arraycopy(pending, pendingPos, dst, off + n, take);
            pendingPos += take;
            n += take;
        }
        if (remaining != Long.MAX_VALUE) remaining -= n;
        return n;
    }

    @Override
    public long totalFrames() {
        return gapless == null ? -1 : gapless.length();
    }

    /**
     * MP3 has no sample index, so this restarts and decodes forward. Fine for loop points, slow for huge files.
     */
    @Override
    public void seek(long frame) {
        closeBitstream();
        open();
        long skip = Math.max(0, frame) * channels;
        short[] scratch = new short[4096];
        while (skip > 0) {
            int n = read(scratch, 0, (int) Math.min(scratch.length, skip));
            if (n <= 0) break;
            skip -= n;
        }
    }

    private void closeBitstream() {
        try {
            bitstream.close();
        } catch (BitstreamException ignored) {
        }
    }

    @Override
    public void close() {
        closeBitstream();
    }
}
