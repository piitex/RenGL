package me.piitex.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seeking, position and output devices. They need OpenAL, so they only run when the device is the silent null driver
 * (run with {@code ALSOFT_DRIVERS=null}), as tests must never play on the real speakers.
 */
class PlaybackPositionTest {
    @TempDir
    File dir;

    @AfterEach
    void close() {
        Audio.stopAll();
    }

    private static void requireSilentDevice() {
        Assumptions.assumeTrue("null".equals(System.getenv("ALSOFT_DRIVERS")), "set ALSOFT_DRIVERS=null to run audio tests");
        Assumptions.assumeTrue(Audio.isAvailable(), "no OpenAL");
    }

    private File silentWav(double seconds) throws Exception {
        int rate = 22050, frames = (int) (seconds * rate);
        ByteBuffer b = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + frames * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate)
                .putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(frames * 2);
        File file = new File(dir, "silence.wav");
        Files.write(file.toPath(), b.array());
        return file;
    }

    @Test
    void aLoadedSoundSeeksAndReportsWhereItIs() {
        requireSilentDevice();
        Sound sound = Sound.tone(440f, 4f);
        Playback p = sound.play();
        p.pause();
        assertEquals(4.0, p.getDuration(), 0.05);
        p.seek(2.5);
        assertEquals(2.5, p.getPosition(), 0.05);
        p.seek(100); // past the end stays inside the sound
        assertTrue(p.getPosition() <= 4.0);
        p.seek(-3);
        assertEquals(0.0, p.getPosition(), 0.05);
    }

    @Test
    void aStreamedTrackSeeksAndKeepsPlayingFromThere() throws Exception {
        requireSilentDevice();
        Music music = Music.load(silentWav(10).getPath());
        Playback p = music.play();
        p.pause();
        assertEquals(10.0, p.getDuration(), 0.05);
        p.seek(6.0);
        assertEquals(6.0, p.getPosition(), 0.05);
        assertTrue(p.isPaused(), "a paused playback stays paused at the new place");
        p.resume();
        assertTrue(p.isPlaying());
        p.seek(9.9999);
        assertFalse(p.isFinished() && p.getPosition() > 10);
    }

    @Test
    void positionFollowsALoopBackToItsStart() throws Exception {
        requireSilentDevice();
        Music music = Music.load(silentWav(10).getPath());
        music.loopPoints(1f, 3f);
        Playback p = music.loop();
        p.pause();
        p.seek(2.9);
        p.resume();
        Thread.sleep(600);
        double position = p.getPosition();
        assertTrue(position >= 1.0 && position < 3.0, "inside the loop region, at " + position);
    }

    @Test
    void theOutputDeviceCanBeListedAndSwitchedWhilePlaying() {
        requireSilentDevice();
        var devices = Audio.getDevices();
        assertFalse(devices.isEmpty());
        assertNotNull(Audio.getDevice());
        Playback p = Sound.tone(440f, 3f).play();
        assertTrue(Audio.setDevice(devices.get(0)));
        assertFalse(p.isFinished(), "what was playing carries on");
        assertTrue(Audio.setDevice(null));
        assertFalse(Audio.setDevice("no such device here"), "an unknown device leaves things as they were");
        assertFalse(p.isFinished());
    }
}
