package me.piitex.engine.upscale.dlss;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.upscale.UpscaleComparison;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DLSS frame generation on a real Vulkan device: a window that draws a panning scene through the DLSS upscaler, with frame
 * generation on, must show more frames than it drew (Streamline says how many it presented since it was last asked).
 * <p>
 * Needs the Streamline libraries for DLSS and for frame generation ({@link Sl}), an NVIDIA RTX 40 or newer GPU with hardware
 * GPU scheduling on, and {@code -Drengl.dlss.framegen=true}; the test skips elsewhere. It opens a window for a few seconds.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class DlssFrameGenGpuTest {
    private static final int SIZE = 512, FRAMES = 260, OFF_FROM = 90, OFF_TO = 130, RESIZE_AT = 170;

    @Test
    void framesAreGeneratedBetweenTheDrawnOnes() {
        assumeTrue(Sl.load() != null, "NVIDIA's Streamline libraries were not found: " + Sl.failure());
        assumeTrue(Boolean.getBoolean("rengl.dlss.framegen"), "frame generation was not asked for (-Drengl.dlss.framegen=true)");
        DlssProvider provider = new DlssProvider();
        DlssFrameGeneration.State[] states = new DlssFrameGeneration.State[FRAMES + 1];
        String[] unavailable = new String[1];
        UpscaleComparison.Outcome outcome = UpscaleComparison.runWindow(SIZE, true, (frame, scene, device, target, delta, window) -> {
            if (frame == 1) {
                unavailable[0] = DlssProvider.unavailableReason(device);
                if (unavailable[0] != null) return true;
                scene.setRenderScale(0.67f);
                scene.setUpscaler(provider);
            }
            if (frame == OFF_FROM) DlssFrameGeneration.setEnabled(false);
            if (frame == OFF_TO) DlssFrameGeneration.setEnabled(true);
            if (frame == RESIZE_AT) window.setSize(SIZE + 64, SIZE + 64);
            float x = 0.03f * frame;
            scene.camera().lookAt(new Vec3(x, 0, 3.5f), new Vec3(x, 0, 0));
            scene.render(device, target, delta);
            if (frame > 1) states[frame] = DlssFrameGeneration.state();
            return frame == FRAMES;
        });

        assumeTrue(outcome.rendered() && outcome.api() == RenderAPI.VULKAN, "no Vulkan window: " + outcome.api());
        assumeTrue(unavailable[0] == null, "DLSS is not usable here: " + unavailable[0]);
        if (outcome.failure() != null) throw new AssertionError("the run failed: " + outcome.failure(), outcome.failure());
        assumeTrue(DlssFrameGeneration.isAvailable() || states[FRAMES] != null && states[FRAMES].active(),
                "frame generation is not usable here: " + DlssFrameGeneration.unavailableReason());

        int onActive = count(states, 10, OFF_FROM - 1, true), offActive = count(states, OFF_FROM + 10, OFF_TO - 1, true),
                backActive = count(states, OFF_TO + 10, RESIZE_AT - 1, true), resizedActive = count(states, RESIZE_AT + 30, FRAMES, true);
        long shownOn = shown(states, 10, OFF_FROM - 1), shownOff = shown(states, OFF_FROM + 10, OFF_TO - 1);
        DlssFrameGeneration.State last = states[FRAMES];
        System.out.printf("Frame generation: on %d of %d frames (%d frames shown), off %d of %d (%d shown), back on %d, after the resize %d; status %s, up to %d generated, about %d MB%n",
                onActive, OFF_FROM - 10, shownOn, offActive, OFF_TO - OFF_FROM - 10, shownOff, backActive, resizedActive, last.statusText(),
                last.maxFramesToGenerate(), last.estimatedVramBytes() >> 20);
        int onFrames = OFF_FROM - 10;
        assertTrue(onActive > onFrames * 0.9, "frame generation ran while it was on: " + onActive + " of " + onFrames + ", last status " + last.statusText());
        assertTrue(shownOn > onFrames * 1.8, "about twice as many frames were shown as were drawn: " + shownOn + " of " + onFrames);
        assertTrue(offActive == 0, "frame generation was off while it was turned off: " + offActive);
        assertTrue(shownOff <= (OFF_TO - OFF_FROM - 10) * 1.1, "one frame was shown for each drawn while it was off: " + shownOff);
        assertTrue(backActive > (RESIZE_AT - OFF_TO - 10) * 0.9, "frame generation came back when turned on again: " + backActive);
        assertTrue(resizedActive > (FRAMES - RESIZE_AT - 30) * 0.9, "frame generation ran again after the swapchain was rebuilt: " + resizedActive);
    }

    private static int count(DlssFrameGeneration.State[] states, int from, int to, boolean active) {
        int n = 0;
        for (int i = from; i <= to; i++) if (states[i] != null && states[i].active() == active) n++;
        return n;
    }

    private static long shown(DlssFrameGeneration.State[] states, int from, int to) {
        long n = 0;
        for (int i = from; i <= to; i++) if (states[i] != null) n += states[i].framesPresented();
        return n;
    }

    @Test
    void withoutTheLibrariesItSaysSo() {
        assumeTrue(Sl.load() == null, "the libraries are present");
        assertTrue(DlssFrameGeneration.unavailableReason() != null && !DlssFrameGeneration.unavailableReason().isBlank());
    }
}
