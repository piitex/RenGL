package me.piitex.engine.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;

/** Decodes an MP3 frame by frame with JLayer. */
final class Mp3Stream implements PcmStream {
    private final String name;
    private final byte[] bytes;
    private Bitstream bitstream;
    private Decoder decoder;
    private int channels, rate;
    private short[] pending;
    private int pendingPos, pendingLen;

    Mp3Stream(String name, byte[] bytes) {
        this.name = name;
        this.bytes = bytes;
        open();
        if (!decodeFrame()) throw new IllegalArgumentException("No audio frames found in MP3 file " + name);
        if (channels > 2) throw AudioDecoder.tooManyChannels(name, channels);
        pendingPos = 0; // decodeFrame left the first frame pending
    }

    private void open() {
        bitstream = new Bitstream(new ByteArrayInputStream(bytes));
        decoder = new Decoder();
        pendingPos = pendingLen = 0;
    }

    /** Decodes the next frame into {@code pending}. Returns false at the end of the file. */
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

    @Override public int channels() { return channels; }
    @Override public int sampleRate() { return rate; }

    @Override
    public int read(short[] dst, int off, int len) {
        int n = 0;
        while (n < len) {
            if (pendingPos >= pendingLen && !decodeFrame()) break;
            int take = Math.min(len - n, pendingLen - pendingPos);
            System.arraycopy(pending, pendingPos, dst, off + n, take);
            pendingPos += take;
            n += take;
        }
        return n;
    }

    /** MP3 has no sample index, so this restarts and decodes forward. Fine for loop points, slow for huge files. */
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

    @Override public void close() { closeBitstream(); }
}
