package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightBudgetTest {
    private static final Camera CAMERA = new Camera().lookAt(new Vec3(0, 2, 8), new Vec3(0, 0, 0));

    private static List<Light> choose(List<Light> lights, boolean[] truncated) {
        float[] planes = SceneRenderer.frustumPlanes(CAMERA.projection(1f).mul(CAMERA.view()));
        return LightBudget.choose(lights, CAMERA.getPosition(), planes, truncated);
    }

    private static Light lamp(float x, float z) {
        return Light.point(new Vec3(x, 1, z), Color.WHITE, 1f, 3f);
    }

    @Test
    void aSceneWithinTheLimitKeepsEveryLightInOrder() {
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) lights.add(lamp(i, 0));
        lights.set(3, Light.point(new Vec3(0, 1, 0), Color.WHITE, 0f, 3f)); // even a dark one: the list is left alone
        boolean[] truncated = {false};
        assertSame(lights, choose(lights, truncated));
        assertFalse(truncated[0]);
    }

    @Test
    void theLimitIsWellAboveEight() {
        assertTrue(Scene3D.MAX_LIGHTS >= 64);
    }

    @Test
    void overTheLimitDarkAndOutOfViewLightsGoFirst() {
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) lights.add(lamp(i % 8 - 4, 0));
        Light dark = Light.point(new Vec3(0, 1, 0), Color.WHITE, 0f, 3f);
        Light behind = lamp(0, 500);   // far behind the camera
        Light visible = lamp(0.5f, 1);
        lights.add(dark);
        lights.add(behind);
        lights.add(visible);
        boolean[] truncated = {false};
        List<Light> chosen = choose(lights, truncated);
        assertEquals(Scene3D.MAX_LIGHTS, chosen.size());
        assertFalse(chosen.contains(dark));
        assertFalse(chosen.contains(behind));
        assertTrue(chosen.contains(visible));
        assertTrue(truncated[0], "the lights that mattered did not all fit");
    }

    @Test
    void whenLeftOutTheDirectionalLightsStayAndTheNearestLocalLightsWin() {
        List<Light> lights = new ArrayList<>();
        Light sun = Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f);
        lights.add(sun);
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) lights.add(lamp(-6 + i * 0.1f, -3 - i * 0.5f)); // each one farther away
        boolean[] truncated = {false};
        List<Light> chosen = choose(lights, truncated);
        assertEquals(Scene3D.MAX_LIGHTS, chosen.size());
        assertSame(sun, chosen.get(0), "directional lights come first");
        assertFalse(chosen.contains(lights.get(lights.size() - 1)), "the farthest lamp is the one left out");
        assertTrue(chosen.contains(lights.get(1)), "the nearest lamp stays");
        assertTrue(truncated[0]);
    }

    @Test
    void lightsLeftOutOnlyBecauseTheyAreDarkDoNotCountAsTruncation() {
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) lights.add(lamp(i % 8 - 4, 0));
        for (int i = 0; i < 10; i++) lights.add(Light.point(new Vec3(0, 1, 0), Color.WHITE, 0f, 3f));
        boolean[] truncated = {false};
        assertEquals(Scene3D.MAX_LIGHTS, choose(lights, truncated).size());
        assertFalse(truncated[0]);
    }
}
