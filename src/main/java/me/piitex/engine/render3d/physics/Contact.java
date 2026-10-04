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

    float friction, restitution;
    // The two directions friction acts along, across the normal.
    float t1x, t1y, t1z, t2x, t2y, t2z;

    /**
     * A point as the solver keeps it.
     */
    static final class Point {
        Vec3 position;
        float depth;
        Vec3 localA; // where the point sits on body A, to find it again next step
        float rax, ray, raz, rbx, rby, rbz; // from each centre of mass to the point
        float normalMass, tangentMass1, tangentMass2;
        float normalImpulse, tangentImpulse1, tangentImpulse2, maxNormalImpulse, pseudoImpulse;
        Vec3 warmTangent = Vec3.ZERO; // last step's friction impulse, carried over in world space
        float relativeVelocity; // how fast the bodies met along the normal, before solving
        boolean bounced;
    }

    private static final float MATCH_DISTANCE_SQUARED = 0.05f * 0.05f;
    private final Vec3[] oldLocal = new Vec3[Manifold.MAX_POINTS];
    private final float[] oldNormal = new float[Manifold.MAX_POINTS];
    private final Vec3[] oldTangent = new Vec3[Manifold.MAX_POINTS];

    Contact(RigidBody bodyA, int shapeA, RigidBody bodyB, int shapeB) {
        this.bodyA = bodyA;
        this.shapeA = shapeA;
        this.bodyB = bodyB;
        this.shapeB = shapeB;
        for (int i = 0; i < points.length; i++) points[i] = new Point();
    }

    /**
     * Takes the new points, and for each one that is where a point was last step, the impulses it needed then: starting
     * from those (warm starting) lets a stack settle in a few iterations instead of creeping.
     */
    void update(Manifold manifold) {
        int oldCount = count;
        for (int i = 0; i < oldCount; i++) {
            Point p = points[i];
            oldLocal[i] = p.localA;
            oldNormal[i] = p.normalImpulse;
            oldTangent[i] = new Vec3(t1x * p.tangentImpulse1 + t2x * p.tangentImpulse2, t1y * p.tangentImpulse1 + t2y * p.tangentImpulse2,
                    t1z * p.tangentImpulse1 + t2z * p.tangentImpulse2);
        }
        normal = manifold.normal;
        count = manifold.count;
        Vec3 centerA = bodyA.getCenterOfMass();
        var rotationA = bodyA.getRotation().conjugate();
        for (int i = 0; i < count; i++) {
            Point p = points[i];
            p.position = manifold.points[i];
            p.depth = manifold.depths[i];
            p.localA = rotationA.rotate(p.position.sub(centerA));
            int match = -1;
            if (oldCount == 1 && count == 1) {
                match = 0;
            } else {
                float best = MATCH_DISTANCE_SQUARED;
                for (int j = 0; j < oldCount; j++) {
                    float d = oldLocal[j] == null ? Float.POSITIVE_INFINITY : oldLocal[j].sub(p.localA).lengthSquared();
                    if (d < best) {
                        best = d;
                        match = j;
                    }
                }
            }
            p.normalImpulse = match >= 0 ? oldNormal[match] : 0f;
            p.warmTangent = match >= 0 ? oldTangent[match] : Vec3.ZERO;
            p.tangentImpulse1 = p.tangentImpulse2 = 0f;
        }
    }

    int shapeIndexA() {
        return shapeA;
    }

    int shapeIndexB() {
        return shapeB;
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
        for (int i = 0; i < count; i++) list.add(new ContactPoint(points[i].position, points[i].depth, points[i].normalImpulse));
        return list;
    }

    /**
     * How hard the bodies pushed on each other in the last step, in newton seconds: the sum over the points. Divide by
     * the step for the force; a sudden large value is an impact.
     */
    public float getImpulse() {
        float impulse = 0f;
        for (int i = 0; i < count; i++) impulse += points[i].normalImpulse;
        return impulse;
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
