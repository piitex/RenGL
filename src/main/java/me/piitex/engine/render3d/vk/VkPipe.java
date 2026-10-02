package me.piitex.engine.render3d.vk;

import me.piitex.engine.render3d.Pipeline;
import me.piitex.engine.render3d.PipelineDesc;
import me.piitex.engine.render3d.ShaderCompiler;
import me.piitex.engine.render3d.VertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

final class VkPipe implements Pipeline {
    private final VkGpuDevice device;
    final PipelineDesc desc;
    final List<ShaderCompiler.Binding> bindings;
    final long descriptorLayout;
    final long layout;
    private final long vertexModule, fragmentModule;
    private final Map<Long, Long> pipelines = new HashMap<>(); // render pass -> VkPipeline
    private boolean closed;

    VkPipe(VkGpuDevice device, PipelineDesc desc) {
        this.device = device;
        this.desc = desc;
        ShaderCompiler.Compiled compiled = ShaderCompiler.compile(desc);
        try {
            bindings = compiled.bindings;
            vertexModule = createModule(compiled.vertexSpirv);
            fragmentModule = createModule(compiled.fragmentSpirv);
        } finally {
            ShaderCompiler.free(compiled.vertexSpirv);
            ShaderCompiler.free(compiled.fragmentSpirv);
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                ShaderCompiler.Binding binding = bindings.get(i);
                layoutBindings.get(i)
                        .binding(binding.binding())
                        .descriptorType(binding.sampler() ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pBindings(layoutBindings);
            VkGpuDevice.check(vkCreateDescriptorSetLayout(device.device, setInfo, null, out), "create a descriptor layout");
            descriptorLayout = out.get(0);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout));
            VkGpuDevice.check(vkCreatePipelineLayout(device.device, layoutInfo, null, out), "create a pipeline layout");
            layout = out.get(0);
        }
    }

    private long createModule(ByteBuffer spirv) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer out = stack.mallocLong(1);
            VkGpuDevice.check(vkCreateShaderModule(device.device, info, null, out), "create a shader module");
            return out.get(0);
        }
    }

    /**
     * The VkPipeline for drawing into the given kind of render pass, built the first time it is needed.
     */
    long forRenderPass(long renderPass, boolean hasDepth, int samples) {
        Long existing = pipelines.get(renderPass);
        if (existing != null) return existing;
        long created = build(renderPass, hasDepth, samples);
        pipelines.put(renderPass, created);
        return created;
    }

    private static int vertexFormat(int components) {
        return switch (components) {
            case 1 -> VK_FORMAT_R32_SFLOAT;
            case 2 -> VK_FORMAT_R32G32_SFLOAT;
            case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
            default -> VK_FORMAT_R32G32B32A32_SFLOAT;
        };
    }

    private long build(long renderPass, boolean hasDepth, int samples) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));

            // Buffer binding 0 is the main layout, 1 and up the extra ones, each with the rate its layout asks for.
            List<VertexLayout> layouts = new java.util.ArrayList<>();
            layouts.add(desc.vertexLayout());
            layouts.addAll(desc.extraLayouts());
            int attributeCount = 0;
            for (VertexLayout layout : layouts) attributeCount += layout.attributes().size();
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(attributeCount, stack);
            VkVertexInputBindingDescription.Buffer bindingDescriptions = VkVertexInputBindingDescription.calloc(layouts.size(), stack);
            int next = 0;
            for (int binding = 0; binding < layouts.size(); binding++) {
                VertexLayout layout = layouts.get(binding);
                bindingDescriptions.get(binding).binding(binding).stride(layout.stride())
                        .inputRate(layout.rate() == VertexLayout.Rate.INSTANCE ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                for (VertexLayout.Attribute attribute : layout.attributes()) {
                    attributes.get(next++).location(attribute.location()).binding(binding)
                            .format(vertexFormat(attribute.format().components)).offset(attribute.offset());
                }
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(bindingDescriptions)
                    .pVertexAttributeDescriptions(attributes);

            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(desc.cullBackFaces() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                    .frontFace(desc.frontClockwise() ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(samples); // VK_SAMPLE_COUNT_n_BIT is n

            boolean depth = desc.depthTest() && hasDepth;
            VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(depth).depthWriteEnable(depth && desc.depthWrite()).depthCompareOp(VK_COMPARE_OP_LESS);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(desc.blend())
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(desc.additive() ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(desc.additive() ? VK_BLEND_FACTOR_ZERO : VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(desc.additive() ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(blendAttachment);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(assembly)
                    .pViewportState(viewport)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pColorBlendState(blend)
                    .pDynamicState(dynamic)
                    .layout(layout)
                    .renderPass(renderPass)
                    .subpass(0);
            if (hasDepth) info.pDepthStencilState(depthState);
            LongBuffer out = stack.mallocLong(1);
            VkGpuDevice.check(vkCreateGraphicsPipelines(device.device, VK_NULL_HANDLE, info, null, out), "create a pipeline");
            return out.get(0);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        List<Long> built = List.copyOf(pipelines.values());
        device.destroyLater(() -> {
            for (long pipeline : built) vkDestroyPipeline(device.device, pipeline, null);
            vkDestroyPipelineLayout(device.device, layout, null);
            vkDestroyDescriptorSetLayout(device.device, descriptorLayout, null);
            vkDestroyShaderModule(device.device, vertexModule, null);
            vkDestroyShaderModule(device.device, fragmentModule, null);
        });
    }
}
