package me.piitex.engine.render3d.upscale;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Camera;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers behind the temporal upscalers that need no GPU: the jitter sequence and the shifted projection.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class UpscaleMathTest {
    @Test
    void phasesFollowTheRecommendedCount() {
        assertEquals(8, JitterSequence.phases(1920, 1920), "no enlargement is the minimum of 8");
        assertEquals(8, JitterSequence.phases(1000, 1000));
        assertEquals(18, JitterSequence.phases(1280, 1920), "1.5 times is 8 * 2.25");
        assertEquals(32, JitterSequence.phases(960, 1920), "twice is 8 * 4");
        assertEquals(72, JitterSequence.phases(640, 1920), "three times is 8 * 9");
    }

    @Test
    void haltonValues() {
        assertEquals(0.5f, JitterSequence.halton(1, 2), 1e-6f);
        assertEquals(0.25f, JitterSequence.halton(2, 2), 1e-6f);
        assertEquals(0.75f, JitterSequence.halton(3, 2), 1e-6f);
        assertEquals(1f / 3f, JitterSequence.halton(1, 3), 1e-6f);
        assertEquals(2f / 3f, JitterSequence.halton(2, 3), 1e-6f);
    }

    @Test
    void offsetsStayInThePixelAndSpreadOut() {
        int phases = 32;
        float[] out = new float[2];
        Set<Long> seen = new HashSet<>();
        double sumX = 0, sumY = 0;
        for (int i = 0; i < phases; i++) {
            JitterSequence.offset(i, phases, out);
            assertTrue(out[0] >= -0.5f && out[0] < 0.5f && out[1] >= -0.5f && out[1] < 0.5f, "offset " + i + " is " + out[0] + ", " + out[1]);
            seen.add(((long) Float.floatToIntBits(out[0]) << 32) | (Float.floatToIntBits(out[1]) & 0xffffffffL));
            sumX += out[0];
            sumY += out[1];
        }
        assertEquals(phases, seen.size(), "every offset in a cycle is different");
        assertEquals(0.0, sumX / phases, 0.05, "the offsets are centred on the pixel");
        assertEquals(0.0, sumY / phases, 0.05);
    }

    @Test
    void theSequenceRepeatsAfterItsLength() {
        float[] a = new float[2], b = new float[2];
        JitterSequence.offset(3, 8, a);
        JitterSequence.offset(3 + 8, 8, b);
        assertEquals(a[0], b[0]);
        assertEquals(a[1], b[1]);
        JitterSequence.offset(0, 8, a);
        assertTrue(a[0] != 0f || a[1] != 0f, "the first frame is jittered too");
    }

    @Test
    void theShiftedProjectionMovesEverythingByTheOffsetOnScreen() {
        Camera camera = new Camera().lookAt(new Vec3(0, 0, 5), Vec3.ZERO).setFovYDegrees(60).setClip(0.1f, 100f);
        float aspect = 16f / 9f;
        Mat4 view = camera.view();
        Mat4 plain = camera.projection(aspect).mul(view);
        float dx = 2f / 1920f * 0.37f, dy = -2f / 1080f * -0.21f;
        Mat4 shifted = camera.projection(aspect, dx, dy).mul(view);
        // Near points and far ones, on and off the axis: all move by the same amount on screen.
        for (Vec3 p : new Vec3[]{new Vec3(0, 0, 0), new Vec3(1.5f, -0.7f, -3f), new Vec3(-2f, 1f, 3.5f), new Vec3(0.2f, 0.2f, -90f)}) {
            Vec3 a = plain.transformPoint(p), b = shifted.transformPoint(p);
            assertEquals(dx, b.x() - a.x(), 1e-5f, "x of " + p);
            assertEquals(dy, b.y() - a.y(), 1e-5f, "y of " + p);
            assertEquals(a.z(), b.z(), 1e-6f, "depth of " + p + " is not changed");
        }
    }

    @Test
    void noOffsetIsTheOrdinaryProjection() {
        Camera camera = new Camera();
        float[] a = camera.projection(1.5f).toArray(), b = camera.projection(1.5f, 0f, 0f).toArray();
        for (int i = 0; i < 16; i++) assertEquals(a[i], b[i] == -0f ? 0f : b[i], 1e-7f, "element " + i);
    }
}
