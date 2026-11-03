package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A spot light's edge curve and cone shape in a scene file: left out while they are what a missing one gives, so old files
 * and plain lights are saved as they always were, and read back when they are not.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class LightConeFileTest {
    @TempDir
    Path temp;

    private static SceneProperty<Light> spot(String name) {
        return find(SceneKinds.light(Light.Type.SPOT), name);
    }

    private static <T> SceneProperty<T> find(List<SceneProperty<T>> list, String name) {
        return list.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Scene3D sceneWith(Light light) {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.lights().add(light);
        return scene;
    }

    @Test
    void aPlainSpotLightIsSavedAsBefore() {
        String json = Scene3DFile.toJson(sceneWith(SceneKinds.newLight(Light.Type.SPOT)));
        assertTrue(json.contains("outerAngle"));
        for (String name : List.of("edgeCurve", "coneAspect", "coneSquareness", "coneRoll")) assertFalse(json.contains(name), name);
    }

    @Test
    void theShapeIsSavedAndReadBack() {
        Light light = SceneKinds.newLight(Light.Type.SPOT);
        spot("edgeCurve").set(light, 2.5f);
        spot("coneAspect").set(light, 0.25f);
        spot("coneSquareness").set(light, 0.75f);
        spot("coneRoll").set(light, -30f);
        String json = Scene3DFile.toJson(sceneWith(light));
        for (String name : List.of("edgeCurve", "coneAspect", "coneSquareness", "coneRoll")) assertTrue(json.contains(name), name);

        Scene3D again = Scene3DFile.parse(json, temp, false);
        Light back = again.lights().get(0);
        assertEquals(2.5f, back.getEdgeCurve());
        assertEquals(0.25f, back.getConeAspect());
        assertEquals(0.75f, back.getConeSquareness());
        assertEquals(-30f, back.getConeRoll());
        assertEquals(json, Scene3DFile.toJson(again));
        assertEquals(light.coneFactor(new Vec3(0.2f, -1f, 0.3f).normalized()), back.coneFactor(new Vec3(0.2f, -1f, 0.3f).normalized()));
    }

    @Test
    void settingOnePartKeepsTheOthers() {
        Light light = SceneKinds.newLight(Light.Type.SPOT);
        spot("coneAspect").set(light, 0.5f);
        spot("coneRoll").set(light, 20f);
        spot("coneSquareness").set(light, 1f);
        assertEquals(0.5f, light.getConeAspect());
        assertEquals(20f, light.getConeRoll());
        assertEquals(1f, light.getConeSquareness());
    }

    @Test
    void rollOnlyShowsForAConeThatHasAShape() {
        Light light = SceneKinds.newLight(Light.Type.SPOT);
        assertFalse(spot("coneRoll").appliesTo(light), "turning a round cone does nothing");
        assertTrue(spot("coneAspect").appliesTo(light));
        spot("coneSquareness").set(light, 0.5f);
        assertTrue(spot("coneRoll").appliesTo(light));
    }

    @Test
    void onlyASpotLightHasACone() {
        for (Light.Type type : List.of(Light.Type.DIRECTIONAL, Light.Type.POINT)) {
            for (String name : List.of("edgeCurve", "coneAspect", "coneSquareness", "coneRoll")) {
                assertTrue(SceneKinds.light(type).stream().noneMatch(p -> p.getName().equals(name)), type + " " + name);
            }
        }
        assertEquals("Cone", spot("edgeCurve").getGroup());
        assertEquals(Color.WHITE, SceneKinds.newLight(Light.Type.SPOT).getColor());
    }
}
