package me.piitex.engine.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class Mp4DemuxerTest {
    @TempDir
    File dir;

    private Mp4Builder movie(Mp4Builder.Layout layout) throws IOException {
        Mp4Builder b = new Mp4Builder(32, 32, 10, 20);
        b.layout = layout;
        return b;
    }

    @Test
    void readsHeaderInfoInEveryLayout() throws Exception {
        for (Mp4Builder.Layout layout : Mp4Builder.Layout.values()) {
            File f = movie(layout).write(dir, layout + ".mp4");
            try (Demuxer d = MediaFiles.open(f)) {
                MediaInfo info = d.info();
                assertEquals("MP4", info.container(), layout.toString());
                assertEquals(32, info.width(), layout.toString());
                assertEquals(32, info.height(), layout.toString());
                assertEquals(2.0, info.duration(), 1e-9, layout.toString());
                assertEquals(10.0, info.frameRate(), 1e-9, layout.toString());
                assertEquals("MJPEG", info.videoCodec(), layout.toString());
                assertTrue(info.hasAudio(), layout.toString());
                assertEquals(8000, info.audioRate(), layout.toString());
                assertEquals("PCM", info.audioCodec(), layout.toString());
            }
        }
    }

    @Test
    void readsPacketsInOrderWithTimestamps() throws Exception {
        for (Mp4Builder.Layout layout : Mp4Builder.Layout.values()) {
            File f = movie(layout).write(dir, layout + ".mp4");
            try (Demuxer d = MediaFiles.open(f)) {
                int video = 0;
                long audioBytes = 0;
                double last = -1;
                Packet p;
                while ((p = d.next()) != null) {
                    if (p.stream() == d.videoStream()) {
                        assertEquals(video / 10.0, p.pts(), 1e-9, layout + " frame " + video);
                        assertTrue(MjpegColor.near(AviBuilder.COLORS[video % AviBuilder.COLORS.length], MjpegColor.of(p.data())),
                                layout + ": wrong picture at frame " + video);
                        video++;
                    } else {
                        assertEquals(audioBytes / 2 / 8000.0, p.pts(), 1e-9, layout + " audio");
                        audioBytes += p.data().length;
                    }
                    assertTrue(p.pts() >= last - 0.2, "packets come out roughly in time order");
                    last = Math.max(last, p.pts());
                }
                assertEquals(20, video, layout.toString());
                assertEquals(2 * 8000 * 2, audioBytes, layout.toString());
            }
        }
    }

    @Test
    void seeksToTheKeyframeBeforeTheTarget() throws Exception {
        for (Mp4Builder.Layout layout : Mp4Builder.Layout.values()) {
            Mp4Builder b = movie(layout);
            b.keyEvery = 5;
            File f = b.write(dir, "k-" + layout + ".mp4");
            try (Demuxer d = MediaFiles.open(f)) {
                for (int target : new int[]{13, 4, 0, 19, 10, 9}) {
                    d.seek(target / 10.0 + 0.01);
                    Packet video = d.next();
                    while (video != null && video.stream() != d.videoStream()) video = d.next();
                    assertNotNull(video, layout + " seek to frame " + target);
                    int key = target / 5 * 5;
                    assertEquals(key / 10.0, video.pts(), 1e-9, layout + " seek to frame " + target);
                    assertTrue(video.keyframe(), layout + " seek to frame " + target);
                    assertTrue(MjpegColor.near(AviBuilder.COLORS[key % AviBuilder.COLORS.length], MjpegColor.of(video.data())),
                            layout + ": wrong picture after seeking to " + target);
                }
            }
        }
    }

    @Test
    void audioResumesNoLaterThanThePicture() throws Exception {
        File f = movie(Mp4Builder.Layout.MOOV_LAST).write(dir, "a.mp4");
        try (Demuxer d = MediaFiles.open(f)) {
            d.seek(1.0);
            Packet p;
            while ((p = d.next()) != null && p.stream() != d.audioStream()) {
                // skip to the first audio packet
            }
            assertNotNull(p);
            assertTrue(p.pts() <= 1.0 + 1e-9, "audio starts at " + p.pts());
            assertTrue(p.pts() > 0.8, "audio starts at " + p.pts());
        }
    }

    @Test
    void compositionDelayAndEditListGiveTheRightTimes() throws Exception {
        for (Mp4Builder.Layout layout : new Mp4Builder.Layout[]{Mp4Builder.Layout.MOOV_LAST, Mp4Builder.Layout.FRAGMENTED}) {
            Mp4Builder b = movie(layout);
            b.delayFrames = 2;
            b.audio = false;
            File f = b.write(dir, "d-" + layout + ".mp4");
            try (Demuxer d = MediaFiles.open(f)) {
                assertFalse(d.info().hasAudio());
                for (int i = 0; i < 20; i++) {
                    Packet p = d.next();
                    assertNotNull(p, layout + " frame " + i);
                    // the file stores a 0.2 s delay, and the edit list takes it back off
                    assertEquals(i / 10.0, p.pts(), 1e-9, layout + " frame " + i);
                }
                assertNull(d.next());
            }
        }
    }

    @Test
    void fileWithoutSoundPlaysSilently() throws Exception {
        Mp4Builder b = movie(Mp4Builder.Layout.MOOV_FIRST);
        b.audio = false;
        try (Demuxer d = MediaFiles.open(b.write(dir, "mute.mp4"))) {
            assertEquals(-1, d.audioStream());
            assertNull(d.audioDecoder());
        }
    }

    @Test
    void unsupportedCodecIsNamedAndTheFileCanBeConverted() throws Exception {
        byte[] bytes = movie(Mp4Builder.Layout.MOOV_LAST).build();
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 'j' && bytes[i + 1] == 'p' && bytes[i + 2] == 'e' && bytes[i + 3] == 'g') {
                bytes[i] = 'a';
                bytes[i + 1] = 'v';
                bytes[i + 2] = 'c';
                bytes[i + 3] = '1';
            }
        }
        File f = new File(dir, "h264.mp4");
        Files.write(f.toPath(), bytes);
        var e = assertThrows(UnsupportedOperationException.class, () -> VideoPlayer.open(f));
        assertTrue(e.getMessage().contains("H.264"), e.getMessage());
        assertTrue(e.getMessage().contains("ffmpeg"), e.getMessage());
    }

    @Test
    void truncatedFileStopsCleanly() throws Exception {
        byte[] bytes = movie(Mp4Builder.Layout.FRAGMENTED).build();
        File f = new File(dir, "cut.mp4");
        Files.write(f.toPath(), java.util.Arrays.copyOf(bytes, bytes.length * 2 / 3));
        try (Demuxer d = MediaFiles.open(f)) {
            int n = 0;
            while (d.next() != null) n++;
            assertTrue(n > 0 && n < 40, "read " + n + " packets");
        }
    }
}
