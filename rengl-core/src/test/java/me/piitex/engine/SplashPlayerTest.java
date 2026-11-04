package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which splash a window plays, and how the engine's player treats one that is the application's own.
 */
class SplashPlayerTest {
    /**
     * A splash that holds the application for {@code holdSeconds}, finishes after {@code finishSeconds}, and counts its calls.
     */
    private static class TimedSplash implements Splash {
        final float holdSeconds, finishSeconds;
        float time;
        int renders, disposals;

        TimedSplash(float holdSeconds, float finishSeconds) {
            this.holdSeconds = holdSeconds;
            this.finishSeconds = finishSeconds;
        }

        @Override
        public void update(float delta) {
            time += delta;
        }

        @Override
        public void render(Renderer renderer, int width, int height) {
            renders++;
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
        public void dispose() {
            disposals++;
        }
    }

    @Test
    void theRenGLSplashIsOnByDefault() {
        WindowOptions options = new WindowOptions("test");
        assertTrue(options.isSplashEnabled());
        assertNull(options.getSplash());
        assertInstanceOf(RenGLSplash.class, SplashPlayer.choose(options));
    }

    @Test
    void theSplashCanBeSwitchedOff() {
        WindowOptions options = new WindowOptions("test").setSplashEnabled(false);
        assertFalse(options.isSplashEnabled());
        assertNull(SplashPlayer.choose(options));
        // A custom splash does not turn it back on.
        options.setSplash(new TimedSplash(1f, 1f));
        assertNull(SplashPlayer.choose(options));
        // And switching it on again plays the custom one.
        options.setSplashEnabled(true);
        assertNotNull(SplashPlayer.choose(options));
    }

    @Test
    void anApplicationSplashReplacesTheRenGLOne() {
        TimedSplash own = new TimedSplash(1f, 1f);
        WindowOptions options = new WindowOptions("test").setSplash(own);
        assertSame(own, options.getSplash());
        assertSame(own, SplashPlayer.choose(options));
        // Null goes back to the RenGL splash.
        options.setSplash(null);
        assertInstanceOf(RenGLSplash.class, SplashPlayer.choose(options));
    }

    @Test
    void holdsTheApplicationFadesAndThenDisposesOnce() {
        TimedSplash splash = new TimedSplash(1f, 1.5f);
        SplashPlayer player = SplashPlayer.of(splash);
        assertTrue(player.holdsApp());
        player.update(0.5f);
        assertTrue(player.holdsApp());
        player.update(0.6f);
        assertFalse(player.holdsApp(), "past the hold, the application shows through");
        assertTrue(player.isActive(), "but the splash is still fading over it");
        player.update(0.5f);
        assertFalse(player.isActive());
        assertEquals(1, splash.disposals);
        player.stop();
        assertEquals(1, splash.disposals, "stopping again does not dispose again");
        player.render(null, 100, 100);
        assertEquals(0, splash.renders, "a finished splash is not drawn");
    }

    @Test
    void aSplashThatThrowsIsDroppedAndTheApplicationIsReleased() {
        for (String failure : new String[]{"update", "holds", "render", "clear"}) {
            TimedSplash splash = new TimedSplash(100f, 100f) {
                @Override
                public void update(float delta) {
                    if (failure.equals("update")) throw new IllegalStateException("boom");
                }

                @Override
                public boolean isHoldingApp() {
                    if (failure.equals("holds")) throw new IllegalStateException("boom");
                    return true;
                }

                @Override
                public void render(Renderer renderer, int width, int height) {
                    if (failure.equals("render")) throw new IllegalStateException("boom");
                }

                @Override
                public Color getClearColor() {
                    if (failure.equals("clear")) throw new IllegalStateException("boom");
                    return Color.WHITE;
                }
            };
            SplashPlayer player = SplashPlayer.of(splash);
            player.update(0.1f);
            player.holdsApp();
            player.render(null, 100, 100);
            player.clearColor();
            assertFalse(player.isActive(), failure + " should drop the splash");
            assertFalse(player.holdsApp(), failure + " should release the application");
            assertEquals(1, splash.disposals, failure + " should still dispose it");
        }
    }

    @Test
    void theClearColorComesFromTheSplash() {
        SplashPlayer player = SplashPlayer.of(new TimedSplash(1f, 1f) {
            @Override
            public Color getClearColor() {
                return Color.WHITE;
            }
        });
        assertSame(Color.WHITE, player.clearColor());
        assertSame(Color.BLACK, SplashPlayer.of(new TimedSplash(1f, 1f)).clearColor(), "black unless the splash says otherwise");
    }
}
