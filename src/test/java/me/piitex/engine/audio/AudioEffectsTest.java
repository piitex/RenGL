package me.piitex.engine.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.openal.AL10.*;

/**
 * Cones, occlusion and reverb, read back from OpenAL. They only run on the silent null device
 * ({@code ALSOFT_DRIVERS=null}), as tests must never play on the real speakers.
 */
class AudioEffectsTest {
    @AfterEach
    void clean() {
        Audio.stopAll();
        Audio.setReverb(null);
        if (Audio.isAvailable()) alGetError(); // so one test's mistake is not blamed on the next
    }

    private static void requireSilentDevice() {
        Assumptions.assumeTrue("null".equals(System.getenv("ALSOFT_DRIVERS")), "set ALSOFT_DRIVERS=null to run audio tests");
        Assumptions.assumeTrue(Audio.isAvailable(), "no OpenAL");
        Assumptions.assumeTrue(Audio.isReverbSupported(), "no EFX");
    }

    private static int sendSlot(Playback p) {
        return p.appliedSendSlot();
    }

    private static Playback world() {
        return Sound.tone(440f, 4f).playAt(1f, 0f, 0f);
    }

    @Test
    void aConeIsHandedToTheSource() {
        requireSilentDevice();
        Playback p = world().direction(0f, 0f, -1f).cone(60f, 180f, 0.25f);
        int source = p.sourceId();
        assertEquals(60f, alGetSourcef(source, AL_CONE_INNER_ANGLE));
        assertEquals(180f, alGetSourcef(source, AL_CONE_OUTER_ANGLE));
        assertEquals(0.25f, alGetSourcef(source, AL_CONE_OUTER_GAIN), 1e-6f);
        float[] d = new float[3];
        alGetSourcefv(source, AL_DIRECTION, d);
        assertEquals(-1f, d[2]);
        assertEquals(AL_NO_ERROR, alGetError());
    }

    @Test
    void theOuterConeIsNeverNarrowerThanTheInnerOne() {
        requireSilentDevice();
        Playback p = world().cone(90f, 20f, 2f);
        assertEquals(90f, alGetSourcef(p.sourceId(), AL_CONE_OUTER_ANGLE));
        assertEquals(1f, alGetSourcef(p.sourceId(), AL_CONE_OUTER_GAIN), "the gain is clamped to 1");
    }

    @Test
    void aFlatPlaybackHasNoDirection() {
        requireSilentDevice();
        Playback p = Sound.tone(440f, 4f).play().direction(1f, 0f, 0f);
        float[] d = new float[3];
        alGetSourcefv(p.sourceId(), AL_DIRECTION, d);
        assertEquals(0f, d[0], "the direction only applies to a positioned sound");
    }

    @Test
    void occlusionPutsAFilterOnTheDirectPathAndTakesItOff() {
        requireSilentDevice();
        Playback p = world();
        assertEquals(0, p.appliedDirectFilter());
        p.occlusion(0.8f);
        assertNotEquals(0, p.appliedDirectFilter());
        assertEquals(0.8f, p.getOcclusion(), 1e-6f);
        assertEquals(AL_NO_ERROR, alGetError(), "OpenAL accepted the filter");
        p.occlusion(0f);
        assertEquals(0, p.appliedDirectFilter());
        assertEquals(AL_NO_ERROR, alGetError());
    }

    @Test
    void aWorldReverbReachesPositionedSoundsAndNotFlatOnes() {
        requireSilentDevice();
        Playback positioned = world();
        Playback flat = Sound.tone(440f, 4f).play();
        assertEquals(0, sendSlot(positioned), "no reverb yet");
        Audio.setReverb(Reverb.hall());
        assertNotEquals(0, sendSlot(positioned), "a playing sound picks the reverb up");
        assertEquals(0, sendSlot(flat), "an interface sound stays dry");
        flat.reverb(0.5f);
        assertNotEquals(0, sendSlot(flat));
        positioned.reverb(0f);
        assertEquals(0, sendSlot(positioned));
        assertEquals(AL_NO_ERROR, alGetError(), "OpenAL accepted every send");
        Audio.setReverb(null);
        assertEquals(0, sendSlot(flat), "with no reverb nothing is sent");
        assertEquals(AL_NO_ERROR, alGetError());
    }

    @Test
    void everyPresetIsAcceptedByTheEffect() {
        requireSilentDevice();
        for (Reverb preset : new Reverb[]{Reverb.room(), Reverb.bathroom(), Reverb.hall(), Reverb.cave(), Reverb.outdoors(), Reverb.padded(),
                Reverb.room().decayTime(1000f).density(-3f).gain(9f)}) {
            Audio.setReverb(preset);
            assertEquals(AL_NO_ERROR, alGetError());
        }
        assertNotNull(Audio.getReverb());
    }

    @Test
    void aReverbSetBeforeTheDeviceIsOpenIsKept() {
        Audio.setReverb(Reverb.cave());
        assertNotNull(Audio.getReverb());
    }
}
