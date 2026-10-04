package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MeshHullTest {
    private static final float M = PhysicsWorld.CONTACT_MARGIN;
    private static final float DT = 1f / 60f;
    private static final float G = 9.81f;

    private static void run(PhysicsWorld world, float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) world.step(DT);
    }

    private static Pose at(float x, float y, float z) {
        return new Pose(new Vec3(x, y, z), Quat.IDENTITY);
    }

    private static void assertVec(Vec3 expected, Vec3 actual, float tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance, "x of " + actual);
        assertEquals(expected.y(), actual.y(), tolerance, "y of " + actual);
        assertEquals(expected.z(), actual.z(), tolerance, "z of " + actual);
    }

    private static Vec3[] cubeCorners(float half) {
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) corners[i] = new Vec3((i & 1) == 0 ? -half : half, (i & 2) == 0 ? -half : half, (i & 4) == 0 ? -half : half);
        return corners;
    }

    private static final ConvexHullShape CUBE_HULL = ConvexHullShape.of(cubeCorners(0.5f));

    /**
     * A square of {@code cells} by {@code cells} cells of {@code size} metres, centred on the origin, two triangles per
     * cell facing up, with heights from {@code height}.
     */
    private static MeshShape grid(int cells, float size, java.util.function.BiFunction<Float, Float, Float> height) {
        int n = cells + 1;
        float[] positions = new float[n * n * 3];
        float start = -cells * size * 0.5f;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float x = start + i * size, z = start + j * size;
                int o = (i * n + j) * 3;
                positions[o] = x;
                positions[o + 1] = height.apply(x, z);
                positions[o + 2] = z;
            }
        }
        int[] indices = new int[cells * cells * 6];
        int k = 0;
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                int a = i * n + j, b = i * n + j + 1, c = (i + 1) * n + j + 1, d = (i + 1) * n + j;
                indices[k++] = a;
                indices[k++] = b;
                indices[k++] = c;
                indices[k++] = a;
                indices[k++] = c;
                indices[k++] = d;
            }
        }
        return new MeshShape(positions, indices);
    }

    private static MeshShape flatGrid(int cells, float size) {
        return grid(cells, size, (_, _) -> 0f);
    }

    private static PhysicsWorld onMesh(MeshShape mesh) {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(mesh));
        return world;
    }

    // ---- Building hulls ----

    @Test
    void cubeHullKeepsOnlyItsCorners() {
        List<Vec3> points = new ArrayList<>(List.of(cubeCorners(0.5f)));
        Random random = new Random(3);
        for (int i = 0; i < 100; i++) points.add(new Vec3(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f).mul(0.99f));
        // Points on the faces and edges are dropped too.
        points.add(new Vec3(0.5f, 0.1f, 0.2f));
        points.add(new Vec3(0.5f, 0.5f, 0f));
        ConvexHullShape hull = ConvexHullShape.of(points);
        assertEquals(8, hull.vertices().size());
        assertEquals(6, hull.faceCount(), "the two triangles of each side merge into one face");
        assertEquals(1f, hull.volume(), 1e-5f);
        assertVec(Vec3.ZERO, hull.centerOfMass(), 1e-5f);
        assertVec(new BoxShape(0.5f, 0.5f, 0.5f).unitInertia(), hull.unitInertia(), 1e-5f);
        Polytope p = hull.polytope();
        assertEquals(12, p.edges.length / 2);
        assertEquals(3, p.directions.length, "a cube's edges run three ways");
    }

    @Test
    void randomCloudHullHoldsEveryPoint() {
        Random random = new Random(11);
        for (int round = 0; round < 20; round++) {
            List<Vec3> points = new ArrayList<>();
            int count = 4 + random.nextInt(300);
            for (int i = 0; i < count; i++) {
                Vec3 p = new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian());
                points.add(round % 2 == 0 ? p : p.normalized().mul(2f)); // odd rounds: every point on a ball, all corners
            }
            Polytope hull = ConvexHullShape.of(points).polytope();
            for (Vec3 point : points) {
                for (int f = 0; f < hull.normals.length; f++) {
                    assertTrue(hull.normals[f].dot(point) - hull.offsets[f] < 1e-4f, "point " + point + " outside face " + f + " in round " + round);
                }
            }
            int edges = hull.edges.length / 2;
            assertEquals(2, hull.vertices.length - edges + hull.faces.length, "Euler's formula, round " + round);
            for (int f = 0; f < hull.normals.length; f++) {
                for (int index : hull.faces[f]) assertEquals(hull.offsets[f], hull.normals[f].dot(hull.vertices[index]), 5e-4f, "flat faces");
            }
        }
    }

    @Test
    void lopsidedHullHasItsCentreAndTurnedInertia() {
        // A 2 x 1 x 1 block turned 30 degrees and moved off the origin.
        Quat turn = Quat.fromAxisAngle(Vec3.Z, (float) Math.toRadians(30));
        Vec3 shift = new Vec3(3f, 1f, -2f);
        List<Vec3> points = new ArrayList<>();
        for (Vec3 c : cubeCorners(0.5f)) points.add(turn.rotate(c.mul(new Vec3(2f, 1f, 1f))).add(shift));
        ConvexHullShape hull = ConvexHullShape.of(points);
        assertEquals(2f, hull.volume(), 1e-4f);
        assertVec(shift, hull.centerOfMass(), 1e-4f);
        Mat3 r = Mat3.rotation(turn);
        Mat3 expected = Mat3.diagonal(new BoxShape(1f, 0.5f, 0.5f).unitInertia()).rotated(r);
        Mat3 actual = hull.unitInertiaTensor();
        float[] e = {expected.xx(), expected.xy(), expected.xz(), expected.yx(), expected.yy(), expected.yz(), expected.zx(), expected.zy(), expected.zz()};
        float[] a = {actual.xx(), actual.xy(), actual.xz(), actual.yx(), actual.yy(), actual.yz(), actual.zx(), actual.zy(), actual.zz()};
        assertArrayEquals(e, a, 1e-4f);
        assertNotEquals(0f, actual.xy(), "turned, so X and Y are coupled");
    }

    @Test
    void hullOfARoundMesh() {
        Mesh ball = Mesh.sphere(1f, 32, 16);
        ConvexHullShape hull = ConvexHullShape.of(ball);
        float sphere = (float) (4.0 / 3.0 * Math.PI);
        assertTrue(hull.volume() < sphere && hull.volume() > sphere * 0.97f, "volume " + hull.volume());
        assertVec(Vec3.ZERO, hull.centerOfMass(), 1e-3f);
        assertVec(new Vec3(0.4f, 0.4f, 0.4f), hull.unitInertia(), 0.02f);
        assertTrue(hull.vertices().size() <= ball.vertexCount());
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(hull).setPosition(0, 1.5f, 0).setVelocity(2f, 0, 0);
        world.add(body);
        run(world, 1f);
        assertEquals(1f, body.getPosition().y(), 0.02f, "rests on its corners, close to its radius");
        assertTrue(body.getAngularVelocity().length() > 1f, "rolls like a ball");
    }

    @Test
    void flatPointsHaveNoHull() {
        assertThrows(IllegalArgumentException.class, () -> ConvexHullShape.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(1, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> ConvexHullShape.of(new Vec3(0, 0, 0), new Vec3(1, 1, 1), new Vec3(2, 2, 2), new Vec3(3, 3, 3)));
        assertThrows(IllegalArgumentException.class, () -> ConvexHullShape.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)));
    }

    @Test
    void hullScalesAndMirrors() {
        ConvexHullShape wide = CUBE_HULL.scaled(new Vec3(2f, 1f, 1f));
        assertEquals(2f, wide.volume(), 1e-5f);
        assertSame(wide, CUBE_HULL.scaled(new Vec3(2f, 1f, 1f)), "kept, not rebuilt");
        ConvexHullShape wedge = ConvexHullShape.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
        ConvexHullShape mirrored = wedge.scaled(new Vec3(-1f, 1f, 1f));
        assertEquals(wedge.volume(), mirrored.volume(), 1e-6f);
        assertVec(new Vec3(-0.25f, 0.25f, 0.25f), mirrored.centerOfMass(), 1e-5f);
    }

    @Test
    void hullFromAMeshLinesUpWithIt() {
        ConvexHullShape hull = ConvexHullShape.of(Mesh.cube(2f));
        assertEquals(8, hull.vertices().size());
        assertEquals(8f, hull.volume(), 1e-4f);
        Node3D root = new Node3D("rock");
        Node3D part = new Node3D("part", Mesh.cube(1f), null).setPosition(new Vec3(5f, 0, 0));
        root.add(part);
        ConvexHullShape both = ConvexHullShape.of(root);
        assertEquals(5f, both.centerOfMass().x(), 1e-4f, "the child's own place counts");

        Mesh drawn = CUBE_HULL.toMesh();
        assertEquals(12, drawn.indexCount() / 3, "two triangles per side");
        assertEquals(24, drawn.vertexCount(), "corners repeated per face for flat normals");
        ConvexHullShape again = ConvexHullShape.of(drawn);
        assertEquals(CUBE_HULL.volume(), again.volume(), 1e-5f);
        MeshShape surface = MeshShape.of(drawn);
        for (int t = 0; t < surface.triangleCount(); t++) {
            Vec3[] corners = surface.triangle(t);
            Vec3 middle = corners[0].add(corners[1]).add(corners[2]).mul(1f / 3f);
            assertTrue(surface.triangleNormal(t).dot(middle) > 0f, "faces wound outward");
        }
    }

    // ---- Hull contacts ----

    private static Manifold collide(Shape a, Pose pa, Shape b, Pose pb) {
        Manifold m = new Manifold();
        Narrowphase.collide(a, pa, b, pb, M, m);
        return m;
    }

    @Test
    void hullRestsOnAPlaneAtItsCorners() {
        Manifold m = collide(CUBE_HULL, at(0, 0.49f, 0), new PlaneShape(), at(0, 0, 0));
        assertEquals(4, m.count);
        assertVec(new Vec3(0, -1, 0), m.normal, 1e-6f);
        for (int i = 0; i < m.count; i++) assertEquals(0.01f, m.depths[i], 1e-5f);
    }

    @Test
    void hullsMatchBoxesFaceToFace() {
        BoxShape box = new BoxShape(0.5f, 0.5f, 0.5f);
        Quat twist = Quat.fromAxisAngle(Vec3.UP, 0.3f);
        Pose lower = at(0, 0, 0), upper = new Pose(new Vec3(0.2f, 0.98f, 0.1f), twist);
        Manifold boxes = collide(box, lower, box, upper);
        Manifold hulls = collide(CUBE_HULL, lower, CUBE_HULL, upper);
        Manifold mixed = collide(box, lower, CUBE_HULL, upper);
        for (Manifold m : new Manifold[]{hulls, mixed}) {
            assertVec(boxes.normal, m.normal, 1e-5f);
            assertEquals(4, m.count);
            for (int i = 0; i < m.count; i++) assertEquals(0.02f, m.depths[i], 1e-4f);
        }
        assertEquals(0, collide(CUBE_HULL, lower, CUBE_HULL, at(0, 1.1f, 0)).count, "apart");
    }

    @Test
    void hullsMeetEdgeToEdge() {
        // Two cubes, each turned 45 degrees about a different axis, touching edge to edge.
        Quat a = Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 4), b = Quat.fromAxisAngle(Vec3.X, (float) Math.PI / 4);
        float reach = (float) Math.sqrt(0.5);
        Manifold m = collide(CUBE_HULL, new Pose(Vec3.ZERO, a), CUBE_HULL, new Pose(new Vec3(0, 2 * reach - 0.01f, 0), b));
        assertEquals(1, m.count);
        assertVec(Vec3.UP, m.normal, 1e-4f);
        assertEquals(0.01f, m.depths[0], 1e-4f);
        assertVec(new Vec3(0, reach - 0.005f, 0), m.points[0], 1e-3f);
    }

    @Test
    void sphereFindsHullFacesEdgesAndCorners() {
        SphereShape ball = new SphereShape(0.5f);
        Manifold face = collide(ball, at(0, 0.9f, 0), CUBE_HULL, at(0, 0, 0));
        assertVec(new Vec3(0, -1, 0), face.normal, 1e-5f);
        assertEquals(0.1f, face.depths[0], 1e-5f);
        float s = 0.5f + 0.4f / (float) Math.sqrt(2);
        Manifold edge = collide(ball, at(s, s, 0), CUBE_HULL, at(0, 0, 0));
        assertVec(new Vec3(-1, -1, 0).normalized(), edge.normal, 1e-4f);
        assertEquals(0.1f, edge.depths[0], 1e-4f);
        float c = 0.5f + 0.45f / (float) Math.sqrt(3);
        Manifold corner = collide(ball, at(c, c, c), CUBE_HULL, at(0, 0, 0));
        assertVec(new Vec3(-1, -1, -1).normalized(), corner.normal, 1e-4f);
        assertEquals(0.05f, corner.depths[0], 1e-4f);
        Manifold inside = collide(ball, at(0, 0.3f, 0), CUBE_HULL, at(0, 0, 0));
        assertVec(new Vec3(0, -1, 0), inside.normal, 1e-5f);
        assertEquals(0.7f, inside.depths[0], 1e-4f);
        assertEquals(0, collide(ball, at(0, 1.1f, 0), CUBE_HULL, at(0, 0, 0)).count);
    }

    @Test
    void capsuleLyingOnAHullTouchesAtBothEnds() {
        CapsuleShape capsule = new CapsuleShape(0.25f, 0.3f);
        Quat lying = Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2);
        Manifold m = collide(capsule, new Pose(new Vec3(0, 0.74f, 0), lying), CUBE_HULL, at(0, 0, 0));
        assertEquals(2, m.count);
        assertVec(new Vec3(0, -1, 0), m.normal, 1e-5f);
        for (int i = 0; i < 2; i++) assertEquals(0.01f, m.depths[i], 1e-5f);
    }

    // ---- Hulls moving ----

    @Test
    void hullCubeRestsLikeABox() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(CUBE_HULL).setPosition(0, 2f, 0);
        world.add(crate);
        run(world, 3f);
        assertEquals(0.5f, crate.getPosition().y(), 0.01f);
        assertEquals(1f, Math.abs(crate.getRotation().w()), 1e-3f);
        assertTrue(crate.isSleeping());
    }

    @Test
    void hullOffTheOriginSpinsAroundItsMiddle() {
        // The hull sits 2 m along X from the body's origin, like a model whose origin is at its corner.
        List<Vec3> points = new ArrayList<>();
        for (Vec3 c : cubeCorners(0.5f)) points.add(c.add(new Vec3(2f, 0, 0)));
        RigidBody body = new RigidBody(BodyType.DYNAMIC).addShape(ConvexHullShape.of(points));
        assertVec(new Vec3(2f, 0, 0), body.getCenterOfMass(), 1e-5f);
        assertVec(new BoxShape(0.5f, 0.5f, 0.5f).unitInertia(), body.getInertia(), 1e-4f);
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        world.add(body.setPosition(0, 1f, 0));
        run(world, 3f);
        assertEquals(0.5f, body.getPosition().y(), 0.01f);
        assertEquals(0f, body.getPosition().x(), 0.01f, "fell straight down");
    }

    @Test
    void rocksSettleOnARockPile() {
        PhysicsWorld world = new PhysicsWorld();
        world.add(new RigidBody(BodyType.STATIC).addShape(new PlaneShape()));
        Random random = new Random(5);
        List<RigidBody> rocks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            List<Vec3> points = new ArrayList<>();
            for (int k = 0; k < 20; k++) {
                Vec3 p = new Vec3((float) random.nextGaussian(), (float) random.nextGaussian() * 0.7f, (float) random.nextGaussian());
                points.add(p.normalized().mul(0.3f + random.nextFloat() * 0.1f));
            }
            RigidBody rock = new RigidBody(BodyType.DYNAMIC).addShape(ConvexHullShape.of(points)).setDensity(2500f)
                    .setPosition((i % 3) * 0.3f, 0.5f + i * 0.6f, (i / 3 % 2) * 0.3f);
            world.add(rock);
            rocks.add(rock);
        }
        run(world, 8f);
        for (RigidBody rock : rocks) {
            assertTrue(rock.getPosition().y() > 0f, "above the floor: " + rock.getPosition());
            assertTrue(rock.getVelocity().length() < 0.05f, "settled: " + rock.getVelocity());
        }
        long asleep = rocks.stream().filter(RigidBody::isSleeping).count();
        assertTrue(asleep >= rocks.size() - 1, asleep + " of " + rocks.size() + " asleep");
    }

    // ---- Meshes ----

    @Test
    void meshJoinsCornersAndFindsItsRealEdges() {
        MeshShape flat = flatGrid(4, 1f);
        assertEquals(32, flat.triangleCount());
        int seams = 0, real = 0;
        for (int t = 0; t < flat.triangleCount(); t++) {
            for (int e = 0; e < 3; e++) {
                if ((flat.realEdges(t) & 1 << e) != 0) real++;
                else seams++;
            }
        }
        assertEquals(16, real, "the border: 4 sides of 4 cells");
        assertEquals(80, seams, "every edge inside, counted from both sides");

        // A roof: two slopes meeting at a ridge along Z. The ridge is a real edge; turned over, it is a valley and a seam.
        float[] roof = {-1, 0, -1, -1, 0, 1, 0, 0.5f, 1, 0, 0.5f, -1, 1, 0, 1, 1, 0, -1};
        int[] up = {0, 1, 2, 0, 2, 3, 3, 2, 4, 3, 4, 5};
        MeshShape ridge = new MeshShape(roof, up);
        assertTrue(ridge.triangleNormal(0).y() > 0f);
        assertEquals(1 << 1, ridge.realEdges(1) & 1 << 1, "the ridge (corners 2-3) is real");
        int[] down = {0, 2, 1, 0, 3, 2, 3, 4, 2, 3, 5, 4};
        MeshShape valley = new MeshShape(roof, down);
        assertEquals(0, valley.realEdges(1) & 1 << 2, "seen from below the ridge is a valley");

        // A mesh made the usual way, with corners repeated per face, is joined back together.
        MeshShape cube = MeshShape.of(Mesh.cube(1f));
        assertEquals(12, cube.triangleCount());
        for (int t = 0; t < 12; t++) assertEquals(2, Integer.bitCount(cube.realEdges(t)), "a cube's edges are real, the diagonals across its sides aren't");
    }

    @Test
    void meshesCannotMove() {
        MeshShape mesh = flatGrid(2, 1f);
        assertThrows(IllegalStateException.class, () -> new RigidBody(BodyType.DYNAMIC).addShape(mesh));
        RigidBody body = new RigidBody(BodyType.STATIC).addShape(mesh);
        assertThrows(IllegalStateException.class, () -> body.setType(BodyType.DYNAMIC));
        body.setType(BodyType.KINEMATIC);
    }

    @Test
    void boxSlidesAcrossSeamsAsOnAPlane() {
        float expected = 25f / (2f * 0.5f * G); // v² / (2 µ g), as on a plane
        Vec3 heading = new Vec3(4f, 0, 3f); // across the cells and their diagonals
        PhysicsWorld world = onMesh(flatGrid(24, 0.5f));
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(1, 1, 1)).setPosition(-2f, 0.5f, -1.5f).setVelocity(heading);
        world.add(crate);
        float worst = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(DT);
            worst = Math.max(worst, Math.abs(crate.getVelocity().y()));
        }
        Vec3 travelled = crate.getPosition().sub(new Vec3(-2f, 0.5f, -1.5f));
        assertEquals(expected, travelled.length(), expected * 0.05f);
        assertEquals(0f, travelled.normalized().sub(heading.normalized()).length(), 0.02f, "kept its heading");
        assertEquals(0f, travelled.y(), 0.01f);
        assertEquals(1f, Math.abs(crate.getRotation().w()), 2e-3f, "didn't trip on a seam");
        assertTrue(worst < 0.1f, "bumped up at " + worst + " m/s");
    }

    @Test
    void ballRollsAcrossSeamsWithoutHopping() {
        PhysicsWorld world = onMesh(flatGrid(30, 0.5f)).setSleeping(false);
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.25f)).setPosition(-6f, 0.25f, -0.13f)
                .setVelocity(3f, 0, 0.4f).setAngularDamping(0f);
        world.add(ball);
        run(world, 0.2f);
        float worst = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(DT);
            worst = Math.max(worst, Math.abs(ball.getVelocity().y()));
            assertEquals(0.25f, ball.getPosition().y(), 0.01f);
        }
        assertTrue(worst < 0.05f, "hopped at " + worst + " m/s");
        // Sliding at first, it rolls on at 5/7 of its speed, about 2.1 m/s.
        assertTrue(ball.getPosition().x() > 0.5f, "rolled to " + ball.getPosition());
    }

    @Test
    void capsuleAndHullRestOnAMeshFloor() {
        PhysicsWorld world = onMesh(flatGrid(8, 1f));
        RigidBody capsule = new RigidBody(BodyType.DYNAMIC).addShape(new CapsuleShape(0.25f, 0.5f))
                .setRotation(Quat.fromAxisAngle(Vec3.Z, (float) Math.PI / 2)).setPosition(0.3f, 0.3f, 0.5f);
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(CUBE_HULL).setPosition(-2f, 1f, -2f);
        world.add(capsule).add(crate);
        run(world, 3f);
        assertEquals(0.25f, capsule.getPosition().y(), 0.01f);
        assertEquals(0.5f, crate.getPosition().y(), 0.01f);
        assertTrue(capsule.isSleeping() && crate.isSleeping());
        assertTrue(world.getContacts().stream().allMatch(c -> c.getTriangle() >= 0), "contacts know their triangle");
        assertEquals(2, world.getContacts().size(), "one per body: the triangles under each merge into one contact");
    }

    @Test
    void rockSettlesOnBumpyTerrain() {
        MeshShape terrain = grid(20, 0.5f, (x, z) -> 0.3f * (float) (Math.sin(x * 1.3) * Math.cos(z * 1.1)));
        PhysicsWorld world = onMesh(terrain);
        Random random = new Random(9);
        List<Vec3> points = new ArrayList<>();
        for (int k = 0; k < 24; k++) points.add(new Vec3((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()).normalized().mul(0.4f));
        RigidBody rock = new RigidBody(BodyType.DYNAMIC).addShape(ConvexHullShape.of(points)).setPosition(0.4f, 2f, 0.2f);
        world.add(rock);
        run(world, 8f);
        assertTrue(rock.getVelocity().length() < 0.05f, "settled: " + rock.getVelocity());
        RaycastHit ground = world.raycast(Ray.of(rock.getCenterOfMass().add(new Vec3(0, 3, 0)), new Vec3(0, -1, 0)), 10f);
        assertNotNull(ground);
        assertSame(rock, ground.body(), "the rock is on top of the terrain, not sunk into it");
    }

    @Test
    void boxHeldOnATurnedMeshRamp() {
        PhysicsWorld world = new PhysicsWorld();
        Quat tilt = Quat.fromAxisAngle(Vec3.Z, (float) Math.toRadians(20));
        world.add(new RigidBody(BodyType.STATIC).addShape(flatGrid(10, 1f)).setRotation(tilt).setPosition(0, 1f, 0));
        RigidBody crate = new RigidBody(BodyType.DYNAMIC).addShape(BoxShape.ofSize(1, 1, 1)).setRotation(tilt)
                .setPosition(new Vec3(0, 1f, 0).add(tilt.rotate(new Vec3(0, 0.52f, 0))));
        world.add(crate);
        run(world, 0.5f);
        Vec3 settled = crate.getPosition();
        run(world, 3f);
        assertTrue(crate.getPosition().distance(settled) < 0.01f, "crept " + crate.getPosition().distance(settled));
        assertEquals(0.5f, tilt.conjugate().rotate(crate.getPosition().sub(new Vec3(0, 1f, 0))).y(), 0.01f, "on the ramp's surface");
    }

    @Test
    void fastBallDoesNotTunnelThroughAMesh() {
        PhysicsWorld world = onMesh(flatGrid(4, 2f));
        RigidBody ball = new RigidBody(BodyType.DYNAMIC).addShape(new SphereShape(0.1f)).setPosition(0.3f, 3f, 0.2f).setVelocity(0, -60f, 0);
        world.add(ball);
        run(world, 1f);
        assertTrue(ball.getPosition().y() > 0f, "went through to " + ball.getPosition());
    }

    @Test
    void meshFromANodeAndItsScale() {
        Node3D level = new Node3D("level");
        Node3D floor = new Node3D("floor", Mesh.plane(4f, 4f), null).setPosition(new Vec3(0, -1f, 0));
        level.add(floor);
        MeshShape shape = MeshShape.of(level);
        assertEquals(-1f, shape.bounds().min().y(), 1e-6f);
        level.setScale(2f);
        RigidBody body = new RigidBody(level, BodyType.STATIC).addShape(shape);
        PhysicsWorld world = new PhysicsWorld().add(body);
        RaycastHit hit = world.raycast(Ray.of(new Vec3(3f, 5f, 0), new Vec3(0, -1, 0)), 20f);
        assertNotNull(hit, "the floor is 8 m wide once scaled");
        assertEquals(-2f, hit.point().y(), 1e-4f);
        assertVec(Vec3.UP, hit.normal(), 1e-6f);
        assertTrue(hit.triangle() >= 0);
    }

    @Test
    void densityBeforeAddingSeesTheNodesScale() {
        Node3D crate = new Node3D("crate", Mesh.cube(1f), null).setScale(0.5f);
        RigidBody body = new RigidBody(crate).addShape(BoxShape.ofSize(1, 1, 1)).setDensity(1000f);
        assertEquals(125f, body.getMass(), 1e-3f, "a 0.5 m cube of water");
    }

    // ---- Queries ----

    @Test
    void raysHitMeshesFromEitherSide() {
        PhysicsWorld world = onMesh(flatGrid(4, 1f));
        RaycastHit down = world.raycast(Ray.of(new Vec3(0.3f, 2f, 0.4f), new Vec3(0, -1, 0)), 10f);
        assertNotNull(down);
        assertEquals(2f, down.distance(), 1e-5f);
        assertVec(Vec3.UP, down.normal(), 1e-6f);
        Set<Integer> triangles = new HashSet<>();
        for (float x = -1.9f; x < 2f; x += 0.25f) {
            triangles.add(world.raycast(Ray.of(new Vec3(x, 1f, x * 0.7f), new Vec3(0, -1, 0)), 10f).triangle());
        }
        assertTrue(triangles.size() > 4, "different triangles: " + triangles);
        RaycastHit up = world.raycast(Ray.of(new Vec3(0.3f, -1f, 0.4f), Vec3.UP), 10f);
        assertNotNull(up, "from below too");
        assertVec(new Vec3(0, -1, 0), up.normal(), 1e-6f);
        assertNull(world.raycast(Ray.of(new Vec3(5f, 1f, 0), new Vec3(0, -1, 0)), 10f), "past the edge");
        assertNull(world.raycast(Ray.of(new Vec3(0.3f, 2f, 0.4f), new Vec3(0, -1, 0)), 1.5f), "too short");
    }

    @Test
    void raysHitHulls() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody rock = new RigidBody(BodyType.STATIC).addShape(CUBE_HULL).setRotation(Quat.fromAxisAngle(Vec3.UP, (float) Math.PI / 4));
        world.add(rock);
        RaycastHit hit = world.raycast(Ray.of(new Vec3(-3f, 0, 0), Vec3.X), 10f);
        assertNotNull(hit);
        assertEquals(3f - (float) Math.sqrt(0.5), hit.distance(), 1e-4f, "meets the turned cube's edge");
        RaycastHit face = world.raycast(Ray.of(new Vec3(0, 3f, 0), new Vec3(0, -1, 0)), 10f);
        assertEquals(2.5f, face.distance(), 1e-5f);
        assertVec(Vec3.UP, face.normal(), 1e-5f);
        assertNull(world.raycast(Ray.of(Vec3.ZERO, Vec3.X), 10f), "from inside");
    }

    @Test
    void overlapQueriesFindMeshesAndHulls() {
        PhysicsWorld world = onMesh(flatGrid(4, 1f));
        RigidBody rock = new RigidBody(BodyType.STATIC).addShape(CUBE_HULL).setPosition(10f, 0, 0);
        world.add(rock);
        assertEquals(1, world.overlapSphere(new Vec3(0.2f, 0.3f, 0.1f), 0.4f).size());
        assertEquals(0, world.overlapSphere(new Vec3(0.2f, 0.5f, 0.1f), 0.4f).size());
        assertEquals(List.of(rock), world.overlapBox(new Vec3(10.9f, 0.5f, 0), new Vec3(0.5f, 0.1f, 0.5f), Quat.IDENTITY));
        assertEquals(List.of(rock), world.overlap(CUBE_HULL, new Vec3(10.5f, 0.6f, 0), Quat.IDENTITY, -1));
        assertThrows(IllegalArgumentException.class, () -> world.overlap(flatGrid(1, 1f), Vec3.ZERO, Quat.IDENTITY, -1));
    }
}
