package me.piitex.engine.gl.renderer;

import me.piitex.engine.ui.color.Color;

/**
 * The opacity set by {@link Renderer#pushAlpha} and {@link Renderer#popAlpha}, for {@link Renderer} implementations:
 * each push multiplies the current alpha, each pop restores the one before. Draw calls apply {@link #get()} to their
 * colors with {@link #argb(Color)}, or to a Skia paint's alpha when it draws with a shader or an image.
 */
public final class AlphaStack {
    private float[] saved = new float[8];
    private int size;
    private float current = 1f;

    /**
     * Multiplies the current alpha by {@code alpha}, clamped to [0, 1], until the matching {@link #pop()}.
     */
    public void push(float alpha) {
        if (size == saved.length) saved = java.util.Arrays.copyOf(saved, size * 2);
        saved[size++] = current;
        current *= Math.clamp(alpha, 0f, 1f);
    }

    /**
     * Restores the alpha from before the last {@link #push}.
     *
     * @throws IllegalStateException if nothing was pushed
     */
    public void pop() {
        if (size == 0) throw new IllegalStateException("popAlpha without a matching pushAlpha");
        current = saved[--size];
    }

    /**
     * The alpha everything is drawn at now, from 0 to 1: 1 when nothing is pushed.
     */
    public float get() {
        return current;
    }

    /**
     * Drops everything pushed, back to full opacity. Renderers call it at the start of each frame, so a push left
     * unmatched by a failed draw doesn't fade the frames after it.
     */
    public void reset() {
        size = 0;
        current = 1f;
    }

    /**
     * {@code color} as packed ARGB, with its alpha multiplied by the current alpha.
     */
    public int argb(Color color) {
        return argb(color.toARGB());
    }

    /**
     * {@code argb} with its alpha multiplied by the current alpha.
     */
    public int argb(int argb) {
        if (current >= 1f) return argb;
        int alpha = Math.round((argb >>> 24) * current);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
