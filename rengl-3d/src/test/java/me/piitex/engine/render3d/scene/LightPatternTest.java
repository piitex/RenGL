package me.piitex.engine.render3d.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LightPatternTest {
    @Test
    void steadyIsAlwaysFull() {
        LightPattern steady = LightPattern.steady();
        assertEquals(1f, steady.level(0f));
        assertEquals(1f, steady.level(123.4f));
        assertEquals(1f, steady.level(-5f));
    }

    @Test
    void blinkIsOnForHalfThePeriodThenOffStartingOn() {
        LightPattern blink = LightPattern.blink(1f);
        assertEquals(1f, blink.level(0f));
        assertEquals(1f, blink.level(0.49f));
        assertEquals(0f, blink.level(0.5f));
        assertEquals(0f, blink.level(0.99f));
        assertEquals(1f, blink.level(1f));
        assertEquals(1f, blink.level(2.25f));
        assertEquals(0f, blink.level(2.75f));
    }

    @Test
    void blinkCanBeOnLongerThanItIsOff() {
        LightPattern blink = LightPattern.blink(0.2f, 0.8f);
        assertEquals(1f, blink.level(0.1f));
        assertEquals(0f, blink.level(0.3f));
        assertEquals(1f, blink.level(1.1f));
        assertEquals(0f, blink.level(1.3f));
    }

    @Test
    void blinkKeepsGoingBackwardsInTime() {
        LightPattern blink = LightPattern.blink(1f);
        assertEquals(0f, blink.level(-0.25f)); // a quarter before 0 is 0.75 into the previous period
        assertEquals(1f, blink.level(-0.75f));
    }

    @Test
    void blinkNeverOffOrNeverOnAreAllowed() {
        assertEquals(1f, LightPattern.blink(1f, 0f).level(3.7f));
        assertEquals(0f, LightPattern.blink(0f, 1f).level(3.7f));
    }

    @Test
    void blinkRejectsTimesThatMakeNoPattern() {
        assertThrows(IllegalArgumentException.class, () -> LightPattern.blink(0f));
        assertThrows(IllegalArgumentException.class, () -> LightPattern.blink(-1f));
        assertThrows(IllegalArgumentException.class, () -> LightPattern.blink(0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> LightPattern.blink(-1f, 2f));
        assertThrows(IllegalArgumentException.class, () -> LightPattern.blink(1f, Float.NaN));
    }

    @Test
    void pulseStartsOffPeaksHalfwayAndComesBack() {
        LightPattern pulse = LightPattern.pulse(2f);
        assertEquals(0f, pulse.level(0f), 1e-5f);
        assertEquals(0.5f, pulse.level(0.5f), 1e-5f);
        assertEquals(1f, pulse.level(1f), 1e-5f);
        assertEquals(0f, pulse.level(2f), 1e-5f);
        assertThrows(IllegalArgumentException.class, () -> LightPattern.pulse(0f));
    }

    @Test
    void flickerStaysBetweenTheDipAndFull() {
        LightPattern flicker = LightPattern.flicker(30f, 0.6f, 7L);
        float lowest = 1f, highest = 0f;
        for (int i = 0; i < 2000; i++) {
            float level = flicker.level(i / 100f);
            assertTrue(level >= 0.4f - 1e-5f && level <= 1f, "level " + level);
            lowest = Math.min(lowest, level);
            highest = Math.max(highest, level);
        }
        assertTrue(lowest < 0.5f, "it should dip well down, lowest " + lowest);
        assertTrue(highest > 0.9f, "it should get back up, highest " + highest);
    }

    @Test
    void flickerHoldsEachBrightnessForAStepAndRepeatsExactly() {
        LightPattern a = LightPattern.flicker(10f, 0.8f, 3L), b = LightPattern.flicker(10f, 0.8f, 3L);
        assertEquals(a.level(0.31f), a.level(0.39f)); // the same tenth of a second
        int changes = 0;
        for (int i = 1; i < 50; i++) {
            assertEquals(a.level(i * 0.1f + 0.05f), b.level(i * 0.1f + 0.05f));
            if (a.level(i * 0.1f + 0.05f) != a.level((i - 1) * 0.1f + 0.05f)) changes++;
        }
        assertTrue(changes > 30, "most steps should differ from the one before, changes " + changes);
    }

    @Test
    void differentSeedsFlickerApart() {
        LightPattern a = LightPattern.flicker(10f, 0.8f, 1L), b = LightPattern.flicker(10f, 0.8f, 2L);
        int same = 0;
        for (int i = 0; i < 100; i++) if (a.level(i * 0.1f + 0.05f) == b.level(i * 0.1f + 0.05f)) same++;
        assertTrue(same < 10, "same in " + same + " of 100 steps");
    }

    @Test
    void flickerWithNoDepthIsSteadyAndFullDepthCanGoDark() {
        LightPattern none = LightPattern.flicker(20f, 0f, 5L), all = LightPattern.flicker(20f, 5f, 5L); // depth is capped at 1
        float lowest = 1f;
        for (int i = 0; i < 500; i++) {
            assertEquals(1f, none.level(i * 0.013f));
            lowest = Math.min(lowest, all.level(i * 0.013f));
        }
        assertTrue(lowest < 0.1f, "lowest " + lowest);
        assertThrows(IllegalArgumentException.class, () -> LightPattern.flicker(0f, 0.5f, 1L));
    }

    @Test
    void faultyIsMostlyOnWithBurstsOfOnAndOffInAboutAThirdOfTheSlots() {
        LightPattern faulty = LightPattern.faulty(1f, 11L);
        int slotsWithBurst = 0, onSamples = 0, samples = 0;
        for (int slot = 0; slot < 300; slot++) {
            boolean burst = false;
            for (int i = 0; i < 100; i++) {
                float level = faulty.level(slot + (i + 0.5f) / 100f);
                assertTrue(level == 0f || level == 1f, "level " + level);
                if (level == 0f) burst = true;
                else onSamples++;
                samples++;
            }
            if (burst) slotsWithBurst++;
        }
        assertTrue(slotsWithBurst > 60 && slotsWithBurst < 140, "slots with a burst " + slotsWithBurst + " of 300");
        assertTrue(onSamples > samples * 0.7f && onSamples < samples, "on for " + onSamples + " of " + samples);
    }

    @Test
    void faultyStutterIsFastAndRepeatsExactly() {
        LightPattern a = LightPattern.faulty(1f, 4L), b = LightPattern.faulty(1f, 4L);
        int flips = 0;
        float before = -1f;
        for (int i = 0; i < 30000; i++) {
            float t = i / 1000f; // 30 seconds in milliseconds, enough slots for a burst
            assertEquals(a.level(t), b.level(t));
            float level = a.level(t);
            if (before >= 0f && level != before) flips++;
            before = level;
        }
        assertTrue(flips > 5, "it should flicker, flips " + flips);
        // a burst only changes every twentieth of a second: nothing flips twice within a single 50 ms step
        for (int slot = 0; slot < 20; slot++) {
            for (int step = 0; step < 20; step++) {
                float first = a.level(slot + step / 20f + 0.001f), last = a.level(slot + step / 20f + 0.049f);
                assertEquals(first, last, "slot " + slot + " step " + step);
            }
        }
    }

    @Test
    void sequenceStepsThroughTheLevelsAndLoops() {
        LightPattern sequence = LightPattern.sequence(0.5f, 1f, 0f, 0.25f);
        assertEquals(1f, sequence.level(0.1f));
        assertEquals(0f, sequence.level(0.6f));
        assertEquals(0.25f, sequence.level(1.1f));
        assertEquals(1f, sequence.level(1.6f));
        assertEquals(0f, sequence.level(2.1f));
        assertEquals(0.25f, sequence.level(-0.1f)); // before 0 it is the end of the one before
    }

    @Test
    void sequenceIsNotChangedByLaterEditsToItsArray() {
        float[] levels = {1f, 0f};
        LightPattern sequence = LightPattern.sequence(1f, levels);
        levels[0] = 0.5f;
        assertEquals(1f, sequence.level(0.5f));
    }

    @Test
    void sequenceRejectsNothingToStepThrough() {
        assertThrows(IllegalArgumentException.class, () -> LightPattern.sequence(1f));
        assertThrows(IllegalArgumentException.class, () -> LightPattern.sequence(0f, 1f));
        assertThrows(IllegalArgumentException.class, () -> LightPattern.sequence(-1f, 1f));
    }
}
