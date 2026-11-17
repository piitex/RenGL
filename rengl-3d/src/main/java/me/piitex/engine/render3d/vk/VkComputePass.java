package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Compute work in a command buffer of its own, submitted in order with the passes. It begins by waiting for everything
 * submitted before it (which may still read the buffers it is about to write) and ends by making its writes visible to
 * whatever comes after: indirect commands, vertex and index reads, shaders and the CPU.
 */
final class VkComputePass implements ComputePass {
    private final VkGpuDevice device;
    private final VkCommandBuffer cmd;
    private final Map<Integer, VkBuf> buffers = new HashMap<>();
    private final Map<Integer, VkTex> textures = new HashMap<>();
    private VkCompute pipeline;
    private boolean dirty;
    private boolean ended;

    VkComputePass(VkGpuDevice device) {
        this.device = device;
        cmd = device.allocateFrameCommandBuffer();
        try (MemoryStack stack = stackPush()) {
            VkGpuDevice.check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin a command buffer");
            // Earlier work (last frame's draws among it) may still be reading the buffers this writes, or writing the
            // pictures it reads.
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                                    | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT),
                    null, null);
        }
    }

    @Override
    public void bindPipeline(ComputePipeline pipeline) {
        this.pipeline = (VkCompute) pipeline;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline.pipeline);
        dirty = true;
    }

    @Override
    public void bindStorageBuffer(int binding, GpuBuffer buffer) {
        if (buffers.put(binding, (VkBuf) buffer) != buffer) dirty = true;
    }

    @Override
    public void bindUniformBuffer(int binding, GpuBuffer buffer) {
        if (buffers.put(binding, (VkBuf) buffer) != buffer) dirty = true;
    }

    @Override
    public void bindTexture(int binding, GpuTexture texture) {
        if (textures.put(binding, (VkTex) texture) != texture) dirty = true;
    }

    @Override
    public void pushConstants(ByteBuffer data) {
        if (pipeline == null) throw new IllegalStateException("Bind a pipeline before setting its push constants");
        if (data.remaining() > VkCompute.PUSH_BYTES) throw new IllegalArgumentException("At most " + VkCompute.PUSH_BYTES + " bytes of push constants");
        vkCmdPushConstants(cmd, pipeline.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, data);
    }

    private void flush() {
        if (pipeline == null) throw new IllegalStateException("Bind a pipeline before dispatching");
        if (!dirty || pipeline.bindings.isEmpty()) return;
        try (MemoryStack stack = stackPush()) {
            LongBuffer set = stack.longs(device.allocateDescriptorSet(stack, pipeline.descriptorLayout));
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(pipeline.bindings.size(), stack);
            for (int i = 0; i < pipeline.bindings.size(); i++) {
                ShaderCompiler.Binding binding = pipeline.bindings.get(i);
                VkWriteDescriptorSet write = writes.get(i).sType$Default().dstSet(set.get(0)).dstBinding(binding.binding()).descriptorCount(1);
                if (binding.sampler()) {
                    VkTex texture = textures.get(binding.binding());
                    if (texture == null) throw new IllegalStateException("No texture bound at binding " + binding.binding());
                    write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                            .sampler(texture.sampler).imageView(texture.view).imageLayout(texture.layout));
                } else {
                    VkBuf buffer = buffers.get(binding.binding());
                    if (buffer == null) throw new IllegalStateException("No buffer bound at binding " + binding.binding());
                    write.descriptorType(binding.storage() ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.handle()).offset(0).range(buffer.size()));
                }
            }
            vkUpdateDescriptorSets(device.device, writes, null);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout, 0, set, null);
        }
        dirty = false;
    }

    @Override
    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) return;
        flush();
        vkCmdDispatch(cmd, groupsX, groupsY, groupsZ);
    }

    @Override
    public void dispatchIndirect(GpuBuffer buffer, long offset) {
        flush();
        vkCmdDispatchIndirect(cmd, ((VkBuf) buffer).handle(), offset);
    }

    @Override
    public void fill(GpuBuffer buffer, long offset, long size, int value) {
        vkCmdFillBuffer(cmd, ((VkBuf) buffer).handle(), offset, size, value);
    }

    @Override
    public void copy(GpuBuffer from, long fromOffset, GpuBuffer to, long toOffset, long size) {
        try (MemoryStack stack = stackPush()) {
            vkCmdCopyBuffer(cmd, ((VkBuf) from).handle(), ((VkBuf) to).handle(),
                    VkBufferCopy.calloc(1, stack).srcOffset(fromOffset).dstOffset(toOffset).size(size));
        }
    }

    @Override
    public void barrier() {
        int stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
        int access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        try (MemoryStack stack = stackPush()) {
            // What follows may also dispatch with counts what came before wrote.
            vkCmdPipelineBarrier(cmd, stages, stages | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(access).dstAccessMask(access | VK_ACCESS_INDIRECT_COMMAND_READ_BIT), null, null);
        }
    }

    @Override
    public void end() {
        if (ended) return;
        ended = true;
        try (MemoryStack stack = stackPush()) {
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
                            | VK_PIPELINE_STAGE_HOST_BIT,
                    0, VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT
                                    | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT
                                    | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_READ_BIT),
                    null, null);
            VkGpuDevice.check(vkEndCommandBuffer(cmd), "end a command buffer");
            // Uploads made so far go first, in the same submission, as they do for a pass.
            VkCommandBuffer uploads = device.takeUploads();
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(uploads == null ? stack.pointers(cmd) : stack.pointers(uploads, cmd));
            VkGpuDevice.check(vkQueueSubmit(device.queue, submit, VK_NULL_HANDLE), "submit compute work");
        }
    }
}
