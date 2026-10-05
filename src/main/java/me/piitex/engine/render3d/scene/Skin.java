package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;

import java.util.List;

/**
 * What a skinned mesh follows: the joints (nodes of the scene, usually a skeleton) and, for each, the matrix that undoes
 * its pose when the mesh was modelled (the inverse bind matrix). Moving the joint nodes, for example with an
 * {@link AnimationPlayer}, bends the mesh. A model file with bones makes these for you.
 * <p>
 * A mesh drawn with a skin ignores its own node's transform: the joints place it, as in glTF. The skin holds up to
 * {@link #MAX_JOINTS} joints.
 */
public final class Skin {
    public static final int MAX_JOINTS = 256;

    private final List<Node3D> joints;
    private final Mat4[] inverseBind;

    /**
     * @param joints      the joint nodes; a vertex's joint number is an index into this list
     * @param inverseBind one matrix per joint, taking a vertex from the mesh's space into that joint's space
     */
    public Skin(List<Node3D> joints, List<Mat4> inverseBind) {
        if (joints.size() != inverseBind.size()) throw new IllegalArgumentException("A skin needs one inverse bind matrix per joint");
        if (joints.size() > MAX_JOINTS) throw new IllegalArgumentException("A skin holds at most " + MAX_JOINTS + " joints, not " + joints.size());
        this.joints = List.copyOf(joints);
        this.inverseBind = inverseBind.toArray(new Mat4[0]);
    }

    public List<Node3D> joints() {
        return joints;
    }

    public Mat4 inverseBind(int joint) {
        return inverseBind[joint];
    }

    /**
     * The matrix taking a vertex of the mesh to its place in the scene for one joint: the joint's scene transform times its
     * inverse bind matrix.
     */
    public Mat4 jointMatrix(int joint) {
        return joints.get(joint).worldMatrix().mul(inverseBind[joint]);
    }

    /**
     * The same skin on another skeleton: each joint is replaced by the node with the same name under {@code skeletonRoot}
     * ({@link Node3D#find}), and the inverse bind matrices stay as they are. Use it to bind a part loaded from its own file,
     * which comes with its own copy of the skeleton, to a body's joints; {@link Node3D#bindSkeleton} does this for a
     * whole part. Both skeletons must have been modelled in the same pose, as a character and its hair are.
     *
     * @throws IllegalArgumentException if a joint has no namesake under {@code skeletonRoot}; the message lists them
     */
    public Skin rebind(Node3D skeletonRoot) {
        return rebind(skeletonRoot.nodesByName());
    }

    Skin rebind(java.util.Map<String, Node3D> bones) {
        List<Node3D> mapped = new java.util.ArrayList<>(joints.size());
        List<String> missing = new java.util.ArrayList<>();
        for (Node3D joint : joints) {
            Node3D replacement = joint.getName() == null ? null : bones.get(joint.getName());
            if (replacement == null) missing.add(String.valueOf(joint.getName()));
            mapped.add(replacement);
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("The skeleton has no bones named " + missing);
        return new Skin(mapped, List.of(inverseBind));
    }

    /**
     * A copy that follows other nodes: the same joints looked up by name under {@code newRoot}, which is how
     * {@link Node3D#copy()} keeps a copied character moving with its own skeleton.
     */
    Skin remap(Node3D oldRoot, Node3D newRoot) {
        List<Node3D> mapped = new java.util.ArrayList<>();
        for (Node3D joint : joints) {
            Node3D replacement = null;
            java.util.ArrayDeque<Integer> path = new java.util.ArrayDeque<>();
            Node3D n = joint;
            while (n != null && n != oldRoot) {
                Node3D parent = n.getParent();
                if (parent == null) break;
                path.push(parent.getChildren().indexOf(n));
                n = parent;
            }
            if (n == oldRoot) {
                replacement = newRoot;
                for (int index : path) replacement = replacement.getChildren().get(index);
            }
            mapped.add(replacement != null ? replacement : joint);
        }
        return new Skin(mapped, List.of(inverseBind));
    }
}
