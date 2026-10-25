package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The CPU depth picture that decides what hides behind what. No GPU. */
class OcclusionBufferTest {
    private final Camera camera = new Camera().lookAt(new Vec3(0, 2, 10), new Vec3(0, 2, 0)).setFovYDegrees(60).setClip(0.1f, 100f);
    private final float[] viewProj = camera.projection(16f / 9f).mul(camera.view()).toArray();
    private static final float[] IDENTITY = Mat4.identity().toArray();

    private OcclusionBuffer withWall(double halfWidth, double top) {
        OcclusionBuffer buffer = new OcclusionBuffer();
        buffer.begin(viewProj);
        quad(buffer, -halfWidth, 0, top, halfWidth, 0, 0);
        return buffer;
    }

    /** A wall in the plane z = 0 from x0 to x1 and from y = bottom to top. */
    private static void quad(OcclusionBuffer b, double x0, double bottom, double top, double x1, double z, double unused) {
        b.addTriangle(x0, bottom, z, x1, bottom, z, x1, top, z);
        b.addTriangle(x0, bottom, z, x1, top, z, x0, top, z);
    }

    private static double[] box(double x, double y, double z, double half) {
        return OcclusionBuffer.corners(new Bounds(new Vec3((float) -half, (float) -half, (float) -half), new Vec3((float) half, (float) half, (float) half)),
                new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, (float) x, (float) y, (float) z, 1});
    }

    @Test
    void aBoxBehindAWallIsHidden() {
        assertTrue(withWall(6, 6).hides(box(0, 2, -5, 0.8)));
    }

    @Test
    void aBoxInFrontOfAWallIsNot() {
        assertFalse(withWall(6, 6).hides(box(0, 2, 5, 0.8)));
    }

    @Test
    void aBoxThatSticksOutPastTheWallIsNot() {
        OcclusionBuffer wall = withWall(6, 3);
        assertFalse(wall.hides(box(0, 3.5, -5, 1.5)), "its top shows above the wall");
        assertFalse(wall.hides(box(7.5, 2, -5, 1)), "it is beside the wall");
    }

    @Test
    void aGapBetweenTwoWallsShowsWhatIsBehindIt() {
        OcclusionBuffer buffer = new OcclusionBuffer();
        buffer.begin(viewProj);
        quad(buffer, -8, 0, 6, -1, 0, 0);
        quad(buffer, 1, 0, 6, 8, 0, 0);
        assertFalse(buffer.hides(box(0, 2, -6, 0.5)));
        assertTrue(buffer.hides(box(-4, 2, -6, 0.5)));
    }

    @Test
    void aFloorUnderTheCameraStillHidesWhatIsUnderIt() {
        OcclusionBuffer buffer = new OcclusionBuffer();
        buffer.begin(viewProj);
        // 200 by 200, so the camera is above its middle and its far corners are behind the camera.
        buffer.addTriangle(-100, 0, -100, 100, 0, -100, 100, 0, 100);
        buffer.addTriangle(-100, 0, -100, 100, 0, 100, -100, 0, 100);
        assertTrue(buffer.hides(box(0, -3, -8, 0.5)));
        assertFalse(buffer.hides(box(0, 1, -8, 0.5)));
    }

    @Test
    void aBoxWithACornerBehindTheCameraIsNeverHidden() {
        assertFalse(withWall(60, 60).hides(box(0, 2, 10.2, 3)));
    }

    @Test
    void anEmptyBufferHidesNothingAndKnowsIt() {
        OcclusionBuffer buffer = new OcclusionBuffer();
        buffer.begin(viewProj);
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hides(box(0, 2, -5, 1)));
    }

    @Test
    void aWallTooSmallToCoverAWholePixelHidesNothing() {
        OcclusionBuffer buffer = new OcclusionBuffer();
        buffer.begin(viewProj);
        quad(buffer, -0.01, 2, 2.02, 0.01, 0, 0);
        assertFalse(buffer.hides(box(0, 2, -5, 0.001)));
    }

    @Test
    void whatItHidesIsReallyBehindTheWall() {
        // 4000 random boxes behind and beside a wall: whenever the picture says hidden, every sampled ray from the eye to
        // the box must cross the wall. The reverse is not required, only that a good share is found.
        OcclusionBuffer buffer = withWall(5, 6);
        Random random = new Random(21);
        Vec3 eye = camera.getPosition();
        int hidden = 0, shouldBe = 0;
        for (int n = 0; n < 4000; n++) {
            double half = 0.1 + random.nextDouble() * 1.2;
            double x = (random.nextDouble() - 0.5) * 18, y = random.nextDouble() * 8 - 1, z = -2 - random.nextDouble() * 25;
            boolean says = buffer.hides(box(x, y, z, half));
            boolean everyRayCrosses = true;
            for (int k = 0; k < 40 && everyRayCrosses; k++) {
                double px = x + (random.nextDouble() * 2 - 1) * half, py = y + (random.nextDouble() * 2 - 1) * half, pz = z + (random.nextDouble() * 2 - 1) * half;
                double t = (0 - eye.z()) / (pz - eye.z());
                double hx = eye.x() + (px - eye.x()) * t, hy = eye.y() + (py - eye.y()) * t;
                everyRayCrosses = Math.abs(hx) <= 5 && hy >= 0 && hy <= 6;
            }
            if (says) {
                hidden++;
                assertTrue(everyRayCrosses, "box at " + x + ", " + y + ", " + z + " half " + half + " is said to be hidden but a ray gets past the wall");
            }
            if (everyRayCrosses) shouldBe++;
        }
        assertTrue(hidden > 200, "a fair number should be hidden: " + hidden);
        assertTrue(hidden >= shouldBe * 0.6, "at least 60% of the really hidden boxes should be found: " + hidden + " of " + shouldBe);
    }
}
