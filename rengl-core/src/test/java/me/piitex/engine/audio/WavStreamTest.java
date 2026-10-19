package me.piitex.engine.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WavStreamTest {
    /** A WAV with a LIST chunk before the data, so chunk walking is exercised. */
    private static byte[] wav(int format, int channels, int bits, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(12 + 8 + 16 + 8 + 4 + 8).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(0).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) format).putShort((short) channels).putInt(8000)
                .putInt(8000 * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        header.put("LIST".getBytes()).putInt(4).put("abcd".getBytes());
        header.put("data".getBytes()).putInt(data.length);
        out.writeBytes(header.array());
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static byte[] shorts(short... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : values) b.putShort(v);
        return b.array();
    }

    private static short[] readAll(PcmStream stream) {
        short[] all = new short[0];
        short[] buffer = new short[7]; // an awkward size on purpose
        int n;
        while ((n = stream.read(buffer, 0, buffer.length)) > 0) {
            short[] grown = java.util.Arrays.copyOf(all, all.length + n);
            System.arraycopy(buffer, 0, grown, all.length, n);
            all = grown;
        }
        return all;
    }

    @Test
    void sixteenBitComesBackAsWritten() {
        byte[] file = wav(1, 2, 16, shorts((short) 1, (short) -2, (short) 300, (short) -400, (short) 5, (short) 6));
        try (PcmStream stream = AudioDecoder.open("a.wav", file)) {
            assertEquals(2, stream.channels());
            assertEquals(8000, stream.sampleRate());
            assertEquals(3, stream.totalFrames());
            assertArrayEquals(new short[]{1, -2, 300, -400, 5, 6}, readAll(stream));
        }
    }

    @Test
    void eightBitAndFloatAreConvertedTo16() {
        try (PcmStream stream = AudioDecoder.open("a.wav", wav(1, 1, 8, new byte[]{(byte) 128, (byte) 255, 0}))) {
            assertArrayEquals(new short[]{0, 127 << 8, -128 << 8}, readAll(stream));
        }
        ByteBuffer floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(-0.5f);
        try (PcmStream stream = AudioDecoder.open("a.wav", wav(3, 1, 32, floats.array()))) {
            assertArrayEquals(new short[]{32767, -16383}, readAll(stream));
        }
    }

    @Test
    void seekGoesBackToAFrame() {
        byte[] file = wav(1, 2, 16, shorts((short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6));
        try (PcmStream stream = AudioDecoder.open("a.wav", file)) {
            readAll(stream);
            stream.seek(1);
            assertArrayEquals(new short[]{3, 4, 5, 6}, readAll(stream));
            stream.seek(0);
            assertArrayEquals(new short[]{1, 2, 3, 4, 5, 6}, readAll(stream));
        }
    }

    @Test
    void aTruncatedDataChunkEndsOnAWholeFrame() {
        byte[] file = wav(1, 2, 16, shorts((short) 1, (short) 2, (short) 3));
        try (PcmStream stream = AudioDecoder.open("a.wav", file)) {
            assertArrayEquals(new short[]{1, 2}, readAll(stream), "the half frame at the end is dropped");
        }
    }

    @Test
    void surroundIsMixedDownToStereo() {
        short[] six = new short[6 * 4];
        for (int i = 0; i < six.length; i++) six[i] = (short) (i % 6 == 2 ? 1000 : 0);
        try (PcmStream stream = AudioDecoder.open("a.wav", wav(1, 6, 16, shorts(six)))) {
            assertEquals(2, stream.channels());
            assertEquals(8, readAll(stream).length);
        }
    }

    @Test
    void aFileOnDiskPlaysFromDiskAndMatchesTheInMemoryDecode(@TempDir Path dir) throws IOException {
        short[] samples = new short[20000];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (i * 7);
        byte[] file = wav(1, 2, 16, shorts(samples));
        Path path = dir.resolve("long.wav");
        Files.write(path, file);
        assertTrue(AudioDecoder.isWavFile(path.toString()));
        assertFalse(AudioDecoder.isWavFile(dir.resolve("missing.wav").toString()));
        try (PcmStream fromDisk = AudioDecoder.openWavFile("long.wav", path.toString());
             PcmStream fromMemory = AudioDecoder.open("long.wav", file)) {
            assertEquals(10000, fromDisk.totalFrames());
            assertArrayEquals(readAll(fromMemory), readAll(fromDisk));
            fromDisk.seek(5000);
            short[] tail = readAll(fromDisk);
            assertEquals(10000, tail.length);
            assertEquals(samples[10000], tail[0]);
        }
    }

    @Test
    void aFullDecodeStillWorksForSounds() {
        byte[] file = wav(1, 1, 16, shorts((short) 9, (short) 8, (short) 7));
        Pcm pcm = AudioDecoder.decode("a.wav", file);
        assertArrayEquals(new short[]{9, 8, 7}, pcm.samples());
        assertEquals(1, pcm.channels());
    }

    @Test
    void badFilesAreRefusedWithAMessage() {
        assertThrows(IllegalArgumentException.class, () -> AudioDecoder.open("a.wav", wav(2, 1, 16, new byte[4])));
        byte[] noData = wav(1, 1, 16, new byte[0]);
        noData[noData.length - 8] = 'x'; // the data chunk is no longer called data
        assertThrows(IllegalArgumentException.class, () -> AudioDecoder.open("a.wav", noData));
    }
}
