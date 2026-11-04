package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Plays splashes one after another as a single {@link Splash}; see {@link Splash#sequence}.
 * <p>
 * A splash that has stopped holding the application is, by the contract of {@link Splash}, only fading out. The next
 * splash starts at that moment and plays under the fade, so one dissolves into the next and the window never goes blank
 * between them. A splash that keeps holding the application until it is finished is followed by the next one when it ends.
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
    /**
     * The splash before the current one while it fades out over it, or null.
     */
    private Splash leaving;

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
        if (leaving != null) updateLeaving(delta);
        if (index >= splashes.size()) return;
        Splash current = splashes.get(index);
        try {
            current.update(delta);
            if (current.isFinished()) {
                moveOn();
            } else if (index < splashes.size() - 1 && leaving == null && !current.isHoldingApp()) {
                // The current splash let go of the application, so it is fading: the next one starts under that fade.
                crossfadeToNext();
            }
        } catch (RuntimeException e) {
            logFailure(current, "updating", e);
            moveOn();
        }
    }

    private void updateLeaving(float delta) {
        try {
            leaving.update(delta);
            if (!leaving.isFinished()) return;
        } catch (RuntimeException e) {
            logFailure(leaving, "fading out", e);
        }
        disposeOf(leaving);
        leaving = null;
    }

    @Override
    public void render(Renderer renderer, int width, int height) {
        if (index < splashes.size()) {
            Splash current = splashes.get(index);
            try {
                current.render(renderer, width, height);
            } catch (RuntimeException e) {
                logFailure(current, "drawing", e);
                moveOn();
            }
        }
        // The one that is leaving is drawn last, so its fade is over the splash that follows it.
        if (leaving != null) {
            try {
                leaving.render(renderer, width, height);
            } catch (RuntimeException e) {
                logFailure(leaving, "drawing its fade", e);
                disposeOf(leaving);
                leaving = null;
            }
        }
    }

    /**
     * True through every splash but the last, so the application never shows between two splashes; the last one
     * decides for itself, so it can fade out over the application. A splash still fading out over the last one keeps the
     * application held too.
     */
    @Override
    public boolean isHoldingApp() {
        if (index >= splashes.size()) return false;
        if (index < splashes.size() - 1) return true;
        Splash last = splashes.get(index);
        try {
            return last.isHoldingApp() || leaving != null;
        } catch (RuntimeException e) {
            logFailure(last, "asking whether it holds the application", e);
            moveOn();
            return false;
        }
    }

    @Override
    public boolean isFinished() {
        return index >= splashes.size() && leaving == null;
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
     * Disposes of the splash playing now, the one fading out and every one that has not played, such as when the window
     * closes early.
     */
    @Override
    public void dispose() {
        if (leaving != null) {
            disposeOf(leaving);
            leaving = null;
        }
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
     * Starts the next splash while the current one carries on as the one leaving: it stays alive, drawn over the next, until
     * it finishes. Only one splash leaves at a time, so a third waits for the fade before to end, and at most two play at once.
     */
    private void crossfadeToNext() {
        FrameTrace.note("the splash " + splashes.get(index).getClass().getSimpleName() + " is fading; the next one starts under it");
        leaving = splashes.get(index);
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
