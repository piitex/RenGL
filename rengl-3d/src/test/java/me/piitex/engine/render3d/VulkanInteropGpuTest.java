package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * What a vendor upscaler uses of the Vulkan device, without any vendor library: a stand-in upscaler gets the colour, depth
 * and motion images' handles from {@link VkGpuDevice#imageOf}, writes the output (a storage target) with commands it records
 * itself through {@link VkGpuDevice#record}, and the scene's post-processing shows what it wrote. Checks the formats,
 * layouts and usage the handles report, and that the recorded commands run in order with the passes. Skips where there is no
 * Vulkan device.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class VulkanInteropGpuTest {
    private static final int VIEW = 64;

    @Test
    void anUpscalerWritesTheOutputWithCommandsOfItsOwn() {
        String[] seen = new String[1];
        boolean[] ran = new boolean[1];
        UpscalerProvider provider = new UpscalerProvider() {
            @Override
            public String name() {
                return "Stand-in";
            }

            @Override
            public boolean isAvailable(GpuDevice device) {
                return device instanceof VkGpuDevice;
            }

            @Override
            public Upscaler create(GpuDevice device) {
                return new Upscaler() {
                    @Override
                    public String name() {
                        return "Stand-in";
                    }

                    @Override
                    public boolean writesStorageImage() {
                        return true;
                    }

                    @Override
                    public void upscale(UpscaleInputs in) {
                        VkGpuDevice vk = (VkGpuDevice) in.device();
                        VkImageInfo color = vk.imageOf(in.color()), depth = vk.imageOf(in.depth()), motion = vk.imageOf(in.motion()),
                                output = vk.imageOf(in.output().texture());
                        String problem = null;
                        if (color.image() == 0 || color.view() == 0 || output.image() == 0 || depth.image() == 0 || motion.image() == 0) problem = "a handle is 0";
                        else if (color.format() != VK_FORMAT_R16G16B16A16_SFLOAT || output.format() != VK_FORMAT_R16G16B16A16_SFLOAT || motion.format() != VK_FORMAT_R16G16B16A16_SFLOAT)
                            problem = "colour formats " + color.format() + ", " + motion.format() + ", " + output.format();
                        else if (depth.format() != vk.depthFormat() || depth.aspect() != VK_IMAGE_ASPECT_DEPTH_BIT) problem = "depth " + depth;
                        else if (depth.layout() != VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL || color.layout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                            problem = "layouts " + color.layout() + ", " + depth.layout();
                        else if ((output.usage() & VK_IMAGE_USAGE_STORAGE_BIT) == 0 || (color.usage() & VK_IMAGE_USAGE_STORAGE_BIT) != 0) problem = "storage usage " + output.usage();
                        else if (color.width() != in.renderWidth() || output.width() != in.displayWidth() || output.height() != in.displayHeight())
                            problem = "sizes " + color.width() + ", " + output.width() + "x" + output.height();
                        if (problem != null && seen[0] == null) seen[0] = problem;
                        ran[0] = true;
                        vk.record(cmd -> fill(cmd, output));
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };

        Scene3D scene = new Scene3D();
        scene.lights().clear();
        scene.setBackground(Color.BLACK);
        scene.setAmbient(Color.WHITE, 1f);
        scene.setSaturation(1.0001f);
        scene.add(new Node3D("cube", Mesh.cube(1f), new Material(new Color(0.9f, 0.2f, 0.2f, 1f)).setUnlit(true)));
        scene.camera().lookAt(new Vec3(0, 0, 3.5f), new Vec3(0, 0, 0)).setFovYDegrees(60).setClip(0.5f, 20f);
        scene.setRenderScale(0.5f);
        scene.setUpscaler(provider);

        byte[][] shot = new byte[1][];
        GpuHarness.Run run = GpuHarness.run(RenderAPI.VULKAN, VIEW, (Consumer<Viewport3D>) viewport -> viewport.setContent(new Viewport3D.Content() {
            @Override
            public void init(GpuDevice device) {
                scene.init(device);
            }

            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                scene.render(device, target, delta);
                ByteBuffer pixels = target.readPixels();
                try {
                    shot[0] = new byte[pixels.remaining()];
                    pixels.get(shot[0]);
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            }

            @Override
            public void dispose(GpuDevice device) {
                scene.dispose(device);
            }
        }), (device, target, recorder) -> {
            if (device.api() != RenderAPI.VULKAN) return;
            recorder.check("the upscaler ran", ran[0], "never called");
            recorder.check("the handles are what the upscaler expects", seen[0] == null, String.valueOf(seen[0]));
            // Every pixel of the output is the stand-in's blue; the post-processing leaves it blue.
            int i = ((target.height() / 2) * target.width() + target.width() / 2) * 4;
            int r = shot[0][i] & 255, g = shot[0][i + 1] & 255, b = shot[0][i + 2] & 255;
            recorder.check("the picture is what the upscaler wrote", b > 120 && r < 40 && g < 40, "centre pixel " + r + ", " + g + ", " + b);
        }, 8);
        assumeTrue(run.available() && run.api() == RenderAPI.VULKAN, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    /**
     * Fills the image with a blue far above white, from the layout the storage image would be written in.
     */
    private static void fill(VkCommandBuffer cmd, VkImageInfo output) {
        try (MemoryStack stack = stackPush()) {
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(output.image()).subresourceRange(range.get(0));
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toGeneral);
            VkClearColorValue blue = VkClearColorValue.calloc(stack).float32(0, 0f).float32(1, 0f).float32(2, 4f).float32(3, 1f);
            vkCmdClearColorImage(cmd, output.image(), VK_IMAGE_LAYOUT_GENERAL, blue, range);
            VkImageMemoryBarrier.Buffer toRead = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(output.layout())
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(output.image()).subresourceRange(range.get(0));
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, toRead);
        }
    }
}
