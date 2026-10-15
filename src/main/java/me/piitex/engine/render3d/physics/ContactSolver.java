package me.piitex.engine.render3d.physics;

import java.util.List;

/**
 * Pushes touching bodies apart with impulses, one contact point at a time, going over all of them several times so the
 * pushes settle (sequential impulses). Each point stops the bodies moving into each other along the normal, and
 * friction resists sliding across it, up to the friction times the push.
 * <p>
 * Sinking in is fixed by a separate push that moves the bodies apart without leaving them any speed (split impulses),
 * so resting bodies don't jitter or gain energy. Shapes still a little apart get a contact too (speculative contacts):
 * it lets them close the gap this step but not pass through, which stops falling bodies at the surface.
 */
final class ContactSolver {
    /**
     * Below this closing speed, in metres per second, nothing bounces: a resting body would otherwise buzz.
     */
    static final float RESTITUTION_THRESHOLD = 0.5f;
    /**
     * Overlap left alone, in metres, so resting contacts stay touching from step to step.
     */
    static final float SLOP = 0.002f;
    /**
     * The share of the overlap fixed each step.
     */
    static final float CORRECTION = 0.2f;
    /**
     * The most a step corrects, in metres, so a deep overlap comes apart over a few steps rather than with a jolt.
     */
    static final float MAX_CORRECTION = 0.2f;

    private ContactSolver() {
    }

    /**
     * The whole contact solve for contacts that share no body with any joint: prepare, the velocity passes, bounce and
     * the position passes. Contacts of separate islands touch separate bodies (static and kinematic ones are only read),
     * so separate islands can run this at the same time and get exactly what one thread would.
     */
    static void solveIslands(List<Contact> contacts, float dt, int velocityIterations, int positionIterations) {
        prepare(contacts, dt);
        for (int i = 0; i < velocityIterations; i++) solveVelocities(contacts, dt, i % 2 == 1);
        applyRestitution(contacts, dt);
        for (int i = 0; i < positionIterations; i++) solvePositions(contacts, dt, i % 2 == 1);
    }

    static boolean active(Contact contact) {
        return !contact.isTrigger() && (contact.getBodyA().isAwakeDynamic() || contact.getBodyB().isAwakeDynamic()) && !standsOn(contact);
    }

    /**
     * Whether a character stands on a loose body. The character puts its weight on the body itself; solved, the contact
     * would hold the body down as if the character were infinitely heavy, and a plank of a rope bridge would follow the
     * character down rather than hold it up.
     */
    private static boolean standsOn(Contact contact) {
        RigidBody a = contact.getBodyA(), b = contact.getBodyB();
        if (a.character != null && b.isDynamic()) return contact.normal.y() < -0.3f;
        if (b.character != null && a.isDynamic()) return contact.normal.y() > 0.3f;
        return false;
    }

    /**
     * Works out what each point needs (lever arms, how hard it is to move the bodies there, friction directions), then
     * applies last step's impulses as a head start.
     */
    static void prepare(List<Contact> contacts, float dt) {
        for (Contact c : contacts) {
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            c.friction = PhysicsMaterial.combineFriction(a.getMaterial(), b.getMaterial());
            c.restitution = PhysicsMaterial.combineRestitution(a.getMaterial(), b.getMaterial());
            float nx = c.normal.x(), ny = c.normal.y(), nz = c.normal.z();
            // Any two directions across the normal; the same normal always gives the same pair.
            if (Math.abs(nx) >= 0.57735f) {
                float inv = 1f / (float) Math.sqrt(nx * nx + ny * ny);
                c.t1x = ny * inv;
                c.t1y = -nx * inv;
                c.t1z = 0f;
            } else {
                float inv = 1f / (float) Math.sqrt(ny * ny + nz * nz);
                c.t1x = 0f;
                c.t1y = nz * inv;
                c.t1z = -ny * inv;
            }
            c.t2x = ny * c.t1z - nz * c.t1y;
            c.t2y = nz * c.t1x - nx * c.t1z;
            c.t2z = nx * c.t1y - ny * c.t1x;

            for (int i = 0; i < c.count; i++) {
                Contact.Point p = c.points[i];
                p.rax = p.position.x() - a.cx;
                p.ray = p.position.y() - a.cy;
                p.raz = p.position.z() - a.cz;
                p.rbx = p.position.x() - b.cx;
                p.rby = p.position.y() - b.cy;
                p.rbz = p.position.z() - b.cz;
                p.normalMass = inverse(effectiveMass(a, b, p, nx, ny, nz));
                p.tangentMass1 = inverse(effectiveMass(a, b, p, c.t1x, c.t1y, c.t1z));
                p.tangentMass2 = inverse(effectiveMass(a, b, p, c.t2x, c.t2y, c.t2z));
                p.relativeVelocity = relativeVelocity(a, b, p, nx, ny, nz);
                p.maxNormalImpulse = 0f;
                p.pseudoImpulse = 0f;
                p.bounced = false;

                // Last step's friction, turned onto this step's friction directions.
                float wx = p.warmTangent.x(), wy = p.warmTangent.y(), wz = p.warmTangent.z();
                p.tangentImpulse1 = wx * c.t1x + wy * c.t1y + wz * c.t1z;
                p.tangentImpulse2 = wx * c.t2x + wy * c.t2y + wz * c.t2z;
                float px = nx * p.normalImpulse + c.t1x * p.tangentImpulse1 + c.t2x * p.tangentImpulse2;
                float py = ny * p.normalImpulse + c.t1y * p.tangentImpulse1 + c.t2y * p.tangentImpulse2;
                float pz = nz * p.normalImpulse + c.t1z * p.tangentImpulse1 + c.t2z * p.tangentImpulse2;
                apply(a, b, p, px, py, pz);
            }
        }
    }

    /**
     * One pass over every point: friction first, then the push along the normal. Passes alternate direction: always
     * going the same way favours the points solved first, which tips each box in a stack a little the same way until
     * the stack leans.
     */
    static void solveVelocities(List<Contact> contacts, float dt, boolean backward) {
        float invDt = 1f / dt;
        int size = contacts.size();
        for (int k = 0; k < size; k++) {
            Contact c = contacts.get(backward ? size - 1 - k : k);
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            float nx = c.normal.x(), ny = c.normal.y(), nz = c.normal.z();

            for (int j = 0; j < c.count; j++) {
                Contact.Point p = c.points[backward ? c.count - 1 - j : j];
                float limit = c.friction * p.normalImpulse;
                float v1 = relativeVelocity(a, b, p, c.t1x, c.t1y, c.t1z);
                float v2 = relativeVelocity(a, b, p, c.t2x, c.t2y, c.t2z);
                float new1 = p.tangentImpulse1 - p.tangentMass1 * v1;
                float new2 = p.tangentImpulse2 - p.tangentMass2 * v2;
                float length = (float) Math.sqrt(new1 * new1 + new2 * new2);
                if (length > limit) { // past what friction can hold: it slides, held back by the full friction
                    float s = limit / length;
                    new1 *= s;
                    new2 *= s;
                }
                float d1 = new1 - p.tangentImpulse1, d2 = new2 - p.tangentImpulse2;
                p.tangentImpulse1 = new1;
                p.tangentImpulse2 = new2;
                apply(a, b, p, c.t1x * d1 + c.t2x * d2, c.t1y * d1 + c.t2y * d2, c.t1z * d1 + c.t2z * d2);
            }

            for (int j = 0; j < c.count; j++) {
                Contact.Point p = c.points[backward ? c.count - 1 - j : j];
                float vn = relativeVelocity(a, b, p, nx, ny, nz);
                float separation = -p.depth;
                // Apart: they may close the gap this step but no more. Touching: they mustn't move further in.
                float target = separation > 0f ? -separation * invDt : 0f;
                float impulse = Math.max(p.normalImpulse - p.normalMass * (vn - target), 0f);
                float d = impulse - p.normalImpulse;
                p.normalImpulse = impulse;
                p.maxNormalImpulse = Math.max(p.maxNormalImpulse, impulse);
                apply(a, b, p, nx * d, ny * d, nz * d);
            }
        }
    }

    /**
     * After the pushes have settled, points that were hit hard enough get their bounce: the bodies leave at the
     * restitution times the speed they met at.
     */
    static void applyRestitution(List<Contact> contacts, float dt) {
        for (Contact c : contacts) {
            if (c.restitution == 0f) continue;
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            float nx = c.normal.x(), ny = c.normal.y(), nz = c.normal.z();
            for (int i = 0; i < c.count; i++) {
                Contact.Point p = c.points[i];
                if (p.relativeVelocity > -RESTITUTION_THRESHOLD || p.maxNormalImpulse == 0f) continue;
                float vn = relativeVelocity(a, b, p, nx, ny, nz);
                float impulse = Math.max(p.normalImpulse - p.normalMass * (vn + c.restitution * p.relativeVelocity), 0f);
                float d = impulse - p.normalImpulse;
                p.normalImpulse = impulse;
                apply(a, b, p, nx * d, ny * d, nz * d);
                // A point still apart at the start of the step would bounce off thin air; its bounce is moved to the
                // surface by the position pass.
                p.bounced = -p.depth > 0f;
            }
        }
    }

    /**
     * One pass of the position fix, on the bodies' pseudo velocities: overlapping points are pushed apart by a share of
     * the overlap, and points that bounced before reaching the surface are carried to it.
     */
    static void solvePositions(List<Contact> contacts, float dt, boolean backward) {
        float invDt = 1f / dt;
        int size = contacts.size();
        for (int k = 0; k < size; k++) {
            Contact c = contacts.get(backward ? size - 1 - k : k);
            RigidBody a = c.getBodyA(), b = c.getBodyB();
            float nx = c.normal.x(), ny = c.normal.y(), nz = c.normal.z();
            for (int j = 0; j < c.count; j++) {
                Contact.Point p = c.points[backward ? c.count - 1 - j : j];
                float vn = pseudoVelocity(a, b, p, nx, ny, nz);
                float impulse;
                if (p.bounced) {
                    // It met the surface part way through the step and came back: end where that would leave it.
                    float target = -(1f + c.restitution) * -p.depth * invDt;
                    impulse = p.pseudoImpulse - p.normalMass * (vn - target);
                } else {
                    float overlap = p.depth - SLOP;
                    if (overlap <= 0f && p.pseudoImpulse == 0f) continue;
                    float target = Math.min(CORRECTION * Math.max(overlap, 0f), MAX_CORRECTION) * invDt;
                    impulse = Math.max(p.pseudoImpulse - p.normalMass * (vn - target), 0f);
                }
                float d = impulse - p.pseudoImpulse;
                p.pseudoImpulse = impulse;
                applyPseudo(a, b, p, nx * d, ny * d, nz * d);
            }
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private static float inverse(float k) {
        return k > 1e-12f ? 1f / k : 0f;
    }

    /**
     * How much an impulse of 1 along (dx, dy, dz) at the point changes the bodies' closing speed there: both bodies'
     * inverse mass along the direction plus what turning them adds.
     */
    private static float effectiveMass(RigidBody a, RigidBody b, Contact.Point p, float dx, float dy, float dz) {
        return bodyMass(a, p.rax, p.ray, p.raz, dx, dy, dz) + bodyMass(b, p.rbx, p.rby, p.rbz, dx, dy, dz);
    }

    private static float bodyMass(RigidBody body, float rx, float ry, float rz, float dx, float dy, float dz) {
        float m = body.getInverseMass();
        if (m == 0f) return 0f;
        float linear = m * (body.lx * dx * dx + body.ly * dy * dy + body.lz * dz * dz);
        // (r x d) . I^-1 (r x d)
        float cx = ry * dz - rz * dy, cy = rz * dx - rx * dz, cz = rx * dy - ry * dx;
        Mat3 i = body.inverseInertiaWorld();
        float ix = i.xx() * cx + i.xy() * cy + i.xz() * cz;
        float iy = i.yx() * cx + i.yy() * cy + i.yz() * cz;
        float iz = i.zx() * cx + i.zy() * cy + i.zz() * cz;
        return linear + cx * ix + cy * iy + cz * iz;
    }

    /**
     * How fast B's point moves away from A's along (dx, dy, dz).
     */
    private static float relativeVelocity(RigidBody a, RigidBody b, Contact.Point p, float dx, float dy, float dz) {
        float vbx = b.vx + b.wy * p.rbz - b.wz * p.rby, vby = b.vy + b.wz * p.rbx - b.wx * p.rbz, vbz = b.vz + b.wx * p.rby - b.wy * p.rbx;
        float vax = a.vx + a.wy * p.raz - a.wz * p.ray, vay = a.vy + a.wz * p.rax - a.wx * p.raz, vaz = a.vz + a.wx * p.ray - a.wy * p.rax;
        return (vbx - vax) * dx + (vby - vay) * dy + (vbz - vaz) * dz;
    }

    private static float pseudoVelocity(RigidBody a, RigidBody b, Contact.Point p, float dx, float dy, float dz) {
        float vbx = b.pvx + b.pwy * p.rbz - b.pwz * p.rby, vby = b.pvy + b.pwz * p.rbx - b.pwx * p.rbz, vbz = b.pvz + b.pwx * p.rby - b.pwy * p.rbx;
        float vax = a.pvx + a.pwy * p.raz - a.pwz * p.ray, vay = a.pvy + a.pwz * p.rax - a.pwx * p.raz, vaz = a.pvz + a.pwx * p.ray - a.pwy * p.rax;
        return (vbx - vax) * dx + (vby - vay) * dy + (vbz - vaz) * dz;
    }

    /**
     * Impulse (px, py, pz) on B at the point, and the opposite on A.
     */
    private static void apply(RigidBody a, RigidBody b, Contact.Point p, float px, float py, float pz) {
        a.addVelocity(-px, -py, -pz, p.rax, p.ray, p.raz, false);
        b.addVelocity(px, py, pz, p.rbx, p.rby, p.rbz, false);
    }

    private static void applyPseudo(RigidBody a, RigidBody b, Contact.Point p, float px, float py, float pz) {
        a.addVelocity(-px, -py, -pz, p.rax, p.ray, p.raz, true);
        b.addVelocity(px, py, pz, p.rbx, p.rby, p.rbz, true);
    }
}
