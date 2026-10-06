package me.piitex.engine.video;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Uncompressed audio (8, 16, 24 or 32-bit integer, or 32-bit float) to 16-bit PCM. Anything past two channels is
 * dropped, since the engine's audio output is mono or stereo.
 */
final class PcmAudioDecoder implements AudioTrackDecoder {
    private final int channels, outChannels, rate, bits;
    private final boolean floating, bigEndian;

    PcmAudioDecoder(int channels, int rate, int bits, boolean floating) {
        this(channels, rate, bits, floating, false);
    }

    PcmAudioDecoder(int channels, int rate, int bits, boolean floating, boolean bigEndian) {
        this.bigEndian = bigEndian;
        this.channels = channels;
        this.outChannels = Math.min(channels, 2);
        this.rate = rate;
        this.bits = bits;
        this.floating = floating;
    }

    static boolean supports(int bits, boolean floating) {
        return floating ? bits == 32 : bits == 8 || bits == 16 || bits == 24 || bits == 32;
    }

    @Override
    public int channels() {
        return outChannels;
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    public short[] decode(Packet packet) {
        byte[] in = packet.data();
        int bytesPer = bits / 8;
        int frames = in.length / (bytesPer * channels);
        short[] out = new short[frames * outChannels];
        ByteBuffer b = ByteBuffer.wrap(in).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < outChannels; c++) {
                int p = (f * channels + c) * bytesPer;
                out[f * outChannels + c] = switch (bits) {
                    case 8 -> (short) (((in[p] & 0xFF) - 128) << 8);
                    case 16 -> b.getShort(p);
                    case 24 -> bigEndian ? (short) ((in[p + 1] & 0xFF) | (in[p] << 8)) : (short) ((in[p + 1] & 0xFF) | (in[p + 2] << 8));
                    default -> floating
                            ? (short) Math.round(Math.clamp(b.getFloat(p), -1f, 1f) * 32767f)
                            : (short) (b.getInt(p) >> 16);
                };
            }
        }
        return out;
    }
}
