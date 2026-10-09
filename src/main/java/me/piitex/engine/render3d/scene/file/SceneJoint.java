package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A joint of a scene file: which nodes' bodies it joins, where and how. Loading the scene makes the real
 * {@link me.piitex.engine.render3d.physics.Joint} from it ({@link Scene3DFile#findJoint} finds it); an editor edits
 * this, through the properties of {@link SceneKinds#joint}.
 * <p>
 * Bodies are named by their nodes' names, and the first node of that name is the one joined. Points and axes are in
 * the world, as the nodes stand in the file. A joint with no second body holds the first to the world.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class SceneJoint {
    final JointKind kind;
    String name = "", bodyA = "", bodyB = "";
    Vec3 anchor = Vec3.ZERO, anchorB = Vec3.ZERO, axis = Vec3.UP;
    boolean limited, motor, rope, collideConnected;
    float lower, upper, motorSpeed, motorStrength = 100f;
    float length, springFrequency, springDamping = 0.5f, breakForce;

    public SceneJoint(JointKind kind) {
        this.kind = kind;
        if (kind == JointKind.HINGE) {
            lower = -90f;
            upper = 90f;
        } else if (kind == JointKind.SLIDER) {
            lower = -1f;
            upper = 1f;
        }
    }

    public JointKind getKind() {
        return kind;
    }

    /** What the game finds it by. */
    public String getName() {
        return name;
    }

    public SceneJoint setName(String name) {
        this.name = name == null ? "" : name;
        return this;
    }

    /** The name of the node whose body is the joint's first. */
    public String getBodyA() {
        return bodyA;
    }

    public SceneJoint setBodyA(String node) {
        bodyA = node == null ? "" : node;
        return this;
    }

    /** The name of the node whose body is the joint's second, or empty to hold the first to the world. */
    public String getBodyB() {
        return bodyB;
    }

    public SceneJoint setBodyB(String node) {
        bodyB = node == null ? "" : node;
        return this;
    }

    @Override
    public String toString() {
        return kind + " " + (name.isEmpty() ? "joint" : '"' + name + '"') + " (" + bodyA + ", " + (bodyB.isEmpty() ? "world" : bodyB) + ")";
    }
}
