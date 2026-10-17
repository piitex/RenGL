package me.piitex.engine.video;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Recognises a video file from its first bytes (not its extension) and opens the matching demuxer.
 */
final class MediaFiles {
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger("Video");

    private MediaFiles() {
    }

    static Demuxer open(File file) {
        if (!file.isFile()) throw new UncheckedIOException(new FileNotFoundException("Video file not found: " + file));
        try {
            byte[] head = new byte[16];
            try (InputStream in = Files.newInputStream(file.toPath())) {
                in.readNBytes(head, 0, head.length);
            }
            if (AviDemuxer.matches(head)) return new AviDemuxer(file);
            if (Mp4Demuxer.matches(head)) {
                Mp4Demuxer own = new Mp4Demuxer(file);
                if (own.fullySupported() || !systemAvailable()) {
                    own.warnIfDegraded();
                    return own;
                }
                double shift = own.videoEditShift();
                own.close();
                Demuxer os = openWithSystem(file, shift);
                return os != null ? os : reopen(file);
            }
            Demuxer os = openWithSystem(file, 0);
            if (os != null) return os;
            throw new UnsupportedOperationException("Unsupported video format: " + file.getName() + " is " + container(head)
                    + ". RenGL plays AVI and MP4 files with Motion JPEG or uncompressed video and PCM audio, and on macOS and Windows any file the system can play. "
                    + convertHint(file.getName()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read video file: " + file, e);
        }
    }

    /** Whether this machine has a system decoder: AVFoundation on macOS, Media Foundation on Windows. */
    static boolean systemAvailable() {
        return MacMediaSource.available() || WinMediaSource.available();
    }

    /** The operating system's decoder, when there is one and it accepts the file. Null otherwise. */
    private static Demuxer openWithSystem(File file, double editShift) throws IOException {
        if (!systemAvailable()) return null;
        try {
            return MacMediaSource.available() ? new MacMediaSource(file) : new WinMediaSource(file, editShift);
        } catch (UnsupportedOperationException e) {
            log.debug("The system cannot play {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private static Demuxer reopen(File file) throws IOException {
        Mp4Demuxer own = new Mp4Demuxer(file);
        own.warnIfDegraded();
        return own;
    }

    /** How to turn a file into one the player reads; shown with errors. */
    static String convertHint(String name) {
        return "Convert it with: ffmpeg -i \"" + name + "\" -c:v mjpeg -q:v 3 -c:a pcm_s16le out.avi";
    }

    private static String container(byte[] head) {
        if ((head[0] & 0xFF) == 0x1A && head[1] == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return "a Matroska/WebM file";
        return "not a recognised video file";
    }
}
