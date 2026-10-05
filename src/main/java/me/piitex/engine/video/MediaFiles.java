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
            throw new UnsupportedOperationException("Unsupported video format: " + file.getName() + " is " + container(head)
                    + ". RenGL plays AVI with Motion JPEG or uncompressed video and PCM audio. Convert it with: "
                    + "ffmpeg -i \"" + file.getName() + "\" -c:v mjpeg -q:v 3 -c:a pcm_s16le out.avi");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read video file: " + file, e);
        }
    }

    private static String container(byte[] head) {
        if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return "an MP4/MOV file";
        if ((head[0] & 0xFF) == 0x1A && head[1] == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return "a Matroska/WebM file";
        return "not a recognised video file";
    }
}
