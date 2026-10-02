package me.piitex.engine.render3d.scene;

import java.util.List;

/**
 * A recorded movement: for a number of nodes, how their position, rotation or scale change over time. Play one with an
 * {@link AnimationPlayer}. Model files with animations load as clips ({@link Model#animations()}); you can also build
 * your own.
 * <pre>
 * AnimationClip bob = new AnimationClip("bob", 2f, List.of(new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION,
 *         new float[]{0f, 1f, 2f}, new float[]{0, 0, 0,  0, 1, 0,  0, 0, 0}, AnimationClip.Interpolation.LINEAR)));
 * </pre>
 */
public final class AnimationClip {
    /**
     * What a channel moves.
     */
    public enum Path {
        /**
         * Three values per key: x, y, z.
         */
        TRANSLATION(3),
        /**
         * Four values per key: x, y, z, w of a unit quaternion.
         */
        ROTATION(4),
        /**
         * Three values per key: x, y, z.
         */
        SCALE(3);

        final int width;

        Path(int width) {
            this.width = width;
        }
    }

    public enum Interpolation {
        /**
         * Blends smoothly between keys (rotations take the short way round).
         */
        LINEAR,
        /**
         * Holds each key's value until the next key.
         */
        STEP
    }

    /**
     * One property of one node over time.
     *
     * @param times  the keys' times in seconds, rising
     * @param values the value at each key, {@code path} width numbers per key
     */
    public record Channel(Node3D target, Path path, float[] times, float[] values, Interpolation interpolation) {
        public Channel {
            if (times.length == 0) throw new IllegalArgumentException("A channel needs at least one key");
            if (values.length != times.length * path.width) {
                throw new IllegalArgumentException(path + " needs " + path.width + " values per key, " + times.length + " keys");
            }
            times = times.clone();
            values = values.clone();
        }
    }

    private final String name;
    private final float duration;
    private final List<Channel> channels;

    public AnimationClip(String name, float duration, List<Channel> channels) {
        this.name = name;
        this.duration = Math.max(0f, duration);
        this.channels = List.copyOf(channels);
    }

    public String name() {
        return name;
    }

    /**
     * The length in seconds.
     */
    public float duration() {
        return duration;
    }

    public List<Channel> channels() {
        return channels;
    }
}
