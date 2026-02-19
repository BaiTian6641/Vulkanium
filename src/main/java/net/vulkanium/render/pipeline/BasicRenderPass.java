package net.vulkanium.render.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Creates a Vulkan render pass for rendering GUI/world into swapchain images.
 *
 * <p>Single-subpass render pass with:
 * <ul>
 *   <li>Color attachment (swapchain format) — CLEAR on load, STORE, final layout PRESENT_SRC</li>
 *   <li>Depth attachment (D32_SFLOAT or similar) — CLEAR on load, DONT_CARE on store</li>
 * </ul>
 */
public class BasicRenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RenderPass");

    private VkDevice device;
    private long renderPass = VK_NULL_HANDLE;
    private long[] framebuffers;

    /**
     * Creates a render pass compatible with the swapchain format.
     */
    public void initialize(VkDevice device, int colorFormat, int depthFormat) {
        this.device = device;

        try (MemoryStack stack = stackPush()) {
            // 2 attachments: color + depth
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);

            // Color attachment (swapchain image)
            attachments.get(0)
                    .format(colorFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            // Depth attachment
            attachments.get(1)
                    .format(depthFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)   // MUST store — SSAO reads depth after render pass
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            // Subpass: use both attachments
            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1)
                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef)
                    .pDepthStencilAttachment(depthRef);

            // Subpass dependency: external → our subpass
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateRenderPass(device, renderPassInfo, null, pRenderPass);
            checkResult(result, "Failed to create render pass");
            this.renderPass = pRenderPass.get(0);

            LOGGER.info("Render pass created (color={}, depth={})", colorFormat, depthFormat);
        }
    }

    /**
     * Creates one framebuffer per swapchain image.
     */
    public void createFramebuffers(long[] imageViews, long depthImageView, int width, int height) {
        if (framebuffers != null) destroyFramebuffers();

        framebuffers = new long[imageViews.length];
        for (int i = 0; i < imageViews.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer attachments = stack.longs(imageViews[i], depthImageView);

                VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(attachments)
                        .width(width)
                        .height(height)
                        .layers(1);

                LongBuffer pFramebuffer = stack.longs(VK_NULL_HANDLE);
                int result = vkCreateFramebuffer(device, fbInfo, null, pFramebuffer);
                checkResult(result, "Failed to create framebuffer");
                framebuffers[i] = pFramebuffer.get(0);
            }
        }
        LOGGER.info("Created {} framebuffers ({}x{})", framebuffers.length, width, height);
    }

    /**
     * Begins the render pass in the given command buffer.
     */
    public void begin(VkCommandBuffer cmd, int imageIndex, int width, int height,
                      float clearR, float clearG, float clearB, float clearA) {
        beginInternal(cmd, imageIndex, width, height, true, clearR, clearG, clearB, clearA);
    }

    /**
     * Begins a render pass preserving existing color/depth content (no clear).
     * Used for post-processing passes that run after main world rendering.
     */
    public void beginPreserve(VkCommandBuffer cmd, int imageIndex, int width, int height) {
        beginInternal(cmd, imageIndex, width, height, false, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    private void beginInternal(VkCommandBuffer cmd, int imageIndex, int width, int height,
                               boolean clearOnBegin,
                               float clearR, float clearG, float clearB, float clearA) {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffers[imageIndex]);
            rpBegin.renderArea().offset().set(0, 0);
            rpBegin.renderArea().extent().set(width, height);

            vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Set default viewport and scissor
            // Negative height viewport (VK_KHR_maintenance1 / Vulkan 1.1+) to match
            // OpenGL's Y-up convention — MC's projection matrices expect Y-up
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(height)
                    .width(width).height(-height)
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);

                if (clearOnBegin) {
                VkClearAttachment.Buffer clearAttachments = VkClearAttachment.calloc(2, stack);
                clearAttachments.get(0)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .colorAttachment(0);
                clearAttachments.get(0).clearValue().color()
                    .float32(0, clearR)
                    .float32(1, clearG)
                    .float32(2, clearB)
                    .float32(3, clearA);

                clearAttachments.get(1)
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .colorAttachment(0);
                clearAttachments.get(1).clearValue().depthStencil().depth(1.0f).stencil(0);

                VkClearRect.Buffer clearRect = VkClearRect.calloc(1, stack);
                clearRect.rect().offset().set(0, 0);
                clearRect.rect().extent().set(width, height);
                clearRect.baseArrayLayer(0);
                clearRect.layerCount(1);

                vkCmdClearAttachments(cmd, clearAttachments, clearRect);
                }
        }
    }

    /**
     * Ends the render pass.
     */
    public void end(VkCommandBuffer cmd) {
        vkCmdEndRenderPass(cmd);
    }

    public long getRenderPass() { return renderPass; }
    public long getFramebuffer(int index) { return framebuffers[index]; }

    private void destroyFramebuffers() {
        if (framebuffers == null) return;
        for (long fb : framebuffers) {
            if (fb != VK_NULL_HANDLE) vkDestroyFramebuffer(device, fb, null);
        }
        framebuffers = null;
    }

    public void destroy() {
        destroyFramebuffers();
        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, renderPass, null);
            renderPass = VK_NULL_HANDLE;
        }
    }
}
