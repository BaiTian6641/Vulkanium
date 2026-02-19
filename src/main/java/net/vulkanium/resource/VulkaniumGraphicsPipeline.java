package net.vulkanium.resource;

import net.vulkanium.core.VulkaniumInstance;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builder-pattern Vulkan graphics pipeline creation.
 *
 * <p>Unlike VulkanMod's {@code GraphicsPipeline} which hardcodes many parameters,
 * Vulkanium's pipeline is fully configurable for different render passes:</p>
 * <ul>
 *   <li>Configurable vertex format (terrain 32-byte, entity, GUI, etc.)</li>
 *   <li>Multiple color blend attachments for MRT</li>
 *   <li>Dynamic state (viewport, scissor, line width)</li>
 *   <li>Push constants for per-draw data</li>
 * </ul>
 */
public class VulkaniumGraphicsPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Pipeline");

    private long pipeline = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private VkDevice device;

    /**
     * Creates a graphics pipeline from the builder configuration.
     */
    public static VulkaniumGraphicsPipeline create(VkDevice device, PipelineConfig config) {
        VulkaniumGraphicsPipeline gp = new VulkaniumGraphicsPipeline();
        gp.device = device;
        gp.build(config);
        return gp;
    }

    private void build(PipelineConfig config) {
        try (MemoryStack stack = stackPush()) {
            // === Shader stages ===
            VkPipelineShaderStageCreateInfo.Buffer shaderStages =
                    VkPipelineShaderStageCreateInfo.calloc(config.shaderModules.size(), stack);

            for (int i = 0; i < config.shaderModules.size(); i++) {
                ShaderModuleInfo info = config.shaderModules.get(i);
                shaderStages.get(i)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(info.stage)
                        .module(info.module)
                        .pName(stack.UTF8("main"));
            }

            // === Vertex input ===
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            if (config.vertexBindings != null && config.vertexAttributes != null) {
                vertexInput
                        .pVertexBindingDescriptions(config.vertexBindings)
                        .pVertexAttributeDescriptions(config.vertexAttributes);
            }

            // === Input assembly ===
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(config.topology)
                    .primitiveRestartEnable(false);

            // === Viewport / Scissor (dynamic) ===
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            // === Rasterization ===
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(config.polygonMode)
                    .lineWidth(1.0f)
                    .cullMode(config.cullMode)
                    .frontFace(config.frontFace)
                    .depthBiasEnable(config.depthBiasEnable);

            if (config.depthBiasEnable) {
                rasterizer
                        .depthBiasConstantFactor(config.depthBiasConstant)
                        .depthBiasSlopeFactor(config.depthBiasSlope);
            }

            // === Multisample ===
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(config.sampleCount);

            // === Depth / stencil ===
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(config.depthTestEnable)
                    .depthWriteEnable(config.depthWriteEnable)
                    .depthCompareOp(config.depthCompareOp)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(config.stencilTestEnable);

            // === Color blend (per-attachment for MRT) ===
            int colorAttachmentCount = Math.max(1, config.colorBlendAttachmentCount);
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachments =
                    VkPipelineColorBlendAttachmentState.calloc(colorAttachmentCount, stack);

            for (int i = 0; i < colorAttachmentCount; i++) {
                colorBlendAttachments.get(i)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(config.blendEnable);

                if (config.blendEnable) {
                    colorBlendAttachments.get(i)
                            .srcColorBlendFactor(config.srcColorBlend)
                            .dstColorBlendFactor(config.dstColorBlend)
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(config.srcAlphaBlend)
                            .dstAlphaBlendFactor(config.dstAlphaBlend)
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachments);

            // === Dynamic state ===
            int[] dynamicStates = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(dynamicStates));

            // === Pipeline layout ===
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

            if (config.descriptorSetLayouts != null && config.descriptorSetLayouts.length > 0) {
                layoutInfo.pSetLayouts(stack.longs(config.descriptorSetLayouts));
            }

            if (config.pushConstantRange != null) {
                VkPushConstantRange.Buffer pushConstant = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(config.pushConstantRange.stageFlags)
                        .offset(config.pushConstantRange.offset)
                        .size(config.pushConstantRange.size);
                layoutInfo.pPushConstantRanges(pushConstant);
            }

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            int result = vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            checkResult(result, "Failed to create pipeline layout");
            pipelineLayout = pLayout.get(0);

            // === Create pipeline ===
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(config.renderPass)
                    .subpass(config.subpass)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pPipeline = stack.longs(VK_NULL_HANDLE);
            result = vkCreateGraphicsPipelines(device, config.pipelineCache, pipelineInfo, null, pPipeline);
            checkResult(result, "Failed to create graphics pipeline");

            pipeline = pPipeline.get(0);
        }
    }

    /**
     * Binds this pipeline to the command buffer.
     */
    public void bind(VkCommandBuffer cmd) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    }

    /**
     * Binds descriptor sets to this pipeline.
     */
    public void bindDescriptorSets(VkCommandBuffer cmd, long... descriptorSets) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineLayout, 0, stack.longs(descriptorSets), null);
        }
    }

    // === Cleanup ===

    public void destroy() {
        if (device == null) return;

        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, pipeline, null);
            pipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
    }

    // === Getters ===

    public long getPipeline() { return pipeline; }
    public long getPipelineLayout() { return pipelineLayout; }

    // === Static Shader Module Helper ===

    /**
     * Creates a VkShaderModule from SPIR-V bytecode.
     */
    public static long createShaderModule(VkDevice device, ByteBuffer spirvCode) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirvCode);

            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateShaderModule(device, createInfo, null, pModule);
            checkResult(result, "Failed to create shader module");

            return pModule.get(0);
        }
    }

    /**
     * Destroys a VkShaderModule.
     */
    public static void destroyShaderModule(VkDevice device, long module) {
        if (module != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, module, null);
        }
    }

    // === Configuration Types ===

    public record ShaderModuleInfo(int stage, long module) {}

    public record PushConstantRangeInfo(int stageFlags, int offset, int size) {}

    /**
     * Full pipeline configuration. Use the builder or set fields directly.
     */
    public static class PipelineConfig {
        public List<ShaderModuleInfo> shaderModules = new ArrayList<>();
        public VkVertexInputBindingDescription.Buffer vertexBindings;
        public VkVertexInputAttributeDescription.Buffer vertexAttributes;
        public int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        public int polygonMode = VK_POLYGON_MODE_FILL;
        public int cullMode = VK_CULL_MODE_BACK_BIT;
        public int frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        public boolean depthBiasEnable = false;
        public float depthBiasConstant = 0;
        public float depthBiasSlope = 0;

        public int sampleCount = VK_SAMPLE_COUNT_1_BIT;

        public boolean depthTestEnable = true;
        public boolean depthWriteEnable = true;
        public int depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
        public boolean stencilTestEnable = false;

        public int colorBlendAttachmentCount = 1;
        public boolean blendEnable = false;
        public int srcColorBlend = VK_BLEND_FACTOR_SRC_ALPHA;
        public int dstColorBlend = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        public int srcAlphaBlend = VK_BLEND_FACTOR_ONE;
        public int dstAlphaBlend = VK_BLEND_FACTOR_ZERO;

        public long[] descriptorSetLayouts;
        public PushConstantRangeInfo pushConstantRange;

        public long renderPass;
        public int subpass = 0;
        public long pipelineCache = VK_NULL_HANDLE;

        public PipelineConfig addShaderModule(int stage, long module) {
            shaderModules.add(new ShaderModuleInfo(stage, module));
            return this;
        }
    }
}
