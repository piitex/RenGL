package me.piitex.engine.render3d;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.scene.AnimationClip;
import me.piitex.engine.render3d.scene.AnimationPlayer;
import me.piitex.engine.render3d.scene.Model;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The step and cubic spline interpolation of glTF animations, which Assimp turns into plain blends.
 */
class GltfCurvesTest {
    private static Model load() throws Exception {
        return Model.load(Path.of("src/test/resources/models/curves.gltf"));
    }

    @Test
    void cubicSplineChannelsKeepTheirTangents() throws Exception {
        Model model = load();
        AnimationClip clip = model.animations().stream().filter(c -> c.name().equals("cubic")).findFirst().orElseThrow();
        AnimationClip.Channel slide = clip.channels().stream().filter(c -> c.path() == AnimationClip.Path.TRANSLATION && "slider".equals(c.target().getName())).findFirst().orElseThrow();
        assertEquals(AnimationClip.Interpolation.CUBICSPLINE, slide.interpolation());
        assertEquals(2, slide.times().length);
        assertArrayEquals(new float[]{0, 0, 0, 1, 0, 0}, slide.values(), 1e-6f);
        assertEquals(slide.values().length, slide.inTangents().length);
        assertEquals(slide.values().length, slide.outTangents().length);
    }

    @Test
    void theCurveEasesInAndOutInsteadOfBlendingStraight() throws Exception {
        Model model = load();
        Node3D slider = model.root().find("slider");
        AnimationPlayer player = model.player();
        player.play("cubic").setLoop(false);
        player.setTime(0.25f);
        // Zero slopes at both keys: 3s^2 - 2s^3 of the way, 0.15625 at a quarter, not 0.25.
        assertEquals(0.15625f, slider.getPosition().x(), 1e-4f);
        player.setTime(0.5f);
        assertEquals(0.5f, slider.getPosition().x(), 1e-4f);
        player.setTime(0.75f);
        assertEquals(0.84375f, slider.getPosition().x(), 1e-4f);
        player.setTime(1f);
        assertEquals(1f, slider.getPosition().x(), 1e-5f);
    }

    @Test
    void aCubicRotationStaysAUnitQuaternion() throws Exception {
        Model model = load();
        Node3D turner = model.root().find("turner");
        AnimationPlayer player = model.player();
        player.play("cubic").setLoop(false);
        for (float t = 0.1f; t < 1f; t += 0.1f) {
            player.setTime(t);
            Quat q = turner.getRotation();
            assertEquals(1f, q.x() * q.x() + q.y() * q.y() + q.z() * q.z() + q.w() * q.w(), 1e-4f, "at " + t);
        }
        player.setTime(0.5f);
        assertEquals(Math.sin(Math.toRadians(22.5)), turner.getRotation().z(), 2e-3, "halfway through a quarter turn, with the ease");
    }

    @Test
    void stepChannelsHoldTheirKeys() throws Exception {
        Model model = load();
        Node3D slider = model.root().find("slider");
        AnimationPlayer player = model.player();
        player.play("stepped").setLoop(false);
        player.setTime(0.9f);
        assertEquals(0f, slider.getPosition().x(), 1e-6f);
        player.setTime(1f);
        assertEquals(1f, slider.getPosition().x(), 1e-6f);
        player.setTime(1.9f);
        assertEquals(1f, slider.getPosition().x(), 1e-6f);
    }

    @Test
    void theChannelConstructorsAreChecked() {
        Node3D node = new Node3D("n");
        float[] times = {0f, 1f}, values = {0, 0, 0, 1, 0, 0};
        assertThrows(IllegalArgumentException.class,
                () -> new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION, times, values, AnimationClip.Interpolation.CUBICSPLINE));
        assertThrows(IllegalArgumentException.class,
                () -> new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION, times, values, new float[3], new float[6]));
        AnimationClip.Channel ok = new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION, times, values, new float[6], new float[6]);
        assertEquals(AnimationClip.Interpolation.CUBICSPLINE, ok.interpolation());
        assertSame(node, ok.withTarget(node).target());
        assertNotNull(ok.withTarget(new Node3D("other")).inTangents());
    }

    @Test
    void aFileWithOnlyLinearCurvesIsUntouched() throws Exception {
        Model bar = Model.load(Path.of("src/test/resources/models/bar.gltf"));
        for (AnimationClip.Channel c : bar.animations().get(0).channels()) assertEquals(AnimationClip.Interpolation.LINEAR, c.interpolation());
    }
}
