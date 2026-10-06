package me.piitex.engine.video;

import me.piitex.engine.audio.Audio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The soundtrack path: sound is the clock while it plays. Needs OpenAL, so it only runs when the audio device is the
 * silent null driver (run with {@code ALSOFT_DRIVERS=null}), as tests must never play on the real speakers.
 */
class VideoAudioTest {
    @TempDir
    File dir;
    private VideoPlayer player;

    @AfterEach
    void close() {
        if (player != null) player.close();
        Audio.stopAll();
    }

    private static void requireSilentDevice() {
        Assumptions.assumeTrue("null".equals(System.getenv("ALSOFT_DRIVERS")), "set ALSOFT_DRIVERS=null to run audio tests");
        Assumptions.assumeTrue(Audio.isAvailable(), "no OpenAL");
    }

    @Test
    void soundDrivesTheClockAndTheVideoFinishesWithIt() throws Exception {
        requireSilentDevice();
        AviBuilder b = new AviBuilder(32, 32, 10, false).frames(20); // 2 s
        b.tone(2.0);
        player = VideoPlayer.open(b.write(dir, "av.avi"));
        assertTrue(player.hasAudio());
        boolean[] done = {false};
        player.onFinish(() -> done[0] = true);

        long start = System.nanoTime();
        double lastPos = 0;
        while (!done[0] && System.nanoTime() - start < 8_000_000_000L) {
            player.update(1 / 60f);
            assertTrue(player.getPosition() >= lastPos - 1e-9, "the clock never runs backwards: " + lastPos + " then " + player.getPosition());
            lastPos = player.getPosition();
            Thread.sleep(5);
        }
        assertTrue(done[0], "should finish in real time, position " + lastPos);
        double took = (System.nanoTime() - start) / 1e9;
        assertTrue(took > 1.6 && took < 5.0, "2 s of sound should take about 2 s of real time, took " + took);
    }

    @Test
    void pauseStopsTheClockAndSeekRestartsTheSound() throws Exception {
        requireSilentDevice();
        AviBuilder b = new AviBuilder(32, 32, 10, false).frames(40); // 4 s
        b.tone(4.0);
        player = VideoPlayer.open(b.write(dir, "av.avi"));
        long start = System.nanoTime();
        while (player.getPosition() < 0.5 && System.nanoTime() - start < 5_000_000_000L) {
            player.update(1 / 60f);
            Thread.sleep(5);
        }
        assertTrue(player.getPosition() >= 0.5);
        player.pause();
        double held = player.getPosition();
        for (int i = 0; i < 20; i++) {
            player.update(1 / 60f);
            Thread.sleep(5);
        }
        assertEquals(held, player.getPosition(), 0.05, "paused video holds its position");

        player.seek(3.0);
        player.play();
        start = System.nanoTime();
        while (player.getPosition() < 3.2 && System.nanoTime() - start < 5_000_000_000L) {
            player.update(1 / 60f);
            Thread.sleep(5);
        }
        assertTrue(player.getPosition() >= 3.2 && player.getPosition() < 4.0, "position " + player.getPosition());
    }
}
