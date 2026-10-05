package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws what a {@link PhysicsWorld} collides with: each body's shapes as see-through outlines, where they really are,
 * which is how to find a collider that doesn't fit its mesh, a body that is asleep when it shouldn't be, or a trigger
 * in the wrong place.
 * <pre>
 * ColliderView colliders = new ColliderView(world);
 * scene.add(colliders.getNode());
 * colliders.setVisible(false);   // toggle it from a key
 * </pre>
 * <p>
 * The colour says what a body is doing:
 * <ul>
 *     <li>green: a dynamic body, awake</li>
 *     <li>grey-blue: a dynamic body, asleep</li>
 *     <li>blue: a kinematic body</li>
 *     <li>light grey: a static body</li>
 *     <li>yellow: a trigger</li>
 *     <li>pink: a {@link CharacterController character}</li>
 * </ul>
 * Red dots are where bodies touch, with a short line along the push. Orange dots are where joints hold their bodies,
 * with a line between them when they are apart (a rope or a spring) and along the axis of a hinge or slider.
 * <p>
 * It follows the world each time the world is updated, with the same smoothing between steps as the bodies' nodes; call
 * {@link #refresh} to follow it while the world isn't running. An endless plane is drawn as a 100 m square around its
 * body. Its node is meant for the scene's root, and is never picked or shadowed.
 */
public final class ColliderView {
    private static final float MARKER = 0.035f, LINE = 0.012f, NORMAL_LENGTH = 0.2f, AXIS_LENGTH = 0.5f;

    private final PhysicsWorld world;
    private final Node3D root = new Node3D("Colliders");
    private final Node3D bodyGroup = new Node3D("Bodies"), contactGroup = new Node3D("Contacts"), jointGroup = new Node3D("Joints");
    private final Map<RigidBody, Entry> entries = new IdentityHashMap<>();
    private final Map<Shape, ColliderMeshes.Built> meshes = new HashMap<>();
    private final Map<ColliderMeshes.Pattern, Map<State, Material>> materials = new EnumMap<>(ColliderMeshes.Pattern.class);
    private final List<Node3D> contactMarks = new ArrayList<>(), jointMarks = new ArrayList<>();
    private final Mesh dot = Mesh.sphere(MARKER, 8, 6), stick = Mesh.cube(1f);
    private final Material contactColor = flat(1f, 0.15f, 0.1f), jointColor = flat(1f, 0.55f, 0.1f);
    private boolean showStatic = true, showContacts = true, showJoints = true;
    private boolean disposed;

    private enum State {
        AWAKE(0.3f, 1f, 0.4f, 0.95f), ASLEEP(0.5f, 0.58f, 0.75f, 0.8f), KINEMATIC(0.3f, 0.65f, 1f, 0.95f),
        STATIC(0.85f, 0.85f, 0.85f, 0.45f), TRIGGER(1f, 0.85f, 0.2f, 0.9f), CHARACTER(1f, 0.45f, 0.85f, 0.95f);

        final float r, g, b, a;

        State(float r, float g, float b, float a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }
    }

    private static final class Entry {
        List<BodyShape> shapes;
        final List<Node3D> nodes = new ArrayList<>();
        final List<ColliderMeshes.Pattern> patterns = new ArrayList<>();
        State state;
        boolean seen;
    }

    /**
     * A view of {@code world}, drawn once its {@link #getNode() node} is added to a scene.
     */
    public ColliderView(PhysicsWorld world) {
        this.world = world;
        root.add(bodyGroup);
        root.add(contactGroup);
        root.add(jointGroup);
        root.setPickable(false);
        world.addView(this);
        sync(1f);
    }

    /** The node that holds the view; add it to the scene's root. */
    public Node3D getNode() {
        return root;
    }

    public PhysicsWorld getWorld() {
        return world;
    }

    public boolean isVisible() {
        return root.isVisible();
    }

    /** Shows or hides the view. A hidden view costs nothing. */
    public ColliderView setVisible(boolean visible) {
        root.setVisible(visible);
        if (visible) refresh();
        return this;
    }

    public boolean isShowStatic() {
        return showStatic;
    }

    /** Whether static bodies are drawn (on by default); a level's own colliders can hide everything else. */
    public ColliderView setShowStatic(boolean show) {
        showStatic = show;
        refresh();
        return this;
    }

    public boolean isShowContacts() {
        return showContacts;
    }

    public ColliderView setShowContacts(boolean show) {
        showContacts = show;
        refresh();
        return this;
    }

    public boolean isShowJoints() {
        return showJoints;
    }

    public ColliderView setShowJoints(boolean show) {
        showJoints = show;
        refresh();
        return this;
    }

    /**
     * Follows the world now, as it stands; the view does this by itself each time the world is updated.
     */
    public void refresh() {
        if (!disposed) sync(world.nodesAlpha);
    }

    /**
     * Stops following the world and takes the view out of the scene.
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        world.removeView(this);
        if (root.getParent() != null) root.getParent().remove(root);
    }

    // ---- Following the world ----------------------------------------------------------------------------------------

    void sync(float alpha) {
        if (!root.isVisible()) return;
        // The view's nodes are placed in the world; if its node was put under a moved parent, undo the parent's move.
        Mat4 toView = root.getParent() == null ? null : root.worldMatrix().inverse();
        Quat unturn = toView == null ? Quat.IDENTITY : root.worldMatrix().decompose().rotation().conjugate();

        for (Entry entry : entries.values()) entry.seen = false;
        for (RigidBody body : world.bodies()) {
            Entry entry = entries.get(body);
            if (entry == null) {
                entry = new Entry();
                entries.put(body, entry);
            }
            entry.seen = true;
            if (entry.shapes != body.getWorldScaledShapes()) build(entry, body.getWorldScaledShapes());
            State state = stateOf(body);
            if (state != entry.state) {
                entry.state = state;
                for (int i = 0; i < entry.nodes.size(); i++) entry.nodes.get(i).setMaterial(material(entry.patterns.get(i), state));
            }
            boolean shown = showStatic || body.getType() != BodyType.STATIC;
            Pose pose = body.interpolatedPose(alpha);
            for (int i = 0; i < entry.nodes.size(); i++) {
                Node3D node = entry.nodes.get(i);
                node.setVisible(shown);
                if (!shown) continue;
                BodyShape shape = entry.shapes.get(i);
                place(node, pose.toWorld(shape.offset()), pose.rotation().mul(shape.rotation()), toView, unturn);
            }
        }
        if (entries.size() > world.bodies().size()) {
            entries.values().removeIf(entry -> {
                if (entry.seen) return false;
                for (Node3D node : entry.nodes) bodyGroup.remove(node);
                return true;
            });
            if (meshes.size() > 2 * Math.max(16, entries.size())) meshes.clear(); // drop the meshes of shapes now gone
        }

        int used = 0;
        if (showContacts) {
            for (Contact contact : world.getContacts()) {
                if (contact.isTrigger()) continue;
                Vec3 normal = contact.getNormal();
                for (ContactPoint point : contact.getPoints()) {
                    if (point.depth() < 0f) continue; // only about to touch
                    place(mark(contactMarks, contactGroup, used++, dot, contactColor), point.position(), Quat.IDENTITY, toView, unturn);
                    line(mark(contactMarks, contactGroup, used++, stick, contactColor), point.position(), point.position().add(normal.mul(NORMAL_LENGTH)), toView, unturn);
                }
            }
        }
        hideFrom(contactMarks, used);

        used = 0;
        if (showJoints) {
            for (Joint joint : world.joints()) {
                Vec3 localA = joint.localAnchorA(), localB = joint.localAnchorB();
                if (localA == null) continue;
                Vec3 onA = joint.a.interpolatedPose(alpha).toWorld(localA), onB = joint.b.interpolatedPose(alpha).toWorld(localB);
                place(mark(jointMarks, jointGroup, used++, dot, jointColor), onA, Quat.IDENTITY, toView, unturn);
                if (onA.distance(onB) > 0.01f) {
                    place(mark(jointMarks, jointGroup, used++, dot, jointColor), onB, Quat.IDENTITY, toView, unturn);
                    line(mark(jointMarks, jointGroup, used++, stick, jointColor), onA, onB, toView, unturn);
                }
                Vec3 axis = switch (joint) {
                    case HingeJoint hinge -> hinge.getAxis();
                    case SliderJoint slider -> slider.getAxis();
                    default -> null;
                };
                if (axis != null) {
                    Vec3 half = axis.mul(AXIS_LENGTH / 2f);
                    line(mark(jointMarks, jointGroup, used++, stick, jointColor), onA.sub(half), onA.add(half), toView, unturn);
                }
            }
        }
        hideFrom(jointMarks, used);
    }

    private State stateOf(RigidBody body) {
        if (body.character != null) return State.CHARACTER;
        if (body.isTrigger()) return State.TRIGGER;
        return switch (body.getType()) {
            case STATIC -> State.STATIC;
            case KINEMATIC -> State.KINEMATIC;
            case DYNAMIC -> body.isSleeping() ? State.ASLEEP : State.AWAKE;
        };
    }

    private void build(Entry entry, List<BodyShape> shapes) {
        for (Node3D node : entry.nodes) bodyGroup.remove(node);
        entry.nodes.clear();
        entry.patterns.clear();
        entry.shapes = shapes;
        entry.state = null;
        for (BodyShape shape : shapes) {
            ColliderMeshes.Built built = meshes.computeIfAbsent(shape.shape(), ColliderMeshes::of);
            Node3D node = quiet(new Node3D("Collider", built.mesh(), null));
            entry.patterns.add(built.pattern());
            node.setFrustumCulled(!(shape.shape() instanceof PlaneShape));
            entry.nodes.add(node);
            bodyGroup.add(node);
        }
    }

    private Material material(ColliderMeshes.Pattern pattern, State state) {
        return materials.computeIfAbsent(pattern, _ -> new EnumMap<>(State.class)).computeIfAbsent(state, _ ->
                new Material(pattern.texture()).setColor(state.r, state.g, state.b, state.a).setUnlit(true).setBlend(true).setDoubleSided(true));
    }

    private static Material flat(float r, float g, float b) {
        return new Material().setColor(r, g, b, 1f).setUnlit(true);
    }

    private static Node3D quiet(Node3D node) {
        node.setPickable(false);
        node.setCastShadow(false);
        node.setReceiveShadow(false);
        return node;
    }

    /** The {@code index}th mark of a kind, made if there aren't that many yet, and shown. */
    private static Node3D mark(List<Node3D> marks, Node3D group, int index, Mesh mesh, Material material) {
        while (marks.size() <= index) {
            Node3D node = quiet(new Node3D("Mark"));
            marks.add(node);
            group.add(node);
        }
        Node3D node = marks.get(index);
        node.setMesh(mesh);
        node.setMaterial(material);
        node.setScale(Vec3.ONE);
        node.setVisible(true);
        return node;
    }

    private static void hideFrom(List<Node3D> marks, int used) {
        for (int i = used; i < marks.size(); i++) marks.get(i).setVisible(false);
    }

    private static void place(Node3D node, Vec3 position, Quat rotation, Mat4 toView, Quat unturn) {
        if (toView != null) {
            position = toView.transformPoint(position);
            rotation = unturn.mul(rotation);
        }
        node.setPosition(position);
        node.setRotation(rotation);
    }

    /** Stretches a unit cube into a thin line from {@code from} to {@code to}. */
    private static void line(Node3D node, Vec3 from, Vec3 to, Mat4 toView, Quat unturn) {
        Vec3 d = to.sub(from);
        float length = d.length();
        place(node, from.add(to).mul(0.5f), length < 1e-6f ? Quat.IDENTITY : turnZTo(d.mul(1f / length)), toView, unturn);
        node.setScale(new Vec3(LINE, LINE, Math.max(length, 1e-4f)));
    }

    /** The turn that takes +Z to the unit vector {@code d}. */
    private static Quat turnZTo(Vec3 d) {
        float cos = d.z();
        if (cos < -0.99999f) return Quat.fromAxisAngle(Vec3.UP, (float) Math.PI);
        Vec3 axis = Vec3.Z.cross(d);
        // Half-angle form: (axis, 1 + cos), normalised, without a trig call.
        return new Quat(axis.x(), axis.y(), axis.z(), 1f + cos).normalized();
    }
}
