package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs one {@link Splash} for the engine, on the render thread. A splash is application code, so a call into it that
 * throws is logged and the splash is dropped: the application is released instead of staying held behind a splash that
 * can no longer finish.
 */
final class SplashPlayer {
    private static final Logger log = LogManager.getLogger("Splash");

    /**
     * The splash being played, or null once it has finished, failed or been stopped.
     */
    private Splash splash;

    private SplashPlayer(Splash splash) {
        this.splash = splash;
    }

    /**
     * The splash the window's options ask for: none when it is switched off, the application's own when it set one,
     * otherwise the RenGL splash.
     */
    static Splash choose(WindowOptions options) {
        if (!options.isSplashEnabled()) return null;
        Splash custom = options.getSplash();
        return custom != null ? custom : new RenGLSplash();
    }

    /**
     * Starts the splash the window's options ask for over {@code window}, or returns null when there is none.
     */
    static SplashPlayer start(Window window) {
        Splash chosen = choose(window.getWindowOptions());
        if (chosen == null) return null;
        SplashPlayer player = new SplashPlayer(chosen);
        try {
            chosen.onStart(window);
        } catch (RuntimeException e) {
            player.fail("starting", e);
        }
        return player;
    }

    /**
     * Plays an already made splash, for tests that have no window.
     */
    static SplashPlayer of(Splash splash) {
        return new SplashPlayer(splash);
    }

    /**
     * Whether the splash is still being played.
     */
    boolean isActive() {
        return splash != null;
    }

    /**
     * Advances the splash and ends it once it says it is finished.
     */
    void update(float delta) {
        Splash current = splash;
        if (current == null) return;
        try {
            current.update(delta);
            if (current.isFinished()) stop();
        } catch (RuntimeException e) {
            fail("updating", e);
        }
    }

    /**
     * Whether the application is held back this frame.
     */
    boolean holdsApp() {
        Splash current = splash;
        if (current == null) return false;
        try {
            return current.isHoldingApp();
        } catch (RuntimeException e) {
            fail("asking whether it holds the application", e);
            return false;
        }
    }

    /**
     * The colour to clear the window to while the application is held.
     */
    Color clearColor() {
        Splash current = splash;
        if (current == null) return Color.BLACK;
        try {
            Color color = current.getClearColor();
            return color != null ? color : Color.BLACK;
        } catch (RuntimeException e) {
            fail("asking for its clear colour", e);
            return Color.BLACK;
        }
    }

    /**
     * Draws the splash over a window of {@code width} x {@code height}.
     */
    void render(Renderer renderer, int width, int height) {
        Splash current = splash;
        if (current == null) return;
        try {
            current.render(renderer, width, height);
        } catch (RuntimeException e) {
            fail("drawing", e);
        }
    }

    /**
     * Ends the splash and frees what it holds. Safe to call again.
     */
    void stop() {
        Splash current = splash;
        splash = null;
        if (current == null) return;
        try {
            current.dispose();
        } catch (RuntimeException e) {
            log.error("The splash {} threw while it was being disposed", current.getClass().getName(), e);
        }
    }

    private void fail(String doing, RuntimeException e) {
        log.error("The splash {} threw while {}; it is dropped and the application carries on",
                splash != null ? splash.getClass().getName() : "(finished)", doing, e);
        stop();
    }
}
