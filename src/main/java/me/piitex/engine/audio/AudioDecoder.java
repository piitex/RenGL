package me.piitex.engine.audio;

import me.piitex.engine.io.AssetManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
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

    /**
     * An Ogg file whose first packet is an Opus header (Vorbis files start with a Vorbis header instead).
     */
    private static boolean isOpus(byte[] b) {
        for (int i = 27; i < Math.min(b.length - 8, 96); i++) {
            if (startsWith(b, "OpusHead", i)) return true;
        }
        return false;
    }

    private static boolean isFlac(byte[] b) {
        return startsWith(b, "fLaC", 0);
    }

    /**
     * An ID3 tag, or an MPEG audio frame header (11 set sync bits).
     */
    private static boolean isMp3(byte[] b) {
        if (startsWith(b, "ID3", 0)) return true;
        return b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0;
    }

    /**
     * Fully decodes a clip.
     */
    static Pcm decode(String name, byte[] bytes) {
        try (PcmStream stream = open(name, bytes)) {
            return drain(stream);
        }
    }

    /**
     * Opens a clip for streaming: it is decoded a little at a time as it plays.
     */
    static PcmStream open(String name, byte[] bytes) {
        if (isWav(bytes)) return wav(new WavStream(name, WavStream.of(bytes)));
        if (isOgg(bytes)) return isOpus(bytes) ? new OpusStream(name, bytes) : new VorbisStream(name, bytes);
        if (isFlac(bytes)) {
            PcmStream flac = new FlacStream(name, bytes);
            return flac.channels() > 2 ? Downmix.stream(flac) : flac;
        }
        if (isMp3(bytes)) return new Mp3Stream(name, bytes);
        throw new IllegalArgumentException("Unsupported audio format: " + name
                + " (supported: .ogg, .opus, .flac, .mp3, .wav)");
    }

    /**
     * Whether {@code path} names a WAV file on disk, so a track can be played straight from it. False for a classpath
     * resource, a missing file, or anything else.
     */
    static boolean isWavFile(String path) {
        File file = new File(path);
        if (!file.isFile()) return false;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return isWav(in.readNBytes(12));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Opens a WAV file on disk for streaming without reading it into memory.
     */
    static PcmStream openWavFile(String name, String path) {
        try {
            return wav(new WavStream(name, WavStream.of(new File(path).toPath())));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read audio file: " + path, e);
        }
    }

    private static PcmStream wav(PcmStream stream) {
        return Downmix.supports(stream.channels()) ? Downmix.stream(stream) : stream;
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
        return new IllegalArgumentException(name + " has " + channels + " channels; mono, stereo and up to "
                + Downmix.MAX_CHANNELS + " channels (mixed down to stereo) are supported");
    }
}
