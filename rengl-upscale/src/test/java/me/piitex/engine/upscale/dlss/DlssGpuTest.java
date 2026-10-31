package me.piitex.engine.upscale.dlss;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.upscale.UpscaleComparison;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DLSS on a real Vulkan device, judged like FSR: by how close its picture gets to the scene drawn at full size
 * ({@link UpscaleComparison}). It must be clearly closer than a smooth enlargement.
 * <p>
 * Needs NVIDIA's Streamline libraries (see {@link Sl}), which are not part of the build, and an NVIDIA RTX GPU; the test skips
 * elsewhere. Run with {@code -Drengl.dlss.dir=<folder>}; add {@code -Drengl.vulkan.validation=true} to have the validation layer
 * check what Streamline records, {@code -Drengl.dlss.log=true} to see its log and {@code -Drengl.dlss.dump=<folder>} to write the
 * pictures.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class DlssGpuTest {
    @Test
    void dlssIsCloserToTheFullSizePictureThanASmoothEnlargement() {
        assumeTrue(Sl.load() != null, "NVIDIA's Streamline libraries were not found: " + Sl.failure());
        DlssProvider provider = new DlssProvider();
        UpscaleComparison.Result result = UpscaleComparison.run(provider, DlssProvider::unavailableReason);

        assumeTrue(result.rendered() && result.api() == RenderAPI.VULKAN, "no Vulkan window: " + result.api());
        assumeTrue(result.unavailable() == null, "DLSS is not usable here: " + result.unavailable());
        if (result.failure() != null) throw new AssertionError("DLSS failed: " + result.failure(), result.failure());
        assertTrue(result.done(), "the run did not reach the DLSS frame");
        byte[][] shots = result.shots();
        double smooth = UpscaleComparison.error(shots[1], shots[0]), dlss = UpscaleComparison.error(shots[2], shots[0]),
                builtIn = UpscaleComparison.error(shots[3], shots[0]);
        System.out.printf("DLSS: smooth enlargement %.5f, DLSS %.5f, built-in %.5f%n", smooth, dlss, builtIn);
        String dump = System.getProperty("rengl.dlss.dump");
        if (dump != null) UpscaleComparison.dump(dump, shots);
        double brightness = UpscaleComparison.brightness(shots[2]);
        assertTrue(brightness > 0.1, "the DLSS picture is not empty, mean brightness " + brightness);
        assertTrue(dlss < smooth * 0.9, "DLSS is closer to the full-size picture than the smooth enlargement: DLSS " + dlss + ", smooth " + smooth);
    }

    @Test
    void dlssKeepsUpWithAPanningCamera() {
        assumeTrue(Sl.load() != null, "NVIDIA's Streamline libraries were not found: " + Sl.failure());
        DlssProvider provider = new DlssProvider();
        UpscaleComparison.MovingResult result = UpscaleComparison.runMoving(provider, DlssProvider::unavailableReason);

        assumeTrue(result.rendered() && result.api() == RenderAPI.VULKAN, "no Vulkan window: " + result.api());
        assumeTrue(result.unavailable() == null, "DLSS is not usable here: " + result.unavailable());
        if (result.failure() != null) throw new AssertionError("DLSS failed: " + result.failure(), result.failure());
        assertTrue(result.done(), "the run did not finish");
        byte[][] shots = result.shots();
        double dlss = UpscaleComparison.error(shots[0], shots[1]), smooth = UpscaleComparison.error(shots[2], shots[1]),
                builtIn = UpscaleComparison.error(shots[3], shots[4]);
        System.out.printf("DLSS panning: smooth enlargement %.5f, DLSS %.5f, built-in %.5f%n", smooth, dlss, builtIn);
        String dump = System.getProperty("rengl.dlss.dump");
        if (dump != null) UpscaleComparison.dump(dump + "/moving", new byte[][]{shots[1], shots[2], shots[0], shots[3]});
        assertTrue(dlss < smooth * 0.9, "DLSS in motion is closer to the full-size picture than the smooth enlargement: DLSS " + dlss + ", smooth " + smooth);
    }

    @Test
    void withoutTheLibrariesTheProviderSaysSo() {
        assumeTrue(Sl.load() == null, "the libraries are present");
        assertTrue(DlssProvider.unavailableReason() != null && !DlssProvider.unavailableReason().isBlank());
    }
}
