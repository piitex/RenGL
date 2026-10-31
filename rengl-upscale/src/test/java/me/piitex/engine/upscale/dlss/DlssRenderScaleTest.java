package me.piitex.engine.upscale.dlss;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.upscale.UpscaleComparison;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DLSS at every render scale a settings screen can offer: the picture must not go black at any of them. Sweeps the scale from
 * the lowest the scene allows to full size, letting each settle for a few frames.
 * <p>
 * Needs NVIDIA's Streamline libraries (see {@link Sl}) and an RTX GPU; skips elsewhere.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class DlssRenderScaleTest {
    private static final int SIZE = 600, SETTLE = 6, LEAD = 10;

    @Test
    void noRenderScaleGivesABlackPicture() {
        assumeTrue(Sl.load() != null, "NVIDIA's Streamline libraries were not found: " + Sl.failure());
        DlssProvider provider = new DlssProvider();
        List<Float> scales = new ArrayList<>();
        for (int percent = 25; percent <= 100; percent++) scales.add(percent / 100f);
        List<String> black = new ArrayList<>();
        String[] unavailable = new String[1];
        StringBuilder report = new StringBuilder();
        UpscaleComparison.Outcome outcome = UpscaleComparison.runWindow(SIZE, false, (frame, scene, device, target, delta, window) -> {
            if (frame == 1) {
                unavailable[0] = DlssProvider.unavailableReason(device);
                if (unavailable[0] != null) return true;
                scene.setUpscaler(provider);
            }
            // A widescreen window, where the rounded height can miss what DLSS takes while the width does not.
            if (frame == 1) window.setSize(1280, 720);
            scene.render(device, target, delta);
            if (frame <= LEAD) return false;
            int index = (frame - LEAD - 1) / SETTLE;
            if (index >= scales.size()) return true;
            if ((frame - LEAD - 1) % SETTLE == 0) scene.setRenderScale(scales.get(index));
            if ((frame - LEAD - 1) % SETTLE == SETTLE - 1) {
                double brightness = UpscaleComparison.brightness(UpscaleComparison.pixels(target));
                report.append(String.format("%d%%=%.3f ", Math.round(scales.get(index) * 100), brightness));
                if (brightness < 0.05) black.add(Math.round(scales.get(index) * 100) + "%");
            }
            return false;
        });

        assumeTrue(outcome.rendered() && outcome.api() == RenderAPI.VULKAN, "no Vulkan window: " + outcome.api());
        assumeTrue(unavailable[0] == null, "DLSS is not usable here: " + unavailable[0]);
        if (outcome.failure() != null) throw new AssertionError("the run failed: " + outcome.failure(), outcome.failure());
        System.out.println("DLSS brightness by render scale: " + report);
        assertTrue(black.isEmpty(), "the picture was black at " + black);
    }
}
