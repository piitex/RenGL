package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Finds the pairs of bodies whose boxes overlap. Only a body that is moving (or kinematic, which may sit in a trigger)
 * can start a new contact, so each of those asks the {@link BodyTree} what its box reaches; a settled scene with a
 * few things moving costs a few small queries, however many bodies it has.
 */
final class Broadphase {
    private final List<RigidBody> found = new ArrayList<>();

    /**
     * Calls {@code pair} for each pair of bodies whose boxes overlap and that may collide, the lower id first, in an
     * order that only depends on what was added and how it moved.
     */
    void findPairs(List<RigidBody> bodies, BodyTree tree, BiConsumer<RigidBody, RigidBody> pair) {
        for (RigidBody a : bodies) {
            if (!asks(a)) continue;
            Bounds ba = a.collisionBounds();
            found.clear();
            tree.query(ba, found::add);
            found.sort(BY_ID);
            for (RigidBody b : found) {
                if (b == a || (asks(b) && b.id < a.id)) continue; // two moving bodies: the lower id asked already
                if (!overlap(ba, b.collisionBounds()) || !mayCollide(a, b)) continue;
                if (a.id < b.id) pair.accept(a, b);
                else pair.accept(b, a);
            }
        }
    }

    static final Comparator<RigidBody> BY_ID = Comparator.comparingInt(body -> body.id);

    private static boolean asks(RigidBody body) {
        return body.isActive() || body.getType() == BodyType.KINEMATIC;
    }

    /**
     * Whether two bodies need testing: one must be dynamic, one must be moving (sleeping bodies keep the contacts they
     * fell asleep with), and their layers and masks must agree. A kinematic body and a trigger are always tested, so
     * a character or a moving platform sets off trigger zones, and stays in one while it stands still.
     */
    static boolean mayCollide(RigidBody a, RigidBody b) {
        boolean kinematicInTrigger = a.isTrigger() != b.isTrigger()
                && (a.getType() == BodyType.KINEMATIC && !b.isDynamic() || b.getType() == BodyType.KINEMATIC && !a.isDynamic());
        if (!kinematicInTrigger) {
            if (!a.isDynamic() && !b.isDynamic()) return false;
            if (!a.isActive() && !b.isActive()) return false;
        }
        return (a.getCollisionLayer() & b.getCollisionMask()) != 0 && (b.getCollisionLayer() & a.getCollisionMask()) != 0;
    }

    static boolean overlap(Bounds a, Bounds b) {
        return a.min().x() <= b.max().x() && a.max().x() >= b.min().x()
                && a.min().y() <= b.max().y() && a.max().y() >= b.min().y()
                && a.min().z() <= b.max().z() && a.max().z() >= b.min().z();
    }
}
