package me.piitex.engine.ui.animation;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.game.GameTime;
import me.piitex.engine.game.Scene;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.layout.FlowLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitionLockTest {
    private Window window;
    private Container panel;
    private Container wrapper;

    @BeforeEach
    void setUp() {
        GameTime.reset();
        window = new Window(new WindowOptions("test").setDimensions(100, 100));
        wrapper = new FlowLayout(100, 100);
        panel = new FlowLayout(50, 50);
        wrapper.addElement(panel);
        window.addContainer(wrapper);
    }

    /** One engine frame: the update pass, then the lock refresh. */
    private void frame(float seconds) {
        wrapper.update(seconds);
        window.refreshTransitionLock();
    }

    @Test
    void unlockedTransitionDoesNotLock() {
        panel.fadeIn(1f);
        frame(0.1f);
        assertFalse(window.isInputLocked());
        assertFalse(window.swallowInput(true));
        assertFalse(panel.hasLockingTransition());
    }

    @Test
    void lockedTransitionBlocksInputUntilItEnds() {
        panel.fadeIn(1f).lock();
        frame(0.5f);
        assertTrue(window.isInputLocked());
        assertTrue(window.swallowInput(false));
        assertTrue(window.swallowInput(true), "not skippable, so a press is swallowed but the fade keeps going");
        assertTrue(panel.isTransitioning());
        assertEquals(0.5f, panel.getOpacity(), 0.001f);
        frame(0.6f);
        assertEquals(1f, panel.getOpacity(), 0.001f);
        assertFalse(window.isInputLocked());
        assertFalse(window.swallowInput(true));
    }

    @Test
    void skippableTransitionEndsOnAPressButNotOnAWheelTurn() {
        boolean[] done = {false};
        panel.fadeOut(1f, () -> done[0] = true).lock().skippable();
        frame(0.1f);
        assertTrue(window.swallowInput(false));
        assertFalse(done[0]);
        assertTrue(window.isInputLocked());

        assertTrue(window.swallowInput(true), "the press that skips is itself swallowed");
        assertTrue(done[0]);
        assertEquals(0f, panel.getOpacity(), 0.001f);
        assertFalse(window.isInputLocked());
        assertFalse(window.swallowInput(true));
    }

    @Test
    void finishLandsOnTheEndStateAndStopDoesNot() {
        ScaleTransition scale = new ScaleTransition(1f, 3f, 1f, null, null);
        panel.playTransition(scale);
        frame(0.5f);
        assertTrue(panel.getScale() > 1f && panel.getScale() < 3f);
        assertTrue(scale.finish(panel));
        assertEquals(3f, panel.getScale(), 0.001f);
        assertTrue(scale.isFinished());
        assertFalse(scale.finish(panel), "already finished");

        ScaleTransition stopped = new ScaleTransition(1f, 3f, 1f, null, null);
        panel.setScale(1f);
        panel.playTransition(stopped);
        frame(0.5f);
        assertTrue(stopped.stop());
        assertTrue(panel.getScale() < 3f);
    }

    @Test
    void finishTransitionsEndsEveryOneThatCan() {
        panel.fadeIn(1f);
        panel.playTransition(new ScaleTransition(1f, 2f, 1f, null, null));
        frame(0.1f);
        assertTrue(panel.finishTransitions());
        assertEquals(1f, panel.getOpacity(), 0.001f);
        assertEquals(2f, panel.getScale(), 0.001f);
        assertFalse(panel.finishTransitions());
    }

    @Test
    void aDisabledElementDoesNotLock() {
        panel.fadeIn(1f).lock();
        frame(0.1f);
        assertTrue(window.isInputLocked());
        panel.setEnabled(false);
        frame(0.1f);
        assertFalse(window.isInputLocked());
    }

    @Test
    void lockedElementTransitionFreezesTheScenes() {
        int[] updates = {0};
        Scene scene = new Scene() {
            @Override
            protected void update(float delta) {
                updates[0]++;
            }
        };
        window.getSceneManager().push(scene);
        window.getSceneManager().update(0.1f);
        int before = updates[0];

        panel.fadeIn(1f).lock();
        frame(0.1f);
        window.getSceneManager().update(0.1f);
        assertEquals(before, updates[0]);

        panel.finishTransitions();
        window.refreshTransitionLock();
        window.getSceneManager().update(0.1f);
        assertTrue(updates[0] > before);
    }

    @Test
    void customTransitionCanOptIn() {
        Transition custom = new Transition() {
            boolean done;

            @Override
            public void update(me.piitex.engine.ui.Element target, float delta) {
            }

            @Override
            public void render(me.piitex.engine.gl.renderer.Renderer renderer, me.piitex.engine.ui.Element target) {
            }

            @Override
            public boolean isLocking() {
                return true;
            }

            @Override
            public boolean isSkippable() {
                return true;
            }

            @Override
            public boolean finish(me.piitex.engine.ui.Element target) {
                done = true;
                return true;
            }

            @Override
            public boolean isFinished() {
                return done;
            }
        };
        panel.playTransition(custom);
        frame(0.1f);
        assertTrue(window.isInputLocked());
        window.swallowInput(true);
        assertFalse(window.isInputLocked());
    }

    @Test
    void afterHookRunsOnlyOnceTheEndTransitionIsDone() {
        int[] order = {0, 0};
        var end = new FadeTransition(1f, FadeTransition.Direction.OUT).lock().skippable();
        panel.playTransition(end, () -> {
            order[0] = 1;
            var start = new FadeTransition(1f, FadeTransition.Direction.IN);
            panel.playTransition(start, () -> order[1] = 1);
        });
        frame(0.5f);
        assertEquals(0, order[0], "the next scene must wait for the end transition");
        frame(0.6f);
        assertEquals(1, order[0]);
        assertEquals(0, order[1], "the start transition begins only after the end one");
        frame(1.1f);
        assertEquals(1, order[1]);
    }

    @Test
    void afterHookRunsOnSkipButNotOnStop() {
        int[] ran = {0};
        panel.playTransition(new FadeTransition(1f, FadeTransition.Direction.OUT).lock().skippable(), () -> ran[0]++);
        frame(0.1f);
        window.swallowInput(true);
        assertEquals(1, ran[0]);
        frame(0.1f);
        assertEquals(1, ran[0], "runs once");

        var stopped = new FadeTransition(1f, FadeTransition.Direction.OUT);
        panel.playTransition(stopped, () -> ran[0]++);
        stopped.stop();
        frame(0.1f);
        frame(0.1f);
        assertEquals(1, ran[0]);

        var done = new FadeTransition(0.1f, FadeTransition.Direction.IN);
        done.finish(panel);
        panel.playTransition(done, () -> ran[0]++);
        assertEquals(2, ran[0], "an already finished transition runs the hook at once");
    }
}
