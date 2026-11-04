package me.piitex.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Loading}: the warm-ups run in order, a slice per warm-up per call, their background work is waited for, the
 * splash gets a moment before the steps start, and a bad one cannot hold the application back.
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

    /**
     * Begins with no delay and waits for the background work, so a test sees the steps and nothing else.
     */
    private static Loading begin(float limitSeconds, WarmUp... warmUps) throws InterruptedException {
        Loading loading = new Loading();
        loading.begin(List.of(warmUps), limitSeconds);
        assertTrue(loading.awaitPrepared(5_000), "the background work returned");
        return loading;
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
    void runsOneSliceOfAWarmUpPerCallInTheOrderAdded() throws Exception {
        List<String> log = new ArrayList<>();
        Loading loading = begin(30f, work("a", 2, log), work("b", 1, log));
        assertFalse(loading.isDone());
        assertEquals("a", loading.getCurrent());
        assertEquals(0f, loading.getProgress());

        loading.step(null, null);
        assertEquals(List.of("a 1"), log, "a warm-up that is not over waits for the next frame, so the splash is drawn between slices");
        assertEquals("a", loading.getCurrent());
        loading.step(null, null);
        assertEquals(List.of("a 1", "a 2", "b 1"), log, "a is over, and b's short slice shares the frame");
        assertTrue(loading.isDone());
        assertEquals(1f, loading.getProgress());
        assertNull(loading.getCurrent());

        loading.step(null, null);
        assertEquals(3, log.size(), "nothing runs once it is done");
    }

    @Test
    void aSliceThatTakesTheWholeFrameLeavesTheNextWarmUpForTheNextFrame() throws Exception {
        List<String> log = new ArrayList<>();
        WarmUp slow = new WarmUp() {
            @Override
            public String getName() {
                return "slow";
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                log.add("slow");
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        };
        Loading loading = begin(30f, slow, work("after", 1, log));
        loading.step(null, null);
        assertEquals(List.of("slow"), log, "the frame's time is spent");
        assertEquals("after", loading.getCurrent());
        loading.step(null, null);
        assertEquals(List.of("slow", "after 1"), log);
        assertTrue(loading.isDone());
    }

    @Test
    void aWarmUpThatThrowsIsDroppedAndTheRestStillRun() throws Exception {
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
        Loading loading = begin(30f, broken, missingLibrary, work("good", 1, log));
        // Logging the failures is slow, so a frame's budget may be spent before the good one runs: a few frames at most.
        for (int frame = 0; frame < 5 && !loading.isDone(); frame++) loading.step(null, null);
        assertTrue(loading.isDone());
        assertEquals(List.of("good 1"), log);
    }

    @Test
    void whenTheTimeLimitRunsOutWhatIsLeftIsAbandoned() throws Exception {
        List<String> log = new ArrayList<>();
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
        Loading loading = begin(0.05f, never, work("after", 1, log));
        loading.step(null, null);
        assertFalse(loading.isDone());
        Thread.sleep(80);
        loading.step(null, null);
        assertTrue(loading.isDone(), "the application starts without waiting");
        assertTrue(log.isEmpty(), "the one after it never ran");
    }

    @Test
    void ofRunsItsWorkOnceWithTheWindow() throws Exception {
        List<Window> seen = new ArrayList<>();
        Loading loading = begin(30f, WarmUp.of("once", seen::add));
        loading.step(null, null);
        assertEquals(1, seen.size());
        assertTrue(loading.isDone());
    }

    @Test
    void theStepsWaitForTheSplashToSettleButTheBackgroundWorkDoesNot() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        WarmUp warmUp = new WarmUp() {
            @Override
            public String getName() {
                return "w";
            }

            @Override
            public void prepare() {
                log.add("prepared");
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                log.add("step");
                return true;
            }
        };
        Loading loading = new Loading();
        loading.begin(List.of(warmUp), 30f, 0.15f);
        assertTrue(loading.awaitPrepared(5_000));
        assertEquals(List.of("prepared"), log, "the background work started at once");

        loading.step(null, null);
        assertEquals(List.of("prepared"), log, "the splash has only just been drawn, so the steps hold back");
        assertFalse(loading.isDone());
        Thread.sleep(200);
        loading.step(null, null);
        assertEquals(List.of("prepared", "step"), log);
        assertTrue(loading.isDone());
    }

    @Test
    void aStepWaitsForItsOwnBackgroundWork() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        WarmUp slowToPrepare = new WarmUp() {
            @Override
            public String getName() {
                return "slow to prepare";
            }

            @Override
            public void prepare() {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.add("prepared");
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                log.add("step");
                return true;
            }
        };
        Loading loading = new Loading();
        loading.begin(List.of(slowToPrepare), 30f);
        loading.step(null, null);
        loading.step(null, null);
        assertTrue(log.isEmpty(), "no step while the background work is going: " + log);
        assertFalse(loading.isDone());

        release.countDown();
        assertTrue(loading.awaitPrepared(5_000));
        loading.step(null, null);
        assertEquals(List.of("prepared", "step"), log);
        assertTrue(loading.isDone());
    }

    @Test
    void backgroundWorkThatThrowsStillLetsTheStepsRun() throws Exception {
        List<String> log = new ArrayList<>();
        WarmUp warmUp = new WarmUp() {
            @Override
            public String getName() {
                return "w";
            }

            @Override
            public void prepare() {
                throw new IllegalStateException("no device");
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                log.add("step");
                return true;
            }
        };
        Loading loading = begin(30f, warmUp);
        loading.step(null, null);
        assertEquals(List.of("step"), log);
    }

    @Test
    void inBackgroundDoesItsWorkOffTheCallingThread() throws Exception {
        Thread caller = Thread.currentThread();
        List<Thread> ran = new CopyOnWriteArrayList<>();
        Loading loading = begin(30f, WarmUp.inBackground("quiet work", () -> ran.add(Thread.currentThread())));
        assertEquals(1, ran.size());
        assertNotSame(caller, ran.get(0));
        loading.step(null, null);
        assertTrue(loading.isDone());
    }

    @Test
    void everyWarmUpThatRanIsToldTheApplicationStartedOnce() throws Exception {
        List<String> told = new ArrayList<>();
        class Telling implements WarmUp {
            private final String name;
            private final boolean fails;

            Telling(String name, boolean fails) {
                this.name = name;
                this.fails = fails;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean step(Window window, me.piitex.engine.gl.renderer.Renderer renderer) {
                if (fails) throw new IllegalStateException("boom");
                return true;
            }

            @Override
            public void onApplicationStarted(Window window) {
                told.add(name);
                if (name.equals("throws when told")) throw new IllegalStateException("boom");
            }
        }
        Loading loading = begin(30f, new Telling("first", false), new Telling("fails", true), new Telling("throws when told", false),
                new Telling("last", false));
        loading.step(null, null);
        assertTrue(loading.isDone());
        loading.applicationStarted(null);
        assertEquals(List.of("first", "fails", "throws when told", "last"), told,
                "a failed warm-up is told too, and one that throws when told does not stop the rest");
        loading.applicationStarted(null);
        assertEquals(4, told.size(), "once");
    }

    @Test
    void optionsKeepTheWarmUpsInOrder() {
        WindowOptions options = new WindowOptions("test");
        WarmUp first = WarmUp.of("first", window -> { });
        WarmUp second = WarmUp.of("second", window -> { });
        options.addWarmUp(first).addWarmUp(second).setWarmUpTimeLimit(5f).setWarmUpDelay(0.5f);
        assertEquals(List.of(first, second), options.getWarmUps());
        assertEquals(5f, options.getWarmUpTimeLimit());
        assertEquals(0.5f, options.getWarmUpDelay());
        assertEquals(2f, new WindowOptions("other").getWarmUpDelay(), "the default gives the splash its introduction first");
        assertThrows(NullPointerException.class, () -> options.addWarmUp(null));
    }
}
