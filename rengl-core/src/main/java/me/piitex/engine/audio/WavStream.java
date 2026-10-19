package me.piitex.engine.audio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Plays a WAV file by converting it to 16-bit a little at a time as it is read, from the file on disk or from bytes
 * already in memory, so a long track costs neither the decoded copy nor, for a file, the bytes themselves. Handles
 * 8, 16, 24 and 32-bit integer PCM and 32-bit float, with any channel count (the caller mixes more than two down).
 */
final class WavStream implements PcmStream {
    /** Where the bytes come from. */
    interface Source extends AutoCloseable {
        long length();

        /** Reads up to {@code len} bytes from {@code pos}; returns how many, or -1 at the end. */
        int read(long pos, byte[] dst, int off, int len) throws IOException;

        @Override
        void close();
    }

    static Source of(byte[] bytes) {
        return new Source() {
            @Override
            public long length() {
                return bytes.length;
            }

            @Override
            public int read(long pos, byte[] dst, int off, int len) {
                if (pos >= bytes.length) return -1;
                int n = (int) Math.min(len, bytes.length - pos);
                System.arraycopy(bytes, (int) pos, dst, off, n);
                return n;
            }

            @Override
            public void close() {
            }
        };
    }

    static Source of(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        return new Source() {
            @Override
            public long length() {
                try {
                    return channel.size();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public int read(long pos, byte[] dst, int off, int len) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(dst, off, len);
                int total = 0;
                while (buffer.hasRemaining()) {
                    int n = channel.read(buffer, pos + total);
                    if (n < 0) break;
                    total += n;
                }
                return total == 0 ? -1 : total;
            }

            @Override
            public void close() {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    private static final int CHUNK_FRAMES = 4096;

    private final Source source;
    private final int channels, rate, bits, bytesPer;
    private final boolean floating;
    private final long dataOffset, dataLength;
    private long position; // bytes into the data chunk
    private final byte[] scratch;

    WavStream(String name, Source source) {
        this.source = source;
        try {
            int format = 0, ch = 0, sr = 0, bt = 0;
            long dataOff = -1, dataLen = 0, pos = 12;
            byte[] head = new byte[40];
            while (pos + 8 <= source.length()) {
                if (source.read(pos, head, 0, 8) < 8) break;
                ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                int id = b.getInt(0);
                int size = b.getInt(4);
                long body = pos + 8;
                if (id == 0x20746d66) { // "fmt "
                    int got = source.read(body, head, 0, Math.min(40, Math.max(0, size)));
                    if (got < 16) break;
                    format = b.getShort(0) & 0xFFFF;
                    ch = b.getShort(2);
                    sr = b.getInt(4);
                    bt = b.getShort(14);
                    if (format == 0xFFFE && size >= 26 && got >= 26) format = b.getShort(24) & 0xFFFF; // extensible: sub-format
                } else if (id == 0x61746164) { // "data"
                    dataOff = body;
                    dataLen = Math.min(size < 0 ? Long.MAX_VALUE : size, source.length() - body);
                    break;
                }
                if (size < 0) break;
                pos = body + size + (size & 1); // chunks are word aligned
            }
            if (dataOff < 0 || ch == 0) throw new IllegalArgumentException("Invalid WAV file: " + name);
            if (ch > Downmix.MAX_CHANNELS) throw AudioDecoder.tooManyChannels(name, ch);
            boolean pcm = format == 1 && (bt == 8 || bt == 16 || bt == 24 || bt == 32);
            floating = format == 3 && bt == 32;
            if (!pcm && !floating) throw new IllegalArgumentException("Unsupported WAV encoding in " + name
                    + " (need 8/16/24/32-bit PCM or 32-bit float)");
            channels = ch;
            rate = sr;
            bits = bt;
            bytesPer = bt / 8;
            dataOffset = dataOff;
            dataLength = dataLen - dataLen % ((long) bytesPer * ch); // whole frames only
            scratch = new byte[CHUNK_FRAMES * bytesPer * ch];
        } catch (IOException e) {
            source.close();
            throw new UncheckedIOException("Could not read WAV file " + name, e);
        } catch (RuntimeException e) {
            source.close();
            throw e;
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
    public long totalFrames() {
        return dataLength / ((long) bytesPer * channels);
    }

    @Override
    public int read(short[] dst, int off, int len) {
        int done = 0;
        int frameBytes = bytesPer * channels;
        while (done < len) {
            long left = dataLength - position;
            if (left <= 0) break;
            int want = (int) Math.min(Math.min(left, scratch.length), (long) (len - done) / channels * frameBytes);
            if (want < frameBytes) break;
            int got;
            try {
                got = source.read(dataOffset + position, scratch, 0, want);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            got -= got % frameBytes;
            if (got <= 0) break;
            convert(got / bytesPer, dst, off + done);
            position += got;
            done += got / bytesPer;
        }
        return done;
    }

    private void convert(int samples, short[] dst, int at) {
        ByteBuffer b = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            int p = i * bytesPer;
            dst[at + i] = switch (bits) {
                case 8 -> (short) (((scratch[p] & 0xFF) - 128) << 8);
                case 16 -> b.getShort(p);
                case 24 -> (short) (((scratch[p + 1] & 0xFF) | (scratch[p + 2] << 8)));
                default -> floating
                        ? (short) Math.round(Math.max(-1f, Math.min(1f, b.getFloat(p))) * 32767f)
                        : (short) (b.getInt(p) >> 16);
            };
        }
    }

    @Override
    public void seek(long frame) {
        position = Math.min(dataLength, Math.max(0, frame) * bytesPer * channels);
    }

    @Override
    public void close() {
        source.close();
    }
}
