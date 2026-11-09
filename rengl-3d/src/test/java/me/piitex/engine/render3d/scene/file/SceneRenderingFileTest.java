package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.upscale.BestUpscaler;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ray tracing, render scale and upscaler a scene asks for are part of its file: saved, loaded back into the same
 * settings, and left out while they are what a scene does without.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SceneRenderingFileTest {
    private static SceneProperty<Scene3D> property(String name) {
        for (SceneProperty<Scene3D> property : SceneKinds.world()) {
            if (property.getName().equals(name)) return property;
        }
        return fail("a scene has no " + name);
    }

    private static Scene3D roundTrip(Scene3D scene) {
        return Scene3DFile.parse(Scene3DFile.toJson(scene), Path.of(""), false);
    }

    @Test
    void aSceneThatAsksForNothingSavesNoneOfIt() {
        String json = Scene3DFile.toJson(Scene3DFile.blank());
        for (String name : new String[]{"rayTraced", "pathTracing", "renderScale", "upscaler", "rayReconstruction"}) {
            assertFalse(json.contains(name), json + " should not mention " + name);
        }
    }

    @Test
    void everyRayTracedSettingComesBackAsItWasSet() {
        Scene3D scene = Scene3DFile.blank();
        scene.setRayTracedShadows(true)
                .setRayTracedReflections(true).setRayTracedReflectionRoughness(0.5f).setRayTracedReflectionDistance(200f)
                .setRayTracedAmbientOcclusion(true)
                .setRayTracedGlobalIllumination(true).setRayTracedGlobalIlluminationRays(4)
                .setRayTracedGlobalIlluminationDistance(40f).setRayTracedGlobalIlluminationStrength(1.5f)
                .setPathTracing(true).setPathTracingBounces(5).setRayReconstruction(false);

        Scene3D back = roundTrip(scene);
        assertTrue(back.isRayTracedShadows());
        assertTrue(back.isRayTracedReflections());
        assertEquals(0.5f, back.getRayTracedReflectionRoughness());
        assertEquals(200f, back.getRayTracedReflectionDistance());
        assertTrue(back.isRayTracedAmbientOcclusion());
        assertTrue(back.isRayTracedGlobalIllumination());
        assertEquals(4, back.getRayTracedGlobalIlluminationRays());
        assertEquals(40f, back.getRayTracedGlobalIlluminationDistance());
        assertEquals(1.5f, back.getRayTracedGlobalIlluminationStrength());
        assertTrue(back.isPathTracing());
        assertEquals(5, back.getPathTracingBounces());
        assertFalse(back.isRayReconstruction());
    }

    @Test
    void theDetailsOfAnEffectAreOnlyShownWhileItIsOn() {
        Scene3D scene = Scene3DFile.blank();
        assertFalse(property("rayTracedReflectionRoughness").appliesTo(scene));
        assertFalse(property("rayTracedGlobalIlluminationRays").appliesTo(scene));
        assertFalse(property("pathTracingBounces").appliesTo(scene));
        assertFalse(property("rayReconstruction").appliesTo(scene));

        scene.setRayTracedReflections(true).setRayTracedGlobalIllumination(true).setPathTracing(true);
        assertTrue(property("rayTracedReflectionRoughness").appliesTo(scene));
        assertTrue(property("rayTracedGlobalIlluminationRays").appliesTo(scene));
        assertTrue(property("pathTracingBounces").appliesTo(scene));
        assertTrue(property("rayReconstruction").appliesTo(scene));
    }

    @Test
    void theRenderScaleAndTheBuiltInUpscalerComeBack() {
        Scene3D scene = Scene3DFile.blank();
        scene.setRenderScale(0.67f).setUpscaler(Upscalers.builtIn());

        Scene3D back = roundTrip(scene);
        assertEquals(0.67f, back.getRenderScale(), 1e-6f);
        assertSame(Upscalers.builtIn(), back.getUpscaler());
        assertEquals(UpscalerChoice.BUILT_IN, property("upscaler").get(back));
    }

    @Test
    void bestAvailableIsASceneOwnProviderThatComesBackAsBestAvailable() {
        Scene3D scene = Scene3DFile.blank();
        property("upscaler").set(scene, UpscalerChoice.BEST);
        assertInstanceOf(BestUpscaler.class, scene.getUpscaler());

        Scene3D back = roundTrip(scene);
        assertInstanceOf(BestUpscaler.class, back.getUpscaler());
        assertNotSame(scene.getUpscaler(), back.getUpscaler(), "each scene has its own, since it remembers the one it chose");
        assertEquals(UpscalerChoice.BEST, property("upscaler").get(back));
    }

    @Test
    void choosingNoneTakesTheUpscalerOff() {
        Scene3D scene = Scene3DFile.blank();
        property("upscaler").set(scene, UpscalerChoice.BUILT_IN);
        property("upscaler").set(scene, UpscalerChoice.NONE);
        assertNull(scene.getUpscaler());
    }

    @Test
    void settingTheChoiceASceneAlreadyHasStartsNothingOver() {
        Scene3D scene = Scene3DFile.blank();
        property("upscaler").set(scene, UpscalerChoice.BEST);
        Object chosen = scene.getUpscaler();
        property("upscaler").set(scene, UpscalerChoice.BEST);
        assertSame(chosen, scene.getUpscaler());
    }
}
