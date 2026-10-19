package me.piitex.engine.audio;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class Mp3GaplessTest {
    private static final int FRAME_BYTES = 417; // 128 kbit/s, 44.1 kHz, no padding

    private static byte[] frameHeader() {
        return new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00}; // MPEG-1 Layer III, 128k, 44.1k, stereo
    }

    /** A frame of silence: a header and zeros, which decodes as 1152 samples of nothing. */
    private static byte[] silentFrame() {
        byte[] frame = new byte[FRAME_BYTES];
        System.arraycopy(frameHeader(), 0, frame, 0, 4);
        return frame;
    }

    /** The encoder's tag frame, with a frame count and LAME's delay and padding. */
    private static byte[] tagFrame(String kind, int frames, int delay, int padding) {
        byte[] frame = silentFrame();
        int at = 4 + 32;
        System.arraycopy(kind.getBytes(), 0, frame, at, 4);
        frame[at + 7] = 1; // flags: the frame count follows
        frame[at + 8] = (byte) (frames >> 24);
        frame[at + 9] = (byte) (frames >> 16);
        frame[at + 10] = (byte) (frames >> 8);
        frame[at + 11] = (byte) frames;
        int lame = at + 12;
        System.arraycopy("LAME3.100".getBytes(), 0, frame, lame, 9);
        frame[lame + 21] = (byte) (delay >> 4);
        frame[lame + 22] = (byte) ((delay & 0xF) << 4 | (padding >> 8));
        frame[lame + 23] = (byte) padding;
        return frame;
    }

    private static byte[] file(byte[] first, int audioFrames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(first);
        for (int i = 0; i < audioFrames; i++) out.writeBytes(silentFrame());
        return out.toByteArray();
    }

    private static long drain(PcmStream stream) {
        short[] buffer = new short[4096];
        long total = 0;
        int n;
        while ((n = stream.read(buffer, 0, buffer.length)) > 0) total += n;
        return total;
    }

    @Test
    void theTagGivesTheDelayPaddingAndFrameCount() {
        Mp3Gapless tag = Mp3Gapless.parse(file(tagFrame("Info", 10, 576, 1000), 10));
        assertNotNull(tag);
        assertTrue(tag.hasDelay());
        assertEquals(576, tag.delay());
        assertEquals(1000, tag.padding());
        assertEquals(10, tag.frames());
        assertEquals(1152, tag.samplesPerFrame());
        assertEquals(576 + 529, tag.skip());
        assertEquals(10L * 1152 - 576 - 1000, tag.length());
    }

    @Test
    void anOrdinaryFirstFrameHasNoTag() {
        assertNull(Mp3Gapless.parse(file(silentFrame(), 4)));
        assertNull(Mp3Gapless.parse(new byte[]{1, 2, 3}));
    }

    @Test
    void anId3TagBeforeTheFramesIsSkipped() {
        byte[] id3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 20};
        byte[] body = file(tagFrame("Xing", 5, 1105, 200), 5);
        byte[] all = new byte[id3.length + 20 + body.length];
        System.arraycopy(id3, 0, all, 0, id3.length);
        System.arraycopy(body, 0, all, id3.length + 20, body.length);
        Mp3Gapless tag = Mp3Gapless.parse(all);
        assertNotNull(tag);
        assertEquals(1105, tag.delay());
    }

    @Test
    void aTagWithoutLameFieldsOnlyDropsItsFrame() {
        byte[] frame = tagFrame("Info", 6, 0, 0);
        java.util.Arrays.fill(frame, 4 + 32 + 12, 4 + 32 + 12 + 9, (byte) 0); // no encoder name
        Mp3Gapless tag = Mp3Gapless.parse(file(frame, 6));
        assertNotNull(tag);
        assertFalse(tag.hasDelay());
        assertEquals(0, tag.skip());
        assertEquals(-1, tag.length());
    }

    @Test
    void theStreamHasTheTracksTrueLength() {
        int frames = 20, delay = 576, padding = 700;
        byte[] bytes = file(tagFrame("Info", frames, delay, padding), frames);
        try (PcmStream stream = AudioDecoder.open("test.mp3", bytes)) {
            long expected = (long) frames * 1152 - delay - padding;
            assertEquals(expected, stream.totalFrames());
            assertEquals(2, stream.channels());
            assertEquals(44100, stream.sampleRate());
            assertEquals(expected * 2, drain(stream), "the tag frame, the delay and the padding are not played");
        }
    }

    @Test
    void seekingToTheStartPlaysTheSameLengthAgain() {
        int frames = 12;
        byte[] bytes = file(tagFrame("Info", frames, 576, 600), frames);
        try (PcmStream stream = AudioDecoder.open("test.mp3", bytes)) {
            long first = drain(stream);
            stream.seek(0);
            assertEquals(first, drain(stream), "a loop plays the same length every time");
            stream.seek(1000);
            assertEquals(first - 2000, drain(stream));
        }
    }

    @Test
    void aFileWithoutATagPlaysEveryFrame() {
        byte[] bytes = file(silentFrame(), 9);
        try (PcmStream stream = AudioDecoder.open("test.mp3", bytes)) {
            assertEquals(-1, stream.totalFrames());
            assertEquals(10L * 1152 * 2, drain(stream));
        }
    }
}
