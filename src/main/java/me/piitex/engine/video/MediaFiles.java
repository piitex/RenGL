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
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read video file: " + file, e);
        }
        throw new UnsupportedOperationException("Unsupported video format: " + file.getName()
                + " (supported: AVI with Motion JPEG or uncompressed video and PCM audio)");
    }
}
