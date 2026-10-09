package me.piitex.engine.video;

import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a {@link VideoPlayer} the way the render loop does, headless: update() is called with a fixed step, so the
 * test runs on virtual time and only waits for the decode thread.
 */
class VideoPlayerTest {
    private static final float STEP = 1 / 30f;

    @TempDir
    File dir;
    private final List<VideoPlayer> players = new ArrayList<>();

    @AfterEach
    void closeAll() {
        players.forEach(VideoPlayer::close);
    }

    private VideoPlayer open(AviBuilder b) throws Exception {
        VideoPlayer p = VideoPlayer.open(b.write(dir, "v" + players.size() + ".avi"));
        players.add(p);
        return p;
    }

    private static AviBuilder movie(int frames) throws Exception {
        return new AviBuilder(32, 32, 10, false).frames(frames);
    }

    /** Updates until {@code done} or ten seconds of real time pass. */
    private static boolean pump(VideoPlayer p, BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            p.update(STEP);
            if (done.getAsBoolean()) return true;
            Thread.sleep(2);
        }
        return false;
    }

    private static int rgb(Image image) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(image.getImageInfo());
            image.readPixels(bitmap);
            return bitmap.getColor(image.getWidth() / 2, image.getHeight() / 2) & 0xFFFFFF;
        }
    }

    private static void assertShows(VideoPlayer p, int frameIndex) {
        assertNotNull(p.getFrame());
        int expected = AviBuilder.COLORS[frameIndex % AviBuilder.COLORS.length];
        assertTrue(MjpegColor.near(expected, rgb(p.getFrame())),
                "frame " + frameIndex + " expected " + Integer.toHexString(expected) + " but showing " + Integer.toHexString(rgb(p.getFrame())));
    }

    @Test
    void playsEveryFrameInOrderThenFinishes() throws Exception {
        VideoPlayer p = open(movie(20));
        AtomicInteger finished = new AtomicInteger();
        p.onFinish(finished::incrementAndGet);
        assertEquals(32, p.getWidth());
        assertEquals(2.0, p.getDuration(), 1e-9);

        List<Integer> seen = new ArrayList<>();
        assertTrue(pump(p, () -> {
            if (p.getFrame() != null) {
                int idx = (int) Math.round(p.getFrameTime() * 10);
                if (seen.isEmpty() || seen.getLast() != idx) seen.add(idx);
            }
            return p.isFinished();
        }));
        assertEquals(1, finished.get());
        assertFalse(p.isPlaying());
        assertEquals(0, seen.getFirst());
        assertEquals(19, seen.getLast());
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) > seen.get(i - 1), "frames must not go backwards: " + seen);
        assertEquals(20, seen.size(), "at this step no frame should be skipped: " + seen);
        assertShows(p, 19);

        p.update(STEP);
        assertEquals(1, finished.get(), "finish fires once");
    }

    @Test
    void showsTheFirstFrameWhilePaused() throws Exception {
        VideoPlayer p = open(movie(10));
        p.pause();
        assertTrue(pump(p, () -> p.getFrame() != null));
        assertShows(p, 0);
        for (int i = 0; i < 30; i++) p.update(STEP);
        assertEquals(0.0, p.getPosition(), 1e-9, "a paused video does not move");
        assertShows(p, 0);
    }

    @Test
    void seekShowsTheFrameAtThatTime() throws Exception {
        VideoPlayer p = open(movie(20));
        p.pause();
        assertTrue(pump(p, () -> p.getFrame() != null));
        for (int target : new int[]{15, 4, 19, 0, 8}) {
            p.seek(target / 10.0 + 0.03);
            assertTrue(pump(p, () -> Math.round(p.getFrameTime() * 10) == target), "seek to " + target);
            assertShows(p, target);
            assertEquals(target / 10.0 + 0.03, p.getPosition(), 1e-9);
        }
    }

    @Test
    void seekAfterFinishReplays() throws Exception {
        VideoPlayer p = open(movie(5));
        assertTrue(pump(p, p::isFinished));
        p.play(); // pressing play on a finished video starts over
        assertFalse(p.isFinished());
        assertTrue(p.isPlaying());
        assertTrue(pump(p, p::isFinished));
        assertShows(p, 4);
    }

    @Test
    void loopCountPlaysThatManyTimes() throws Exception {
        VideoPlayer p = open(movie(5));
        p.setLoopCount(3);
        AtomicInteger loops = new AtomicInteger(), finishes = new AtomicInteger();
        p.onLoop(loops::incrementAndGet).onFinish(finishes::incrementAndGet);
        assertTrue(pump(p, p::isFinished));
        assertEquals(2, loops.get(), "three plays means two wraps");
        assertEquals(1, finishes.get());
    }

    @Test
    void loopingNeverFinishes() throws Exception {
        VideoPlayer p = open(movie(5));
        p.setLooping(true);
        AtomicInteger loops = new AtomicInteger();
        p.onLoop(loops::incrementAndGet);
        assertTrue(pump(p, () -> loops.get() >= 3));
        assertFalse(p.isFinished());
        assertTrue(p.isPlaying());
    }

    @Test
    void aLoopIsAlreadyQueuedWhenTheLastFrameShows() throws Exception {
        VideoPlayer p = open(movie(5)); // 0.5 s
        p.setLooping(true);
        AtomicInteger loops = new AtomicInteger();
        p.onLoop(loops::incrementAndGet);
        assertTrue(pump(p, () -> Math.round(p.getFrameTime() * 10) == 4), "the last frame of the first pass");
        assertEquals(0, loops.get());
        assertTrue(p.queuedFrames() > 0, "the next pass was decoded before the first one ended, so there is no gap");
        assertTrue(pump(p, () -> loops.get() == 1));
        assertTrue(p.getPosition() < 0.2, "the position starts over with the new pass: " + p.getPosition());
        assertFalse(p.isBuffering());
    }

    @Test
    void aLoopNeverStopsToBuffer() throws Exception {
        VideoPlayer p = open(movie(5));
        p.setLooping(true);
        AtomicInteger loops = new AtomicInteger(), stalls = new AtomicInteger();
        p.onLoop(loops::incrementAndGet);
        assertTrue(pump(p, () -> {
            if (p.isBuffering() && p.getFrame() != null) stalls.incrementAndGet();
            return loops.get() >= 4;
        }));
        assertEquals(0, stalls.get());
        assertShows(p, (int) Math.round(p.getFrameTime() * 10));
    }

    @Test
    void turningLoopingOffMidwayStopsAtTheEnd() throws Exception {
        VideoPlayer p = open(movie(5));
        p.setLooping(true);
        AtomicInteger loops = new AtomicInteger(), finishes = new AtomicInteger();
        p.onLoop(loops::incrementAndGet).onFinish(finishes::incrementAndGet);
        assertTrue(pump(p, () -> loops.get() >= 1));
        p.setLooping(false);
        assertTrue(pump(p, p::isFinished));
        assertEquals(1, finishes.get());
        assertTrue(loops.get() <= 2, "no more than the pass already under way: " + loops.get());
    }

    @Test
    void stopReturnsToTheFirstFrame() throws Exception {
        VideoPlayer p = open(movie(20));
        assertTrue(pump(p, () -> p.getPosition() > 0.8));
        p.stop();
        assertFalse(p.isPlaying());
        assertTrue(pump(p, () -> Math.round(p.getFrameTime() * 10) == 0 && p.queuedFrames() > 0));
        assertShows(p, 0);
        assertEquals(0.0, p.getPosition(), 1e-9);
    }

    @Test
    void speedScalesTheClock() throws Exception {
        VideoPlayer p = open(movie(20));
        p.setSpeed(2f);
        assertTrue(pump(p, () -> p.getFrame() != null));
        double before = p.getPosition();
        // buffered frames are ready, so each update advances by exactly step * speed
        for (int i = 0; i < 3; i++) p.update(STEP);
        assertEquals(before + 3 * STEP * 2, p.getPosition(), 0.15 * 2);
    }

    @Test
    void decodedPicturesWaitInABoundedQueue() throws Exception {
        VideoPlayer p = open(new AviBuilder(64, 64, 30, true).frames(400)); // 400 frames, never played
        p.pause();
        Thread.sleep(500);
        assertTrue(p.queuedFrames() <= 100, "decode ran ahead without bound: " + p.queuedFrames());
        assertTrue(p.queuedFrames() >= 4);
    }

    @Test
    void uncompressedVideoPlays() throws Exception {
        VideoPlayer p = open(new AviBuilder(16, 16, 10, true).frames(3));
        assertEquals("Uncompressed", p.getInfo().videoCodec());
        assertTrue(pump(p, p::isFinished));
        assertNotNull(p.getFrame());
    }

    @Test
    void closeStopsTheDecodeThread() throws Exception {
        VideoPlayer p = open(movie(20));
        assertTrue(pump(p, () -> p.getFrame() != null));
        p.close();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("RenGL-Video-") && t.isAlive()
                && t.getName().endsWith(p.getFile().getName())));
        assertNull(p.getFrame());
        p.close(); // twice is fine
        p.update(STEP); // and so is updating afterwards
    }

    @Test
    void unsupportedCodecIsExplained() throws Exception {
        byte[] bytes = movie(3).build();
        // Rewrite the codec fourcc in the headers from MJPG to H264.
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 'M' && bytes[i + 1] == 'J' && bytes[i + 2] == 'P' && bytes[i + 3] == 'G') {
                bytes[i] = 'H';
                bytes[i + 1] = '2';
                bytes[i + 2] = '6';
                bytes[i + 3] = '4';
            }
        }
        File f = new File(dir, "h264.avi");
        java.nio.file.Files.write(f.toPath(), bytes);
        var e = assertThrows(UnsupportedOperationException.class, () -> VideoPlayer.open(f));
        assertTrue(e.getMessage().contains("H264"), e.getMessage());
    }

    @Test
    void playsAndSeeksMp4FilesLikeAvi() throws Exception {
        for (Mp4Builder.Layout layout : Mp4Builder.Layout.values()) {
            Mp4Builder b = new Mp4Builder(32, 32, 10, 20);
            b.layout = layout;
            b.keyEvery = 5;
            b.audio = false; // tests never play sound on the real speakers
            VideoPlayer p = VideoPlayer.open(b.write(dir, layout + ".mp4"));
            players.add(p);
            assertEquals("MP4", p.getInfo().container());
            assertTrue(pump(p, p::isFinished), layout + " plays to the end");
            assertShows(p, 19);
            for (int target : new int[]{12, 3, 17}) {
                p.seek(target / 10.0 + 0.03);
                assertTrue(pump(p, () -> Math.round(p.getFrameTime() * 10) == target), layout + " seek to " + target);
                assertShows(p, target);
            }
        }
    }

    @Test
    void playsAndSeeksH264ThroughTheSystemDecoder() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MacMediaSource.available(), "no system media framework on this OS");
        VideoPlayer p = VideoPlayer.open(MacMediaSourceTest.fixture("h264-640x360.mp4")); // no soundtrack: stays silent
        players.add(p);
        assertEquals("H.264", p.getInfo().videoCodec());
        assertTrue(pump(p, p::isFinished), "plays to the end");
        assertShows(p, 89);
        for (int target : new int[]{45, 12, 70}) {
            p.seek(target / 30.0 + 0.01);
            assertTrue(pump(p, () -> Math.round(p.getFrameTime() * 30) == target), "seek to " + target);
            assertShows(p, target);
        }
    }
}
