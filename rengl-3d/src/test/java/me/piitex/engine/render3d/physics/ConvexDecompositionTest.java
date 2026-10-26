package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConvexDecompositionTest {
    private static final float DT = 1f / 60f;

    /**
     * A surface built a quad at a time.
     */
    private static final class Surface {
        final List<Float> positions = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
            int base = positions.size() / 3;
            for (Vec3 v : new Vec3[]{a, b, c, d}) {
                positions.add(v.x());
                positions.add(v.y());
                positions.add(v.z());
            }
            for (int i : new int[]{0, 1, 2, 0, 2, 3}) indices.add(base + i);
        }

        float[] positions() {
            float[] out = new float[positions.size()];
            for (int i = 0; i < out.length; i++) out[i] = positions.get(i);
            return out;
        }

        int[] indices() {
            return indices.stream().mapToInt(Integer::intValue).toArray();
        }

        Mesh mesh() {
            float[] p = positions();
            return new Mesh(p, new float[p.length], null, indices());
        }
    }

    /**
     * An L-shaped prism: two metres by two, a metre thick, with a metre square cut from one corner. Its volume is 3.
     */
    private static Surface lPrism() {
        float[][] outline = {{0, 0}, {2, 0}, {2, 1}, {1, 1}, {1, 2}, {0, 2}};
        Surface s = new Surface();
        for (int i = 0; i < outline.length; i++) {
            float[] p = outline[i], q = outline[(i + 1) % outline.length];
            s.quad(new Vec3(p[0], p[1], 0), new Vec3(q[0], q[1], 0), new Vec3(q[0], q[1], 1), new Vec3(p[0], p[1], 1));
        }
        for (float z : new float[]{0f, 1f}) {
            s.quad(new Vec3(0, 0, z), new Vec3(2, 0, z), new Vec3(2, 1, z), new Vec3(0, 1, z));
            s.quad(new Vec3(0, 1, z), new Vec3(1, 1, z), new Vec3(1, 2, z), new Vec3(0, 2, z));
        }
        return s;
    }

    private static Surface box(float x, float y, float z, float w, float h, float d) {
        Surface s = new Surface();
        Vec3 a = new Vec3(x, y, z), b = new Vec3(x + w, y, z), c = new Vec3(x + w, y + h, z), e = new Vec3(x, y + h, z);
        Vec3 a2 = a.add(new Vec3(0, 0, d)), b2 = b.add(new Vec3(0, 0, d)), c2 = c.add(new Vec3(0, 0, d)), e2 = e.add(new Vec3(0, 0, d));
        s.quad(a, b, c, e);
        s.quad(a2, e2, c2, b2);
        s.quad(a, a2, b2, b);
        s.quad(e, c, c2, e2);
        s.quad(a, e, e2, a2);
        s.quad(b, b2, c2, c);
        return s;
    }

    private static float volume(List<ConvexHullShape> hulls) {
        float total = 0f;
        for (ConvexHullShape h : hulls) total += h.volume();
        return total;
    }

    @Test
    void aConvexMeshStaysOneHull() {
        Surface cube = box(0, 0, 0, 1, 2, 3);
        List<ConvexHullShape> hulls = ConvexDecomposition.of(cube.positions(), cube.indices(), 8, 0f);
        assertEquals(1, hulls.size());
        assertEquals(6f, hulls.get(0).volume(), 1e-3f);
    }

    @Test
    void anLShapeIsCutIntoTwoBoxes() {
        Surface l = lPrism();
        assertEquals(3.5f, ConvexHullShape.of(l.positions()).volume(), 1e-3f, "the plain hull fills in the corner");
        List<ConvexHullShape> hulls = ConvexDecomposition.of(l.mesh(), 8);
        assertEquals(2, hulls.size());
        assertEquals(3f, volume(hulls), 0.05f);
    }

    @Test
    void theHullsCoverTheWholeSurface() {
        Surface l = lPrism();
        List<ConvexHullShape> hulls = ConvexDecomposition.of(l.mesh(), 8);
        float[] p = l.positions();
        for (int i = 0; i < p.length; i += 3) {
            Vec3 corner = new Vec3(p[i], p[i + 1], p[i + 2]);
            float deepest = Float.NEGATIVE_INFINITY;
            for (ConvexHullShape h : hulls) {
                Polytope polytope = h.polytope();
                float inside = Float.POSITIVE_INFINITY;
                for (int f = 0; f < polytope.normals.length; f++) inside = Math.min(inside, polytope.offsets[f] - polytope.normals[f].dot(corner));
                deepest = Math.max(deepest, inside);
            }
            assertTrue(deepest >= -1e-3f, "the corner " + corner + " is outside every hull by " + -deepest);
        }
    }

    /**
     * A staircase of {@code steps} steps, a metre wide, as a surface.
     */
    private static Surface stairs(int steps) {
        Surface s = new Surface();
        for (int i = 0; i < steps; i++) {
            float x = i * 0.5f, top = (i + 1) * 0.5f;
            s.quad(new Vec3(x, top, 0), new Vec3(x + 0.5f, top, 0), new Vec3(x + 0.5f, top, 1), new Vec3(x, top, 1)); // tread
            s.quad(new Vec3(x, i * 0.5f, 0), new Vec3(x, top, 0), new Vec3(x, top, 1), new Vec3(x, i * 0.5f, 1)); // riser
        }
        s.quad(new Vec3(0, 0, 0), new Vec3(steps * 0.5f, 0, 0), new Vec3(steps * 0.5f, 0, 1), new Vec3(0, 0, 1)); // bottom
        s.quad(new Vec3(steps * 0.5f, 0, 0), new Vec3(steps * 0.5f, steps * 0.5f, 0), new Vec3(steps * 0.5f, steps * 0.5f, 1), new Vec3(steps * 0.5f, 0, 1));
        for (float z : new float[]{0f, 1f}) { // sides
            for (int i = 0; i < steps; i++) s.quad(new Vec3(0, 0, z), new Vec3(steps * 0.5f, 0, z), new Vec3(steps * 0.5f, (i + 1) * 0.5f, z), new Vec3(i * 0.5f, (i + 1) * 0.5f, z));
        }
        return s;
    }

    @Test
    void moreHullsFollowTheShapeCloserAndNeverMoreThanAsked() {
        Surface stairs = stairs(6);
        float previous = Float.POSITIVE_INFINITY;
        for (int most : new int[]{1, 2, 4, 8}) {
            List<ConvexHullShape> hulls = ConvexDecomposition.of(stairs.positions(), stairs.indices(), most, 0.001f);
            assertTrue(hulls.size() <= most, hulls.size() + " hulls for at most " + most);
            float v = volume(hulls);
            assertTrue(v <= previous + 1e-3f, "volume " + v + " with " + most + " after " + previous);
            previous = v;
        }
        // Six half-metre steps are 5.25 m2 of stair seen from the side; the one hull is the wedge around them, 5.875.
        assertEquals(5.875f, volume(ConvexDecomposition.of(stairs.positions(), stairs.indices(), 1, 0f)), 0.01f);
        assertTrue(previous < 5.5f && previous > 5.2f, "eight hulls should be much closer to 5.25 than to 5.875, got " + previous);
    }

    @Test
    void aTighterToleranceCutsMore() {
        Surface stairs = stairs(6);
        int loose = ConvexDecomposition.of(stairs.positions(), stairs.indices(), 32, 0.4f).size();
        int tight = ConvexDecomposition.of(stairs.positions(), stairs.indices(), 32, 0.02f).size();
        assertTrue(tight > loose, loose + " hulls at 0.4 and " + tight + " at 0.02");
    }

    @Test
    void aFlatSheetBecomesThinSlabsNotAnError() {
        Surface s = new Surface();
        s.quad(new Vec3(0, 0, 0), new Vec3(2, 0, 0), new Vec3(2, 0, 1), new Vec3(0, 0, 1));
        s.quad(new Vec3(0, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 1, 1), new Vec3(0, 0, 1)); // a wall up from its edge: a bent sheet
        List<ConvexHullShape> hulls = ConvexDecomposition.of(s.positions(), s.indices(), 4, 0f);
        assertFalse(hulls.isEmpty());
        for (ConvexHullShape hull : hulls) assertTrue(hull.volume() > 0f);
        List<ConvexHullShape> flat = ConvexDecomposition.of(new float[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, new int[]{0, 1, 2, 0, 2, 3}, 4, 0f);
        assertEquals(1, flat.size());
    }

    @Test
    void badInputIsSaidInASentence() {
        Surface cube = box(0, 0, 0, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> ConvexDecomposition.of(cube.positions(), cube.indices(), 0, 0f));
        assertThrows(IllegalArgumentException.class, () -> ConvexDecomposition.of(new float[]{0, 0, 0, 1, 0, 0, 2, 0, 0}, new int[]{0, 1, 2}, 4, 0f));
        assertThrows(IllegalArgumentException.class, () -> ConvexDecomposition.of(new float[0], new int[0], 4, 0f));
    }

    @Test
    void theBodyAddsUpToTheModelsMiddle() {
        List<ConvexHullShape> hulls = ConvexDecomposition.of(lPrism().mesh(), 8);
        RigidBody body = new RigidBody(BodyType.DYNAMIC).setMass(3f);
        for (ConvexHullShape hull : hulls) body.addShape(hull);
        Vec3 middle = body.getLocalCenterOfMass();
        assertEquals(5f / 6f, middle.x(), 0.03f);
        assertEquals(5f / 6f, middle.y(), 0.03f);
        assertEquals(0.5f, middle.z(), 0.03f);
    }

    private static RigidBody lBody(PhysicsWorld world, float x, float y, float z) {
        RigidBody body = new RigidBody(BodyType.DYNAMIC).setMass(3f);
        for (ConvexHullShape hull : ConvexDecomposition.of(lPrism().mesh(), 8)) body.addShape(hull);
        body.setPosition(x, y, z);
        world.add(body);
        return body;
    }

    private static MeshShape floorMesh() {
        float[] p = {-5, 0, -5, 5, 0, -5, 5, 0, 5, -5, 0, 5};
        return new MeshShape(p, new int[]{0, 2, 1, 0, 3, 2});
    }

    @Test
    void aConcaveBodyFallsOntoAMeshAndComesToRestOnIt() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(floorMesh()));
        RigidBody body = lBody(world, 0f, 2f, 0f);
        for (int i = 0; i < 360; i++) world.step(DT);
        assertTrue(body.isSleeping(), "still moving at " + body.getVelocity());
        // Every corner of the model is above the floor, and one face of it rests on it.
        float lowest = Float.POSITIVE_INFINITY;
        float[] p = lPrism().positions();
        for (int i = 0; i < p.length; i += 3) {
            float y = body.getPosition().add(body.getRotation().rotate(new Vec3(p[i], p[i + 1], p[i + 2]))).y();
            lowest = Math.min(lowest, y);
        }
        assertEquals(0f, lowest, 0.03f);
    }

    @Test
    void twoConcaveBodiesHookedTogetherDontSinkIntoEachOther() {
        // One L upside down over the other's notch: a hull around either would fill the notch and hold them apart.
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(floorMesh()));
        RigidBody lower = lBody(world, 0f, 0f, 0f);
        lower.sleep();
        RigidBody upper = lBody(world, 0.1f, 1.3f, 0.2f);
        for (int i = 0; i < 240; i++) world.step(DT);
        // The upper one rests on the lower or beside it: never inside it. Measure by how far their hulls overlap.
        Manifold m = new Manifold();
        float deepest = 0f;
        for (int i = 0; i < upper.shapeCount(); i++) {
            for (int j = 0; j < lower.shapeCount(); j++) {
                if (Narrowphase.collide(upper.collisionShape(i), upper.shapePose(i), lower.collisionShape(j), lower.shapePose(j), 0f, m)) {
                    for (int k = 0; k < m.count; k++) deepest = Math.max(deepest, m.depths[k]);
                }
            }
        }
        assertTrue(deepest < 0.05f, "the pieces overlap by " + deepest);
        assertTrue(upper.getPosition().y() > 0.2f);
    }

    @Test
    void aConcaveBodyRollsDownAMeshRampAndKeepsOnIt() {
        // A static mesh ramp, tilted 35 degrees, steeper than the friction holds: the L slides down it, and no corner of the model gets under the surface.
        float run = 12f;
        float rise = run * (float) Math.tan(Math.toRadians(35));
        float[] p = {0, rise, -3, 0, rise, 3, run, 0, 3, run, 0, -3};
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new MeshShape(p, new int[]{0, 1, 2, 0, 2, 3})));
        RigidBody body = lBody(world, 1f, rise * (1f - 1f / run) + 0.3f, 0f);
        float[] model = lPrism().positions();
        float under = 0f, travelled = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(DT);
            for (int k = 0; k < model.length; k += 3) {
                Vec3 c = body.getPosition().add(body.getRotation().rotate(new Vec3(model[k], model[k + 1], model[k + 2])));
                if (c.x() < 0f || c.x() > run) continue;
                under = Math.min(under, c.y() - rise * (1f - c.x() / run));
            }
            travelled = Math.max(travelled, body.getPosition().x() - 1f);
        }
        assertTrue(under > -0.05f, "a corner got " + -under + " m under the ramp");
        assertTrue(travelled > 0.5f, "it should have slid down the ramp, but moved " + travelled);
    }
}
