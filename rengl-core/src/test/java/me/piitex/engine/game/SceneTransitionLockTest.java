package me.piitex.engine.game;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneTransitionLockTest {
    private static class Counting extends Scene {
        int updates, fixed;

        @Override
        protected void update(float delta) {
            updates++;
        }

        @Override
        protected void fixedUpdate(float step) {
            fixed++;
        }
    }

    private Window window;
    private SceneManager scenes;

    @BeforeEach
    void setUp() {
        GameTime.reset();
        window = new Window(new WindowOptions("test").setDimensions(100, 100));
        scenes = window.getSceneManager();
    }

    /** One engine frame of {@code seconds}: the fixed steps, then the per-frame update. */
    private void frame(float seconds) {
        GameTime.advance(seconds, scenes::fixedUpdate);
        scenes.update(GameTime.getDelta());
    }

    @Test
    void unlockedTransitionLeavesTheScenesRunning() {
        Counting scene = new Counting();
        scenes.push(scene, SceneTransition.fade(1f));
        frame(0.1f);
        assertTrue(scenes.isTransitioning());
        assertFalse(scenes.isInputLocked());
        assertEquals(1, scene.updates);
        assertFalse(scenes.swallowInput(true));
    }

    @Test
    void lockedTransitionFreezesTheScenesUntilItEnds() {
        Counting scene = new Counting();
        scenes.push(scene, SceneTransition.fade(1f).lock());
        assertTrue(scenes.isInputLocked());
        frame(0.4f);
        frame(0.4f);
        assertEquals(0, scene.updates);
        assertEquals(0, scene.fixed);
        assertTrue(scenes.swallowInput(true));
        assertTrue(scenes.isTransitioning(), "not skippable, so a press does not end it");

        frame(0.4f); // 1.2s in: over
        assertFalse(scenes.isTransitioning());
        assertFalse(scenes.isInputLocked());
        assertEquals(1, scene.updates, "the frame it ends in already runs the scenes");
        frame(0.1f);
        assertEquals(2, scene.updates);
        assertFalse(scenes.swallowInput(true));
    }

    @Test
    void skippableTransitionEndsOnAPressButNotOnAWheelTurn() {
        scenes.push(new Counting(), SceneTransition.fade(5f).lock().skippable());
        assertTrue(scenes.swallowInput(false), "a wheel turn is swallowed");
        assertTrue(scenes.isTransitioning());
        assertTrue(scenes.swallowInput(true), "the press that skips is swallowed too");
        assertFalse(scenes.isTransitioning());
        assertFalse(scenes.swallowInput(true), "the next one reaches the game");
    }

    @Test
    void finishTransitionUnlocksFromCode() {
        Counting scene = new Counting();
        scenes.push(scene, SceneTransition.slide(SceneTransition.Direction.LEFT, 5f).lock());
        frame(0.1f);
        scenes.finishTransition();
        assertFalse(scenes.isInputLocked());
        frame(0.1f);
        assertEquals(1, scene.updates);
    }
}
