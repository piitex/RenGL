package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Finds the pairs of bodies whose boxes overlap, by sweep and prune: the bodies are kept sorted by where their boxes
 * start along one axis, so only neighbours along it are compared. Bodies move little between steps, so the order is
 * nearly sorted already and keeping it sorted costs about one pass. The axis is the one the bodies are most spread
 * along.
 */
final class Broadphase {
    private final List<RigidBody> sorted = new ArrayList<>();
    private int axis;

    void add(RigidBody body) {
        sorted.add(body);
    }

    void remove(RigidBody body) {
        sorted.remove(body);
    }

    int axis() {
        return axis;
    }

    /**
     * Calls {@code pair} for each pair of bodies whose boxes overlap and that may collide, the lower id first.
     */
    void findPairs(BiConsumer<RigidBody, RigidBody> pair) {
        int n = sorted.size();
        axis = spreadAxis();
        for (int i = 1; i < n; i++) { // insertion sort: quick on a nearly sorted list
            RigidBody body = sorted.get(i);
            int j = i - 1;
            while (j >= 0 && before(body, sorted.get(j))) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, body);
        }
        for (int i = 0; i < n; i++) {
            RigidBody a = sorted.get(i);
            Bounds ba = a.collisionBounds();
            float end = Narrowphase.get(ba.max(), axis);
            for (int j = i + 1; j < n; j++) {
                RigidBody b = sorted.get(j);
                Bounds bb = b.collisionBounds();
                if (Narrowphase.get(bb.min(), axis) > end) break;
                if (!overlap(ba, bb) || !mayCollide(a, b)) continue;
                if (a.id < b.id) pair.accept(a, b);
                else pair.accept(b, a);
            }
        }
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

    private boolean before(RigidBody a, RigidBody b) {
        float ma = Narrowphase.get(a.collisionBounds().min(), axis), mb = Narrowphase.get(b.collisionBounds().min(), axis);
        return ma < mb || (ma == mb && a.id < b.id);
    }

    private int spreadAxis() {
        double[] sum = new double[3], sumSquares = new double[3];
        int count = 0;
        for (RigidBody body : sorted) {
            Bounds bounds = body.collisionBounds();
            if (Float.isInfinite(bounds.min().x()) || Float.isInfinite(bounds.max().y())) continue; // an endless plane spreads nothing
            for (int k = 0; k < 3; k++) {
                double c = (Narrowphase.get(bounds.min(), k) + Narrowphase.get(bounds.max(), k)) * 0.5;
                sum[k] += c;
                sumSquares[k] += c * c;
            }
            count++;
        }
        if (count < 2) return axis;
        double[] spread = new double[3];
        int best = 0;
        for (int k = 0; k < 3; k++) {
            spread[k] = sumSquares[k] - sum[k] * sum[k] / count;
            if (spread[k] > spread[best]) best = k;
        }
        // Stick with the current axis unless another is clearly better, so the order stays nearly sorted.
        if (spread[axis] * 1.25 >= spread[best]) return axis;
        return best;
    }
}
