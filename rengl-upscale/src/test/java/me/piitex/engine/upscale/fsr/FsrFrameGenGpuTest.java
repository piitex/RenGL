package me.piitex.engine.upscale.fsr;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.upscale.UpscaleComparison;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FSR frame generation on a real Vulkan device: a window that draws a panning scene through the FSR upscaler, with frame
 * generation on, must give the display more frames than it drew (the library's swapchain says how many it presented).
 * <p>
 * Needs the FidelityFX libraries with the frame generation one ({@link Ffx}) and {@code -Drengl.fsr.framegen=true}; the test
 * skips elsewhere. It opens a window for a few seconds.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class FsrFrameGenGpuTest {
    private static final int SIZE = 512, FRAMES = 260, OFF_FROM = 90, OFF_TO = 130, RESIZE_AT = 170;

    @Test
    void framesAreGeneratedBetweenTheDrawnOnes() {
        Ffx ffx = Ffx.load();
        assumeTrue(ffx != null, "the FidelityFX libraries were not found: " + Ffx.failure());
        assumeTrue(ffx.hasFrameGeneration(), Ffx.FRAME_GENERATION + " was not found beside the loader");
        assumeTrue(Boolean.getBoolean("rengl.fsr.framegen"), "frame generation was not asked for (-Drengl.fsr.framegen=true)");
        FsrProvider provider = new FsrProvider();
        FsrFrameGeneration.State[] states = new FsrFrameGeneration.State[FRAMES + 1];
        String[] unavailable = new String[1];
        UpscaleComparison.Outcome outcome = UpscaleComparison.runWindow(SIZE, true, (frame, scene, device, target, delta, window) -> {
            if (frame == 1) {
                unavailable[0] = provider.isAvailable(device) ? null : "the SDK would not make an upscaler on " + device.name();
                if (unavailable[0] != null) return true;
                scene.setRenderScale(0.67f);
                scene.setUpscaler(provider);
            }
            if (frame == OFF_FROM) FsrFrameGeneration.setEnabled(false);
            if (frame == OFF_TO) FsrFrameGeneration.setEnabled(true);
            if (frame == RESIZE_AT) window.setSize(SIZE + 64, SIZE + 64);
            float x = 0.03f * frame;
            scene.camera().lookAt(new Vec3(x, 0, 3.5f), new Vec3(x, 0, 0));
            scene.render(device, target, delta);
            if (frame > 1) states[frame] = FsrFrameGeneration.state();
            return frame == FRAMES;
        });

        assumeTrue(outcome.rendered() && outcome.api() == RenderAPI.VULKAN, "no Vulkan window: " + outcome.api());
        assumeTrue(unavailable[0] == null, "FSR is not usable here: " + unavailable[0]);
        if (outcome.failure() != null) throw new AssertionError("the run failed: " + outcome.failure(), outcome.failure());
        assumeTrue(states[FRAMES] != null && states[FRAMES].presentations() > 0,
                "frame generation is not usable here: " + FsrFrameGeneration.unavailableReason());

        int onFrames = OFF_FROM - 10, offFrames = OFF_TO - OFF_FROM - 10, backFrames = RESIZE_AT - OFF_TO - 10;
        long shownOn = shown(states, 10, OFF_FROM - 1), shownOff = shown(states, OFF_FROM + 10, OFF_TO - 1),
                shownBack = shown(states, OFF_TO + 10, RESIZE_AT - 1), shownResized = shown(states, RESIZE_AT + 30, FRAMES);
        long drawnResized = drawn(states, RESIZE_AT + 30, FRAMES);
        System.out.printf("Frame generation: %d frames shown for %d drawn with it on, %d for %d with it off, %d for %d back on, "
                        + "%d for %d after the resize%n", shownOn, onFrames, shownOff, offFrames, shownBack, backFrames, shownResized,
                drawnResized);
        assertTrue(shownOn > onFrames * 1.8, "about twice as many frames were shown as were drawn: " + shownOn + " of " + onFrames);
        assertTrue(shownOff <= offFrames * 1.1, "one frame was shown for each drawn while it was off: " + shownOff + " of " + offFrames);
        assertTrue(shownBack > backFrames * 1.8, "frame generation came back when turned on again: " + shownBack + " of " + backFrames);
        assertTrue(shownResized > drawnResized * 1.8, "frame generation ran again after the swapchain was rebuilt: " + shownResized + " of " + drawnResized);
    }

    /** The frames given to the display from frame {@code from} to frame {@code to} (counted from the state after the frame before). */
    private static long shown(FsrFrameGeneration.State[] states, int from, int to) {
        return states[to].presentations() - states[from - 1].presentations();
    }

    private static long drawn(FsrFrameGeneration.State[] states, int from, int to) {
        return states[to].framesDrawn() - states[from - 1].framesDrawn();
    }

    @Test
    void withoutTheLibrariesItSaysSo() {
        assumeTrue(Ffx.load() == null || !Boolean.getBoolean("rengl.fsr.framegen"), "the libraries are present and asked for");
        assertTrue(FsrFrameGeneration.unavailableReason() != null && !FsrFrameGeneration.unavailableReason().isBlank());
    }
}
