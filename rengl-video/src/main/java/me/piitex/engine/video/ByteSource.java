package me.piitex.engine.video;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Random access to a media file that never holds more than a small window of it in memory, so a file of any size
 * opens as fast as a small one. Not thread-safe: each demuxer owns one and uses it from the decode thread only.
 */
final class ByteSource implements AutoCloseable {
    private static final int WINDOW = 64 * 1024;

    private final FileChannel channel;
    private final long size;
    private final ByteBuffer window = ByteBuffer.allocate(WINDOW);
    private long windowStart = -1;
    private int windowLength;
    private long position;

    ByteSource(File file) throws IOException {
        channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        size = channel.size();
    }

    long size() {
        return size;
    }

    long position() {
        return position;
    }

    void seek(long position) {
        this.position = position;
    }

    void skip(long bytes) {
        position += bytes;
    }

    /**
     * Reads exactly {@code len} bytes. Small reads come out of the window, big ones (video frames) go straight to
     * the destination without being copied twice.
     */
    void readFully(byte[] dst, int off, int len) throws IOException {
        if (position < 0 || position + len > size) throw new EOFException();
        if (len >= WINDOW) {
            ByteBuffer target = ByteBuffer.wrap(dst, off, len);
            long at = position;
            while (target.hasRemaining()) {
                int n = channel.read(target, at);
                if (n < 0) throw new EOFException();
                at += n;
            }
            position = at;
            return;
        }
        int done = 0;
        while (done < len) {
            if (position < windowStart || position >= windowStart + windowLength) fill();
            int inWindow = (int) (position - windowStart);
            int n = Math.min(len - done, windowLength - inWindow);
            System.arraycopy(window.array(), inWindow, dst, off + done, n);
            done += n;
            position += n;
        }
    }

    private void fill() throws IOException {
        window.clear();
        int n = channel.read(window, position);
        if (n <= 0) throw new EOFException();
        windowStart = position;
        windowLength = n;
    }

    byte[] bytes(int len) throws IOException {
        if (len < 0 || position + len > size) throw new EOFException();
        byte[] out = new byte[len];
        readFully(out, 0, len);
        return out;
    }

    private final byte[] scratch = new byte[8];

    private ByteBuffer scratch(int n) throws IOException {
        readFully(scratch, 0, n);
        return ByteBuffer.wrap(scratch, 0, n).order(ByteOrder.LITTLE_ENDIAN);
    }

    int u8() throws IOException {
        return scratch(1).get() & 0xFF;
    }

    int u16() throws IOException {
        return scratch(2).getShort() & 0xFFFF;
    }

    int s32() throws IOException {
        return scratch(4).getInt();
    }

    long u32() throws IOException {
        return scratch(4).getInt() & 0xFFFFFFFFL;
    }

    long s64() throws IOException {
        return scratch(8).getLong();
    }

    int be16() throws IOException {
        readFully(scratch, 0, 2);
        return ((scratch[0] & 0xFF) << 8) | (scratch[1] & 0xFF);
    }

    int be32s() throws IOException {
        readFully(scratch, 0, 4);
        return ((scratch[0] & 0xFF) << 24) | ((scratch[1] & 0xFF) << 16) | ((scratch[2] & 0xFF) << 8) | (scratch[3] & 0xFF);
    }

    long be32() throws IOException {
        return be32s() & 0xFFFFFFFFL;
    }

    long be64() throws IOException {
        return (be32() << 32) | be32();
    }

    /**
     * Four bytes as text, for chunk ids such as {@code "RIFF"} or {@code "00dc"}.
     */
    String fourcc() throws IOException {
        readFully(scratch, 0, 4);
        return new String(scratch, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
