package me.piitex.engine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RenGL splash's sound: each cue starts once, in order, when the timeline passes its moment; a cue whose sound is
 * late waits a little and is then skipped; the fade's sound waits for the warm-ups; and every sound is clean (no clipping,
 * no click at either end). Also writes {@code target/splash-sound-preview.wav}, every cue mixed at its moment, to listen to.
 */
class RenGLSplashSoundTest {
    /**
     * An output that records what was played and when, by the timeline.
     */
    private static final class RecordingOutput implements SoundOutput {
        final List<String> played = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Clip load(String name, short[] samples, int channels, int sampleRate) {
            return new Clip() {
                @Override
                public void play(float volume) {
                    played.add(name);
                }

                @Override
                public void release() {
                    released.add(name);
                }
            };
        }
    }

    /**
     * Runs the splash at 60 frames a second until it is over.
     */
    private static void playThrough(RenGLSplash splash) {
        for (int frame = 0; frame < 10_000 && !splash.isFinished(); frame++) splash.update(1f / 60f);
    }

    @Test
    void playsEveryCueOnceInOrder() {
        RecordingOutput output = new RecordingOutput();
        RenGLSplashSound sound = new RenGLSplashSound(output);
        sound.load();
        RenGLSplash splash = new RenGLSplash();
        splash.playSound(sound);
        playThrough(splash);
        assertEquals(List.of("splash:chord", "splash:rise", "splash:bloom", "splash:chimes", "splash:whoosh"), output.played);
        splash.dispose();
        assertEquals(5, output.released.size());
    }

    @Test
    void startsEachCueTheFrameItsMomentIsPassed() {
        RecordingOutput output = new RecordingOutput();
        RenGLSplashSound sound = new RenGLSplashSound(output);
        sound.load();
        sound.advance(0.19f);
        assertEquals(List.of("splash:chord"), output.played);
        sound.advance(0.21f);
        assertEquals(List.of("splash:chord", "splash:rise"), output.played);
        sound.advance(1.04f);
        assertEquals(2, output.played.size());
        sound.advance(1.06f);
        assertEquals("splash:bloom", output.played.getLast());
    }

    @Test
    void aSoundNotReadyWaitsALittleThenIsSkipped() {
        RecordingOutput output = new RecordingOutput();
        RenGLSplashSound sound = new RenGLSplashSound(output);
        // Nothing is loaded: the chord may wait up to 0.6 s of the timeline, so nothing is given up yet at 0.5 s.
        sound.advance(0.5f);
        assertTrue(output.played.isEmpty());
        // Past every cue's grace, all of them are skipped; loading afterwards plays none of them.
        sound.advance(10f);
        sound.load();
        sound.advance(11f);
        assertTrue(output.played.isEmpty());
    }

    @Test
    void aSoundThatArrivesWithinItsGraceStillPlays() {
        RecordingOutput output = new RecordingOutput();
        RenGLSplashSound sound = new RenGLSplashSound(output);
        sound.advance(0.3f);
        sound.load();
        sound.advance(0.31f);
        // The chord was waiting and is played; the rise was 0.11 s late, past its 0.1 s, and is skipped.
        assertEquals(List.of("splash:chord"), output.played);
        sound.advance(1.1f);
        assertEquals(List.of("splash:chord", "splash:bloom"), output.played);
    }

    @Test
    void noDeviceMeansNoSound() {
        RecordingOutput output = new RecordingOutput();
        output.available = false;
        RenGLSplashSound sound = new RenGLSplashSound(output);
        sound.load();
        sound.advance(10f);
        assertTrue(output.played.isEmpty());
    }

    @Test
    void soundsMadeAfterTheSplashEndedAreFreedAtOnce() {
        RecordingOutput output = new RecordingOutput();
        RenGLSplashSound sound = new RenGLSplashSound(output);
        sound.release();
        sound.load();
        // The first is made, found unwanted and freed, and the rest are not made at all.
        assertEquals(List.of("splash:chord"), output.released);
    }

    @Test
    void theFadesSoundWaitsForTheWarmUps() throws Exception {
        RecordingOutput output = new RecordingOutput();
        RenGLSplashSound sound = new RenGLSplashSound(output);
        sound.load();
        Loading loading = new Loading();
        loading.begin(List.of(WarmUp.of("3D scene", window -> { })), 30f);
        assertTrue(loading.awaitPrepared(5_000));
        RenGLSplash splash = new RenGLSplash();
        splash.watch(loading);
        splash.playSound(sound);
        for (int frame = 0; frame < 600; frame++) splash.update(1f / 60f);
        assertTrue(splash.isHoldingApp());
        assertFalse(output.played.contains("splash:whoosh"), "the fade has not started, so neither has its sound");
        assertEquals(4, output.played.size());

        loading.step(null, null); // the warm-up finishes
        splash.update(1f / 60f);
        assertEquals("splash:whoosh", output.played.getLast(), "the fade and its sound start together");
    }

    @Test
    void everySoundIsCleanAndAsLoudAsItsCueSays() {
        for (int index = 0; index < RenGLSplashSound.CUES.length; index++) {
            RenGLSplashSound.Cue cue = RenGLSplashSound.CUES[index];
            short[] pcm = RenGLSplashSound.synthesize(index);
            assertEquals(0, pcm.length % 2, cue.name() + " is stereo");
            int loudest = 0;
            long leftEnergy = 0, rightEnergy = 0;
            for (int i = 0; i < pcm.length; i += 2) {
                loudest = Math.max(loudest, Math.max(Math.abs(pcm[i]), Math.abs(pcm[i + 1])));
                leftEnergy += (long) pcm[i] * pcm[i];
                rightEnergy += (long) pcm[i + 1] * pcm[i + 1];
            }
            // Scaled so its loudest sample is the cue's peak, to within rounding.
            assertEquals(cue.peak() * Short.MAX_VALUE, loudest, 2, cue.name() + " peak");
            assertTrue(leftEnergy > 0 && rightEnergy > 0, cue.name() + " sounds on both sides");
            // A sound that begins or ends away from zero clicks; the first and last few samples are all near silence.
            for (int i = 0; i < 8; i++) {
                assertTrue(Math.abs(pcm[i]) < 200, cue.name() + " starts quietly, sample " + i + " is " + pcm[i]);
                assertTrue(Math.abs(pcm[pcm.length - 1 - i]) < 200, cue.name() + " ends quietly");
            }
        }
    }

    @Test
    void theInverseEaseFindsWhenAnEasedMovementArrives() {
        for (float x = 0f; x <= 1f; x += 0.125f) {
            float eased = x * x * (3f - 2f * x);
            // Smoothstep is flat at both ends, so there a float's rounding of the eased value moves x by up to ~1e-4;
            // a tenth of a millisecond of a half-second sweep is far below a sample's worth of timing that matters.
            assertEquals(x, RenGLSplashSound.easeInverse(eased), 5e-4f);
        }
    }

    /**
     * Mixes every cue at its moment, as the splash plays them, into one WAV file under target/ to listen to.
     */
    @Test
    void writesAPreviewToListenTo() throws IOException {
        int rate = RenGLSplashSound.RATE;
        short[][] cues = new short[RenGLSplashSound.CUES.length][];
        int frames = 0;
        for (int index = 0; index < cues.length; index++) {
            cues[index] = RenGLSplashSound.synthesize(index);
            int start = Math.round(RenGLSplashSound.CUES[index].at() * rate);
            frames = Math.max(frames, start + cues[index].length / 2);
        }
        int[] mix = new int[frames * 2];
        for (int index = 0; index < cues.length; index++) {
            int start = Math.round(RenGLSplashSound.CUES[index].at() * rate) * 2;
            for (int i = 0; i < cues[index].length; i++) mix[start + i] += cues[index][i];
        }
        ByteBuffer data = ByteBuffer.allocate(mix.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        int loudest = 0;
        for (int sample : mix) {
            loudest = Math.max(loudest, Math.abs(sample));
            data.putShort((short) Math.clamp(sample, Short.MIN_VALUE, Short.MAX_VALUE));
        }
        assertTrue(loudest <= Short.MAX_VALUE, "the cues together never clip: loudest " + loudest);

        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes());
        // A plain PCM format chunk: 16 bytes, format 1, two channels, the rate, bytes per second, 4 bytes a frame, 16 bits.
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4)
                .putShort((short) 4).putShort((short) 16);
        header.put("data".getBytes()).putInt(data.capacity());
        wav.write(header.array());
        wav.write(data.array());
        Path out = Path.of("target", "splash-sound-preview.wav");
        Files.createDirectories(out.getParent());
        Files.write(out, wav.toByteArray());
    }
}
