package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Per-bone size and position adjustments that survive animation, for things like the proportion sliders of a character
 * creator. An animation sets the position and scale of the nodes it moves every frame, which overwrites a scale you gave
 * a bone; with offsets, the {@link AnimationPlayer} poses the skeleton first and then applies them on top, so they hold
 * whatever clip plays.
 * <pre>
 * BoneOffsets proportions = new BoneOffsets(body.root().find("Armature"));   // right after loading
 * player.setBoneOffsets(proportions);
 * proportions.scale("head", 1.15f);                    // bigger head, in every animation
 * proportions.position("shin_l", new Vec3(0, 0.05f, 0)); // longer lower leg
 * </pre>
 * The scale of a bone is multiplied by the offset, so it also scales the bones under it (a bigger {@code spine} is a
 * bigger chest, arms and head). The position offset is added to the bone's own position, in its parent's space; moving
 * a bone away from its parent along the limb lengthens that limb.
 * <p>
 * The rest values of the skeleton's position and scale are noted when the offsets are made, so make them before
 * anything moves the skeleton (a freshly loaded model is at rest). Rotation is not touched. A bone that has offsets
 * should not also be moved by your own code, since the next pose overwrites it.
 */
public final class BoneOffsets {
    private record Rest(Vec3 position, Vec3 scale) {
    }

    private static final class Offset {
        Vec3 scale = Vec3.ONE;
        Vec3 position = Vec3.ZERO;
    }

    private final Map<String, Node3D> bones;
    private final Map<Node3D, Rest> rest = new IdentityHashMap<>();
    private final Map<Node3D, Offset> offsets = new IdentityHashMap<>();
    private Runnable changed;

    /**
     * @param skeletonRoot the skeleton, usually the armature node; bones are found under it by name
     */
    public BoneOffsets(Node3D skeletonRoot) {
        bones = skeletonRoot.nodesByName();
        skeletonRoot.traverse(n -> rest.put(n, new Rest(n.getPosition(), n.getScale())));
    }

    /**
     * Multiplies the bone's scale (and so its children's) by {@code scale} after the animation.
     *
     * @throws IllegalArgumentException if there is no such bone
     */
    public BoneOffsets scale(String bone, Vec3 scale) {
        offset(bone).scale = scale;
        return notifyChanged();
    }

    public BoneOffsets scale(String bone, float uniform) {
        return scale(bone, new Vec3(uniform, uniform, uniform));
    }

    /**
     * Adds {@code offset} to the bone's position, relative to its parent, after the animation.
     *
     * @throws IllegalArgumentException if there is no such bone
     */
    public BoneOffsets position(String bone, Vec3 offset) {
        offset(bone).position = offset;
        return notifyChanged();
    }

    /**
     * The scale multiplier of a bone, {@link Vec3#ONE} when it has none.
     */
    public Vec3 getScale(String bone) {
        Offset o = offsets.get(node(bone));
        return o == null ? Vec3.ONE : o.scale;
    }

    /**
     * The position offset of a bone, {@link Vec3#ZERO} when it has none.
     */
    public Vec3 getPosition(String bone) {
        Offset o = offsets.get(node(bone));
        return o == null ? Vec3.ZERO : o.position;
    }

    /**
     * Removes the offsets of one bone.
     */
    public BoneOffsets clear(String bone) {
        Offset o = offsets.get(node(bone));
        if (o != null) {
            o.scale = Vec3.ONE;
            o.position = Vec3.ZERO;
        }
        return notifyChanged();
    }

    /**
     * Removes all offsets.
     */
    public BoneOffsets clear() {
        for (Offset o : offsets.values()) {
            o.scale = Vec3.ONE;
            o.position = Vec3.ZERO;
        }
        return notifyChanged();
    }

    /**
     * Poses the adjusted bones at their rest values plus the offsets. A player does this on top of its pose by itself
     * ({@link AnimationPlayer#setBoneOffsets}); call this for a skeleton that no clip moves.
     */
    public BoneOffsets apply() {
        reset();
        applyOffsets();
        return this;
    }

    /**
     * Puts the adjusted bones back to their rest position and scale, ready for the animation to pose them.
     */
    void reset() {
        for (Node3D bone : offsets.keySet()) {
            Rest r = rest.get(bone);
            bone.setPosition(r.position());
            bone.setScale(r.scale());
        }
    }

    /**
     * Applies the offsets to the bones' current (animated) position and scale.
     */
    void applyOffsets() {
        for (Map.Entry<Node3D, Offset> entry : offsets.entrySet()) {
            Node3D bone = entry.getKey();
            Offset o = entry.getValue();
            bone.setScale(bone.getScale().mul(o.scale));
            bone.setPosition(bone.getPosition().add(o.position));
        }
    }

    /**
     * What to run after an offset changes; the player that owns these offsets uses it to repose, so that a slider moved
     * while the animation is paused still shows.
     */
    void onChange(Runnable changed) {
        this.changed = changed;
    }

    private BoneOffsets notifyChanged() {
        if (changed != null) changed.run();
        return this;
    }

    private Node3D node(String bone) {
        Node3D node = bones.get(bone);
        if (node == null) throw new IllegalArgumentException("The skeleton has no bone named " + bone);
        return node;
    }

    private Offset offset(String bone) {
        return offsets.computeIfAbsent(node(bone), n -> new Offset());
    }
}
