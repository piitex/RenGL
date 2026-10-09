package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShadowPlannerTest {
    private static final Camera CAMERA = new Camera().lookAt(new Vec3(0, 2, 8), new Vec3(0, 0, 0));

    private static float[] cameraPlanes() {
        return SceneRenderer.frustumPlanes(CAMERA.projection(1f).mul(CAMERA.view()));
    }

    private static List<ShadowPlanner.Candidate> select(Scene3D scene, boolean[] truncated) {
        return ShadowPlanner.select(scene, CAMERA.getPosition(), cameraPlanes(), truncated);
    }

    private static List<ShadowPlanner.Candidate> select(Scene3D scene, boolean[] truncated, int limit) {
        return ShadowPlanner.select(scene, CAMERA.getPosition(), cameraPlanes(), truncated, limit);
    }

    private static float[] clip(Mat4 m, Vec3 p) {
        Vec3 unused = Vec3.ZERO;
        float[] a = m.toArray();
        float x = a[0] * p.x() + a[4] * p.y() + a[8] * p.z() + a[12];
        float y = a[1] * p.x() + a[5] * p.y() + a[9] * p.z() + a[13];
        float z = a[2] * p.x() + a[6] * p.y() + a[10] * p.z() + a[14];
        float w = a[3] * p.x() + a[7] * p.y() + a[11] * p.z() + a[15];
        return new float[]{x, y, z, w};
    }

    private static boolean inside(Mat4 m, Vec3 p) {
        float[] c = clip(m, p);
        return c[3] > 0 && Math.abs(c[0] / c[3]) <= 1f && Math.abs(c[1] / c[3]) <= 1f && c[2] / c[3] >= 0f && c[2] / c[3] <= 1f;
    }

    @Test
    void viewCountsPerKindOfLight() {
        Light sun = Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f);
        assertEquals(1, ShadowPlanner.viewCount(sun, Shadows.SCENE));
        assertEquals(3, ShadowPlanner.viewCount(sun, Shadows.SCENE.cascades(3)));
        assertEquals(1, ShadowPlanner.viewCount(Light.spot(Vec3.ZERO, new Vec3(0, -1, 0), Color.WHITE, 1f, 5f, 10f, 20f), Shadows.SCENE));
        assertEquals(6, ShadowPlanner.viewCount(Light.point(Vec3.ZERO, Color.WHITE, 1f, 5f), Shadows.SCENE));
    }

    @Test
    void cascadeSplitsGrowAndEndAtTheDistance() {
        float near = 0.1f, far = 60f;
        float previous = near;
        for (int k = 1; k <= 4; k++) {
            float split = ShadowPlanner.cascadeSplit(near, far, k, 4);
            assertTrue(split > previous, "split " + k);
            previous = split;
        }
        assertEquals(far, ShadowPlanner.cascadeSplit(near, far, 4, 4), 1e-3f);
        // the first cascade is much shorter than a quarter of the range: the detail is near the camera
        assertTrue(ShadowPlanner.cascadeSplit(near, far, 1, 4) < far / 4f);
    }

    @Test
    void aDirectionalLightGetsOneViewPerCascadeWithSplits() {
        Scene3D scene = new Scene3D();
        Light sun = Light.directional(new Vec3(-0.5f, -1f, -0.3f), Color.WHITE, 1f).setShadows(Shadows.of(ShadowQuality.MEDIUM).cascades(3).distance(90f));
        ShadowPlanner.Candidate c = new ShadowPlanner.Candidate(sun, 0, sun.getShadows(), 1024);
        ShadowPlanner.LightPlan plan = ShadowPlanner.plan(scene, CAMERA, 1f, c, 1024, List.of());
        assertEquals(3, plan.views().size());
        assertEquals(90f, plan.splits()[2], 1e-3f);
        assertTrue(plan.splits()[0] < plan.splits()[1] && plan.splits()[1] < plan.splits()[2]);
        for (ShadowPlanner.View v : plan.views()) assertFalse(v.perspective);
        // a nearer cascade covers less ground, so its texels are finer and its offsets smaller
        assertTrue(plan.views().get(0).normalOffset < plan.views().get(2).normalOffset);
    }

    @Test
    void oneCascadeCoversTheWholeShadowDistance() {
        Scene3D scene = new Scene3D().setShadowDistance(40f);
        Light sun = Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f).setCastShadow(true);
        ShadowPlanner.LightPlan plan = ShadowPlanner.plan(scene, CAMERA, 1f,
                new ShadowPlanner.Candidate(sun, 0, sun.getShadows(), 2048), 2048, List.of());
        assertEquals(1, plan.views().size());
        assertEquals(40f, plan.splits()[0], 1e-3f);
        // a point in front of the camera, within the distance, is inside the one view
        assertTrue(inside(plan.views().get(0).viewProj, new Vec3(0, 0, 3)));
    }

    @Test
    void casterAboveTheViewStillReachesTheShadowMap() {
        Scene3D scene = new Scene3D();
        Light sun = Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f).setCastShadow(true);
        List<float[]> casters = List.of(new float[]{0, 50, 0, 1});
        ShadowPlanner.LightPlan plan = ShadowPlanner.plan(scene, CAMERA, 1f,
                new ShadowPlanner.Candidate(sun, 0, sun.getShadows(), 1024), 1024, casters);
        assertTrue(inside(plan.views().get(0).viewProj, new Vec3(0, 50, 0)), "the caster is inside the light's view volume");
        assertTrue(inside(plan.views().get(0).viewProj, new Vec3(0, 0, 0)), "and so is the floor below it");
    }

    @Test
    void pointLightHasSixFacesEachCoveringItsAxis() {
        Light fire = Light.point(new Vec3(1, 2, 3), Color.WHITE, 2f, 20f).setShadows(ShadowQuality.MEDIUM);
        List<ShadowPlanner.View> faces = ShadowPlanner.point(fire, 1024, 1f);
        assertEquals(6, faces.size());
        Vec3[] axes = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        for (int i = 0; i < 6; i++) {
            assertTrue(faces.get(i).perspective);
            assertTrue(inside(faces.get(i).viewProj, fire.getVector().add(axes[i].mul(10f))), "face " + i + " looks along its axis");
            for (int j = 0; j < 6; j++) {
                if (j != i) assertFalse(inside(faces.get(j).viewProj, fire.getVector().add(axes[i].mul(10f))), "face " + j + " does not see axis " + i);
            }
        }
    }

    @Test
    void everyDirectionAroundAPointLightIsInTheFaceTheShaderPicks() {
        Light fire = Light.point(Vec3.ZERO, Color.WHITE, 2f, 30f);
        List<ShadowPlanner.View> faces = ShadowPlanner.point(fire, 1024, 1f);
        java.util.Random random = new java.util.Random(3);
        for (int n = 0; n < 2000; n++) {
            Vec3 d = new Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
            if (d.length() < 0.05f) continue;
            Vec3 p = d.normalized().mul(5f);
            // the shader's choice: the axis with the largest component, + before - on a tie
            float ax = Math.abs(p.x()), ay = Math.abs(p.y()), az = Math.abs(p.z());
            int face = (ax >= ay && ax >= az) ? (p.x() > 0 ? 0 : 1) : (ay >= az ? (p.y() > 0 ? 2 : 3) : (p.z() > 0 ? 4 : 5));
            assertTrue(inside(faces.get(face).viewProj, p), "direction " + d + " is in face " + face);
        }
    }

    @Test
    void spotViewIsOnePerspectiveMapAlongTheCone() {
        Light lamp = Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 15f, 30f);
        ShadowPlanner.View view = ShadowPlanner.spot(lamp, 1024, 1f);
        assertTrue(view.perspective);
        assertTrue(inside(view.viewProj, new Vec3(0, 0, 0)));
        assertFalse(inside(view.viewProj, new Vec3(0, 12, 0)), "behind the light");
        assertEquals(20f, view.far, 1e-4f);
    }

    @Test
    void biasScalesTheOffsets() {
        Light lamp = Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 15f, 30f);
        ShadowPlanner.View one = ShadowPlanner.spot(lamp, 1024, 1f), two = ShadowPlanner.spot(lamp, 1024, 2f), small = ShadowPlanner.spot(lamp, 2048, 1f);
        assertEquals(one.normalOffset * 2f, two.normalOffset, 1e-6f);
        assertEquals(one.depthBias * 2f, two.depthBias, 1e-6f);
        assertTrue(small.normalOffset < one.normalOffset, "finer texels need a smaller offset");
    }

    @Test
    void whenThereAreMoreLightsThanTheLimitTheNearestWin() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.point(new Vec3(0, 1, 30), Color.WHITE, 1f, 100f).setCastShadow(true));  // 0: far (but in range of the view)
        scene.lights().add(Light.point(new Vec3(1, 1, 5), Color.WHITE, 1f, 20f).setCastShadow(true));    // 1: near
        scene.lights().add(Light.point(new Vec3(2, 1, 6), Color.WHITE, 1f, 20f).setCastShadow(true));    // 2
        scene.lights().add(Light.point(new Vec3(3.5f, 1, 7), Color.WHITE, 1f, 20f).setCastShadow(true)); // 3: the fifth nearest
        scene.lights().add(Light.point(new Vec3(0.5f, 2, 7), Color.WHITE, 1f, 20f).setCastShadow(true)); // 4: nearest
        scene.lights().add(Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f).setCastShadow(true));  // 5: always first
        boolean[] truncated = {false};
        List<ShadowPlanner.Candidate> chosen = select(scene, truncated, 4);
        assertTrue(truncated[0]);
        assertEquals(4, chosen.size());
        assertEquals(5, chosen.get(0).index(), "the sun is first");
        assertEquals(4, chosen.get(1).index());
        assertEquals(2, chosen.get(2).index());
        assertEquals(1, chosen.get(3).index());
    }

    @Test
    void everyLightOfTheSceneCanCastShadows() {
        assertEquals(Scene3D.MAX_LIGHTS, Scene3D.MAX_SHADOW_LIGHTS);
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            scene.lights().add(Light.point(new Vec3(i - 3, 1, 5), Color.WHITE, 1f, 20f).setCastShadow(true));
        }
        boolean[] truncated = {false};
        assertEquals(8, select(scene, truncated).size());
        assertFalse(truncated[0]);
    }

    @Test
    void lightsWithoutShadowsOrOutOfViewOrDarkAreSkipped() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(Light.point(new Vec3(0, 1, 5), Color.WHITE, 1f, 10f));                            // no shadows
        scene.lights().add(Light.point(new Vec3(0, 1, 5), Color.WHITE, 0f, 10f).setCastShadow(true));         // dark
        scene.lights().add(Light.point(new Vec3(500, 0, 500), Color.WHITE, 1f, 5f).setCastShadow(true));      // far outside the view
        scene.lights().add(Light.point(new Vec3(0, 1, 5), Color.WHITE, 1f, 10f).setCastShadow(true));         // yes
        boolean[] truncated = {false};
        List<ShadowPlanner.Candidate> chosen = select(scene, truncated);
        assertFalse(truncated[0]);
        assertEquals(1, chosen.size());
        assertEquals(3, chosen.get(0).index());
    }

    @Test
    void candidatesCarryTheRequestedSize() {
        Scene3D scene = new Scene3D().setShadowMapSize(1024);
        scene.lights().clear();
        scene.lights().add(Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f).setCastShadow(true));
        scene.lights().add(Light.point(new Vec3(0, 1, 5), Color.WHITE, 1f, 10f).setCastShadow(true));
        scene.lights().add(Light.spot(new Vec3(0, 5, 0), new Vec3(0, -1, 0), Color.WHITE, 1f, 10f, 10f, 20f).setShadows(ShadowQuality.ULTRA));
        List<ShadowPlanner.Candidate> chosen = select(scene, new boolean[1]);
        assertEquals(3, chosen.size());
        assertEquals(1024, chosen.get(0).size());   // the sun: the scene's size
        // the point light and the spot light, by distance from the camera at (0, 2, 8)
        for (ShadowPlanner.Candidate c : chosen) {
            if (c.light().getType() == Light.Type.POINT) assertEquals(1024, c.size());
            if (c.light().getType() == Light.Type.SPOT) assertEquals(4096, c.size());
        }
    }
}
