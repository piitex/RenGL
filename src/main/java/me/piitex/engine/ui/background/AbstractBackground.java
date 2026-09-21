package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;

/**
 * A {@link Background} with its own enabled, opacity, and fade state. Extending this
 * rather than implementing {@link Background} directly puts independent
 * enable and disable, {@link #fadeIn}, and {@link #fadeOut} on the background object
 * itself, so {@code myConstellationBackground.fadeOut(1f)} works with no Element or
 * Overlay involved. The alternative is wrapping a background in a standalone Overlay
 * Element and using {@link Element}'s own {@code setEnabled}, {@code fadeIn}, and
 * {@code fadeOut}.
 * <p>
 * {@link #render} and {@link #update} are {@code final} here and handle the enabled,
 * opacity, and fade bookkeeping themselves. Subclasses implement
 * {@link #renderEffect} and {@link #updateEffect} instead of overriding those, the same
 * split {@link Element#renderWithTransitions} makes between opacity handling and a
 * subclass's own {@link Element#render}.
 */
public abstract class AbstractBackground implements Background {
    private boolean enabled = true;
    private float opacity = 1f;

    private float fadeStartOpacity;
    private float fadeTargetOpacity = -1f; // -1 = no fade in progress
    private float fadeDurationSeconds;
    private float fadeElapsedSeconds;
    private Runnable onFadeComplete;

    /** Whether this background renders or updates at all. See {@link #render} and {@link #update}. This is independent of whatever Element it is attached to through {@link Element#setBackground}: disabling that Element already skips this background (see {@link Element#render}), while this turns the background off on its own. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** This background's own opacity in [0, 1], defaulting to 1, applied independently of whatever Element it is attached to. See {@link Element#getOpacity()} for the separate whole-Element version. Setting this directly cancels any {@link #fadeIn} or {@link #fadeOut} in progress. */
    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = clamp(opacity);
        fadeTargetOpacity = -1f;
    }

    /** Fades this background in from its current opacity to fully opaque over {@code durationSeconds}. */
    public void fadeIn(float durationSeconds) {
        fadeIn(durationSeconds, null);
    }

    /** As {@link #fadeIn(float)}, firing {@code onComplete} once the fade finishes. */
    public void fadeIn(float durationSeconds, Runnable onComplete) {
        startFade(1f, durationSeconds, onComplete);
    }

    /** Fades this background out from its current opacity to fully transparent over {@code durationSeconds}. This affects {@link #getOpacity()} only. {@link #isEnabled()} stays true, so {@link #updateEffect}, such as a particle simulation, keeps running underneath. Disable it separately once faded out to stop that work as well. */
    public void fadeOut(float durationSeconds) {
        fadeOut(durationSeconds, null);
    }

    /** As {@link #fadeOut(float)}, firing {@code onComplete} once the fade finishes. */
    public void fadeOut(float durationSeconds, Runnable onComplete) {
        startFade(0f, durationSeconds, onComplete);
    }

    private void startFade(float target, float durationSeconds, Runnable onComplete) {
        fadeStartOpacity = opacity;
        fadeTargetOpacity = target;
        fadeDurationSeconds = Math.max(0.0001f, durationSeconds);
        fadeElapsedSeconds = 0f;
        onFadeComplete = onComplete;
    }

    private void advanceFade(float delta) {
        if (fadeTargetOpacity < 0f) return;

        fadeElapsedSeconds += delta;
        float t = Math.min(1f, fadeElapsedSeconds / fadeDurationSeconds);
        opacity = fadeStartOpacity + (fadeTargetOpacity - fadeStartOpacity) * t;

        if (t >= 1f) {
            fadeTargetOpacity = -1f;
            Runnable callback = onFadeComplete;
            onFadeComplete = null;
            if (callback != null) callback.run();
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public final void update(float delta, float x, float y, float width, float height) {
        if (!enabled) return;
        advanceFade(delta);
        updateEffect(delta, x, y, width, height);
    }

    @Override
    public final void render(Renderer renderer, float x, float y, float width, float height) {
        if (!enabled || opacity <= 0f) return;

        if (opacity < 1f) {
            renderer.pushOpacityLayer(x, y, width, height, opacity);
            renderEffect(renderer, x, y, width, height);
            renderer.popOpacityLayer();
        } else {
            renderEffect(renderer, x, y, width, height);
        }
    }

    /** A subclass's per-frame update, called from {@link #update} after this class's own enabled and fade bookkeeping. See that method. It defaults to a no-op, which suits a purely static background such as {@link DotGridBackground}. */
    protected void updateEffect(float delta, float x, float y, float width, float height) {}

    /** A subclass's drawing, called from {@link #render} after this class's own enabled and opacity handling. See that method. */
    protected abstract void renderEffect(Renderer renderer, float x, float y, float width, float height);
}
