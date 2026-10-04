package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Two shapes touching, or about to: which bodies and shapes, the direction from the first to the second, and up to four
 * points. A contact lasts as long as the shapes stay close, so the same object comes back step after step.
 */
public final class Contact {
    private final RigidBody bodyA, bodyB;
    private final int shapeA, shapeB;
    Vec3 normal = Vec3.UP;
    final Point[] points = new Point[Manifold.MAX_POINTS];
    int count;

    /**
     * A point as the solver keeps it.
     */
    static final class Point {
        Vec3 position;
        float depth;
    }

    Contact(RigidBody bodyA, int shapeA, RigidBody bodyB, int shapeB) {
        this.bodyA = bodyA;
        this.shapeA = shapeA;
        this.bodyB = bodyB;
        this.shapeB = shapeB;
        for (int i = 0; i < points.length; i++) points[i] = new Point();
    }

    void update(Manifold manifold) {
        normal = manifold.normal;
        count = manifold.count;
        for (int i = 0; i < count; i++) {
            points[i].position = manifold.points[i];
            points[i].depth = manifold.depths[i];
        }
    }

    public RigidBody getBodyA() {
        return bodyA;
    }

    public RigidBody getBodyB() {
        return bodyB;
    }

    public BodyShape getShapeA() {
        return bodyA.getShapes().get(shapeA);
    }

    public BodyShape getShapeB() {
        return bodyB.getShapes().get(shapeB);
    }

    /**
     * The body on the other side from {@code body}, or null when {@code body} isn't in this contact.
     */
    public RigidBody getOther(RigidBody body) {
        return body == bodyA ? bodyB : body == bodyB ? bodyA : null;
    }

    /**
     * The direction from body A to body B, a unit vector in world space. B is pushed along it, A the other way.
     */
    public Vec3 getNormal() {
        return normal;
    }

    public List<ContactPoint> getPoints() {
        List<ContactPoint> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(new ContactPoint(points[i].position, points[i].depth));
        return list;
    }

    /**
     * The deepest overlap, in metres.
     */
    public float getDepth() {
        float depth = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) depth = Math.max(depth, points[i].depth);
        return depth;
    }

    /**
     * Whether the shapes actually touch, rather than only being about to.
     */
    public boolean isTouching() {
        return getDepth() >= 0f;
    }

    @Override
    public String toString() {
        return "Contact[" + bodyA + " - " + bodyB + ", normal " + normal + ", " + getPoints() + "]";
    }
}
