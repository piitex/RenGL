package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of a spot light's cone: its edge curve, and a cone that is oval, rectangular or turned. Checked on the CPU
 * against numbers worked out by hand; the shaders do the same sums, and {@code ConeShapeGpuTest} compares them.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class LightConeShapeTest {
    private static final float TAN_OUTER = (float) Math.tan(Math.toRadians(40));

    /** A spot light pointing straight down, which puts the width of a shaped cone along +Z and its height along +X. */
    private static Light lamp() {
        return Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 10f, 40f);
    }

    /** The unit direction from the light to the point {@code x} along the height and {@code z} along the width, one unit down. */
    private static Vec3 toward(float heightwise, float widthwise) {
        return new Vec3(heightwise, -1f, widthwise).normalized();
    }

    @Test
    void aPlainConeIsWhatItAlwaysWas() {
        Light light = lamp();
        assertFalse(light.isConeShaped());
        assertEquals(1f, light.getEdgeCurve());
        Vec3 right = light.coneRight();
        assertEquals(0f, right.x(), 1e-6f);
        assertEquals(0f, right.y(), 1e-6f);
        assertEquals(1f, right.z(), 1e-6f);
        for (int degrees = 0; degrees <= 50; degrees += 5) {
            double a = Math.toRadians(degrees);
            Vec3 v = new Vec3((float) Math.sin(a), -(float) Math.cos(a), 0f);
            float outer = (float) Math.cos(Math.toRadians(40)), inner = (float) Math.cos(Math.toRadians(10));
            float old = Math.max(0f, Math.min(1f, ((float) Math.cos(a) - outer) / (inner - outer)));
            assertEquals(old, light.coneFactor(v), 1e-5f, degrees + " degrees");
        }
        assertEquals(0f, light.coneFactor(new Vec3(0, 1, 0)), "behind the light");
    }

    @Test
    void theShapeIsKeptInRange() {
        Light light = lamp().setEdgeCurve(100f).setConeShape(100f, 5f, 270f);
        assertEquals(8f, light.getEdgeCurve());
        assertEquals(20f, light.getConeAspect());
        assertEquals(1f, light.getConeSquareness());
        assertEquals(-90f, light.getConeRoll());
        light.setEdgeCurve(-1f).setConeShape(0f, -1f, 180f);
        assertEquals(0.1f, light.getEdgeCurve());
        assertEquals(0.05f, light.getConeAspect());
        assertEquals(0f, light.getConeSquareness());
        assertEquals(-180f, light.getConeRoll());
        assertEquals(0f, lamp().setConeShape(1f, 0f, 360f).getConeRoll());
        assertTrue(lamp().setConeShape(0.5f, 0f, 0f).isConeShaped());
        assertTrue(lamp().setConeShape(1f, 0.2f, 0f).isConeShaped());
        assertFalse(lamp().setConeShape(1f, 0f, 45f).isConeShaped(), "turning a round cone changes nothing");
    }

    @Test
    void theEdgeCurveBendsTheRampAndNothingElse() {
        Light straight = lamp(), tight = lamp().setEdgeCurve(2f), hard = lamp().setEdgeCurve(0.25f);
        Vec3 middle = new Vec3((float) Math.sin(Math.toRadians(25)), -(float) Math.cos(Math.toRadians(25)), 0f);
        float s = straight.coneFactor(middle);
        assertTrue(s > 0.2f && s < 0.8f, "a point on the ramp: " + s);
        assertEquals(s * s, tight.coneFactor(middle), 1e-5f);
        assertEquals((float) Math.pow(s, 0.25), hard.coneFactor(middle), 1e-5f);
        for (Light light : List.of(straight, tight, hard)) {
            assertEquals(1f, light.coneFactor(new Vec3(0, -1, 0)), 1e-6f, "full strength in the middle");
            assertEquals(1f, light.coneFactor(toward((float) Math.tan(Math.toRadians(9)), 0f)), 1e-6f, "and anywhere inside the inner angle");
            assertEquals(0f, light.coneFactor(toward(TAN_OUTER * 1.01f, 0f)), "and none outside the outer");
        }
    }

    @Test
    void anOvalConeIsAsTallAsItsAspectSays() {
        Light wide = lamp().setConeShape(0.5f, 0f, 0f);
        // The width is the angles', the height half of that.
        assertTrue(wide.coneFactor(toward(0f, TAN_OUTER * 0.95f)) > 0f, "just inside along the width");
        assertEquals(0f, wide.coneFactor(toward(0f, TAN_OUTER * 1.05f)), "just outside along the width");
        assertTrue(wide.coneFactor(toward(TAN_OUTER * 0.45f, 0f)) > 0f, "just inside along the height");
        assertEquals(0f, wide.coneFactor(toward(TAN_OUTER * 0.55f, 0f)), "just outside along the height");
        // An oval is an ellipse: a point on the diagonal at 0.8 of both is outside, at 0.6 of both is in.
        assertEquals(0f, lamp().setConeShape(1f, 0f, 0f).coneFactor(toward(TAN_OUTER * 0.8f, TAN_OUTER * 0.8f)));
        Light tall = lamp().setConeShape(3f, 0f, 0f);
        assertTrue(tall.coneFactor(toward(TAN_OUTER * 2.9f, 0f)) > 0f, "a tall cone reaches three times as far along its height");
        assertEquals(0f, tall.coneFactor(toward(TAN_OUTER * 3.1f, 0f)));
    }

    @Test
    void aRectangularConeFillsItsCorners() {
        Light round = lamp(), box = lamp().setConeShape(1f, 1f, 0f);
        Vec3 corner = toward(TAN_OUTER * 0.9f, TAN_OUTER * 0.9f);
        assertEquals(0f, round.coneFactor(corner), "a round cone leaves the corner dark");
        assertTrue(box.coneFactor(corner) > 0f, "a rectangle does not");
        assertEquals(0f, box.coneFactor(toward(TAN_OUTER * 1.05f, TAN_OUTER * 0.5f)), "but it still ends at its sides");
        // Half way between, the corner at 0.9 is 1.27 away by the round measure and 0.9 by the square one.
        float rounded = lamp().setConeShape(1f, 0.5f, 0f).coneFactor(toward(TAN_OUTER * 0.9f, TAN_OUTER * 0.9f));
        assertTrue(rounded >= 0f && rounded <= box.coneFactor(corner), "a rounded rectangle lies between: " + rounded);
    }

    @Test
    void rollingATallConeTurnsIt() {
        Light flat = lamp().setConeShape(0.4f, 0f, 0f), turned = lamp().setConeShape(0.4f, 0f, 90f);
        Vec3 alongWidth = toward(0f, TAN_OUTER * 0.8f), alongHeight = toward(TAN_OUTER * 0.8f, 0f);
        assertTrue(flat.coneFactor(alongWidth) > 0f);
        assertEquals(0f, flat.coneFactor(alongHeight));
        assertEquals(0f, turned.coneFactor(alongWidth));
        assertTrue(turned.coneFactor(alongHeight) > 0f, "a quarter turn swaps them");
        Vec3 right = turned.coneRight();
        assertEquals(1f, right.x(), 1e-5f, "the width now runs along what was the height");
        assertEquals(0f, right.dot(turned.getDirection()), 1e-5f, "and stays across the axis");
        // A full turn comes back, and a half turn is the same cone upside down.
        assertEquals(flat.coneFactor(alongWidth), lamp().setConeShape(0.4f, 0f, 180f).coneFactor(alongWidth), 1e-5f);
    }

    @Test
    void theWidthLiesAcrossTheHorizonForALightThatPointsSideways() {
        Light light = Light.spot(new Vec3(0, 3, 0), new Vec3(0, 0, -1), Color.WHITE, 1f, 10f, 10f, 40f).setConeShape(0.5f, 0f, 0f);
        Vec3 right = light.coneRight();
        assertEquals(1f, right.x(), 1e-6f);
        assertEquals(0f, right.y(), 1e-6f);
        // Turned a quarter, anticlockwise as seen from behind the light, the width stands up.
        assertEquals(1f, light.setConeShape(0.5f, 0f, 90f).coneRight().y(), 1e-6f);
    }

    @Test
    void theGridCarriesTheShapeToTheShader() {
        Light plain = lamp();
        Light shaped = lamp().setEdgeCurve(2f).setConeShape(0.5f, 0.25f, 0f);
        Light point = Light.point(new Vec3(1, 2, 3), Color.WHITE, 1f, 5f);
        ByteBuffer out = ByteBuffer.allocate(4 * LightClusters.LIGHT_TEXELS * 4).order(ByteOrder.LITTLE_ENDIAN);
        LightClusters.writeLights(out, List.of(plain, shaped, point), 4, light -> 0);
        out.order(ByteOrder.LITTLE_ENDIAN);
        int row = LightClusters.LIGHT_TEXELS * 4, shapeAt = 11 * 4;
        assertEquals(0f, out.getFloat(shapeAt), "a plain cone has nothing more to read");
        assertEquals(1f, out.getFloat(row + shapeAt));
        assertEquals(2f, out.getFloat(row + shapeAt + 4), "the edge curve");
        assertEquals(0.5f, out.getFloat(row + shapeAt + 8), "the aspect");
        assertEquals(0.25f, out.getFloat(row + shapeAt + 12), "the squareness");
        assertEquals(0f, out.getFloat(row + shapeAt + 16), 1e-6f, "the way across the width, x");
        assertEquals(0f, out.getFloat(row + shapeAt + 20), 1e-6f, "y");
        assertEquals(1f, out.getFloat(row + shapeAt + 24), 1e-6f, "and z");
        assertEquals(0f, out.getFloat(2 * row + shapeAt), "a point light has no cone");
        assertEquals(0f, out.getFloat(3 * row + shapeAt), "and a row with no light is empty");
        // An edge curve alone is enough to need reading, a roll of a round cone is not.
        out.clear();
        LightClusters.writeLights(out, List.of(lamp().setEdgeCurve(0.5f), lamp().setConeShape(1f, 0f, 30f)), 2, light -> 0);
        out.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1f, out.getFloat(shapeAt));
        assertEquals(0.5f, out.getFloat(shapeAt + 4));
        assertEquals(1f, out.getFloat(shapeAt + 8), "the aspect of a round cone");
        assertEquals(0f, out.getFloat(row + shapeAt));
    }

    private static boolean inside(ShadowPlanner.View view, Vec3 point) {
        Vec3 ndc = view.viewProj.transformPoint(point);
        return Math.abs(ndc.x()) <= 1f && Math.abs(ndc.y()) <= 1f;
    }

    @Test
    void theShadowViewOfATallConeReachesItsHeight() {
        Light round = Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 5f, 20f);
        Light tall = Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 5f, 20f).setConeShape(3f, 0f, 0f);
        Light wide = Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 5f, 20f).setConeShape(0.3f, 0f, 0f);
        // The floor 5 beside the point under the light, along the height (x) and along the width (z). The cone reaches
        // 6 * tan(20) * 3 = 6.5 along the height of the tall one, but only 2.2 along the width.
        Vec3 alongHeight = new Vec3(5, 0, 0), alongWidth = new Vec3(0, 0, 2f);
        assertFalse(inside(ShadowPlanner.spot(round, 1024, 1f), alongHeight));
        assertTrue(inside(ShadowPlanner.spot(tall, 1024, 1f), alongHeight));
        assertTrue(inside(ShadowPlanner.spot(tall, 1024, 1f), alongWidth));
        assertTrue(inside(ShadowPlanner.spot(wide, 1024, 1f), alongWidth), "a wide one is held by the width, as before");
        assertEquals(inside(ShadowPlanner.spot(round, 1024, 1f), alongWidth), inside(ShadowPlanner.spot(wide, 1024, 1f), alongWidth));
    }
}
