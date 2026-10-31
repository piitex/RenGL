package me.piitex.engine.upscale.fsr;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.upscale.UpscaleComparison;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FSR on a real Vulkan device, judged the way the built-in upscaler is: by how close its picture gets to the scene drawn at
 * full size ({@link UpscaleComparison}). FSR must be clearly closer than a smooth enlargement.
 * <p>
 * Needs AMD's libraries (see {@link Ffx}), which are not part of the build: the test skips where they are not found,
 * where there is no Vulkan device, or where the SDK will not make an upscaler on it. Run with
 * {@code -Drengl.fsr.dir=<folder>}; add {@code -Drengl.vulkan.validation=true} to have the validation layer check what the
 * SDK records, and {@code -Drengl.fsr.version=3.1} to pin FSR 3. {@code -Drengl.fsr.dump=<folder>} writes the pictures.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class FsrGpuTest {
    @Test
    void fsrIsCloserToTheFullSizePictureThanASmoothEnlargement() {
        assumeTrue(Ffx.load() != null, "the FidelityFX libraries were not found: " + Ffx.failure());
        FsrProvider provider = new FsrProvider();
        UpscaleComparison.Result result = UpscaleComparison.run(provider, device ->
                provider.isAvailable(device) ? null : FsrProvider.unavailableReason() != null ? FsrProvider.unavailableReason()
                        : "the SDK would not make an upscaler on " + device.name());

        assumeTrue(result.rendered() && result.api() == RenderAPI.VULKAN, "no Vulkan window: " + result.api());
        assumeTrue(result.unavailable() == null, "FSR is not usable here: " + result.unavailable());
        if (result.failure() != null) throw new AssertionError("FSR failed: " + result.failure(), result.failure());
        assertTrue(result.done(), "the run did not reach the FSR frame");
        byte[][] shots = result.shots();
        double smooth = UpscaleComparison.error(shots[1], shots[0]), fsr = UpscaleComparison.error(shots[2], shots[0]),
                builtIn = UpscaleComparison.error(shots[3], shots[0]);
        System.out.printf("FSR: smooth enlargement %.5f, FSR %.5f, built-in %.5f%n", smooth, fsr, builtIn);
        String dump = System.getProperty("rengl.fsr.dump");
        if (dump != null) UpscaleComparison.dump(dump, shots);
        double brightness = UpscaleComparison.brightness(shots[2]);
        assertTrue(brightness > 0.1, "the FSR picture is not empty, mean brightness " + brightness);
        // The wall's checks are only two pixels wide at half the size, so every picture drawn that small, whatever enlarges it,
        // is a little off the full-size one at each edge; that puts a floor under both errors. FSR measures about 0.75 of the
        // smooth enlargement here.
        assertTrue(fsr < smooth * 0.9, "FSR is closer to the full-size picture than the smooth enlargement: FSR " + fsr + ", smooth " + smooth);
    }

    @Test
    void fsrKeepsUpWithAPanningCamera() {
        assumeTrue(Ffx.load() != null, "the FidelityFX libraries were not found: " + Ffx.failure());
        FsrProvider provider = new FsrProvider();
        UpscaleComparison.MovingResult result = UpscaleComparison.runMoving(provider, device ->
                provider.isAvailable(device) ? null : FsrProvider.unavailableReason() != null ? FsrProvider.unavailableReason()
                        : "the SDK would not make an upscaler on " + device.name());

        assumeTrue(result.rendered() && result.api() == RenderAPI.VULKAN, "no Vulkan window: " + result.api());
        assumeTrue(result.unavailable() == null, "FSR is not usable here: " + result.unavailable());
        if (result.failure() != null) throw new AssertionError("FSR failed: " + result.failure(), result.failure());
        assertTrue(result.done(), "the run did not finish");
        byte[][] shots = result.shots();
        double fsr = UpscaleComparison.error(shots[0], shots[1]), smooth = UpscaleComparison.error(shots[2], shots[1]),
                builtIn = UpscaleComparison.error(shots[3], shots[4]);
        System.out.printf("FSR panning: smooth enlargement %.5f, FSR %.5f, built-in %.5f%n", smooth, fsr, builtIn);
        // The motion vectors carry the history along: with a wrong sign or scale the picture is worse than a plain enlargement.
        assertTrue(fsr < smooth * 0.9, "FSR in motion is closer to the full-size picture than the smooth enlargement: FSR " + fsr + ", smooth " + smooth);
    }

    @Test
    void withoutTheLibrariesTheProviderSaysSo() {
        assumeTrue(Ffx.load() == null, "the libraries are present");
        assertTrue(FsrProvider.unavailableReason() != null && !FsrProvider.unavailableReason().isBlank());
    }
}
