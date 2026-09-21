package me.piitex.engine.audio;

import me.piitex.engine.io.AssetManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Package-private: finds audio bytes, works out which format they are, and opens the matching decoder.
 */
final class AudioDecoder {
    private AudioDecoder() {
    }

    /**
     * Reads a file from disk, or failing that from the classpath.
     */
    static byte[] read(String path) {
        try {
            File file = new File(path);
            if (file.isFile()) return Files.readAllBytes(file.toPath());
            String resource = path.startsWith("/") ? path.substring(1) : path;
            try (InputStream in = AudioDecoder.class.getClassLoader().getResourceAsStream(resource)) {
                if (in != null) return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read audio file: " + path, e);
        }
        throw new UncheckedIOException(new java.io.FileNotFoundException(
                "Audio file not found on disk or classpath: " + path));
    }

    static byte[] read(AssetManager assets, String name) {
        ByteBuffer buffer = assets.loadAssetAsBuffer(name).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static boolean startsWith(byte[] b, String magic, int offset) {
        if (b.length < offset + magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) {
            if (b[offset + i] != magic.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isWav(byte[] b) {
        return startsWith(b, "RIFF", 0) && startsWith(b, "WAVE", 8);
    }

    private static boolean isOgg(byte[] b) {
        return startsWith(b, "OggS", 0);
    }

    /** An Ogg file whose first packet is an Opus header (Vorbis files start with a Vorbis header instead). */
    private static boolean isOpus(byte[] b) {
        for (int i = 27; i < Math.min(b.length - 8, 96); i++) {
            if (startsWith(b, "OpusHead", i)) return true;
        }
        return false;
    }

    private static boolean isFlac(byte[] b) {
        return startsWith(b, "fLaC", 0);
    }

    /** An ID3 tag, or an MPEG audio frame header (11 set sync bits). */
    private static boolean isMp3(byte[] b) {
        if (startsWith(b, "ID3", 0)) return true;
        return b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0;
    }

    /**
     * Fully decodes a clip.
     */
    static Pcm decode(String name, byte[] bytes) {
        if (isWav(bytes)) return decodeWav(name, bytes);
        try (PcmStream stream = open(name, bytes)) {
            return drain(stream);
        }
    }

    /**
     * Opens a clip for streaming. WAV is already small enough to hold decoded, everything else is decoded as it plays.
     */
    static PcmStream open(String name, byte[] bytes) {
        if (isWav(bytes)) return decodeWav(name, bytes).stream();
        if (isOgg(bytes)) return isOpus(bytes) ? new OpusStream(name, bytes) : new VorbisStream(name, bytes);
        if (isFlac(bytes)) return new FlacStream(name, bytes);
        if (isMp3(bytes)) return new Mp3Stream(name, bytes);
        throw new IllegalArgumentException("Unsupported audio format: " + name
                + " (supported: .ogg, .opus, .flac, .mp3, .wav)");
    }

    private static Pcm drain(PcmStream stream) {
        int channels = stream.channels();
        long hint = stream.totalFrames();
        short[] out = new short[(int) Math.max(hint > 0 ? hint * channels : 0, stream.sampleRate() * (long) channels)];
        int filled = 0;
        while (true) {
            if (filled == out.length) out = Arrays.copyOf(out, out.length * 2);
            int n = stream.read(out, filled, out.length - filled);
            if (n <= 0) break;
            filled += n;
        }
        return new Pcm(Arrays.copyOf(out, filled), channels, stream.sampleRate());
    }

    static IllegalArgumentException tooManyChannels(String name, int channels) {
        return new IllegalArgumentException(name + " has " + channels + " channels; only mono and stereo are supported");
    }

    private static Pcm decodeWav(String name, byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int format = 0, channels = 0, rate = 0, bits = 0;
        int dataOff = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos);
            int size = b.getInt(pos + 4);
            int body = pos + 8;
            if (id == 0x20746d66) { // "fmt "
                format = b.getShort(body) & 0xFFFF;
                channels = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
                if (format == 0xFFFE && size >= 26) format = b.getShort(body + 24) & 0xFFFF; // extensible: sub-format
            } else if (id == 0x61746164) { // "data"
                dataOff = body;
                dataLen = Math.min(size < 0 ? Integer.MAX_VALUE : size, bytes.length - body);
                break;
            }
            if (size < 0) break;
            pos = body + size + (size & 1); // chunks are word aligned
        }
        if (dataOff < 0 || channels == 0) throw new IllegalArgumentException("Invalid WAV file: " + name);
        if (channels > 2) throw tooManyChannels(name, channels);
        boolean pcm = format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32);
        boolean floating = format == 3 && bits == 32;
        if (!pcm && !floating) throw new IllegalArgumentException("Unsupported WAV encoding in " + name
                + " (need 8/16/24/32-bit PCM or 32-bit float)");

        int bytesPer = bits / 8;
        int count = dataLen / bytesPer;
        count -= count % channels;
        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            int p = dataOff + i * bytesPer;
            out[i] = switch (bits) {
                case 8 -> (short) (((bytes[p] & 0xFF) - 128) << 8);
                case 16 -> b.getShort(p);
                case 24 -> (short) (((bytes[p + 1] & 0xFF) | (bytes[p + 2] << 8)));
                default -> floating
                        ? (short) Math.round(Math.max(-1f, Math.min(1f, b.getFloat(p))) * 32767f)
                        : (short) (b.getInt(p) >> 16);
            };
        }
        return new Pcm(out, channels, rate);
    }
}
