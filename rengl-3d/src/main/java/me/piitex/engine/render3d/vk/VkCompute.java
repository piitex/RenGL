package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.ComputePipeline;
import me.piitex.engine.render3d.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** A compute pipeline: one shader, its descriptor layout, and 128 bytes of push constants. */
final class VkCompute implements ComputePipeline {
    static final int PUSH_BYTES = 128;
    private final VkGpuDevice device;
    final List<ShaderCompiler.Binding> bindings;
    final long descriptorLayout;
    final long layout;
    final long pipeline;
    private boolean closed;

    VkCompute(VkGpuDevice device, String source) {
        this.device = device;
        ShaderCompiler.ComputeCompiled compiled = ShaderCompiler.compileCompute(source);
        bindings = compiled.bindings();
        try (MemoryStack stack = stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            long module;
            try {
                VkGpuDevice.check(vkCreateShaderModule(device.device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(compiled.spirv()),
                        null, out), "create a shader module");
                module = out.get(0);
            } finally {
                ShaderCompiler.free(compiled.spirv());
            }
            VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                ShaderCompiler.Binding binding = bindings.get(i);
                layoutBindings.get(i).binding(binding.binding())
                        .descriptorType(binding.sampler() ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                : binding.storage() ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(binding.count())
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkGpuDevice.check(vkCreateDescriptorSetLayout(device.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(layoutBindings), null, out), "create a descriptor layout");
            descriptorLayout = out.get(0);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkGpuDevice.check(vkCreatePipelineLayout(device.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, out), "create a pipeline layout");
            layout = out.get(0);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            int result = vkCreateComputePipelines(device.device, VK_NULL_HANDLE, info, null, out);
            vkDestroyShaderModule(device.device, module, null);
            VkGpuDevice.check(result, "create a compute pipeline");
            pipeline = out.get(0);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        device.destroyLater(() -> {
            vkDestroyPipeline(device.device, pipeline, null);
            vkDestroyPipelineLayout(device.device, layout, null);
            vkDestroyDescriptorSetLayout(device.device, descriptorLayout, null);
        });
    }
}
