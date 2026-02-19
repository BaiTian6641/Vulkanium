package net.vulkanium.rt;

import net.vulkanium.resource.SPIRVCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Composites the SSAO output onto the swapchain image using a fullscreen triangle
 * with multiplicative blending.
 *
 * <p>The compositing render pass uses {@code LOAD_OP_LOAD} to preserve the existing
 * rasterized scene, then draws a fullscreen triangle that outputs the AO value as RGB.
 * Multiplicative blending ({@code dst * src}) darkens occluded areas naturally.</p>
 *
 * <h3>Blending Setup</h3>
 * <pre>
 *   srcColorFactor = DST_COLOR     dstColorFactor = ZERO     → result = src * dst = ao * scene
 *   srcAlphaFactor = ZERO          dstAlphaFactor = ONE      → preserves original alpha
 * </pre>
 *
 * <h3>Fullscreen Triangle</h3>
 * <p>Procedurally generated in the vertex shader — no vertex buffer needed.
 * Three vertices cover the entire screen using the standard oversized-triangle trick.</p>
 */
public class SSAOCompositor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/SSAOCompositor");

    // ── Vulkan Resources ──
    private VkDevice device;

    // Render pass: color-only, LOAD_OP_LOAD, PRESENT_SRC ↔ COLOR_ATTACHMENT
    private long compositeRenderPass = VK_NULL_HANDLE;

    // One framebuffer per swapchain image
    private long[] compositeFramebuffers;

    // Graphics pipeline for fullscreen AO overlay
    private long compositePipeline = VK_NULL_HANDLE;
    private long compositePipelineLayout = VK_NULL_HANDLE;

    // Descriptor resources (binds the SSAO sampler)
    private long compositeDescSetLayout = VK_NULL_HANDLE;
    private long compositeDescPool = VK_NULL_HANDLE;
    private long compositeDescSet = VK_NULL_HANDLE;

    // Shader modules
    private long vertShaderModule = VK_NULL_HANDLE;
    private long fragShaderModule = VK_NULL_HANDLE;

    // State
    private boolean initialized = false;
    private int width, height;

    /**
     * Initializes the compositor with swapchain and SSAO information.
     *
     * @param device       Vulkan logical device
     * @param compiler     SPIR-V compiler
     * @param colorFormat  Swapchain image format
     * @param imageViews   Swapchain image views (one per image)
     * @param width        Framebuffer width
     * @param height       Framebuffer height
     * @param ssaoImageView RT output image view (R8 SSAO)
     * @param ssaoSampler   Sampler for the SSAO image
     */
    public void initialize(VkDevice device, SPIRVCompiler compiler,
                           int colorFormat, long[] imageViews,
                           int width, int height,
                           long ssaoImageView, long ssaoSampler) {
        this.device = device;
        this.width = width;
        this.height = height;

        try {
            createRenderPass(colorFormat);
            createFramebuffers(imageViews, width, height);
            createDescriptorResources(ssaoImageView, ssaoSampler);
            createPipeline(compiler);

            initialized = true;
            LOGGER.info("SSAO compositor initialized: {}x{}, {} framebuffers",
                    width, height, imageViews.length);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize SSAO compositor", e);
            destroy();
        }
    }

    /**
     * Composites the SSAO mask onto the swapchain image.
     *
     * <p>Called after SSAO compute dispatch, while the command buffer is still
     * recording (outside any render pass). The swapchain image is in
     * PRESENT_SRC_KHR layout from the main render pass.</p>
     *
     * @param commandBuffer Active command buffer
     * @param imageIndex    Current swapchain image index
     */
    public void composite(VkCommandBuffer commandBuffer, int imageIndex) {
        if (!initialized || compositePipeline == VK_NULL_HANDLE) return;
        if (imageIndex < 0 || compositeFramebuffers == null
                || imageIndex >= compositeFramebuffers.length) return;

        try (MemoryStack stack = stackPush()) {
            // Begin compositing render pass — LOAD_OP_LOAD preserves rasterized scene
            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
            // No actual clear — LOAD_OP_LOAD means we keep existing content

            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(compositeRenderPass)
                    .framebuffer(compositeFramebuffers[imageIndex])
                    .pClearValues(clearValues);
            rpBegin.renderArea().offset().set(0, 0);
            rpBegin.renderArea().extent().set(width, height);

            vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Set viewport (standard Y-down for fullscreen compositing)
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0)
                    .width(width).height(height)
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetScissor(commandBuffer, 0, scissor);

            // Bind pipeline and descriptor set
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    compositePipelineLayout, 0,
                    stack.longs(compositeDescSet), null);

            // Draw fullscreen triangle (3 vertices, no VBO)
            vkCmdDraw(commandBuffer, 3, 1, 0, 0);

            vkCmdEndRenderPass(commandBuffer);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Resource Creation
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a render pass that loads the existing swapchain content and writes
     * the composited result. No depth attachment needed.
     */
    private void createRenderPass(int colorFormat) {
        try (MemoryStack stack = stackPush()) {
            // Single color attachment — load existing, store result
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(1, stack);
            attachments.get(0)
                    .format(colorFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)      // preserve rasterized scene
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)  // from main render pass
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);   // ready for present

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            // Dependencies: compute shader → color attachment, color attachment → present
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);

            // Incoming: compute writes must be visible before we read/write color
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            // Outgoing: color writes must complete before present
            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                    .dstAccessMask(0);

            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependencies);

            LongBuffer pRenderPass = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateRenderPass(device, rpInfo, null, pRenderPass),
                    "Failed to create composite render pass");
            compositeRenderPass = pRenderPass.get(0);
        }
    }

    /**
     * Creates framebuffers for the compositing pass — one per swapchain image,
     * color attachment only (no depth).
     */
    private void createFramebuffers(long[] imageViews, int width, int height) {
        destroyFramebuffers();

        compositeFramebuffers = new long[imageViews.length];
        for (int i = 0; i < imageViews.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer attachments = stack.longs(imageViews[i]);

                VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(compositeRenderPass)
                        .pAttachments(attachments)
                        .width(width)
                        .height(height)
                        .layers(1);

                LongBuffer pFb = stack.longs(VK_NULL_HANDLE);
                checkResult(vkCreateFramebuffer(device, fbInfo, null, pFb),
                        "Failed to create composite framebuffer");
                compositeFramebuffers[i] = pFb.get(0);
            }
        }
    }

    /**
     * Creates descriptor set layout, pool, and set for binding the SSAO image.
     */
    private void createDescriptorResources(long ssaoImageView, long ssaoSampler) {
        try (MemoryStack stack = stackPush()) {
            // Layout: binding 0 = combined image sampler (SSAO texture)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout),
                    "Failed to create composite descriptor set layout");
            compositeDescSetLayout = pLayout.get(0);

            // Pool: 1 combined image sampler
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateDescriptorPool(device, poolInfo, null, pPool),
                    "Failed to create composite descriptor pool");
            compositeDescPool = pPool.get(0);

            // Allocate descriptor set
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(compositeDescPool)
                    .pSetLayouts(stack.longs(compositeDescSetLayout));

            LongBuffer pSet = stack.longs(VK_NULL_HANDLE);
            checkResult(vkAllocateDescriptorSets(device, allocInfo, pSet),
                    "Failed to allocate composite descriptor set");
            compositeDescSet = pSet.get(0);

            // Write the SSAO sampler binding
            updateSSAOBinding(ssaoImageView, ssaoSampler);
        }
    }

    /**
     * Updates the descriptor set to point to the current SSAO output image.
     * Called on init and after resize.
     */
    public void updateSSAOBinding(long ssaoImageView, long ssaoSampler) {
        if (compositeDescSet == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(ssaoImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL) // SSAO image stays in GENERAL
                    .sampler(ssaoSampler);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(compositeDescSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /**
     * Creates the fullscreen triangle graphics pipeline with multiplicative blending.
     */
    private void createPipeline(SPIRVCompiler compiler) {
        // Compile shaders
        ByteBuffer vertSpirv = compiler.compileVertex(COMPOSITE_VERTEX_SHADER, "ssao_composite.vert");
        ByteBuffer fragSpirv = compiler.compileFragment(COMPOSITE_FRAGMENT_SHADER, "ssao_composite.frag");
        if (vertSpirv == null || fragSpirv == null) {
            LOGGER.error("Failed to compile SSAO compositor shaders");
            if (vertSpirv != null) memFree(vertSpirv);
            if (fragSpirv != null) memFree(fragSpirv);
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // Create shader modules
            VkShaderModuleCreateInfo vertModuleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(vertSpirv);
            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateShaderModule(device, vertModuleInfo, null, pModule),
                    "Failed to create composite vertex shader module");
            vertShaderModule = pModule.get(0);

            VkShaderModuleCreateInfo fragModuleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(fragSpirv);
            pModule.put(0, VK_NULL_HANDLE);
            checkResult(vkCreateShaderModule(device, fragModuleInfo, null, pModule),
                    "Failed to create composite fragment shader module");
            fragShaderModule = pModule.get(0);

            // Pipeline layout
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(compositeDescSetLayout));

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreatePipelineLayout(device, layoutInfo, null, pLayout),
                    "Failed to create composite pipeline layout");
            compositePipelineLayout = pLayout.get(0);

            // Shader stages
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertShaderModule)
                    .pName(stack.UTF8("main"));
            stages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragShaderModule)
                    .pName(stack.UTF8("main"));

            // Vertex input: no vertex buffers (procedural triangle)
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            // Input assembly: triangle list
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            // Viewport/scissor: dynamic state
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            // Rasterization: fill, no cull (fullscreen triangle)
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);

            // Multisample: none
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // Color blend: MULTIPLICATIVE blending
            // result.rgb = src.rgb * dst.rgb  (AO value darkens scene)
            // result.a   = dst.a              (preserve original alpha)
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_DST_COLOR)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment);

            // No depth/stencil
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(false)
                    .depthWriteEnable(false)
                    .stencilTestEnable(false);

            // Dynamic state: viewport + scissor
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            // Create pipeline
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pColorBlendState(colorBlend)
                    .pDepthStencilState(depthStencil)
                    .pDynamicState(dynamicState)
                    .layout(compositePipelineLayout)
                    .renderPass(compositeRenderPass)
                    .subpass(0);

            LongBuffer pPipeline = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "Failed to create SSAO composite pipeline");
            compositePipeline = pPipeline.get(0);
        }

        memFree(vertSpirv);
        memFree(fragSpirv);
        LOGGER.info("SSAO composite pipeline created successfully");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Resize / Recreate
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Rebuilds framebuffers after swapchain recreation.
     * Also updates the SSAO binding if the RT output image was resized.
     */
    public void onSwapchainRecreated(long[] imageViews, int newWidth, int newHeight,
                                      long ssaoImageView, long ssaoSampler) {
        if (!initialized) return;

        this.width = newWidth;
        this.height = newHeight;

        createFramebuffers(imageViews, newWidth, newHeight);
        updateSSAOBinding(ssaoImageView, ssaoSampler);

        LOGGER.info("SSAO compositor resized: {}x{}", newWidth, newHeight);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    private void destroyFramebuffers() {
        if (compositeFramebuffers == null) return;
        for (long fb : compositeFramebuffers) {
            if (fb != VK_NULL_HANDLE) vkDestroyFramebuffer(device, fb, null);
        }
        compositeFramebuffers = null;
    }

    public void destroy() {
        destroyFramebuffers();

        if (compositePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, compositePipeline, null);
            compositePipeline = VK_NULL_HANDLE;
        }
        if (vertShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, vertShaderModule, null);
            vertShaderModule = VK_NULL_HANDLE;
        }
        if (fragShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, fragShaderModule, null);
            fragShaderModule = VK_NULL_HANDLE;
        }
        if (compositePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, compositePipelineLayout, null);
            compositePipelineLayout = VK_NULL_HANDLE;
        }
        if (compositeDescPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, compositeDescPool, null);
            compositeDescPool = VK_NULL_HANDLE;
            compositeDescSet = VK_NULL_HANDLE; // freed with pool
        }
        if (compositeDescSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, compositeDescSetLayout, null);
            compositeDescSetLayout = VK_NULL_HANDLE;
        }
        if (compositeRenderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, compositeRenderPass, null);
            compositeRenderPass = VK_NULL_HANDLE;
        }

        initialized = false;
        LOGGER.info("SSAO compositor destroyed");
    }

    public boolean isInitialized() { return initialized; }

    // ═══════════════════════════════════════════════════════════════════
    //  Shaders
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fullscreen triangle vertex shader — procedurally generates 3 vertices
     * that cover the entire screen. No vertex buffer needed.
     *
     * <pre>
     *   gl_VertexIndex 0 → (-1, -1)  bottom-left
     *   gl_VertexIndex 1 → ( 3, -1)  bottom-right (oversized)
     *   gl_VertexIndex 2 → (-1,  3)  top-left (oversized)
     * </pre>
     */
    private static final String COMPOSITE_VERTEX_SHADER = """
            #version 450

            layout(location = 0) out vec2 fragUV;

            void main() {
                // Standard fullscreen triangle trick:
                // 3 vertices cover [-1,1] × [-1,1] with a single oversized triangle
                vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                fragUV = pos;
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * Compositing fragment shader — samples the SSAO texture and outputs the AO
     * value as RGB. Multiplicative blending in the pipeline handles the darkening.
     */
    private static final String COMPOSITE_FRAGMENT_SHADER = """
            #version 450

            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;

            layout(set = 0, binding = 0) uniform sampler2D ssaoTexture;

            void main() {
                // Sample SSAO value (R8 texture, bilinear upscale from half-res)
                float ao = texture(ssaoTexture, fragUV).r;

                // Output AO as RGB — multiplicative blending applies: scene * ao
                // AO = 1.0 means no occlusion (no darkening)
                // AO = 0.0 means fully occluded (black)
                outColor = vec4(ao, ao, ao, 1.0);
            }
            """;
}
