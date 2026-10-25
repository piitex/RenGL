package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Clustered lighting, glow lights and occlusion culling in scene files. */
class SceneLightingFileTest {
    @TempDir
    Path temp;

    private static <T> SceneProperty<T> find(List<SceneProperty<T>> list, String name) {
        return list.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theNewSettingsRoundTrip() {
        Scene3D scene = new Scene3D();
        find(SceneKinds.world(), "lightMode").set(scene, Scene3D.LightMode.CLUSTERED);
        find(SceneKinds.world(), "occlusionCulling").set(scene, true);
        Node3D wall = SceneKinds.newNode(Shape.CUBE, "Wall");
        find(SceneKinds.node(), "occluder").set(wall, true);
        find(SceneKinds.material(), "emissiveStrength").set(wall.getMaterial(), 2f);
        find(SceneKinds.material(), "glowRange").set(wall.getMaterial(), 9f);
        find(SceneKinds.material(), "glowStrength").set(wall.getMaterial(), 0.5f);
        scene.add(wall);

        String json = Scene3DFile.toJson(scene);
        assertEquals(json, Scene3DFile.toJson(Scene3DFile.parse(json, temp, false)));
        Scene3D back = Scene3DFile.parse(json, temp, false);
        assertEquals(Scene3D.LightMode.CLUSTERED, back.getLightMode());
        assertTrue(back.isOcclusionCulling());
        Node3D again = Scene3DFile.find(back, "Wall");
        assertTrue(again.isOccluder());
        Material material = again.getMaterial();
        assertEquals(9f, material.getGlowRange());
        assertEquals(0.5f, material.getGlowStrength());
    }

    @Test
    void theDefaultsAreLeftOutOfTheFile() {
        Scene3D scene = new Scene3D();
        Node3D box = SceneKinds.newNode(Shape.CUBE, "Box");
        scene.add(box);
        String json = Scene3DFile.toJson(scene);
        assertFalse(json.contains("lightMode"), json);
        assertFalse(json.contains("occlusionCulling"), json);
        assertFalse(json.contains("occluder"), json);
        assertFalse(json.contains("glowRange"), json);
    }
}
