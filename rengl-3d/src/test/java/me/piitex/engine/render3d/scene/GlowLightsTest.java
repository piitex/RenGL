package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Glowing surfaces become lights. No GPU. */
class GlowLightsTest {
    private static Node3D lamp(Material material) {
        return new Node3D("lamp", Mesh.cube(2f), material);
    }

    private static List<Light> collect(Node3D root, int limit) {
        List<Light> out = new ArrayList<>();
        GlowLights.collect(root, Mat4.identity(), out, limit);
        return out;
    }

    private static float linear(float srgb) {
        return (float) Math.pow(srgb, 2.2);
    }

    @Test
    void aGlowingNodeMakesOnePointLightAtItsMiddle() {
        Node3D root = new Node3D("root");
        root.add(lamp(new Material().setEmissive(1f, 0.5f, 0.25f).setGlow(7f)).setPosition(3, 2, -1));
        List<Light> lights = collect(root, 10);
        assertEquals(1, lights.size());
        Light light = lights.get(0);
        assertEquals(Light.Type.POINT, light.getType());
        assertEquals(7f, light.getRange());
        assertEquals(3f, light.getVector().x(), 1e-5);
        assertEquals(2f, light.getVector().y(), 1e-5);
        assertEquals(-1f, light.getVector().z(), 1e-5);
        assertFalse(light.isCastShadow());
    }

    @Test
    void theLightGivesBackTheEmissiveColourExactly() {
        Node3D root = new Node3D("root");
        root.add(lamp(new Material().setEmissive(2f, 1f, 0.5f).setGlow(5f, 3f)));
        Light light = collect(root, 10).get(0);
        float k = light.getIntensity();
        assertEquals(2f * 3f, linear(light.getColor().getR()) * k, 1e-3);
        assertEquals(1f * 3f, linear(light.getColor().getG()) * k, 1e-3);
        assertEquals(0.5f * 3f, linear(light.getColor().getB()) * k, 1e-3);
    }

    @Test
    void aNodeWithoutGlowOrWithoutEmissiveColourMakesNone() {
        Node3D root = new Node3D("root");
        root.add(lamp(new Material().setEmissive(1f, 1f, 1f)));                 // glows, but no range
        root.add(lamp(new Material().setGlow(5f)));                              // range, but no emissive colour
        root.add(lamp(new Material().setEmissive(1f, 1f, 1f).setGlow(5f)).setVisible(false));
        assertTrue(collect(root, 10).isEmpty());
    }

    @Test
    void anInstancedNodeMakesOneLightPerInstance() {
        Node3D lamps = lamp(new Material().setEmissive(1f, 1f, 1f).setGlow(4f));
        Instances instances = new Instances();
        for (int i = 0; i < 5; i++) instances.add(new Vec3(i * 10, 0, 0));
        lamps.setInstances(instances);
        lamps.setPosition(0, 3, 0);
        Node3D root = new Node3D("root");
        root.add(lamps);
        List<Light> lights = collect(root, 100);
        assertEquals(5, lights.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i * 10f, lights.get(i).getVector().x(), 1e-4);
            assertEquals(3f, lights.get(i).getVector().y(), 1e-4);
        }
    }

    @Test
    void theLimitStopsTheCollecting() {
        Node3D lamps = lamp(new Material().setEmissive(1f, 1f, 1f).setGlow(4f));
        Instances instances = new Instances();
        for (int i = 0; i < 50; i++) instances.add(new Vec3(i, 0, 0));
        lamps.setInstances(instances);
        Node3D root = new Node3D("root");
        root.add(lamps);
        assertEquals(20, collect(root, 20).size());
    }

    @Test
    void aParentsMovementCarriesTheLight() {
        Node3D parent = new Node3D("parent").setPosition(10, 0, 0);
        parent.add(lamp(new Material().setEmissive(1f, 1f, 1f).setGlow(4f)).setPosition(0, 5, 0));
        Light light = collect(parent, 10).get(0);
        assertEquals(10f, light.getVector().x(), 1e-5);
        assertEquals(5f, light.getVector().y(), 1e-5);
    }

    @Test
    void theSwitchOnlyReportsGlowWhileAMaterialHasOne() {
        Material material = new Material();
        material.setGlow(5f);
        assertTrue(Material.anyGlow());
        material.setGlow(0f);
        material.setGlow(0f);
    }
}
