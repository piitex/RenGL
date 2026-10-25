package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A thing in a {@link Scene3D}: a position, rotation and scale relative to its parent, optionally a {@link Mesh} drawn
 * with a {@link Material}, and any number of child nodes that move with it. A node with no mesh is a handle for
 * grouping and moving things together.
 * <pre>
 * Node3D box = new Node3D("box", Mesh.cube(1f), new Material(Color.RED));
 * box.setPosition(0, 0.5f, 0);
 * scene.add(box);
 * </pre>
 * Not thread-safe; change nodes from the render thread (for example in {@link Scene3D#setUpdateHandler}) or while
 * the scene isn't drawing.
 */
public final class Node3D {
    private String name;
    private Node3D parent;
    private final List<Node3D> children = new ArrayList<>();
    private Vec3 position = Vec3.ZERO;
    private Quat rotation = Quat.IDENTITY;
    private Vec3 scale = Vec3.ONE;
    private Mesh mesh;
    private Material material;
    private boolean visible = true;
    private boolean pickable = true;
    private boolean castShadow = true;
    private boolean receiveShadow = true;
    private boolean frustumCulled = true;
    private boolean occluder;
    private boolean sortTriangles;
    private Instances instances;
    private Skin skin;
    private MorphState morph;

    /**
     * The blend-shape weights of a node, held apart so that the nodes of one multi-part mesh can share them.
     */
    private static final class MorphState {
        float[] weights = new float[0];
    }

    public Node3D(String name) {
        this.name = name;
    }

    public Node3D(String name, Mesh mesh, Material material) {
        this.name = name;
        this.mesh = mesh;
        this.material = material;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Node3D getParent() {
        return parent;
    }

    public List<Node3D> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Adds {@code child} under this node, taking it from its previous parent.
     */
    public Node3D add(Node3D child) {
        if (child == this) throw new IllegalArgumentException("A node can't be its own child");
        for (Node3D n = this; n != null; n = n.parent) {
            if (n == child) throw new IllegalArgumentException("A node can't be added under its own descendant");
        }
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this;
        children.add(child);
        return this;
    }

    public boolean remove(Node3D child) {
        if (!children.remove(child)) return false;
        child.parent = null;
        return true;
    }

    public Mesh getMesh() {
        return mesh;
    }

    public Node3D setMesh(Mesh mesh) {
        this.mesh = mesh;
        return this;
    }

    public Material getMaterial() {
        return material;
    }

    public Node3D setMaterial(Material material) {
        this.material = material;
        return this;
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * A node that isn't visible hides itself and everything under it.
     */
    public Node3D setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public boolean isPickable() {
        return pickable;
    }

    /**
     * Whether {@link Scene3D#pick} can hit this node's own mesh (true by default). Its children are tested either way,
     * so turn it off on a decoration that clicks should go through, such as a floor or a glass dome.
     */
    public Node3D setPickable(boolean pickable) {
        this.pickable = pickable;
        return this;
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    /**
     * Whether this node's mesh throws a shadow when the scene has a shadow-casting light (true by default).
     */
    public Node3D setCastShadow(boolean castShadow) {
        this.castShadow = castShadow;
        return this;
    }

    /**
     * How much of each of the mesh's {@link Mesh#setMorphTargets blend shapes} is mixed in: 0 is none, 1 is the shape
     * fully on. A copy; one entry per target (the mesh's own defaults until set).
     */
    public float[] getMorphWeights() {
        Mesh m = mesh;
        if (morph != null) return morph.weights.clone();
        return m == null ? new float[0] : m.morphDefaults().clone();
    }

    /**
     * Sets the blend-shape weights. Weights for shapes the mesh doesn't have are ignored and missing ones are 0. The
     * animation of a model file does this each frame through {@link AnimationClip.Path#WEIGHTS}.
     */
    public Node3D setMorphWeights(float... weights) {
        ensureMorph().weights = weights.clone();
        return this;
    }

    /**
     * Sets one weight, leaving the others as they are.
     */
    public Node3D setMorphWeight(int target, float weight) {
        if (target < 0) throw new IndexOutOfBoundsException("Morph target " + target);
        MorphState state = ensureMorph();
        if (target >= state.weights.length) state.weights = java.util.Arrays.copyOf(state.weights, target + 1);
        state.weights[target] = weight;
        return this;
    }

    /**
     * Makes this node use the other node's blend-shape weights, so that the several parts of one mesh (a model with a
     * material per part) morph together. Changing either changes both.
     */
    public Node3D shareMorphWeights(Node3D other) {
        this.morph = other.ensureMorph();
        return this;
    }

    private MorphState ensureMorph() {
        if (morph == null) {
            morph = new MorphState();
            if (mesh != null) morph.weights = mesh.morphDefaults().clone();
        }
        return morph;
    }

    /**
     * The weights to draw with, or null when the mesh has no blend shapes or all the weights are 0.
     */
    float[] activeMorph() {
        Mesh m = mesh;
        if (m == null || !m.isMorphed()) return null;
        float[] w = morph != null ? morph.weights : m.morphDefaults();
        for (int i = 0; i < w.length && i < m.morphTargets().size(); i++) {
            if (w[i] != 0f) return w;
        }
        return null;
    }

    public boolean isOccluder() {
        return occluder;
    }

    /**
     * Lets this node hide other nodes behind it when the scene has {@link Scene3D#setOcclusionCulling occlusion culling} on. The
     * mesh's own triangles are drawn into a small depth picture on the CPU, so mark big solid shapes with few triangles: a wall,
     * a floor, a building, a hill. Pick nodes that really are solid, because whatever lies behind one is not drawn: a node
     * with see-through or cut-out pixels, lines, a skin or blend shapes does not count as an occluder. Off by default.
     */
    public Node3D setOccluder(boolean occluder) {
        this.occluder = occluder;
        return this;
    }

    public boolean isFrustumCulled() {
        return frustumCulled;
    }

    /**
     * Whether the node is skipped when it is entirely outside the camera's view (true by default). Turn it off for a mesh
     * whose picture is not where its box is. A skinned mesh is bounded by its joints (the farthest vertex each one moves),
     * so it is skipped when all of them are out of view. A node outside the view still throws its shadow.
     */
    public Node3D setFrustumCulled(boolean frustumCulled) {
        this.frustumCulled = frustumCulled;
        return this;
    }

    public boolean isSortTriangles() {
        return sortTriangles;
    }

    /**
     * Draws the triangles of this node's see-through ({@link Material#setBlend blended}) mesh from far to near each frame
     * (false by default). Without it a mesh is drawn in the order its triangles are stored, so a see-through shape that
     * folds over itself (a bottle, a bush of leaf cards, a cloud of particles in one mesh) can show its far side over its
     * near side from some angles. It costs a sort of the triangles on the CPU and an upload of the indices every frame
     * the camera or the node moves, so turn it on for the nodes that need it. It does not sort one node against another
     * (that is by the middle of each), and has no effect on a node with instances, a skin or blend shapes.
     */
    public Node3D setSortTriangles(boolean sortTriangles) {
        this.sortTriangles = sortTriangles;
        return this;
    }

    public boolean isReceiveShadow() {
        return receiveShadow;
    }

    /**
     * Whether shadows fall on this node's mesh (true by default). Turn it off for something that should stay evenly
     * lit, such as a sky dome.
     */
    public Node3D setReceiveShadow(boolean receiveShadow) {
        this.receiveShadow = receiveShadow;
        return this;
    }

    public Instances getInstances() {
        return instances;
    }

    /**
     * Draws this node's mesh once for each instance, each with its own transform (relative to this node), in one draw
     * call. {@code null}, the default, draws it once as usual. The children are not repeated.
     */
    public Node3D setInstances(Instances instances) {
        this.instances = instances;
        return this;
    }

    public Skin getSkin() {
        return skin;
    }

    /**
     * Makes this node's mesh follow the skin's joints. The mesh needs {@link Mesh#setSkinning skinning data}. While it
     * is set, the node's own transform is ignored by drawing and the joints place the mesh.
     */
    public Node3D setSkin(Skin skin) {
        this.skin = skin;
        return this;
    }

    public Vec3 getPosition() {
        return position;
    }

    public Node3D setPosition(Vec3 position) {
        this.position = position;
        return this;
    }

    public Node3D setPosition(float x, float y, float z) {
        return setPosition(new Vec3(x, y, z));
    }

    public Quat getRotation() {
        return rotation;
    }

    public Node3D setRotation(Quat rotation) {
        this.rotation = rotation.normalized();
        return this;
    }

    /**
     * Turns the node by {@code radians} around {@code axis} (in the parent's space) on top of its current rotation.
     */
    public Node3D rotate(Vec3 axis, float radians) {
        return setRotation(Quat.fromAxisAngle(axis, radians).mul(rotation));
    }

    public Vec3 getScale() {
        return scale;
    }

    public Node3D setScale(Vec3 scale) {
        this.scale = scale;
        return this;
    }

    public Node3D setScale(float uniform) {
        return setScale(new Vec3(uniform, uniform, uniform));
    }

    /**
     * Sets position, rotation and scale from a matrix; shear is dropped.
     */
    public Node3D setLocalMatrix(Mat4 matrix) {
        Mat4.Trs trs = matrix.decompose();
        position = trs.position();
        rotation = trs.rotation();
        scale = trs.scale();
        return this;
    }

    /**
     * This node's transform relative to its parent.
     */
    public Mat4 localMatrix() {
        return Mat4.compose(position, rotation, scale);
    }

    /**
     * This node's transform in the scene: its parents' transforms and its own.
     */
    public Mat4 worldMatrix() {
        Mat4 local = localMatrix();
        return parent == null ? local : parent.worldMatrix().mul(local);
    }

    /**
     * The first node named {@code name} in this node and its descendants, or null.
     */
    public Node3D find(String name) {
        if (name.equals(this.name)) return this;
        for (Node3D child : children) {
            Node3D found = child.find(name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Every node in this node and its descendants by name, one lookup instead of a {@link #find} walk per name. Where
     * names repeat, the node {@code find} would return wins.
     */
    java.util.Map<String, Node3D> nodesByName() {
        java.util.Map<String, Node3D> byName = new java.util.HashMap<>();
        traverse(n -> {
            if (n.name != null) byName.putIfAbsent(n.name, n);
        });
        return byName;
    }

    /**
     * Makes every skinned mesh in this node and its descendants follow the skeleton under {@code skeletonRoot} instead of
     * the one it was loaded with, matching joints by bone name ({@link Skin#rebind}). Hair, clothes and other parts
     * loaded from their own files carry their own copy of the skeleton; this ties them to the body's.
     * <pre>
     * Model hair = Model.load(Path.of("assets/hair_long.glb"));
     * hair.root().bindSkeleton(body.root().find("Armature"));
     * scene.add(hair.root());
     * </pre>
     * Pass the skeleton's own root (such as the armature node) rather than a node that also contains this part: when
     * the part's own copy of the skeleton is under {@code skeletonRoot}, the names match it first.
     *
     * @throws IllegalArgumentException if a joint of a skin has no node with its name under {@code skeletonRoot}
     */
    public Node3D bindSkeleton(Node3D skeletonRoot) {
        java.util.Map<String, Node3D> bones = skeletonRoot.nodesByName();
        java.util.Map<Skin, Skin> done = new java.util.IdentityHashMap<>();
        traverse(n -> {
            if (n.skin != null) n.skin = done.computeIfAbsent(n.skin, s -> s.rebind(bones));
        });
        return this;
    }

    /**
     * Visits this node and everything under it.
     */
    public void traverse(Consumer<Node3D> visitor) {
        visitor.accept(this);
        for (Node3D child : new ArrayList<>(children)) child.traverse(visitor);
    }

    /**
     * The box around this node's meshes and its descendants', in scene space; null when there is no mesh.
     */
    public Bounds bounds() {
        return bounds(parent == null ? Mat4.identity() : parent.worldMatrix());
    }

    private Bounds bounds(Mat4 parentWorld) {
        Mat4 world = parentWorld.mul(localMatrix());
        Bounds result = mesh != null ? mesh.bounds().transform(world) : null;
        for (Node3D child : children) {
            Bounds b = child.bounds(world);
            if (b != null) result = result == null ? b : result.union(b);
        }
        return result;
    }

    /**
     * A copy of this node and its descendants, not yet under any parent. Meshes, materials and textures are shared
     * with the original, so copying a loaded model costs no GPU memory.
     */
    public Node3D copy() {
        Node3D c = copyTree(new java.util.IdentityHashMap<>());
        remapSkins(this, this, c, c);
        return c;
    }

    private Node3D copyTree(java.util.Map<MorphState, MorphState> morphs) {
        Node3D c = new Node3D(name, mesh, material);
        c.position = position;
        c.rotation = rotation;
        c.scale = scale;
        c.visible = visible;
        c.pickable = pickable;
        c.castShadow = castShadow;
        c.receiveShadow = receiveShadow;
        c.frustumCulled = frustumCulled;
        c.sortTriangles = sortTriangles;
        c.instances = instances == null ? null : instances.copy();
        c.skin = skin;
        if (morph != null) {
            // Parts that shared their weights still share them, in the copy.
            c.morph = morphs.computeIfAbsent(morph, m -> {
                MorphState copy = new MorphState();
                copy.weights = m.weights.clone();
                return copy;
            });
        }
        for (Node3D child : children) c.add(child.copyTree(morphs));
        return c;
    }

    /**
     * A copied skinned mesh follows the copied skeleton, not the original's.
     */
    private static void remapSkins(Node3D originalRoot, Node3D original, Node3D copy, Node3D copyRoot) {
        if (original.skin != null) copy.skin = original.skin.remap(originalRoot, copyRoot);
        for (int i = 0; i < original.children.size(); i++) {
            remapSkins(originalRoot, original.children.get(i), copy.children.get(i), copyRoot);
        }
    }
}
