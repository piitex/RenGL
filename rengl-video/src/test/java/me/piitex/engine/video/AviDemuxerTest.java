package me.piitex.engine.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class AviDemuxerTest {
    @TempDir
    File dir;

    private static AviBuilder movie(AviBuilder.Index index, boolean audio) throws IOException {
        AviBuilder b = new AviBuilder(32, 32, 10, false).frames(20);
        b.index = index;
        if (audio) b.tone(2.0);
        return b;
    }

    @Test
    void readsHeaderInfo() throws Exception {
        File f = movie(AviBuilder.Index.IDX1, true).write(dir, "a.avi");
        try (Demuxer d = MediaFiles.open(f)) {
            MediaInfo info = d.info();
            assertEquals(32, info.width());
            assertEquals(32, info.height());
            assertEquals(10.0, info.frameRate(), 1e-9);
            assertEquals(2.0, info.duration(), 1e-9);
            assertEquals("MJPEG", info.videoCodec());
            assertTrue(info.hasAudio());
            assertEquals(1, info.audioChannels());
            assertEquals(8000, info.audioRate());
        }
    }

    @Test
    void readsPacketsInOrderWithTimestamps() throws Exception {
        File f = movie(AviBuilder.Index.IDX1, true).write(dir, "a.avi");
        try (Demuxer d = MediaFiles.open(f)) {
            int video = 0, audio = 0;
            long audioBytes = 0;
            Packet p;
            while ((p = d.next()) != null) {
                if (p.stream() == d.videoStream()) {
                    assertEquals(video / 10.0, p.pts(), 1e-9);
                    video++;
                } else {
                    assertEquals(audioBytes / 2 / 8000.0, p.pts(), 1e-9);
                    audioBytes += p.data().length;
                    audio++;
                }
            }
            assertEquals(20, video);
            assertEquals(20, audio);
            assertEquals(2 * 8000 * 2, audioBytes); // 2 s of 16-bit mono
        }
    }

    @Test
    void seeksThroughEveryIndexLayout() throws Exception {
        for (AviBuilder.Index index : AviBuilder.Index.values()) {
            File f = movie(index, true).write(dir, index + ".avi");
            try (Demuxer d = MediaFiles.open(f)) {
                for (int target : new int[]{15, 3, 0, 19, 10}) {
                    d.seek(target / 10.0 + 0.01);
                    Packet p = d.next();
                    assertNotNull(p, index + " seek to frame " + target);
                    // the first packet is the video frame we asked for, or audio from just before it
                    Packet video = p;
                    while (video.stream() != d.videoStream()) video = d.next();
                    assertEquals(target / 10.0, video.pts(), 1e-9, index + " seek to frame " + target);
                    assertTrue(MjpegColor.near(AviBuilder.COLORS[target % AviBuilder.COLORS.length], MjpegColor.of(video.data())),
                            index + ": wrong picture at frame " + target);
                }
            }
        }
    }

    @Test
    void audioPositionFollowsAfterSeek() throws Exception {
        for (AviBuilder.Index index : AviBuilder.Index.values()) {
            File f = movie(index, true).write(dir, "s" + index + ".avi");
            try (Demuxer d = MediaFiles.open(f)) {
                d.seek(1.0);
                Packet p;
                while ((p = d.next()) != null && p.stream() != d.audioStream()) ;
                assertNotNull(p);
                assertEquals(1.0, p.pts(), 0.11, index + ": audio should resume near the seek target");
            }
        }
    }

    @Test
    void seekToKeyframeBeforeTarget() throws Exception {
        AviBuilder b = movie(AviBuilder.Index.IDX1, false);
        b.keyEvery = 5;
        File f = b.write(dir, "k.avi");
        try (Demuxer d = MediaFiles.open(f)) {
            d.seek(1.3); // frame 13: the keyframe at or before it is 10
            assertEquals(1.0, d.next().pts(), 1e-9);
        }
    }

    @Test
    void rejectsUnsupportedFiles() throws Exception {
        File junk = new File(dir, "junk.avi");
        java.nio.file.Files.write(junk.toPath(), new byte[64]);
        assertThrows(UnsupportedOperationException.class, () -> MediaFiles.open(junk));
        assertThrows(java.io.UncheckedIOException.class, () -> MediaFiles.open(new File(dir, "missing.avi")));
    }

    @Test
    void truncatedFilePlaysUpToTheDamage() throws Exception {
        byte[] bytes = movie(AviBuilder.Index.NONE, false).build();
        File f = new File(dir, "cut.avi");
        java.nio.file.Files.write(f.toPath(), java.util.Arrays.copyOf(bytes, bytes.length * 2 / 3));
        try (Demuxer d = MediaFiles.open(f)) {
            int n = 0;
            while (d.next() != null) n++;
            assertTrue(n > 5 && n < 20, "got " + n);
        }
    }
}
