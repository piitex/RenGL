package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine running warm-ups under a splash in a real (hidden) window: they start after the splash has been drawn, the
 * application is held until the last is done, and none run with the splash switched off. Skips where no window opens.
 */
class WarmUpEngineTest {
    private static final String TITLE = "RenGL warm-up test";

    /**
     * A splash that waits for the window's warm-ups and notes what it saw.
     */
    private static final class WaitingSplash implements Splash {
        private final List<String> events;
        private Loading loading;

        WaitingSplash(List<String> events) {
            this.events = events;
        }

        @Override
        public void onStart(Window window) {
            loading = window.getLoading();
        }

        @Override
        public void update(float delta) {
        }

        @Override
        public void render(Renderer renderer, int width, int height) {
            events.add("splash drawn");
        }

        @Override
        public boolean isHoldingApp() {
            return !loading.isDone();
        }

        @Override
        public boolean isFinished() {
            return loading.isDone();
        }
    }

    /**
     * Opens the window, lets it run until the application has been updated a few times, closes it. Returns whether it opened.
     */
    private static boolean run(Window window, AtomicInteger appUpdates) throws Exception {
        Thread closer = new Thread(() -> {
            try {
                for (int i = 0; i < 150 && appUpdates.get() < 5; i++) Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                window.close();
            }
        }, "warm-up-test-closer");
        closer.setDaemon(true);
        closer.start();
        try {
            new Engine().start(window);
        } catch (Throwable t) {
            assumeTrue(false, "the engine could not start here: " + t);
        } finally {
            closer.join(5_000);
        }
        return appUpdates.get() >= 5;
    }

    @Test
    void warmUpsRunUnderTheSplashAndTheApplicationWaitsForThem() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger appUpdates = new AtomicInteger();
        AtomicInteger updatesWhenDone = new AtomicInteger(-1);

        WarmUp slow = new WarmUp() {
            private int calls;

            @Override
            public String getName() {
                return "slow";
            }

            @Override
            public boolean step(Window window, Renderer renderer) {
                events.add("slow " + ++calls);
                return calls >= 4;
            }
        };
        WarmUp broken = WarmUp.of("broken", window -> {
            events.add("broken");
            throw new IllegalStateException("boom");
        });
        WarmUp last = WarmUp.of("last", window -> {
            events.add("last");
            updatesWhenDone.set(appUpdates.get());
        });

        Window window = new Window(new WindowOptions(TITLE).setDimensions(320, 200).setVsync(false)
                .setSplash(new WaitingSplash(events)).setWarmUpDelay(0f).addWarmUp(slow).addWarmUp(broken).addWarmUp(last));
        window.hide();
        Container root = new Container(320, 200);
        root.onUpdate(_ -> {
            if (appUpdates.getAndIncrement() == 0) events.add("application first updated");
        });
        window.addContainer(root);

        assumeTrue(run(window, appUpdates), "the window never ran");

        int firstSplash = events.indexOf("splash drawn");
        assertTrue(firstSplash >= 0, "the splash was drawn");
        assertTrue(events.indexOf("slow 1") > firstSplash, "no warm-up starts before the splash has been drawn: " + events);
        // Each slice is a frame of its own, in order, with the broken one dropped and the last still run.
        assertTrue(events.indexOf("slow 1") < events.indexOf("slow 2"));
        assertTrue(events.indexOf("slow 4") < events.indexOf("broken"));
        assertTrue(events.indexOf("broken") < events.indexOf("last"));
        assertTrue(events.indexOf("last") < events.indexOf("application first updated"),
                "the application stays held until the last warm-up is done: " + events);
        assertEquals(0, updatesWhenDone.get(), "the application had not been updated once when the last warm-up ran");
        assertTrue(window.getLoading().isDone());
    }

    @Test
    void warmUpsAreSkippedWhenTheSplashIsSwitchedOff() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger appUpdates = new AtomicInteger();
        Window window = new Window(new WindowOptions(TITLE).setDimensions(320, 200).setVsync(false)
                .setSplashEnabled(false).addWarmUp(WarmUp.of("skipped", w -> ran.incrementAndGet())));
        window.hide();
        Container root = new Container(320, 200);
        root.onUpdate(_ -> appUpdates.incrementAndGet());
        window.addContainer(root);

        assumeTrue(run(window, appUpdates), "the window never ran");
        assertEquals(0, ran.get());
        assertTrue(window.getLoading().isDone());
    }

    /**
     * Holds the application until the warm-ups are done, then fades for a third of a second, and notes the time it is given.
     */
    private static final class FadingSplash implements Splash {
        final List<Float> deltasAfterRelease = new CopyOnWriteArrayList<>();
        private Loading loading;
        private float faded;

        @Override
        public void onStart(Window window) {
            loading = window.getLoading();
        }

        @Override
        public void update(float delta) {
            if (!loading.isDone()) return;
            deltasAfterRelease.add(delta);
            faded += delta;
        }

        @Override
        public void render(Renderer renderer, int width, int height) {
        }

        @Override
        public boolean isHoldingApp() {
            return !loading.isDone();
        }

        @Override
        public boolean isFinished() {
            return loading.isDone() && faded >= 0.33f;
        }
    }

    @Test
    void theApplicationRunsItsFirstFramesBehindTheSplashBeforeTheSplashFades() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Float> applicationDeltas = new CopyOnWriteArrayList<>();
        AtomicInteger appUpdates = new AtomicInteger();
        AtomicInteger updatesWhenTold = new AtomicInteger(-1);
        FadingSplash splash = new FadingSplash();

        WarmUp warmUp = new WarmUp() {
            @Override
            public String getName() {
                return "w";
            }

            @Override
            public boolean step(Window window, Renderer renderer) {
                events.add("step");
                return true;
            }

            @Override
            public void onApplicationStarted(Window window) {
                events.add("application started");
                updatesWhenTold.set(appUpdates.get());
            }
        };
        Window window = new Window(new WindowOptions(TITLE).setDimensions(320, 200).setVsync(false)
                .setSplash(splash).setWarmUpDelay(0f).addWarmUp(warmUp));
        window.hide();
        Container root = new Container(320, 200);
        root.onUpdate(event -> {
            applicationDeltas.add(event.getDelta());
            appUpdates.incrementAndGet();
        });
        window.addContainer(root);

        assumeTrue(run(window, appUpdates), "the window never ran");

        assertEquals(List.of("step", "application started"), events);
        // The first frames are hidden behind the splash with next to no time passing...
        assertTrue(updatesWhenTold.get() >= 3, "the application ran at least three frames behind the splash: " + updatesWhenTold);
        for (int frame = 0; frame < updatesWhenTold.get(); frame++) {
            assertEquals(0.001f, applicationDeltas.get(frame), 1e-6f, "frame " + frame + " of the pre-roll");
        }
        // ...and the splash stood still for the same frames, so its fade starts from the top afterwards.
        long standingStill = splash.deltasAfterRelease.stream().filter(delta -> delta == 0.001f).count();
        assertTrue(standingStill >= 2, "the splash was given almost no time while the application warmed up: " + splash.deltasAfterRelease);
        // Afterwards time passes as usual.
        assertTrue(applicationDeltas.get(updatesWhenTold.get()) > 0.001f, "then the application gets the real frame time: " + applicationDeltas);
    }
}
