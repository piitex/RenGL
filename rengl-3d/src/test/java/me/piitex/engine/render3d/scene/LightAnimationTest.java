package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightAnimationTest {
    private static Light lamp(float intensity) {
        return Light.point(new Vec3(0, 1, 0), Color.WHITE, intensity, 5f);
    }

    @Test
    void fullStrengthIsWhatTheLightHadWhenItWasAdded() {
        Light lamp = lamp(5f);
        LightAnimation animation = LightAnimation.of(LightPattern.steady()).light(lamp);
        lamp.setIntensity(99f); // changed after: not the full strength
        animation.update(0.1f);
        assertEquals(5f, lamp.getIntensity(), 1e-5f);
    }

    @Test
    void aBlinkingLightGoesOutAndComesBack() {
        Light lamp = lamp(6f);
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f)).light(lamp);
        animation.update(0f);
        assertEquals(6f, lamp.getIntensity(), 1e-5f);
        animation.update(0.6f); // 0.6 s in: the off half
        assertEquals(0f, lamp.getIntensity());
        assertEquals(0f, animation.getLevel());
        animation.update(0.5f); // 1.1 s in: on again
        assertEquals(6f, lamp.getIntensity(), 1e-5f);
        assertEquals(1f, animation.getLevel());
    }

    @Test
    void aMinimumLeavesADimLevelInsteadOfDark() {
        Light lamp = lamp(6f);
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f)).light(lamp).setMinimum(0.2f);
        animation.update(0.75f);
        assertEquals(1.2f, lamp.getIntensity(), 1e-5f);
        assertEquals(0.2f, animation.getLevel(), 1e-6f);
        animation.update(0.5f); // on
        assertEquals(6f, lamp.getIntensity(), 1e-5f);
        assertEquals(0.2f, animation.setMinimum(0.2f).getMinimum());
        assertEquals(1f, animation.setMinimum(7f).getMinimum(), "the minimum is held between 0 and 1");
        assertEquals(0f, animation.setMinimum(-1f).getMinimum());
    }

    @Test
    void aGlowingMaterialFollowsTheSamePattern() {
        Material bulb = new Material(Color.BLACK).setEmissive(2f, 1f, 0.5f);
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f)).glow(bulb);
        animation.update(0f);
        assertEquals(2f, bulb.getEmissiveRed(), 1e-5f);
        assertEquals(1f, bulb.getEmissiveGreen(), 1e-5f);
        assertEquals(0.5f, bulb.getEmissiveBlue(), 1e-5f);
        animation.update(0.75f); // off
        assertEquals(0f, bulb.getEmissiveRed());
        assertEquals(0f, bulb.getEmissiveGreen());
        assertEquals(0f, bulb.getEmissiveBlue());
        animation.update(0.5f); // on: full strength comes back, not the zero it was left at
        assertEquals(2f, bulb.getEmissiveRed(), 1e-5f);
    }

    @Test
    void aSmoothPatternScalesTheGlowPartWay() {
        Material bulb = new Material(Color.BLACK).setEmissive(4f, 4f, 4f);
        LightAnimation animation = LightAnimation.of(LightPattern.pulse(4f)).glow(bulb);
        animation.update(1f); // a quarter of the way round a pulse is half bright
        assertEquals(2f, bulb.getEmissiveRed(), 1e-4f);
        assertEquals(0.5f, animation.getLevel(), 1e-4f);
    }

    @Test
    void aMaterialWithNoGlowIsLeftAlone() {
        Material plain = new Material(Color.RED);
        assertFalse(plain.isPbr());
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f)).glow(plain);
        animation.update(0.75f);
        animation.update(0.5f);
        assertEquals(0f, plain.getEmissiveRed());
        assertFalse(plain.isPbr(), "scaling nothing must not switch the material to PBR");
    }

    @Test
    void oneAnimationDrivesEveryLightAndMaterialItWasGiven() {
        Light a = lamp(2f), b = lamp(8f);
        Material m = new Material(Color.BLACK).setEmissive(1f, 0f, 0f), n = new Material(Color.BLACK).setEmissive(0f, 3f, 0f);
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f)).light(a).light(b).glow(m).glow(n);
        animation.update(0.75f);
        assertEquals(0f, a.getIntensity());
        assertEquals(0f, b.getIntensity());
        assertEquals(0f, m.getEmissiveRed());
        assertEquals(0f, n.getEmissiveGreen());
        animation.update(0.5f);
        assertEquals(2f, a.getIntensity(), 1e-5f);
        assertEquals(8f, b.getIntensity(), 1e-5f);
        assertEquals(1f, m.getEmissiveRed(), 1e-5f);
        assertEquals(3f, n.getEmissiveGreen(), 1e-5f);
    }

    @Test
    void speedChangesHowFastThePatternRuns() {
        Light fast = lamp(1f), still = lamp(1f);
        LightAnimation twice = LightAnimation.of(LightPattern.blink(1f)).light(fast).setSpeed(2f);
        twice.update(0.3f); // 0.6 s into the pattern: off
        assertEquals(0f, fast.getIntensity());
        assertEquals(0.6f, twice.getTime(), 1e-5f);
        LightAnimation stopped = LightAnimation.of(LightPattern.blink(1f)).light(still).setSpeed(0f);
        stopped.update(10f);
        assertEquals(0f, stopped.getTime());
        assertEquals(1f, still.getIntensity());
        assertEquals(0f, stopped.setSpeed(-3f).getSpeed(), "a negative speed is held at 0");
    }

    @Test
    void theStartTimePutsAnimationsOutOfStep() {
        Light a = lamp(1f), b = lamp(1f);
        LightAnimation first = LightAnimation.of(LightPattern.blink(1f)).light(a);
        LightAnimation second = LightAnimation.of(LightPattern.blink(1f)).light(b).setTime(0.5f);
        first.update(0f);
        second.update(0f);
        assertEquals(1f, a.getIntensity());
        assertEquals(0f, b.getIntensity());
    }

    @Test
    void aPausedAnimationDoesNotAdvance() {
        Light lamp = lamp(1f);
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f)).light(lamp);
        animation.update(0.25f);
        animation.setPlaying(false);
        assertFalse(animation.isPlaying());
        animation.update(5f);
        assertEquals(0.25f, animation.getTime(), 1e-6f);
        assertEquals(1f, lamp.getIntensity());
        animation.setPlaying(true);
        animation.update(0.5f); // 0.75: off
        assertEquals(0f, lamp.getIntensity());
    }

    @Test
    void thePatternIsGivenTheTimeInSeconds() {
        List<Float> seen = new ArrayList<>();
        LightAnimation animation = LightAnimation.of(time -> {
            seen.add(time);
            return 1f;
        });
        animation.update(0.5f);
        animation.update(0.25f);
        assertEquals(List.of(0.5f, 0.75f), seen);
    }

    @Test
    void aPatternOutsideZeroToOneIsHeldInRangeAndNeverNotANumber() {
        Light lamp = lamp(4f);
        float[] answer = {7f};
        LightAnimation animation = LightAnimation.of(time -> answer[0]).light(lamp);
        animation.update(0f);
        assertEquals(4f, lamp.getIntensity(), 1e-5f);
        answer[0] = -3f;
        animation.update(0f);
        assertEquals(0f, lamp.getIntensity());
        answer[0] = Float.NaN;
        animation.update(0f);
        assertFalse(Float.isNaN(lamp.getIntensity()));
    }

    @Test
    void nothingIsAcceptedThatCouldNotWork() {
        assertThrows(IllegalArgumentException.class, () -> LightAnimation.of(null));
        LightAnimation animation = LightAnimation.of(LightPattern.steady());
        assertThrows(IllegalArgumentException.class, () -> animation.light(null));
        assertThrows(IllegalArgumentException.class, () -> animation.glow(null));
    }

    @Test
    void aSceneWithAPlayingLightAnimationAsksForFrames() {
        Scene3D scene = new Scene3D();
        LightAnimation animation = LightAnimation.of(LightPattern.blink(1f));
        assertFalse(scene.needsFrames());
        scene.animate(animation).animate(animation); // twice is still once
        assertTrue(scene.needsFrames());
        animation.setPlaying(false);
        assertFalse(scene.needsFrames(), "a paused animation needs no frames");
        animation.setPlaying(true);
        scene.stopAnimating(animation);
        assertFalse(scene.needsFrames(), "removed once, it is gone");
    }
}
