package net.vulkanium.render.composite;

import net.vulkanium.render.gbuffer.GBufferTargets;
import net.vulkanium.render.gbuffer.MRTRenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Final pass: tonemaps and outputs the rendered scene to the swapchain image.
 *
 * <p>This pass reads from the G-buffer (typically colortex0 after composites)
 * and writes the final image to the swapchain framebuffer. It corresponds to
 * Iris's {@code final.fsh} / {@code final.vsh} shader pair.</p>
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Bind final shader (custom pack shader or built-in blit)</li>
 *   <li>Sample from colortex0 (post-composite result)</li>
 *   <li>Apply pack-specified tonemapping / post-processing</li>
 *   <li>Render to swapchain framebuffer (single RGBA8 attachment)</li>
 * </ol>
 *
 * <h3>Swapchain Output</h3>
 * <p>The render pass writes directly to the swapchain image view.
 * The image layout is transitioned from UNDEFINED → COLOR_ATTACHMENT_OPTIMAL → PRESENT_SRC_KHR.</p>
 */
public class FinalPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/FinalPass");

    // ── Shader Modules ──
    private long vertexModule = VK_NULL_HANDLE;
    private long fragmentModule = VK_NULL_HANDLE;

    // ── Vulkan Objects ──
    private long pipeline = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long renderPass = VK_NULL_HANDLE;

    // ── Sources ──
    private final GBufferTargets gBuffer;
    private boolean hasCustomShader = false;

    /** Index of the color buffer to read from (default: 0 = colortex0) */
    private int sourceBuffer = 0;

    /** Whether to write depth to swapchain depth (usually false) */
    private boolean writeDepth = false;

    /** Gamma correction toggle (pack may handle gamma in shader) */
    private boolean applyGamma = true;

    /** Dithering for banding reduction on low-precision targets */
    private boolean applyDither = false;

    public FinalPass(GBufferTargets gBuffer) {
        this.gBuffer = gBuffer;
    }

    // ── Configuration (called during shader pack loading) ──

    public void setCustomShader(long vertexModule, long fragmentModule) {
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.hasCustomShader = true;
    }

    public void setSourceBuffer(int index) {
        this.sourceBuffer = index;
    }

    public void setWriteDepth(boolean writeDepth) {
        this.writeDepth = writeDepth;
    }

    public void setApplyGamma(boolean applyGamma) {
        this.applyGamma = applyGamma;
    }

    public void setApplyDither(boolean applyDither) {
        this.applyDither = applyDither;
    }

    /**
     * Creates the final pass render pass and pipeline for the given swapchain format.
     *
     * @param device         Logical device handle
     * @param swapchainFormat VkFormat of the swapchain images (typically VK_FORMAT_B8G8R8A8_SRGB)
     * @param depthFormat    VkFormat for depth, or 0 if no depth
     */
    public void create(long device, int swapchainFormat, int depthFormat) {
        createRenderPass(device, swapchainFormat, depthFormat);
        createPipeline(device);
        LOGGER.info("Final pass created (custom shader: {}, source: colortex{})",
                hasCustomShader, sourceBuffer);
    }

    private void createRenderPass(long device, int swapchainFormat, int depthFormat) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            int attachmentCount = writeDepth && depthFormat != 0 ? 2 : 1;
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);

            // Color attachment (swapchain image)
            attachments.get(0)
                    .format(swapchainFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            // Depth attachment (optional)
            if (attachmentCount > 1) {
                attachments.get(1)
                        .format(depthFormat)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            if (attachmentCount > 1) {
                VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(1)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                subpass.pDepthStencilAttachment(depthRef);
            }

            // Subpass dependency: external → subpass 0
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo rpCI = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);
            int result = vkCreateRenderPass(vkDevice, rpCI, null, pRenderPass);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create final render pass: VkResult " + result);
            }
            this.renderPass = pRenderPass.get(0);
        }
    }

    private void createPipeline(long device) {
        if (vertexModule == VK_NULL_HANDLE || fragmentModule == VK_NULL_HANDLE) {
            LOGGER.warn("Final pass shader modules not set — pipeline creation deferred");
            return;
        }

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            // Shader stages
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertexModule)
                    .pName(stack.UTF8("main"));
            stages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragmentModule)
                    .pName(stack.UTF8("main"));

            // Empty vertex input (fullscreen triangle from gl_VertexIndex)
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            // Dynamic viewport/scissor
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(false)
                    .depthWriteEnable(false);

            var dynamicStates = stack.mallocInt(2);
            dynamicStates.put(0, VK_DYNAMIC_STATE_VIEWPORT);
            dynamicStates.put(1, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates);

            // Pipeline layout (2 descriptor set slots: UBO + samplers)
            VkPipelineLayoutCreateInfo layoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            // Descriptor set layouts would be set here if available

            LongBuffer pLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(vkDevice, layoutCI, null, pLayout);
            this.pipelineLayout = pLayout.get(0);

            // Graphics pipeline
            VkGraphicsPipelineCreateInfo.Buffer pipelineCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateGraphicsPipelines(vkDevice, VK_NULL_HANDLE, pipelineCI, null, pPipeline);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create final pass pipeline: VkResult " + result);
            }
            this.pipeline = pPipeline.get(0);
        }
    }

    /**
     * Records the final pass into the command buffer.
     *
     * @param commandBuffer     Active command buffer
     * @param swapchainFB       Framebuffer wrapping the current swapchain image
     * @param uniformDescSet    UBO descriptor set (set 0)
     * @param samplerDescSet    Sampler descriptor set (set 1)
     * @param width             Swapchain width
     * @param height            Swapchain height
     */
    public void render(long commandBuffer, long swapchainFB,
                       long uniformDescSet, long samplerDescSet,
                       int width, int height) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            // Begin render pass
            VkClearValue.Buffer clearValues = VkClearValue.calloc(writeDepth ? 2 : 1, stack);
            clearValues.get(0).color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);
            if (writeDepth) {
                clearValues.get(1).depthStencil().depth(1.0f).stencil(0);
            }

            VkRenderPassBeginInfo rpBeginInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(swapchainFB)
                    .pClearValues(clearValues);
            rpBeginInfo.renderArea().offset().set(0, 0);
            rpBeginInfo.renderArea().extent().set(width, height);

            vkCmdBeginRenderPass(cmd, rpBeginInfo, VK_SUBPASS_CONTENTS_INLINE);

            // Set viewport and scissor
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f)
                    .width(width).height(height)
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);

            // Bind pipeline
            if (pipeline != VK_NULL_HANDLE) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            }

            // Bind descriptor sets
            if (pipelineLayout != VK_NULL_HANDLE && (uniformDescSet != 0 || samplerDescSet != 0)) {
                LongBuffer pSets = stack.mallocLong(2);
                pSets.put(0, uniformDescSet);
                pSets.put(1, samplerDescSet);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineLayout, 0, pSets, null);
            }

            // Draw fullscreen triangle
            vkCmdDraw(cmd, 3, 1, 0, 0);

            // End render pass
            vkCmdEndRenderPass(cmd);
        }
    }

    /**
     * Creates a framebuffer for a swapchain image view.
     *
     * @param device         Logical device
     * @param swapchainView  Image view of the current swapchain image
     * @param depthView      Depth image view, or VK_NULL_HANDLE
     * @param width          Swapchain width
     * @param height         Swapchain height
     * @return VkFramebuffer handle
     */
    public long createSwapchainFramebuffer(long device, long swapchainView,
                                           long depthView, int width, int height) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            int attachmentCount = (writeDepth && depthView != VK_NULL_HANDLE) ? 2 : 1;
            LongBuffer pAttachments = stack.mallocLong(attachmentCount);
            pAttachments.put(0, swapchainView);
            if (attachmentCount > 1) {
                pAttachments.put(1, depthView);
            }

            VkFramebufferCreateInfo fbCI = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(pAttachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFB = stack.mallocLong(1);
            int result = vkCreateFramebuffer(vkDevice, fbCI, null, pFB);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create swapchain framebuffer: VkResult " + result);
            }
            return pFB.get(0);
        }
    }

    private long getSourceImageView() {
        // Read from the correct ping-pong target based on flip state
        net.vulkanium.resource.RenderTarget target = gBuffer.getReadTarget(sourceBuffer);
        return target != null ? target.getImageView() : 0;
    }

    // ── Getters ──

    public boolean hasCustomShader() { return hasCustomShader; }
    public int getSourceBuffer() { return sourceBuffer; }
    public long getRenderPass() { return renderPass; }
    public long getPipeline() { return pipeline; }

    // ── Lifecycle ──

    public void destroy(long device) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(vkDevice, pipeline, null);
            pipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(vkDevice, renderPass, null);
            renderPass = VK_NULL_HANDLE;
        }
        hasCustomShader = false;
        LOGGER.debug("Final pass destroyed");
    }
}
