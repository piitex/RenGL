package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

final class VkPass implements RenderPass {
    private final VkGpuDevice device;
    private final VkTarget target;
    private final VkCommandBuffer cmd;
    private final Map<Integer, VkBuf> uniformBuffers = new HashMap<>();
    private final Map<Integer, VkTex> textures = new HashMap<>();
    private final Map<Integer, VkRay.Scene> structures = new HashMap<>();
    private final Map<Integer, VkSoftRay.Scene> softScenes = new HashMap<>();
    private final Map<Integer, VkSoftRay.Scene> softGeometry = new HashMap<>();
    private final Map<Integer, VkTex[]> textureArrays = new HashMap<>();
    private final long renderPass;
    private VkPipe pipeline;
    private boolean descriptorsDirty;
    private boolean ended;

    VkPass(VkGpuDevice device, VkTarget target, float r, float g, float b, float a) {
        this.device = device;
        this.target = target;
        this.renderPass = device.renderPass(target.hasDepth(), target.samples(), target.sampled(), target.hdr(), target.readableDepth());
        cmd = device.allocateFrameCommandBuffer();
        try (MemoryStack stack = stackPush()) {
            VkGpuDevice.check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin a command buffer");

            // One clear value per attachment, by index; the resolve target of a multisampled pass is not cleared.
            VkClearValue.Buffer clears = VkClearValue.calloc(target.attachmentCount(), stack);
            clears.get(0).color().float32(0, r).float32(1, g).float32(2, b).float32(3, a);
            if (target.hasDepth()) clears.get(target.depthAttachment()).depthStencil().depth(1f).stencil(0);

            VkRenderPassBeginInfo begin = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass)
                    .framebuffer(target.framebuffer)
                    .renderArea(area -> area.extent().width(target.width()).height(target.height()))
                    .pClearValues(clears);
            vkCmdBeginRenderPass(cmd, begin, VK_SUBPASS_CONTENTS_INLINE);

            // Negative height flips Y so clip space is +Y up like OpenGL's (core in Vulkan 1.1).
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack)
                    .x(0).y(target.height()).width(target.width()).height(-target.height())
                    .minDepth(0f).maxDepth(1f));
            vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack)
                    .extent(e -> e.width(target.width()).height(target.height())));
        }
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack)
                    .x(x).y(y + height).width(width).height(-height)
                    .minDepth(0f).maxDepth(1f));
            vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack)
                    .offset(o -> o.x(x).y(y)).extent(e -> e.width(width).height(height)));
        }
    }

    @Override
    public void bindPipeline(Pipeline pipeline) {
        this.pipeline = (VkPipe) pipeline;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                this.pipeline.forRenderPass(renderPass, target.hasDepth(), target.samples()));
        descriptorsDirty = true;
    }

    @Override
    public void bindVertexBuffer(int binding, GpuBuffer buffer) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd, binding, stack.longs(((VkBuf) buffer).handle()), stack.longs(0));
        }
    }

    @Override
    public void bindIndexBuffer(GpuBuffer buffer) {
        vkCmdBindIndexBuffer(cmd, ((VkBuf) buffer).handle(), 0, VK_INDEX_TYPE_UINT32);
    }

    @Override
    public void bindUniformBuffer(int binding, GpuBuffer buffer) {
        if (uniformBuffers.put(binding, (VkBuf) buffer) != buffer) descriptorsDirty = true;
    }

    @Override
    public void bindTexture(int binding, GpuTexture texture) {
        if (textures.put(binding, (VkTex) texture) != texture) descriptorsDirty = true;
    }

    @Override
    public void bindTextures(int binding, GpuTexture[] textures) {
        VkTex[] bound = new VkTex[textures.length];
        for (int i = 0; i < bound.length; i++) bound[i] = (VkTex) textures[i];
        VkTex[] before = textureArrays.put(binding, bound);
        if (before == null || !java.util.Arrays.equals(before, bound)) descriptorsDirty = true;
    }

    @Override
    public void bindAccelerationStructure(int binding, RayScene scene) {
        if (scene instanceof VkSoftRay.Scene soft) {
            // The scene's own storage buffer (its instances and the hierarchy over them) stands in for the structure.
            if (softScenes.put(binding, soft) != scene) descriptorsDirty = true;
            return;
        }
        if (!(scene instanceof VkRay.Scene vk)) throw new IllegalArgumentException("Not a ray scene of this device: " + scene);
        if (structures.put(binding, vk) != scene) descriptorsDirty = true;
    }

    @Override
    public void bindRayGeometry(int binding, RayScene scene) {
        if (!(scene instanceof VkSoftRay.Scene soft)) throw new IllegalArgumentException("Not a software ray scene of this device: " + scene);
        if (softGeometry.put(binding, soft) != scene) descriptorsDirty = true;
    }

    /**
     * Allocates a descriptor set for the bound resources, once per change, from the frame's pool.
     */
    private void flushDescriptors() {
        if (pipeline == null) throw new IllegalStateException("Bind a pipeline before drawing");
        if (!descriptorsDirty || pipeline.bindings.isEmpty()) return;
        try (MemoryStack stack = stackPush()) {
            LongBuffer set = stack.longs(device.allocateDescriptorSet(stack, pipeline.descriptorLayout));

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(pipeline.bindings.size(), stack);
            for (int i = 0; i < pipeline.bindings.size(); i++) {
                ShaderCompiler.Binding binding = pipeline.bindings.get(i);
                VkWriteDescriptorSet write = writes.get(i).sType$Default()
                        .dstSet(set.get(0)).dstBinding(binding.binding()).descriptorCount(1);
                if (binding.storage()) {
                    VkSoftRay.Scene scene = softScenes.get(binding.binding());
                    long handle, size;
                    if (scene != null) {
                        handle = scene.buffer();
                        size = scene.bufferSize();
                    } else if ((scene = softGeometry.get(binding.binding())) != null) {
                        handle = scene.geometryBuffer();
                        size = scene.geometryBufferSize();
                    } else {
                        throw new IllegalStateException("No ray storage bound at binding " + binding.binding());
                    }
                    write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(handle).offset(0).range(size));
                } else if (binding.accel()) {
                    VkRay.Scene scene = structures.get(binding.binding());
                    if (scene == null) throw new IllegalStateException("No acceleration structure bound at binding " + binding.binding());
                    write.descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                            .pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                                    .pAccelerationStructures(stack.longs(scene.structure())).address());
                } else if (binding.sampler() && binding.count() > 1) {
                    VkTex[] array = textureArrays.get(binding.binding());
                    if (array == null || array.length != binding.count()) {
                        throw new IllegalStateException("Bind " + binding.count() + " textures at binding " + binding.binding());
                    }
                    VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(array.length, stack);
                    for (int k = 0; k < array.length; k++) {
                        infos.get(k).sampler(array[k].sampler).imageView(array[k].view).imageLayout(array[k].layout);
                    }
                    write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(array.length).pImageInfo(infos);
                } else if (binding.sampler()) {
                    VkTex texture = textures.get(binding.binding());
                    if (texture == null) throw new IllegalStateException("No texture bound at binding " + binding.binding());
                    write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                                    .sampler(texture.sampler).imageView(texture.view)
                                    .imageLayout(texture.layout));
                } else {
                    VkBuf buffer = uniformBuffers.get(binding.binding());
                    if (buffer == null) throw new IllegalStateException("No uniform buffer bound at binding " + binding.binding());
                    write.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
                                    .buffer(buffer.handle()).offset(0).range(buffer.size()));
                }
            }
            vkUpdateDescriptorSets(device.device, writes, null);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout, 0, set, null);
        }
        descriptorsDirty = false;
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstInstance) {
        flushDescriptors();
        vkCmdDraw(cmd, vertexCount, instanceCount, 0, firstInstance);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstInstance) {
        flushDescriptors();
        vkCmdDrawIndexed(cmd, indexCount, instanceCount, 0, 0, firstInstance);
    }

    @Override
    public void end() {
        if (ended) return;
        ended = true;
        vkCmdEndRenderPass(cmd);
        VkGpuDevice.check(vkEndCommandBuffer(cmd), "end a command buffer");
        try (MemoryStack stack = stackPush()) {
            // Uploads made while this pass was recorded go first, in the same submission.
            VkCommandBuffer uploads = device.takeUploads();
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(uploads == null ? stack.pointers(cmd) : stack.pointers(uploads, cmd));
            VkGpuDevice.check(vkQueueSubmit(device.queue, submit, VK_NULL_HANDLE), "submit a pass");
        }
    }
}
