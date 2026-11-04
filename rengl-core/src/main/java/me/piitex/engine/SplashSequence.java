package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Plays splashes one after another as a single {@link Splash}; see {@link Splash#sequence}.
 * <p>
 * A splash in the sequence that throws is logged, disposed and skipped, and the next one plays, so a faulty splash
 * from a third party does not take the others down with it.
 */
final class SplashSequence implements Splash {
    private static final Logger log = LogManager.getLogger("Splash");

    private final List<Splash> splashes;
    private Window window;
    /**
     * The splash playing now; equal to the number of splashes once all are over.
     */
    private int index;

    SplashSequence(Splash[] splashes) {
        this.splashes = List.of(splashes);
    }

    @Override
    public void onStart(Window window) {
        this.window = window;
        startCurrent();
    }

    @Override
    public void update(float delta) {
        if (index >= splashes.size()) return;
        Splash current = splashes.get(index);
        try {
            current.update(delta);
            if (!current.isFinished()) return;
        } catch (RuntimeException e) {
            logFailure(current, "updating", e);
        }
        moveOn();
    }

    @Override
    public void render(Renderer renderer, int width, int height) {
        if (index >= splashes.size()) return;
        try {
            splashes.get(index).render(renderer, width, height);
        } catch (RuntimeException e) {
            logFailure(splashes.get(index), "drawing", e);
            moveOn();
        }
    }

    /**
     * True through every splash but the last, so the application never shows between two splashes; the last one
     * decides for itself, so it can fade out over the application.
     */
    @Override
    public boolean isHoldingApp() {
        if (index >= splashes.size()) return false;
        if (index < splashes.size() - 1) return true;
        Splash last = splashes.get(index);
        try {
            return last.isHoldingApp();
        } catch (RuntimeException e) {
            logFailure(last, "asking whether it holds the application", e);
            moveOn();
            return false;
        }
    }

    @Override
    public boolean isFinished() {
        return index >= splashes.size();
    }

    @Override
    public Color getClearColor() {
        if (index >= splashes.size()) return Color.BLACK;
        Splash current = splashes.get(index);
        try {
            Color color = current.getClearColor();
            return color != null ? color : Color.BLACK;
        } catch (RuntimeException e) {
            logFailure(current, "asking for its clear colour", e);
            moveOn();
            return Color.BLACK;
        }
    }

    /**
     * Disposes of the splash playing now and every one that has not played, such as when the window closes early.
     */
    @Override
    public void dispose() {
        while (index < splashes.size()) {
            disposeOf(splashes.get(index));
            index++;
        }
    }

    /**
     * Ends the splash playing now and starts the next.
     */
    private void moveOn() {
        disposeOf(splashes.get(index));
        index++;
        startCurrent();
    }

    /**
     * Calls {@link Splash#onStart} on the splash at the current position, skipping over any that fail to start.
     */
    private void startCurrent() {
        while (index < splashes.size()) {
            Splash current = splashes.get(index);
            try {
                current.onStart(window);
                return;
            } catch (RuntimeException e) {
                logFailure(current, "starting", e);
                disposeOf(current);
                index++;
            }
        }
    }

    private static void disposeOf(Splash splash) {
        try {
            splash.dispose();
        } catch (RuntimeException e) {
            log.error("The splash {} threw while it was being disposed", splash.getClass().getName(), e);
        }
    }

    private static void logFailure(Splash splash, String doing, RuntimeException e) {
        log.error("The splash {} threw while {}; it is skipped and the next one plays", splash.getClass().getName(), doing, e);
    }
}
