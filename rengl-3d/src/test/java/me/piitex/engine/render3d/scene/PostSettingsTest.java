package me.piitex.engine.render3d.scene;

import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scene's finishing settings that need no GPU: what is off by default, what turns the offscreen path on, and the limits.
 */
class PostSettingsTest {
    @Test
    void aNewSceneNeedsNoEffectsPass() {
        assertFalse(new Scene3D().usesPostProcessing());
    }

    @Test
    void eachEffectTurnsThePassOn() {
        assertTrue(new Scene3D().setBloom(0.5f).usesPostProcessing());
        assertTrue(new Scene3D().setAmbientOcclusion(1f).usesPostProcessing());
        assertTrue(new Scene3D().setChromaticAberration(0.01f).usesPostProcessing());
        assertTrue(new Scene3D().setVignette(0.3f).usesPostProcessing());
        assertTrue(new Scene3D().setSaturation(0.5f).usesPostProcessing());
        assertTrue(new Scene3D().setContrast(1.2f).usesPostProcessing());
        assertTrue(new Scene3D().setShadowTint(new Color(0f, 0.5f, 0.5f, 1f)).usesPostProcessing());
        assertTrue(new Scene3D().setHighlightTint(new Color(1f, 0.4f, 0.8f, 1f)).usesPostProcessing());
    }

    @Test
    void settingAnEffectBackToNothingTurnsThePassOff() {
        Scene3D scene = new Scene3D().setAmbientOcclusion(1f).setSaturation(0.5f).setShadowTint(new Color(0f, 1f, 1f, 1f));
        scene.setAmbientOcclusion(0f).setSaturation(1f).setShadowTint(Color.WHITE);
        assertFalse(scene.usesPostProcessing());
        assertFalse(new Scene3D().setShadowTint(null).setHighlightTint(null).usesPostProcessing(), "no tint at all is white");
    }

    @Test
    void valuesAreKeptInTheirRange() {
        Scene3D scene = new Scene3D();
        assertEquals(0f, scene.setAmbientOcclusion(-1f).getAmbientOcclusion());
        assertEquals(0f, scene.setVignette(-1f).getVignette());
        assertEquals(0.05f, scene.setChromaticAberration(9f).getChromaticAberration());
        assertEquals(0f, scene.setSaturation(-2f).getSaturation());
        assertEquals(0.01f, scene.setAmbientOcclusionRadius(0f).getAmbientOcclusionRadius());
        assertEquals(20f, scene.setAmbientOcclusionRadius(500f).getAmbientOcclusionRadius());
    }
}
