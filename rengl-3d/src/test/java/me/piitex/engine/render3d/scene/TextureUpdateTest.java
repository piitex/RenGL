package me.piitex.engine.render3d.scene;

import me.piitex.engine.math.Vec2;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two halves of an animated, clickable screen that need no GPU: a texture that can change, and a pick that says
 * where on the texture it landed.
 */
class TextureUpdateTest {
    private static final float EPS = 1e-4f;

    @Test
    void aNewTextureHasVersionZeroAndEveryChangeCountsOnce() {
        TextureData texture = TextureData.solid(1, 0, 0, 1);
        assertEquals(0, texture.version());
        texture.update(new byte[]{0, 10, 20, (byte) 255});
        assertEquals(1, texture.version());
        texture.edit(px -> px[0] = 77);
        assertEquals(2, texture.version());
        long[] seen = new long[1];
        java.nio.ByteBuffer buffer = texture.toBuffer(seen);
        try {
            assertEquals(2, seen[0]);
            assertEquals(77, buffer.get(0));
            assertEquals(10, buffer.get(1));
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
    }

    @Test
    void updateCopiesAndChecksTheSize() {
        TextureData texture = new TextureData(2, 2, new byte[16]);
        byte[] fresh = new byte[16];
        fresh[0] = 5;
        texture.update(fresh);
        fresh[0] = 9; // the texture holds its own copy
        texture.edit(px -> assertEquals(5, px[0]));
        assertThrows(IllegalArgumentException.class, () -> texture.update(new byte[12]));
        assertEquals(2, texture.version(), "the refused update changed nothing; the update and the edit counted");
    }

    /**
     * A 2 by 2 square on the XY plane facing +Z, its texture coordinates running from the top-left like an image.
     */
    private static Mesh quad() {
        return new Mesh(
                new float[]{0, 0, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new float[]{0, 1, 1, 1, 1, 0, 0, 0},
                new int[]{0, 1, 2, 0, 2, 3});
    }

    private static Hit pick(Scene3D scene, Vec3 from, Vec3 direction) {
        return scene.pick(Ray.of(from, direction));
    }

    @Test
    void aPickReportsTheTextureCoordinateWhereItLanded() {
        Scene3D scene = new Scene3D();
        scene.add(new Node3D("panel", quad(), new Material(Color.WHITE)));
        Vec2 uv = pick(scene, new Vec3(1.5f, 0.5f, 5), new Vec3(0, 0, -1)).uv();
        assertEquals(0.75f, uv.x(), EPS);
        assertEquals(0.75f, uv.y(), EPS); // a quarter up from the bottom edge is three quarters down the picture
        Vec2 topLeft = pick(scene, new Vec3(0.2f, 1.8f, 5), new Vec3(0, 0, -1)).uv();
        assertEquals(0.1f, topLeft.x(), EPS);
        assertEquals(0.1f, topLeft.y(), EPS);
    }

    @Test
    void theCoordinateSurvivesMovingScalingAndTurningTheNode() {
        Scene3D scene = new Scene3D();
        Node3D panel = new Node3D("panel", quad(), new Material(Color.WHITE));
        panel.setPosition(10, 4, -3);
        panel.setScale(new Vec3(3, 0.5f, 1));
        panel.setRotation(Quat.fromAxisAngle(Vec3.UP, (float) Math.toRadians(30)));
        scene.add(panel);
        // Aim at the middle of the panel, where u and v are both one half, from an angle.
        Vec3 middle = panel.localMatrix().transformPoint(new Vec3(1, 1, 0));
        Vec3 from = middle.add(new Vec3(1, 1, 6));
        Hit hit = pick(scene, from, middle.sub(from));
        assertNotNull(hit);
        assertEquals(0.5f, hit.uv().x(), 1e-3f);
        assertEquals(0.5f, hit.uv().y(), 1e-3f);
        // And toward the bottom-right corner, where u and v are both one.
        Vec3 corner = panel.localMatrix().transformPoint(new Vec3(1.98f, 0.02f, 0));
        Hit edge = pick(scene, corner.add(new Vec3(0, 0, 6)), new Vec3(0, 0, -1));
        assertNotNull(edge);
        assertEquals(0.99f, edge.uv().x(), 1e-2f);
        assertEquals(0.99f, edge.uv().y(), 1e-2f);
    }

    @Test
    void aMeshWithoutCoordinatesAnswersZero() {
        Scene3D scene = new Scene3D();
        Mesh plain = new Mesh(new float[]{0, 0, 0, 2, 0, 0, 0, 2, 0}, null, null, new int[]{0, 1, 2});
        scene.add(new Node3D("tri", plain, new Material(Color.WHITE)));
        Hit hit = pick(scene, new Vec3(0.5f, 0.5f, 5), new Vec3(0, 0, -1));
        assertNotNull(hit);
        assertEquals(0f, hit.uv().x(), EPS);
        assertEquals(0f, hit.uv().y(), EPS);
    }
}
