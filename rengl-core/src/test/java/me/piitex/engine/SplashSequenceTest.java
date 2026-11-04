package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Splash#sequence}: splashes played one after another.
 */
class SplashSequenceTest {
    /**
     * Holds the application for {@code holdSeconds}, finishes after {@code finishSeconds}, and records what happens to
     * it in a shared log.
     */
    private static class RecordingSplash implements Splash {
        final String name;
        final float holdSeconds, finishSeconds;
        final Color clear;
        final List<String> events;
        float time;

        RecordingSplash(String name, float holdSeconds, float finishSeconds, Color clear, List<String> events) {
            this.name = name;
            this.holdSeconds = holdSeconds;
            this.finishSeconds = finishSeconds;
            this.clear = clear;
            this.events = events;
        }

        @Override
        public void onStart(Window window) {
            events.add("start " + name);
        }

        @Override
        public void update(float delta) {
            time += delta;
        }

        @Override
        public void render(Renderer renderer, int width, int height) {
            events.add("render " + name);
        }

        @Override
        public boolean isHoldingApp() {
            return time < holdSeconds;
        }

        @Override
        public boolean isFinished() {
            return time >= finishSeconds;
        }

        @Override
        public Color getClearColor() {
            return clear;
        }

        @Override
        public void dispose() {
            events.add("dispose " + name);
        }
    }

    private static RecordingSplash splash(String name, float seconds, List<String> events) {
        return new RecordingSplash(name, seconds, seconds, Color.BLACK, events);
    }

    @Test
    void playsEachSplashInOrderAndDisposesEachOnce() {
        List<String> events = new ArrayList<>();
        Splash sequence = Splash.sequence(splash("publisher", 1f, events), splash("framework", 1f, events), splash("last", 1f, events));
        sequence.onStart(null);
        assertEquals(List.of("start publisher"), events);

        sequence.update(1f);
        sequence.render(null, 100, 100);
        assertEquals(List.of("start publisher", "dispose publisher", "start framework", "render framework"), events);

        sequence.update(1f);
        sequence.update(1f);
        assertTrue(sequence.isFinished());
        assertEquals(List.of("start publisher", "dispose publisher", "start framework", "render framework",
                "dispose framework", "start last", "dispose last"), events);

        sequence.dispose();
        assertEquals(7, events.size(), "nothing is disposed twice");
        sequence.render(null, 100, 100);
        assertEquals(7, events.size(), "a finished sequence draws nothing");
    }

    @Test
    void holdsTheApplicationBetweenSplashesButLetsTheLastOneFade() {
        List<String> events = new ArrayList<>();
        // The first holds for 1 s but is not finished until 2 s; the last holds for 1 s and then fades for 1 s.
        Splash sequence = Splash.sequence(new RecordingSplash("first", 1f, 2f, Color.BLACK, events),
                new RecordingSplash("last", 1f, 2f, Color.BLACK, events));
        sequence.onStart(null);
        sequence.update(1.5f);
        assertTrue(sequence.isHoldingApp(), "the first one's fade does not reveal the application");
        sequence.update(0.5f);
        assertTrue(sequence.isHoldingApp(), "the last one has not started to hold off");
        sequence.update(1f);
        assertFalse(sequence.isHoldingApp(), "the last one fades over the application");
        assertFalse(sequence.isFinished());
        sequence.update(1f);
        assertTrue(sequence.isFinished());
        assertFalse(sequence.isHoldingApp());
    }

    @Test
    void theClearColorFollowsTheSplashPlaying() {
        List<String> events = new ArrayList<>();
        Splash sequence = Splash.sequence(new RecordingSplash("a", 1f, 1f, Color.WHITE, events),
                new RecordingSplash("b", 1f, 1f, Color.RED, events));
        sequence.onStart(null);
        assertSame(Color.WHITE, sequence.getClearColor());
        sequence.update(1f);
        assertSame(Color.RED, sequence.getClearColor());
        sequence.update(1f);
        assertSame(Color.BLACK, sequence.getClearColor(), "black once everything has played");
    }

    @Test
    void aSplashThatThrowsIsSkippedAndTheOthersStillPlay() {
        for (String failure : new String[]{"start", "update", "render", "clear"}) {
            List<String> events = new ArrayList<>();
            RecordingSplash faulty = new RecordingSplash("faulty", 1f, 1f, Color.BLACK, events) {
                @Override
                public void onStart(Window window) {
                    if (failure.equals("start")) throw new IllegalStateException("boom");
                    super.onStart(window);
                }

                @Override
                public void update(float delta) {
                    if (failure.equals("update")) throw new IllegalStateException("boom");
                    super.update(delta);
                }

                @Override
                public void render(Renderer renderer, int width, int height) {
                    if (failure.equals("render")) throw new IllegalStateException("boom");
                    super.render(renderer, width, height);
                }

                @Override
                public Color getClearColor() {
                    if (failure.equals("clear")) throw new IllegalStateException("boom");
                    return super.getClearColor();
                }
            };
            Splash sequence = Splash.sequence(faulty, splash("good", 1f, events));
            sequence.onStart(null);
            // Whichever call throws, one trip through a frame reaches it.
            sequence.update(0.1f);
            sequence.render(null, 100, 100);
            sequence.getClearColor();
            assertTrue(events.contains("start good"), failure + " should move on to the next splash: " + events);
            assertTrue(events.contains("dispose faulty") || failure.equals("start"), failure + " should dispose the faulty splash: " + events);
            sequence.update(1f);
            assertTrue(sequence.isFinished(), failure + ": the good splash should still run to its end");
            assertEquals(1, events.stream().filter("dispose good"::equals).count());
        }
    }

    @Test
    void closingEarlyDisposesThePlayingSplashAndThoseThatNeverPlayed() {
        List<String> events = new ArrayList<>();
        Splash sequence = Splash.sequence(splash("first", 1f, events), splash("second", 1f, events), splash("third", 1f, events));
        sequence.onStart(null);
        sequence.dispose();
        assertEquals(List.of("start first", "dispose first", "dispose second", "dispose third"), events);
    }

    @Test
    void anEmptySequenceIsOverAtOnce() {
        Splash sequence = Splash.sequence();
        sequence.onStart(null);
        assertTrue(sequence.isFinished());
        assertFalse(sequence.isHoldingApp());
        // And the engine's player ends it on its first update.
        SplashPlayer player = SplashPlayer.of(Splash.sequence());
        player.update(0.1f);
        assertFalse(player.isActive());
    }

    @Test
    void worksAsTheWindowsSplash() {
        List<String> events = new ArrayList<>();
        Splash sequence = Splash.sequence(splash("only", 1f, events));
        WindowOptions options = new WindowOptions("test").setSplash(sequence);
        assertSame(sequence, SplashPlayer.choose(options));
    }
}
