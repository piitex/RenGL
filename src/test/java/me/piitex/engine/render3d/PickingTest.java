package me.piitex.engine.render3d;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Hit;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Picking and the math under it. No GPU needed.
 */
class PickingTest {
    private static final float EPS = 1e-3f;
    private static final Vec3 FORWARD = new Vec3(0, 0, -1);

    private Scene3D scene;
    private Node3D cube;

    @BeforeEach
    void setUp() {
        scene = new Scene3D();
        cube = new Node3D("cube", Mesh.cube(2f), new Material(Color.RED));
        cube.setPosition(0, 0, -5);
        scene.add(cube);
    }

    private Hit pick(Vec3 origin, Vec3 direction) {
        return scene.pick(Ray.of(origin, direction));
    }

    @Test
    void hitsTheNearestFaceWithItsNormal() {
        Hit hit = pick(Vec3.ZERO, FORWARD);
        assertNotNull(hit);
        assertSame(cube, hit.node());
        assertEquals(4f, hit.distance(), EPS);
        assertEquals(-4f, hit.point().z(), EPS);
        assertEquals(1f, hit.normal().z(), EPS);
    }

    @Test
    void missesAndIgnoresWhatIsBehind() {
        assertNull(pick(new Vec3(0, 3, 0), FORWARD));
        assertNull(pick(Vec3.ZERO, new Vec3(0, 0, 1)));
    }

    @Test
    void backFacesAreSkippedUnlessDoubleSided() {
        assertNull(pick(new Vec3(0, 0, -5), FORWARD));
        cube.setMaterial(new Material(Color.RED).setDoubleSided(true));
        Hit inside = pick(new Vec3(0, 0, -5), FORWARD);
        assertNotNull(inside);
        assertEquals(1f, inside.distance(), EPS);
        assertEquals(1f, inside.normal().z(), EPS);
    }

    @Test
    void scaledAndRotatedBoxGivesTheRightDistance() {
        cube.setScale(new Vec3(2, 1, 1));
        cube.setRotation(Quat.fromAxisAngle(Vec3.UP, (float) Math.toRadians(45)));
        Hit hit = pick(Vec3.ZERO, FORWARD);
        assertNotNull(hit);
        assertEquals(marchedDistance(cube), hit.distance(), EPS);
    }

    @Test
    void mirroredNodePicksLikeAnyOther() {
        cube.setScale(new Vec3(-1, 1, 1));
        Hit outside = pick(Vec3.ZERO, FORWARD);
        assertNotNull(outside);
        assertEquals(4f, outside.distance(), EPS);
        assertNull(pick(new Vec3(0, 0, -5), FORWARD), "inside a mirrored cube is still its back face");
    }

    @Test
    void childrenFollowTheirParentsTransform() {
        Node3D group = new Node3D("group");
        group.setPosition(10, 0, 0);
        group.setScale(2f);
        Node3D ball = new Node3D("ball", Mesh.sphere(1f, 48, 24), new Material(Color.BLUE));
        group.add(ball);
        scene.add(group);
        Hit hit = pick(new Vec3(10, 0, 5), FORWARD);
        assertNotNull(hit);
        assertSame(ball, hit.node());
        assertEquals(3f, hit.distance(), 0.01f);
        assertTrue(hit.normal().z() > 0.99f);
    }

    @Test
    void pickableAndVisibleFlagsAreHonoured() {
        Node3D group = new Node3D("group");
        Node3D ball = new Node3D("ball", Mesh.sphere(1f, 16, 8), new Material(Color.BLUE));
        group.add(ball);
        group.setPosition(10, 0, 0);
        scene.add(group);
        Vec3 origin = new Vec3(10, 0, 5);
        assertNotNull(pick(origin, FORWARD));
        ball.setPickable(false);
        assertNull(pick(origin, FORWARD));
        ball.setPickable(true);
        group.setVisible(false);
        assertNull(pick(origin, FORWARD));
    }

    @Test
    void pickAllIsNearestFirst() {
        Node3D back = new Node3D("back", Mesh.cube(2f), new Material(Color.GREEN));
        back.setPosition(0, 0, -12);
        scene.add(back);
        List<Hit> all = scene.pickAll(Ray.of(Vec3.ZERO, FORWARD));
        assertEquals(2, all.size());
        assertSame(cube, all.get(0).node());
        assertSame(back, all.get(1).node());
        assertTrue(all.get(0).distance() < all.get(1).distance());
    }

    @Test
    void screenRaysFollowTheCamera() {
        scene.camera().lookAt(Vec3.ZERO, FORWARD).setFovYDegrees(90);
        Ray centre = scene.camera().screenRay(320, 180, 640, 360);
        assertEquals(-1f, centre.direction().z(), EPS);
        assertEquals(0f, centre.direction().x(), EPS);
        assertEquals(0f, centre.direction().y(), EPS);
        Ray top = scene.camera().screenRay(320, 0, 640, 360);
        assertEquals(1f, top.direction().y() / -top.direction().z(), EPS);
        Ray right = scene.camera().screenRay(640, 180, 640, 360);
        assertEquals(640f / 360f, right.direction().x() / -right.direction().z(), EPS);
        Hit click = scene.pick(320, 180, 640, 360);
        assertNotNull(click);
        assertSame(cube, click.node());
    }

    @Test
    void screenPickStopsAtTheFarPlane() {
        scene.camera().lookAt(Vec3.ZERO, FORWARD).setClip(0.1f, 10f);
        assertNotNull(scene.pick(320, 180, 640, 360));
        cube.setScale(1f);
        cube.setPosition(0, 0, -20);
        assertNull(scene.pick(320, 180, 640, 360), "the cube is beyond the far plane");
        assertNotNull(scene.pick(Ray.of(Vec3.ZERO, FORWARD)), "a ray has no far plane");
    }

    @Test
    void inverseUndoesATransform() {
        Mat4 world = Mat4.compose(new Vec3(1, 2, 3), Quat.fromAxisAngle(new Vec3(1, 1, 0).normalized(), 0.7f), new Vec3(2, 3, 4));
        Mat4 identity = world.mul(world.inverse());
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                assertEquals(row == col ? 1f : 0f, identity.get(row, col), 1e-4f, "[" + row + "," + col + "]");
            }
        }
        assertNull(Mat4.scale(new Vec3(0, 1, 1)).inverse(), "a flattened transform can't be undone");
    }

    /**
     * Distance from the origin along -z to the node's surface, found by walking the transformed box.
     */
    private static float marchedDistance(Node3D node) {
        Mat4 inverse = node.worldMatrix().inverse();
        for (float t = 0; t < 20; t += 0.0005f) {
            Vec3 p = inverse.transformPoint(new Vec3(0, 0, -t));
            if (Math.abs(p.x()) <= 1 && Math.abs(p.y()) <= 1 && Math.abs(p.z()) <= 1) return t;
        }
        return -1;
    }
}
