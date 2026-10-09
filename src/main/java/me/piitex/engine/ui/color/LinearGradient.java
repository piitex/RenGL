package me.piitex.engine.ui.color;

import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.List;
import java.util.Objects;

/**
 * A fill that blends from one color to the next along a straight line: two colors, or more spaced evenly between.
 * <p>
 * Without an angle the line runs corner to corner, from the top left to the bottom right, which is what a gradient
 * always did and stays what {@code new LinearGradient(start, end)} gives. With an {@link #getAngle() angle} it
 * runs in that direction across the box and is as long as the box is in that direction, so the first color
 * is at one edge or corner and the last at the opposite one: 0 is left to right, 90 top to bottom, 180 right to left and
 * 270 bottom to top.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public class LinearGradient implements Paint {
    private final List<Color> colors;
    private final float angle;

    /** Corner to corner, from {@code startColor} to {@code endColor}. */
    public LinearGradient(Color startColor, Color endColor) {
        this(List.of(requireColor(startColor), requireColor(endColor)), Float.NaN);
    }

    /** From {@code startColor} to {@code endColor} in the direction {@code angle} degrees: see the class docs. */
    public LinearGradient(Color startColor, Color endColor, float angle) {
        this(List.of(requireColor(startColor), requireColor(endColor)), angle);
    }

    /**
     * Through {@code colors}, two or more, spaced evenly, in the direction {@code angle} degrees, or corner to corner
     * for {@link Float#NaN}.
     *
     * @throws IllegalArgumentException if there are fewer than two colors
     * @throws NullPointerException     if a color is null
     */
    public LinearGradient(List<Color> colors, float angle) {
        if (colors == null || colors.size() < 2) throw new IllegalArgumentException("a gradient needs at least two colors");
        for (Color color : colors) requireColor(color);
        this.colors = List.copyOf(colors);
        this.angle = angle;
    }

    private static Color requireColor(Color color) {
        return Objects.requireNonNull(color, "a gradient's colors must not be null");
    }

    public Color getStartColor() {
        return colors.getFirst();
    }

    public Color getEndColor() {
        return colors.getLast();
    }

    /** Every color, first to last. */
    public List<Color> getColors() {
        return colors;
    }

    /**
     * The color a fraction {@code t} of the way along the gradient, from 0 at the first color to 1 at the last
     * (clamped), blended between the two colors either side of it. The colors are spaced evenly.
     */
    public Color colorAt(float t) {
        float position = Math.clamp(t, 0f, 1f) * (colors.size() - 1);
        int index = Math.min((int) position, colors.size() - 2);
        float local = position - index;
        Color a = colors.get(index), b = colors.get(index + 1);
        return new Color(a.getR() + (b.getR() - a.getR()) * local, a.getG() + (b.getG() - a.getG()) * local,
                a.getB() + (b.getB() - a.getB()) * local, a.getA() + (b.getA() - a.getA()) * local);
    }

    /**
     * The direction in degrees, as the class docs say, or {@link Float#NaN} for corner to corner. Use {@link #hasAngle()}
     * to tell them apart.
     */
    public float getAngle() {
        return angle;
    }

    /** Whether the direction is set, not left as corner to corner. */
    public boolean hasAngle() {
        return !Float.isNaN(angle);
    }
}
