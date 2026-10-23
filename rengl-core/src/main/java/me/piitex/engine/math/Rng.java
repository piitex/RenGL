package me.piitex.engine.math;

import java.util.List;
import java.util.Random;

/**
 * A {@link Random} with the picks games make all the time: a number in a range, a coin toss with odds, an item from a
 * list, a direction, a point in a circle.
 * <pre>{@code
 * Rng rng = new Rng(seed);                         // the same seed gives the same game
 * float speed = rng.range(200f, 400f);
 * if (rng.chance(0.1f)) dropLoot();
 * Enemy target = rng.pick(enemies);
 * float lifetime = rng.vary(1.5f, 0.2f);           // 1.2 to 1.8
 * }</pre>
 * It is a {@code Random}, so it can be passed anywhere one is taken, such as a
 * {@link me.piitex.engine.graphics.ParticleSystem}. Keep one seeded instance for everything that must replay the
 * same way from a seed, and draw from it in the same order each time.
 * <p>
 * Like {@code Random}, each pick draws the same numbers as the plain {@code Random} call it stands for:
 * {@code range(min, max)} is {@code min + nextFloat() * (max - min)}, so switching to it doesn't change a seeded
 * game.
 */
public class Rng extends Random {

    /**
     * An Rng with a seed of its own, different every run.
     */
    public Rng() {
        super();
    }

    /**
     * An Rng that gives the same numbers every time for the same {@code seed}.
     */
    public Rng(long seed) {
        super(seed);
    }

    /**
     * A number from {@code min} up to {@code max}. The same number at both ends always gives that number, and the
     * ends may be either way round.
     */
    public float range(float min, float max) {
        return min + nextFloat() * (max - min);
    }

    /**
     * A whole number from {@code min} to {@code max}, both included: {@code range(1, 6)} rolls a die. The ends may be
     * either way round.
     */
    public int range(int min, int max) {
        if (min > max) {
            int swap = min;
            min = max;
            max = swap;
        }
        return min + nextInt(max - min + 1);
    }

    /**
     * {@code value} give or take {@code fraction} of itself: {@code vary(10f, 0.2f)} is from 8 to 12.
     */
    public float vary(float value, float fraction) {
        return value * (1f + (nextFloat() * 2f - 1f) * fraction);
    }

    /**
     * True with a probability of {@code probability}: {@code chance(0.25f)} is true a quarter of the time. 0 or less
     * is never true, 1 or more always.
     */
    public boolean chance(float probability) {
        return nextFloat() < probability;
    }

    /**
     * 1 or -1, evenly.
     */
    public float sign() {
        return nextBoolean() ? 1f : -1f;
    }

    /**
     * An angle in degrees, from 0 up to 360.
     */
    public float angle() {
        return nextFloat() * 360f;
    }

    /**
     * A direction of length 1, every way equally likely.
     */
    public Vec2 direction() {
        return Vec2.fromAngle(angle());
    }

    /**
     * A point in the circle of {@code radius} around (0, 0), evenly spread over its area, not bunched in the middle.
     * Add it to a position: {@code spawn = center.add(rng.inCircle(50f))}.
     */
    public Vec2 inCircle(float radius) {
        double angle = nextDouble() * Math.PI * 2;
        float distance = radius * (float) Math.sqrt(nextFloat());
        return new Vec2(distance * (float) Math.cos(angle), distance * (float) Math.sin(angle));
    }

    /**
     * A point on the outline of the circle of {@code radius} around (0, 0).
     */
    public Vec2 onCircle(float radius) {
        return direction().scale(radius);
    }

    /**
     * A number from a bell curve around {@code mean}: about two thirds of picks land within {@code deviation} of it,
     * and nearly all within three times that.
     */
    public float gaussian(float mean, float deviation) {
        return mean + (float) nextGaussian() * deviation;
    }

    /**
     * One of {@code items}, each equally likely.
     *
     * @throws IllegalArgumentException if there are none
     */
    @SafeVarargs
    public final <T> T pick(T... items) {
        if (items.length == 0) throw new IllegalArgumentException("nothing to pick from");
        return items[nextInt(items.length)];
    }

    /**
     * One of {@code items}, each equally likely.
     *
     * @throws IllegalArgumentException if the list is empty
     */
    public <T> T pick(List<? extends T> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("nothing to pick from");
        return items.get(nextInt(items.size()));
    }

    /**
     * The index of one of {@code weights}, each as likely as its weight: {@code weighted(70, 25, 5)} gives 0 seven
     * times in ten. Weights of 0 or less are never picked.
     *
     * @throws IllegalArgumentException if no weight is above 0
     */
    public int weighted(float... weights) {
        float total = 0f;
        for (float weight : weights) {
            if (weight > 0f) total += weight;
        }
        if (!(total > 0f)) throw new IllegalArgumentException("no weight is above 0");
        float roll = nextFloat() * total;
        int last = -1;
        for (int i = 0; i < weights.length; i++) {
            if (!(weights[i] > 0f)) continue;
            last = i;
            roll -= weights[i];
            if (roll < 0f) return i;
        }
        return last; // rounding left a sliver at the end
    }

    /**
     * Shuffles {@code items} in place, every order equally likely.
     */
    public <T> void shuffle(List<T> items) {
        for (int i = items.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T swap = items.get(i);
            items.set(i, items.get(j));
            items.set(j, swap);
        }
    }
}
