package me.piitex.engine.video;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The files here were written by real encoders (Apple's for H.264, HEVC and AAC, Chromium's for the quadrants clip),
 * so they exercise the system decoder (AVFoundation on macOS, Media Foundation on Windows, GStreamer on Linux) for real. Frame {@code i} is solid {@code AviBuilder.COLORS[i % 8]}. Skipped where there is no system decoder.
 */
class SystemMediaSourceTest {
    @TempDir
    File dir;

    @BeforeEach
    void needSystemDecoder() {
        Assumptions.assumeTrue(MediaFiles.systemAvailable(), "no system media framework on this OS");
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
    void turnsAPictureTheFileMarksAsSideways() throws Exception {
        // The same video, with a quarter turn in its display transform, as a phone writes one held upright.
        try (Demuxer d = MediaFiles.open(fixture("h264-rotated-640x360.mp4"))) {
            assertEquals(360, d.info().width());
            assertEquals(640, d.info().height());
            FrameDecoder video = d.videoDecoder();
            Packet p;
            int frames = 0;
            while ((p = d.next()) != null) {
                if (p.stream() != d.videoStream()) continue;
                VideoFrame f = video.decode(p);
                assertEquals(360, f.width());
                assertEquals(640, f.height());
                assertEquals(360 * 640 * 4, f.pixels().length);
                frames++;
            }
            assertTrue(frames > 0);
        }
    }

    /** Whether the pixel at (x, y) of a BGRA picture is near the colour, each channel as 0 or 255. */
    private static boolean pixelIs(VideoFrame f, int x, int y, int r, int g, int b) {
        int o = (y * f.width() + x) * 4;
        byte[] px = f.pixels();
        return Math.abs((px[o] & 0xFF) - b) < 40 && Math.abs((px[o + 1] & 0xFF) - g) < 40 && Math.abs((px[o + 2] & 0xFF) - r) < 40
                && (px[o + 3] & 0xFF) == 255;
    }

    @Test
    void picturesAreUprightAndTheRightColoursAndCropped() throws Exception {
        // Written by Chromium's H.264 encoder: red top-left, green bottom-right, blue elsewhere, 180 rows coded as 192.
        try (Demuxer d = MediaFiles.open(fixture("h264-quadrants-320x180.mp4"))) {
            assertEquals(320, d.info().width());
            assertEquals(180, d.info().height());
            assertTrue(d.info().frameRate() > 25 && d.info().frameRate() < 35, "frame rate " + d.info().frameRate());
            FrameDecoder video = d.videoDecoder();
            Packet p;
            int checked = 0;
            while ((p = d.next()) != null) {
                if (p.stream() != d.videoStream()) continue;
                VideoFrame f = video.decode(p);
                assertEquals(320 * 180 * 4, f.pixels().length);
                if (checked++ < 3) continue;
                assertTrue(pixelIs(f, 80, 45, 255, 0, 0), "top-left is red");
                assertTrue(pixelIs(f, 240, 135, 0, 255, 0), "bottom-right is green");
                assertTrue(pixelIs(f, 240, 45, 0, 0, 255), "top-right is blue");
                assertTrue(pixelIs(f, 80, 135, 0, 0, 255), "bottom-left is blue");
                return;
            }
            fail("fewer than four pictures");
        }
    }

    @Test
    void decodesHevc() throws Exception {
        Demuxer opened = null;
        FrameDecoder video;
        try {
            opened = MediaFiles.open(fixture("hevc-640x360.mp4"));
            // Opening can succeed where the decoder is missing: Windows reports it when the decoder is asked for.
            video = opened.videoDecoder();
        } catch (UnsupportedOperationException e) {
            if (opened != null) opened.close();
            Assumptions.abort("this machine has no HEVC decoder (Windows ships it as a separate extension): " + e.getMessage());
            return;
        }
        try (Demuxer d = opened) {
            assertEquals("H.265", d.info().videoCodec());
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
