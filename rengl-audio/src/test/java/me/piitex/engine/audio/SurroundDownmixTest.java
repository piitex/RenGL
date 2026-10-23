package me.piitex.engine.audio;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** Files with more than two channels are mixed down to stereo instead of being refused. */
class SurroundDownmixTest {
    private static byte[] wav(int channels, short[] samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + samples.length * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(48000)
                .putInt(48000 * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        b.put("data".getBytes()).putInt(samples.length * 2);
        for (short s : samples) b.putShort(s);
        return b.array();
    }

    @Test
    void aSixChannelWavComesOutStereo() {
        // One frame: front left loud, everything else silent.
        Pcm pcm = AudioDecoder.decode("5.1.wav", wav(6, new short[]{20000, 0, 0, 0, 0, 0, 20000, 0, 0, 0, 0, 0}));
        assertEquals(2, pcm.channels());
        assertEquals(2, pcm.frames());
        assertTrue(pcm.samples()[0] > 5000, "front left is heard on the left");
        assertEquals(0, pcm.samples()[1], "and not on the right");
    }

    @Test
    void theCentreIsHeardOnBothSidesAndTheBassChannelIsDropped() {
        short[] frame = {0, 0, 20000, 20000, 0, 0}; // centre and LFE only
        short[] out = Downmix.toStereo(frame, 6);
        assertEquals(2, out.length);
        assertEquals(out[0], out[1]);
        assertTrue(out[0] > 3000 && out[0] < 20000);
        short[] lfeOnly = Downmix.toStereo(new short[]{0, 0, 0, 20000, 0, 0}, 6);
        assertEquals(0, lfeOnly[0]);
        assertEquals(0, lfeOnly[1]);
    }

    @Test
    void aFullScaleMixCannotClip() {
        for (int channels = 3; channels <= Downmix.MAX_CHANNELS; channels++) {
            short[] loud = new short[channels];
            java.util.Arrays.fill(loud, Short.MAX_VALUE);
            for (short s : Downmix.toStereo(loud, channels)) assertTrue(s >= 0 && s <= Short.MAX_VALUE, channels + " channels: " + s);
        }
    }

    @Test
    void aStreamMixesAsItIsRead() {
        short[] samples = {10000, 0, 0, 0, 0, 0, 0, 10000, 0, 0, 0, 0};
        Pcm six = new Pcm(samples, 6, 44100);
        PcmStream stream = Downmix.stream(six.stream());
        assertEquals(2, stream.channels());
        short[] out = new short[4];
        assertEquals(4, stream.read(out, 0, 4));
        assertTrue(out[0] > 0 && out[1] == 0);
        assertTrue(out[2] == 0 && out[3] > 0);
        assertEquals(2, stream.totalFrames());
    }

    @Test
    void moreThanEightChannelsAreStillRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AudioDecoder.decode("big.wav", wav(9, new short[9])));
        assertTrue(e.getMessage().contains("9 channels"), e.getMessage());
    }
}
