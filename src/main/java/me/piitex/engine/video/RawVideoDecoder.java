package me.piitex.engine.video;

import java.io.IOException;

/**
 * Uncompressed frames (AVI "DIB"): 24 or 32 bits per pixel, rows padded to four bytes, bottom row first unless the
 * file's height is negative. This is what a renderer writes when asked for an uncompressed animation, so it
 * matters for pre-rendered footage. A 32-bit frame keeps its alpha channel, unless every alpha byte is zero, which
 * is how writers mark "no alpha".
 */
final class RawVideoDecoder implements FrameDecoder {
    private final int width, height, bitCount;
    private final boolean topDown;
    private Buffers buffers;

    RawVideoDecoder(int width, int height, int bitCount, boolean topDown) {
        this.width = width;
        this.height = height;
        this.bitCount = bitCount;
        this.topDown = topDown;
    }

    static boolean supports(int bitCount) {
        return bitCount == 24 || bitCount == 32;
    }

    @Override
    public void useBuffers(Buffers buffers) {
        this.buffers = buffers;
    }

    @Override
    public VideoFrame decode(Packet packet) throws IOException {
        byte[] in = packet.data();
        int bytesPer = bitCount / 8;
        int stride = (width * bytesPer + 3) & ~3;
        if (in.length < stride * (long) height) throw new IOException("Truncated uncompressed frame");
        byte[] out = buffers != null ? buffers.take(width * height * 4) : new byte[width * height * 4];
        boolean alpha = false;
        if (bytesPer == 4) {
            for (int i = 3; i < in.length && !alpha; i += 4) alpha = in[i] != 0;
        }
        for (int y = 0; y < height; y++) {
            int src = (topDown ? y : height - 1 - y) * stride;
            int dst = y * width * 4;
            for (int x = 0; x < width; x++, src += bytesPer, dst += 4) {
                int a = bytesPer == 4 && alpha ? in[src + 3] & 0xFF : 255;
                if (a == 255) {
                    out[dst] = in[src];
                    out[dst + 1] = in[src + 1];
                    out[dst + 2] = in[src + 2];
                } else { // premultiply
                    out[dst] = (byte) (((in[src] & 0xFF) * a + 127) / 255);
                    out[dst + 1] = (byte) (((in[src + 1] & 0xFF) * a + 127) / 255);
                    out[dst + 2] = (byte) (((in[src + 2] & 0xFF) * a + 127) / 255);
                }
                out[dst + 3] = (byte) a;
            }
        }
        return new VideoFrame(width, height, out, packet.pts());
    }
}
