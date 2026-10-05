package me.piitex.engine.video;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class VideoDecodersTest {
    private static int pixel(VideoFrame f, int x, int y) {
        int p = (y * f.width() + x) * 4;
        return ((f.pixels()[p + 2] & 0xFF) << 16) | ((f.pixels()[p + 1] & 0xFF) << 8) | (f.pixels()[p] & 0xFF);
    }

    @Test
    void standardHuffmanTablesMatchTheOnesJavaWrites() throws IOException {
        byte[] jpeg = new AviBuilder(16, 16, 10, false).jpeg(0x808080);
        // Collect every DHT segment before the scan, which is how the JDK writes its (standard) tables.
        ByteArrayOutputStream dht = new ByteArrayOutputStream();
        int i = 2;
        while ((jpeg[i + 1] & 0xFF) != 0xDA) {
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if ((jpeg[i + 1] & 0xFF) == 0xC4) dht.write(jpeg, i, 2 + len);
            i += 2 + len;
        }
        assertEquals(4, MjpegDecoder.standardTables().length > 0 ? 4 : 0);
        byte[] java = dht.toByteArray();
        assertEquals(java.length, MjpegDecoder.standardTables().length);
        // The order of segments may differ, but each table must appear.
        for (int offset = 0; offset < java.length; ) {
            int len = 2 + (((java[offset + 2] & 0xFF) << 8) | (java[offset + 3] & 0xFF));
            byte[] segment = Arrays.copyOfRange(java, offset, offset + len);
            assertTrue(contains(MjpegDecoder.standardTables(), segment), "missing table class/id " + (segment[4] & 0xFF));
            offset += len;
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    @Test
    void jpegWithoutHuffmanTablesStillDecodes() throws IOException {
        byte[] jpeg = new AviBuilder(32, 32, 10, false).jpeg(0x2020E0);
        // Cut out the DHT segments, as a capture card's MJPEG does.
        ByteArrayOutputStream stripped = new ByteArrayOutputStream();
        stripped.write(jpeg, 0, 2);
        int i = 2;
        while ((jpeg[i + 1] & 0xFF) != 0xDA) {
            int len = 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
            if ((jpeg[i + 1] & 0xFF) != 0xC4) stripped.write(jpeg, i, len);
            i += len;
        }
        stripped.write(jpeg, i, jpeg.length - i);
        byte[] without = stripped.toByteArray();
        assertTrue(without.length < jpeg.length);

        VideoFrame frame = new MjpegDecoder().decode(new Packet(0, 0, true, without));
        assertEquals(32, frame.width());
        assertTrue(MjpegColor.near(0x2020E0, MjpegColor.center(frame)));
    }

    @Test
    void corruptJpegIsAnErrorNotACrash() {
        assertThrows(IOException.class, () -> new MjpegDecoder().decode(new Packet(0, 0, true, new byte[]{1, 2, 3, 4})));
    }

    @Test
    void uncompressedFramesAreFlippedUpright() throws IOException {
        AviBuilder b = new AviBuilder(6, 4, 10, true); // 6 px wide: rows are padded from 18 to 20 bytes
        VideoFrame f = new RawVideoDecoder(6, 4, 24, false).decode(new Packet(0, 0.5, true, b.rawFrame(0xE02010)));
        assertEquals(0.5, f.pts());
        assertEquals(0xE02010, pixel(f, 2, 0), "top row is the picture's top");
        assertEquals(0xE02010, pixel(f, 5, 1));
        assertEquals(0x000000, pixel(f, 0, 3), "bottom row");
        assertEquals(255, f.pixels()[3] & 0xFF);
    }

    @Test
    void uncompressed32BitKeepsAlphaPremultiplied() throws IOException {
        byte[] frame = new byte[]{(byte) 200, 100, 50, (byte) 128}; // one BGRA pixel at half alpha
        VideoFrame f = new RawVideoDecoder(1, 1, 32, true).decode(new Packet(0, 0, true, frame));
        assertEquals(128, f.pixels()[3] & 0xFF);
        assertEquals((200 * 128 + 127) / 255, f.pixels()[0] & 0xFF);
        // All-zero alpha is how writers say "no alpha": the picture is opaque.
        byte[] noAlpha = new byte[]{(byte) 200, 100, 50, 0};
        VideoFrame g = new RawVideoDecoder(1, 1, 32, true).decode(new Packet(0, 0, true, noAlpha));
        assertEquals(255, g.pixels()[3] & 0xFF);
        assertEquals(200, g.pixels()[0] & 0xFF);
    }

    @Test
    void truncatedUncompressedFrameIsRejected() {
        assertThrows(IOException.class, () -> new RawVideoDecoder(8, 8, 24, false).decode(new Packet(0, 0, true, new byte[10])));
    }

    @Test
    void pcmAudioConvertsAndDropsExtraChannels() {
        var d16 = new PcmAudioDecoder(2, 44100, 16, false);
        short[] out = d16.decode(new Packet(0, 0, true, new byte[]{1, 0, (byte) 0xFF, (byte) 0xFF, 2, 0, 3, 0}));
        assertArrayEquals(new short[]{1, -1, 2, 3}, out);

        var d8 = new PcmAudioDecoder(1, 8000, 8, false);
        assertArrayEquals(new short[]{0, 127 << 8, -128 << 8}, d8.decode(new Packet(0, 0, true, new byte[]{(byte) 128, (byte) 255, 0})));

        var surround = new PcmAudioDecoder(6, 48000, 16, false);
        assertEquals(2, surround.channels());
        byte[] frame = new byte[12];
        frame[0] = 7;
        frame[2] = 9;
        assertArrayEquals(new short[]{7, 9}, surround.decode(new Packet(0, 0, true, frame)));

        var f32 = new PcmAudioDecoder(1, 48000, 32, true);
        byte[] one = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(-2f).array();
        assertArrayEquals(new short[]{32767, -32767}, f32.decode(new Packet(0, 0, true, one)));
    }
}
