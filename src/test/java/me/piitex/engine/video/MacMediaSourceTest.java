package me.piitex.engine.video;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The files here were written by Apple's own encoder (H.264, HEVC, AAC), so they exercise the system decoder for
 * real. Frame {@code i} is solid {@code AviBuilder.COLORS[i % 8]}. Skipped where there is no system decoder.
 */
class MacMediaSourceTest {
    @TempDir
    File dir;

    @BeforeEach
    void needSystemDecoder() {
        Assumptions.assumeTrue(MacMediaSource.available(), "no system media framework on this OS");
    }

    static File fixture(String name) {
        return new File("src/test/resources/video/" + name);
    }

    private static boolean colorOk(int frame, VideoFrame f) {
        return MjpegColor.near(AviBuilder.COLORS[frame % AviBuilder.COLORS.length], MjpegColor.center(f));
    }

    @Test
    void decodesH264PicturesAndAacSound() throws Exception {
        try (Demuxer d = MediaFiles.open(fixture("h264-aac-640x360.mp4"))) {
            MediaInfo info = d.info();
            assertTrue(info.container().startsWith("OS"), info.container());
            assertEquals(640, info.width());
            assertEquals(360, info.height());
            assertEquals("H.264", info.videoCodec());
            assertEquals(3.0, info.duration(), 0.05);
            assertEquals(2, info.audioChannels());
            assertEquals(44100, info.audioRate());
            assertEquals("AAC", info.audioCodec());

            FrameDecoder video = d.videoDecoder();
            AudioTrackDecoder audio = d.audioDecoder();
            int frames = 0;
            long audioShorts = 0;
            double last = -1;
            Packet p;
            while ((p = d.next()) != null) {
                if (p.stream() == d.videoStream()) {
                    VideoFrame f = video.decode(p);
                    assertEquals(640, f.width());
                    assertEquals(360, f.height());
                    assertTrue(f.pts() > last, "pictures come out in display order");
                    last = f.pts();
                    int index = (int) Math.round(f.pts() * 30);
                    assertEquals(frames, index);
                    assertTrue(colorOk(index, f), "wrong picture at frame " + index);
                    frames++;
                } else {
                    audioShorts += audio.decode(p).length;
                }
            }
            assertEquals(90, frames);
            assertEquals(3.0, audioShorts / 2 / 44100.0, 0.1);
        }
    }

    @Test
    void decodesHevc() throws Exception {
        try (Demuxer d = MediaFiles.open(fixture("hevc-640x360.mp4"))) {
            assertEquals("H.265", d.info().videoCodec());
            FrameDecoder video = d.videoDecoder();
            int frames = 0;
            Packet p;
            while ((p = d.next()) != null) {
                VideoFrame f = video.decode(p);
                assertTrue(colorOk((int) Math.round(f.pts() * 30), f));
                frames++;
            }
            assertEquals(90, frames);
        }
    }

    @Test
    void seekStartsAtOrBeforeTheTargetAndReachesIt() throws Exception {
        try (Demuxer d = MediaFiles.open(fixture("h264-aac-640x360.mp4"))) {
            FrameDecoder video = d.videoDecoder();
            for (double target : new double[]{2.0, 0.9, 2.9, 0.0}) {
                d.seek(target);
                boolean reached = false;
                double firstVideo = -1, firstAudio = -1;
                Packet p;
                while ((p = d.next()) != null) {
                    if (p.stream() == d.videoStream()) {
                        if (firstVideo < 0) firstVideo = p.pts();
                        if (Math.abs(p.pts() - target) < 0.02) {
                            reached = colorOk((int) Math.round(p.pts() * 30), video.decode(p));
                            break;
                        }
                    } else if (firstAudio < 0) {
                        firstAudio = p.pts();
                    }
                }
                assertTrue(firstVideo <= target + 1e-6, "first picture " + firstVideo + " after target " + target);
                assertTrue(firstAudio <= target + 1e-6, "first sound " + firstAudio + " after target " + target);
                assertTrue(reached, "never reached the picture at " + target);
            }
        }
    }

    @Test
    void filesTheEngineDecodesItselfDoNotGoThroughTheSystem() throws Exception {
        File f = new Mp4Builder(32, 32, 10, 5).write(dir, "mjpeg.mp4");
        try (Demuxer d = MediaFiles.open(f)) {
            assertEquals("MP4", d.info().container());
        }
    }

    @Test
    void aFileNothingCanPlayIsRejected() throws Exception {
        File f = new File(dir, "junk.bin");
        Files.write(f.toPath(), new byte[4096]);
        assertThrows(UnsupportedOperationException.class, () -> MediaFiles.open(f));
    }
}
