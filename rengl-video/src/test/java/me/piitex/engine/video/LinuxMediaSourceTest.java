package me.piitex.engine.video;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything of the Linux source that is not a call into GStreamer, run against a pipeline that is made up. The calls
 * themselves ({@link GStreamer}, {@link GstPipeline}) can only be tried on Linux, where {@link SystemMediaSourceTest} runs
 * the same real files the other systems' decoders are tried with.
 */
class LinuxMediaSourceTest {
    private static final File FILE = new File("clip.mp4");
    private static final GstPlayback.Format STEREO = new GstPlayback.Format(640, 360, 30, 3.0, "H.264 / AVC", 2, 44100, "MPEG-4 AAC");
    private static final GstPlayback.Format SILENT = new GstPlayback.Format(640, 360, 30, 3.0, "H.264 / AVC", 0, 0, null);

    private static final class Fake implements GstPlayback {
        final Format format;
        final ArrayDeque<Sample> video = new ArrayDeque<>(), audio = new ArrayDeque<>();
        int hiddenPulls;          // pulls that find nothing before the queues show their samples
        int waits;                // pulls that were asked to wait
        double sought = -1;
        Consumer<Fake> afterSeek = f -> {
        };
        String failure;
        int closes;

        Fake(Format format) {
            this.format = format;
        }

        Fake video(double... pts) {
            for (double p : pts) video.add(new Sample(new byte[640 * 360 * 4], p));
            return this;
        }

        Fake audio(double... pts) {
            for (double p : pts) audio.add(new Sample(new byte[4410 * 4], p)); // a tenth of a second of 44.1 kHz stereo
            return this;
        }

        private Sample take(ArrayDeque<Sample> queue, int millis) {
            if (millis > 0) waits++;
            if (hiddenPulls > 0) {
                if (millis > 0) hiddenPulls--;
                return null;
            }
            return queue.poll();
        }

        @Override
        public Format format() {
            return format;
        }

        @Override
        public Sample pullVideo(int millis) {
            return take(video, millis);
        }

        @Override
        public Sample pullAudio(int millis) {
            return take(audio, millis);
        }

        @Override
        public boolean videoEnded() {
            return video.isEmpty() && hiddenPulls == 0 && failure == null;
        }

        @Override
        public boolean audioEnded() {
            return format.audioChannels() <= 0 || (audio.isEmpty() && hiddenPulls == 0 && failure == null);
        }

        @Override
        public void seek(double seconds) {
            sought = seconds;
            video.clear();
            audio.clear();
            afterSeek.accept(this);
        }

        @Override
        public String failure() {
            return failure;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static List<String> drain(Demuxer d) throws IOException {
        List<String> out = new ArrayList<>();
        Packet p;
        while ((p = d.next()) != null) out.add((p.stream() == d.videoStream() ? "v" : "a") + String.format("%.3f", p.pts()));
        return out;
    }

    @Test
    void infoComesFromTheFormat() {
        MediaInfo info = new LinuxMediaSource(FILE, new Fake(STEREO)).info();
        assertEquals(640, info.width());
        assertEquals(360, info.height());
        assertEquals(30, info.frameRate(), 1e-9);
        assertEquals(3.0, info.duration(), 1e-9);
        assertEquals("H.264", info.videoCodec());
        assertEquals("AAC", info.audioCodec());
        assertEquals(2, info.audioChannels());
        assertEquals(44100, info.audioRate());
        assertEquals("OS (GStreamer)", info.container());
        assertTrue(info.hasAudio());
    }

    @Test
    void aFileWithoutSoundHasNoAudioTrack() throws Exception {
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, new Fake(SILENT).video(0, 0.033, 0.066))) {
            assertEquals(-1, d.audioStream());
            assertNull(d.audioDecoder());
            assertFalse(d.info().hasAudio());
            assertNull(d.info().audioCodec());
            assertEquals(List.of("v0.000", "v0.033", "v0.066"), drain(d));
        }
    }

    @Test
    void pictureAndSoundComeOutInTimeOrder() throws Exception {
        Fake f = new Fake(STEREO).video(0, 0.033, 0.066).audio(0, 0.02, 0.04, 0.06);
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            assertEquals(List.of("v0.000", "a0.000", "a0.020", "v0.033", "a0.040", "a0.060", "v0.066"), drain(d));
        }
    }

    @Test
    void theDecodersMatchWhatThePipelineDelivers() throws Exception {
        Fake f = new Fake(STEREO).video(0).audio(0);
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            Packet v = d.next();
            VideoFrame frame = d.videoDecoder().decode(v);
            assertEquals(640, frame.width());
            assertEquals(360, frame.height());
            assertEquals(640 * 360 * 4, frame.pixels().length);
            AudioTrackDecoder audio = d.audioDecoder();
            assertEquals(2, audio.channels());
            assertEquals(44100, audio.sampleRate());
            assertEquals(4410 * 2, audio.decode(d.next()).length);
        }
    }

    @Test
    void timesAreCountedWhenThePipelineGivesNone() throws Exception {
        GstPlayback.Format quick = new GstPlayback.Format(64, 36, 25, 1.0, null, 2, 48000, null);
        Fake f = new Fake(quick).video(-1, -1, -1);
        f.audio.add(new GstPlayback.Sample(new byte[4800], -1)); // 1200 frames of 16-bit stereo, 25 ms at 48 kHz
        f.audio.add(new GstPlayback.Sample(new byte[4800], -1));
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            assertEquals("Video", d.info().videoCodec());
            assertEquals("Audio", d.info().audioCodec());
            assertEquals(List.of("v0.000", "a0.000", "a0.025", "v0.040", "v0.080"), drain(d));
        }
    }

    @Test
    void aTimeThatStopsRisingIsNotBelieved() throws Exception {
        GstPlayback.Format slow = new GstPlayback.Format(64, 36, 10, 1.0, null, 0, 0, null);
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, new Fake(slow).video(0, 0, 0, 0.35, 0.3))) {
            assertEquals(List.of("v0.000", "v0.100", "v0.200", "v0.350", "v0.450"), drain(d));
        }
    }

    @Test
    void waitsForAPipelineThatIsStillDecoding() throws Exception {
        Fake f = new Fake(SILENT).video(0.5);
        f.hiddenPulls = 3;
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            Packet p = d.next();
            assertNotNull(p);
            assertEquals(0.5, p.pts(), 1e-9);
            assertTrue(f.waits >= 3, "waited " + f.waits + " times");
            assertNull(d.next());
        }
    }

    @Test
    void endsOnlyWhenBothStreamsHave() throws Exception {
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, new Fake(STEREO).audio(0, 0.1))) {
            assertEquals(List.of("a0.000", "a0.100"), drain(d));
        }
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, new Fake(STEREO).video(0, 0.033))) {
            assertEquals(List.of("v0.000", "v0.033"), drain(d));
        }
    }

    @Test
    void aPipelineThatFailedWhileNothingWasReadyIsAnError() {
        Fake f = new Fake(STEREO);
        f.failure = "Internal data stream error.";
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            IOException e = assertThrows(IOException.class, d::next);
            assertTrue(e.getMessage().contains("Internal data stream error"), e.getMessage());
            assertTrue(e.getMessage().contains("clip.mp4"), e.getMessage());
        }
    }

    @Test
    void seekRestartsBothStreams() throws Exception {
        Fake f = new Fake(STEREO).video(0, 0.033).audio(0);
        f.afterSeek = g -> g.video(1.4, 1.433).audio(1.4);
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            assertEquals(List.of("v0.000", "a0.000", "v0.033"), drain(d)); // both streams have ended
            d.seek(1.5);
            assertEquals(1.5, f.sought, 1e-9);
            assertEquals(List.of("v1.400", "a1.400", "v1.433"), drain(d));
        }
    }

    @Test
    void aSeekCountsTimeFromTheTargetUntilTheFirstTimeArrives() throws Exception {
        Fake f = new Fake(SILENT).video(0, 0.033);
        f.afterSeek = g -> g.video(-1, -1);
        try (LinuxMediaSource d = new LinuxMediaSource(FILE, f)) {
            drain(d);
            d.seek(2.0);
            assertEquals(List.of("v2.000", "v2.033"), drain(d));
        }
    }

    @Test
    void closeClosesThePipelineOnce() {
        Fake f = new Fake(STEREO);
        LinuxMediaSource d = new LinuxMediaSource(FILE, f);
        d.close();
        d.close();
        assertEquals(1, f.closes);
    }

    @Test
    void aFormatWithoutAPictureSizeIsRejectedAndClosed() {
        Fake f = new Fake(new GstPlayback.Format(0, 0, 30, 1, null, 0, 0, null));
        assertThrows(UnsupportedOperationException.class, () -> new LinuxMediaSource(FILE, f));
        assertEquals(1, f.closes);
    }

    @Test
    void codecsGetShortNames() {
        assertEquals("H.264", LinuxMediaSource.codecName("H.264 / AVC", true));
        assertEquals("H.264", LinuxMediaSource.codecName("H.264 (High Profile)", true));
        assertEquals("H.265", LinuxMediaSource.codecName("H.265 / HEVC (Main Profile)", true));
        assertEquals("VP9", LinuxMediaSource.codecName("VP9", true));
        assertEquals("AV1", LinuxMediaSource.codecName("AV1", true));
        assertEquals("MJPEG", LinuxMediaSource.codecName("Motion JPEG", true));
        assertEquals("MPEG-4", LinuxMediaSource.codecName("MPEG-4 Video", true));
        assertEquals("Something", LinuxMediaSource.codecName("Something", true));
        assertEquals("Video", LinuxMediaSource.codecName(null, true));
        assertEquals("AAC", LinuxMediaSource.codecName("MPEG-4 AAC", false));
        assertEquals("MP3", LinuxMediaSource.codecName("MPEG-1 Layer 3 (MP3)", false));
        assertEquals("Opus", LinuxMediaSource.codecName("Opus", false));
        assertEquals("Vorbis", LinuxMediaSource.codecName("Vorbis", false));
        assertEquals("AC-3", LinuxMediaSource.codecName("AC-3 (ATSC A/52)", false));
        assertEquals("PCM", LinuxMediaSource.codecName("Uncompressed 16-bit PCM audio", false));
        assertEquals("Audio", LinuxMediaSource.codecName("  ", false));
    }

    // ------------------------------------------------------------------ the parts of the pipeline that are not native calls

    @Test
    void aPathBecomesAFileAddress() {
        String uri = GstPipeline.uriOf(new File("clips/a b.mp4").getAbsoluteFile());
        assertTrue(uri.startsWith("file:///"), uri);
        assertTrue(uri.endsWith("/clips/a%20b.mp4"), uri);
    }

    @Test
    void timesInNanosecondsBecomeSecondsOrNothing() {
        assertEquals(2.5, GstPipeline.seconds(2_500_000_000L, 10), 1e-9);
        assertEquals(0, GstPipeline.seconds(0, 10), 0);
        assertEquals(-1, GstPipeline.seconds(-1L, 10), 0);                 // GST_CLOCK_TIME_NONE
        assertEquals(-1, GstPipeline.seconds(Long.MAX_VALUE, 10), 0);
        assertEquals(-1, GstPipeline.seconds(100_000_000_000L, 3), 0);     // 100 s in a 3 s file
        assertEquals(50, GstPipeline.seconds(50_000_000_000L, 3), 0);      // a minute of slack
        assertEquals(1000, GstPipeline.seconds(1_000_000_000_000L, 0), 0); // length unknown
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void aTightBufferIsCopiedAsItIs() {
        byte[] in = bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16); // 2 x 2 pixels
        byte[] out = new byte[16];
        assertTrue(GstPipeline.copyPicture(MemorySegment.ofArray(in), 2, 2, out));
        assertArrayEquals(in, out);
    }

    @Test
    void rowsPaddedToACommonPitchAreCutBackToTheWidth() {
        byte[] in = bytes(1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0, 9, 10, 11, 12, 13, 14, 15, 16, 0, 0, 0, 0); // pitch 12
        byte[] out = new byte[16];
        assertTrue(GstPipeline.copyPicture(MemorySegment.ofArray(in), 2, 2, out));
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), out);
    }

    @Test
    void paddingAfterTheLastRowIsLeftOut() {
        byte[] in = new byte[20]; // 20 / 2 rows = 10, not a whole pixel row: the extra four bytes trail the picture
        for (int i = 0; i < 16; i++) in[i] = (byte) (i + 1);
        byte[] out = new byte[16];
        assertTrue(GstPipeline.copyPicture(MemorySegment.ofArray(in), 2, 2, out));
        assertArrayEquals(java.util.Arrays.copyOf(in, 16), out);
    }

    @Test
    void aBufferTooSmallForThePictureIsRefused() {
        assertFalse(GstPipeline.copyPicture(MemorySegment.ofArray(new byte[15]), 2, 2, new byte[16]));
        assertFalse(GstPipeline.copyPicture(MemorySegment.ofArray(new byte[16]), 0, 2, new byte[16]));
    }

    @Test
    void thereIsNoGStreamerOffLinux() {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("linux"));
        assertFalse(GStreamer.available());
        assertFalse(LinuxMediaSource.available());
    }
}
