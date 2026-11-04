package me.piitex.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Loading}: the warm-ups run in order, one step per call, and a bad one cannot hold the application back.
 */
class LoadingTest {
    /**
     * Needs {@code steps} calls to finish and records each in a shared log.
     */
    private static WarmUp work(String name, int steps, List<String> log) {
        return new WarmUp() {
            private int calls;

            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                log.add(name + " " + ++calls);
                return calls >= steps;
            }
        };
    }

    @Test
    void aWindowWithNoWarmUpsIsDoneFromTheStart() {
        Loading loading = new Loading();
        assertTrue(loading.isDone());
        assertEquals(1f, loading.getProgress());
        assertNull(loading.getCurrent());
        loading.begin(List.of(), 30f);
        assertTrue(loading.isDone());
    }

    @Test
    void runsOneStepPerCallInTheOrderAdded() {
        List<String> log = new ArrayList<>();
        Loading loading = new Loading();
        loading.begin(List.of(work("a", 2, log), work("b", 1, log)), 30f);
        assertFalse(loading.isDone());
        assertEquals("a", loading.getCurrent());
        assertEquals(0f, loading.getProgress());

        loading.step(null, null);
        assertEquals(List.of("a 1"), log);
        assertEquals("a", loading.getCurrent());
        loading.step(null, null);
        assertEquals("b", loading.getCurrent(), "a is over, b is next");
        assertEquals(0.5f, loading.getProgress());
        loading.step(null, null);
        assertEquals(List.of("a 1", "a 2", "b 1"), log);
        assertTrue(loading.isDone());
        assertEquals(1f, loading.getProgress());
        assertNull(loading.getCurrent());

        loading.step(null, null);
        assertEquals(3, log.size(), "nothing runs once it is done");
    }

    @Test
    void aWarmUpThatThrowsIsDroppedAndTheRestStillRun() {
        List<String> log = new ArrayList<>();
        WarmUp broken = WarmUp.of("broken", window -> {
            throw new IllegalStateException("boom");
        });
        WarmUp missingLibrary = new WarmUp() {
            @Override
            public String getName() {
                return "missing";
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                throw new NoClassDefFoundError("vendor/Upscaler");
            }
        };
        Loading loading = new Loading();
        loading.begin(List.of(broken, missingLibrary, work("good", 1, log)), 30f);
        loading.step(null, null);
        loading.step(null, null);
        assertEquals("good", loading.getCurrent());
        loading.step(null, null);
        assertTrue(loading.isDone());
        assertEquals(List.of("good 1"), log);
    }

    @Test
    void whenTheTimeLimitRunsOutWhatIsLeftIsAbandoned() throws Exception {
        List<String> log = new ArrayList<>();
        Loading loading = new Loading();
        WarmUp never = new WarmUp() {
            @Override
            public String getName() {
                return "never";
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                return false;
            }
        };
        loading.begin(List.of(never, work("after", 1, log)), 0.05f);
        loading.step(null, null);
        assertFalse(loading.isDone());
        Thread.sleep(80);
        loading.step(null, null);
        assertTrue(loading.isDone(), "the application starts without waiting");
        assertTrue(log.isEmpty(), "the one after it never ran");
    }

    @Test
    void ofRunsItsWorkOnceWithTheWindow() {
        List<Window> seen = new ArrayList<>();
        Loading loading = new Loading();
        loading.begin(List.of(WarmUp.of("once", seen::add)), 30f);
        loading.step(null, null);
        assertEquals(1, seen.size());
        assertTrue(loading.isDone());
    }

    @Test
    void optionsKeepTheWarmUpsInOrder() {
        WindowOptions options = new WindowOptions("test");
        WarmUp first = WarmUp.of("first", window -> { });
        WarmUp second = WarmUp.of("second", window -> { });
        options.addWarmUp(first).addWarmUp(second).setWarmUpTimeLimit(5f);
        assertEquals(List.of(first, second), options.getWarmUps());
        assertEquals(5f, options.getWarmUpTimeLimit());
        assertThrows(NullPointerException.class, () -> options.addWarmUp(null));
    }
}
