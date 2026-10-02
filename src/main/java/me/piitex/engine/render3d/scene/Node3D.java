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
    private Instances instances;
    private Skin skin;

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
        Node3D c = copyTree();
        remapSkins(this, this, c, c);
        return c;
    }

    private Node3D copyTree() {
        Node3D c = new Node3D(name, mesh, material);
        c.position = position;
        c.rotation = rotation;
        c.scale = scale;
        c.visible = visible;
        c.pickable = pickable;
        c.castShadow = castShadow;
        c.receiveShadow = receiveShadow;
        c.instances = instances == null ? null : instances.copy();
        c.skin = skin;
        for (Node3D child : children) c.add(child.copyTree());
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
