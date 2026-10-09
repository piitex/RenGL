package me.piitex.engine.graphics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ParticleEmitterTest {
    private final ParticleSystem system = new ParticleSystem(1000, new Random(1));
    private final ParticleEffect effect = new ParticleEffect().lifetime(10f, 10f);

    @Test
    void aRateGivesThatManyPerSecondWhateverTheStep() {
        ParticleEmitter emitter = new ParticleEmitter(system, effect).rate(10f);
        int total = 0;
        for (int i = 0; i < 60; i++) total += emitter.update(1 / 60f);
        assertEquals(10, total);
        assertEquals(10, system.getCount());
    }

    @Test
    void aFractionCarriesOverToTheNextUpdate() {
        ParticleEmitter emitter = new ParticleEmitter(system, effect).rate(1f);
        assertEquals(0, emitter.update(0.6f));
        assertEquals(1, emitter.update(0.6f));
        assertEquals(0, emitter.update(0.3f));
    }

    @Test
    void aSlowFrameEmitsTheWholeShare() {
        ParticleEmitter emitter = new ParticleEmitter(system, effect).rate(100f);
        assertEquals(50, emitter.update(0.5f));
    }

    @Test
    void offEmitsNothingAndRateZeroEmitsNothing() {
        ParticleEmitter emitter = new ParticleEmitter(system, effect).rate(100f);
        emitter.setOn(false);
        assertEquals(0, emitter.update(1f));
        emitter.setOn(true).rate(0f);
        assertEquals(0, emitter.update(1f));
        assertEquals(0, system.getCount());
    }

    @Test
    void aMovingEmitterLeavesAnEvenTrailAndAJumpDoesNot() {
        ParticleEmitter emitter = new ParticleEmitter(system, effect).rate(40f);
        emitter.setPosition(0, 0);
        emitter.moveTo(100, 0);
        assertEquals(4, emitter.update(0.1f));
        assertEquals(4, system.getCount());
        for (int i = 0; i < 4; i++) {
            assertEquals(25f * (i + 1), system.getParticle(i).getX(), 0.001f, "particle " + i);
        }
        emitter.setPosition(500, 0); // a jump: no line back to 100
        assertEquals(4, emitter.update(0.1f));
        for (int i = 4; i < 8; i++) assertEquals(500f, system.getParticle(i).getX(), 0.001f);
    }

    @Test
    void aConeSendsThemTheWayItPoints() {
        ParticleEffect fast = new ParticleEffect().speed(100f, 100f).lifetime(10f, 10f);
        ParticleEmitter emitter = new ParticleEmitter(system, fast).rate(100f).cone(90f, 0f); // straight down
        emitter.update(0.2f);
        assertEquals(20, system.getCount());
        for (int i = 0; i < 20; i++) {
            assertEquals(0f, system.getParticle(i).getVelocityX(), 0.01f);
            assertEquals(100f, system.getParticle(i).getVelocityY(), 0.01f);
        }
    }
}
