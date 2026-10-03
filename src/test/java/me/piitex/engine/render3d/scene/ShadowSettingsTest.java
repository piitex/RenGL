package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowSettingsTest {
    @Test
    void setCastShadowMeansTheSceneDefaults() {
        Scene3D scene = new Scene3D();
        scene.setShadowMapSize(1024).setShadowBias(2f).setShadowSoftness(1.5f).setShadowDistance(33f);
        Light sun = Light.directional(new Vec3(0, -1, 0), Color.WHITE, 1f);
        assertFalse(sun.isCastShadow());
        assertNull(sun.getShadows());

        sun.setCastShadow(true);
        Shadows s = sun.getShadows();
        assertTrue(sun.isCastShadow());
        assertEquals(1024, s.resolveSize(Light.Type.DIRECTIONAL, scene));
        assertEquals(2f, s.resolveBias(scene));
        assertEquals(1.5f, s.resolveSoftness(scene));
        assertEquals(33f, s.resolveDistance(scene));
        assertEquals(1, s.cascades());
        assertSame(ShadowFilter.PCF, s.resolveFilter());

        sun.setCastShadow(false);
        assertFalse(sun.isCastShadow());
    }

    @Test
    void turningShadowsOnAgainKeepsExplicitSettings() {
        Light light = Light.point(Vec3.ZERO, Color.WHITE, 1f, 5f).setShadows(Shadows.of(ShadowQuality.HIGH).bias(3f));
        light.setCastShadow(true);
        assertEquals(3f, light.getShadows().bias());
    }

    @Test
    void pointLightsDefaultToMediumTiles() {
        Scene3D scene = new Scene3D();
        assertEquals(1024, Shadows.SCENE.resolveSize(Light.Type.POINT, scene));
        assertEquals(scene.getShadowMapSize(), Shadows.SCENE.resolveSize(Light.Type.SPOT, scene));
    }

    @Test
    void presetsSetSizeAndFilterAndLeaveTheRestToTheScene() {
        Scene3D scene = new Scene3D();
        for (ShadowQuality q : ShadowQuality.values()) {
            Shadows s = Shadows.of(q);
            assertEquals(q.size(), s.resolveSize(Light.Type.SPOT, scene));
            assertSame(q.filter(), s.resolveFilter());
            assertEquals(scene.getShadowBias(), s.resolveBias(scene));
        }
        assertEquals(512, ShadowQuality.LOW.size());
        assertEquals(4096, ShadowQuality.ULTRA.size());
        assertSame(ShadowFilter.SOFT, ShadowQuality.ULTRA.filter());
    }

    @Test
    void overridesReplaceTheSceneAndAreClamped() {
        Scene3D scene = new Scene3D();
        Shadows s = Shadows.of(ShadowQuality.LOW).bias(0.5f).softness(100f).distance(80f).cascades(9).size(100000);
        assertEquals(0.5f, s.resolveBias(scene));
        assertEquals(4f, s.resolveSoftness(scene));
        assertEquals(80f, s.resolveDistance(scene));
        assertEquals(4, s.cascades());
        assertEquals(8192, s.size());
        assertEquals(1, Shadows.SCENE.cascades(0).cascades());
    }

    @Test
    void withersDoNotChangeTheOriginal() {
        Shadows a = Shadows.of(ShadowQuality.MEDIUM);
        Shadows b = a.cascades(3);
        assertEquals(1, a.cascades());
        assertEquals(3, b.cascades());
    }

    @Test
    void customPiecesNeedSimpleNames() {
        assertThrows(IllegalArgumentException.class, () -> ShadowFilter.custom("bad name", "return 1.0;"));
        assertThrows(IllegalArgumentException.class, () -> LightFalloff.custom("a-b", "return 1.0;"));
        assertEquals("cross", ShadowFilter.custom("cross", "return 1.0;").name());
        assertNotNull(LightFalloff.custom("smooth", "return 1.0;").glsl());
        assertNull(ShadowFilter.PCF.glsl());
    }

    @Test
    void lightFalloffDefaultsAndResets() {
        Light light = Light.point(Vec3.ZERO, Color.WHITE, 1f, 5f);
        assertSame(LightFalloff.WINDOWED_INVERSE_SQUARE, light.getFalloff());
        light.setFalloff(LightFalloff.LINEAR);
        assertSame(LightFalloff.LINEAR, light.getFalloff());
        light.setFalloff(null);
        assertSame(LightFalloff.WINDOWED_INVERSE_SQUARE, light.getFalloff());
    }
}
