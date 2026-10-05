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
        SCALE(3),
        /**
         * The blend-shape weights of a node ({@link Node3D#setMorphWeights}): one value per morph target at every key,
         * however many the mesh has.
         */
        WEIGHTS(-1);

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
     * One property of one node over time. The key arrays are copied when you build a channel, and are shared between a
     * channel and its {@link AnimationClip#retarget retargeted} copies: don't change the arrays the accessors return.
     */
    public static final class Channel {
        private final Node3D target;
        private final Path path;
        private final float[] times;
        private final float[] values;
        private final Interpolation interpolation;

        /**
         * @param times  the keys' times in seconds, rising
         * @param values the value at each key, {@code path} width numbers per key
         */
        public Channel(Node3D target, Path path, float[] times, float[] values, Interpolation interpolation) {
            if (times.length == 0) throw new IllegalArgumentException("A channel needs at least one key");
            if (path == Path.WEIGHTS) {
                if (values.length == 0 || values.length % times.length != 0) {
                    throw new IllegalArgumentException("WEIGHTS needs the same number of values at every key (" + times.length + " keys, " + values.length + " values)");
                }
            } else if (values.length != times.length * path.width) {
                throw new IllegalArgumentException(path + " needs " + path.width + " values per key, " + times.length + " keys");
            }
            this(target, path, times.clone(), values.clone(), interpolation, true);
        }

        /**
         * Takes the arrays as they are, already checked and not to be changed; the flag only tells it from the public one.
         */
        private Channel(Node3D target, Path path, float[] times, float[] values, Interpolation interpolation, boolean shared) {
            this.target = target;
            this.path = path;
            this.times = times;
            this.values = values;
            this.interpolation = interpolation;
        }

        public Node3D target() {
            return target;
        }

        public Path path() {
            return path;
        }

        public float[] times() {
            return times;
        }

        public float[] values() {
            return values;
        }

        public Interpolation interpolation() {
            return interpolation;
        }

        /**
         * The same keys moving another node.
         */
        public Channel withTarget(Node3D other) {
            return new Channel(other, path, times, values, interpolation, true);
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

    /**
     * The same movement for another character: a clip whose channels move the nodes under {@code skeletonRoot} that have
     * the same names as the nodes this clip moves ({@link Node3D#find}). One animation library can then drive any number
     * of characters that share a skeleton, such as an NPC crowd made with {@link Node3D#copy()}; give each its own
     * {@link AnimationPlayer} ({@link AnimationPlayer#retarget}).
     * <pre>
     * AnimationClip walk = library.clip("Walk");
     * AnimationClip npcWalk = walk.retarget(npc.find("Armature"));
     * </pre>
     * The keys are shared with this clip, not copied. A channel whose node the other skeleton lacks (a prop, a bone it
     * doesn't have) is left out.
     *
     * @throws IllegalArgumentException if none of the channels found a node, which means the names don't match
     */
    public AnimationClip retarget(Node3D skeletonRoot) {
        return retarget(skeletonRoot.nodesByName());
    }

    AnimationClip retarget(java.util.Map<String, Node3D> nodes) {
        List<Channel> mapped = new java.util.ArrayList<>(channels.size());
        for (Channel channel : channels) {
            String targetName = channel.target().getName();
            Node3D node = targetName == null ? null : nodes.get(targetName);
            if (node != null) mapped.add(channel.withTarget(node));
        }
        if (mapped.isEmpty() && !channels.isEmpty()) {
            throw new IllegalArgumentException("None of the nodes " + name + " moves exist in the skeleton, so there is nothing to retarget");
        }
        return new AnimationClip(name, duration, mapped);
    }
}
