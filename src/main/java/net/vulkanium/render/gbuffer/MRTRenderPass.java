package net.vulkanium.render.gbuffer;

import net.vulkanium.core.VulkaniumInstance;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Arrays;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/**
 * Vulkan render pass for MRT (Multiple Render Target) rendering.
 *
 * <p>Creates a VkRenderPass that supports N color attachments + 1 depth attachment.
 * This is the Vulkan equivalent of OpenGL's framebuffer with multiple draw buffers
 * ({@code glDrawBuffers}).</p>
 *
 * <h3>Attachment Layout</h3>
 * <pre>
 *   Attachment 0..N-1: Color attachments (formats from RenderTargetSettings)
 *   Attachment N:      Depth attachment (D32_SFLOAT)
 * </pre>
 *
 * <h3>Subpass Configuration</h3>
 * <p>Single subpass with all color attachments as output references. The render pass
 * handles clear/load/store operations per the pack directives:</p>
 * <ul>
 *   <li><b>Clear:</b> Targets with {@code colortexNClear = true} use LOAD_OP_CLEAR</li>
 *   <li><b>Preserve:</b> Targets with clear disabled use LOAD_OP_LOAD</li>
 *   <li><b>Store:</b> All targets use STORE_OP_STORE</li>
 * </ul>
 *
 * <h3>Layout Transitions</h3>
 * <p>Color targets transition from UNDEFINED/COLOR_ATTACHMENT_OPTIMAL to
 * SHADER_READ_ONLY_OPTIMAL at the end of the pass (ready for composite sampling).
 * Depth transitions similarly for depth texture sampling.</p>
 */
public class MRTRenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/MRTPass");

    /** Render pass type for different pipeline stages */
    public enum PassType {
        /** G-buffer fill pass — writes to color + depth, clears as configured */
        GBUFFER_FILL,
        /** Composite pass — writes to color only (fullscreen quad), no depth */
        COMPOSITE,
        /** Shadow pass — depth-only (optionally with shadow color attachments) */
        SHADOW,
        /** Final pass — single color output to swapchain image */
        FINAL
    }

    // ── State ──
    private VkDevice device;
    private long renderPass = VK_NULL_HANDLE;
    private long framebuffer = VK_NULL_HANDLE;

    private PassType passType;
    private int[] colorFormats;
    private int depthFormat;
    private boolean hasDepth;
    private int width;
    private int height;

    /** Per-attachment clear behavior */
    private boolean[] clearColors;
    private boolean clearDepth;

    /** Per-attachment load ops (derived from clear settings) */
    private int[] colorLoadOps;

    /**
     * Creates an MRT render pass with the specified configuration.
     *
     * @param device       Vulkan logical device
     * @param passType     Type of render pass
     * @param colorFormats VkFormat for each color attachment (can be empty for depth-only)
     * @param depthFormat  VkFormat for depth (0 if no depth)
     * @param clearColors  Per-attachment clear flags (same length as colorFormats)
     * @param clearDepth   Whether to clear depth
     */
    public void create(VkDevice device, PassType passType,
                       int[] colorFormats, int depthFormat,
                       boolean[] clearColors, boolean clearDepth) {
        this.device = device;
        this.passType = passType;
        this.colorFormats = colorFormats;
        this.depthFormat = depthFormat;
        this.hasDepth = depthFormat != 0;
        this.clearColors = clearColors;
        this.clearDepth = clearDepth;

        // Compute load ops
        this.colorLoadOps = new int[colorFormats.length];
        for (int i = 0; i < colorFormats.length; i++) {
            colorLoadOps[i] = clearColors[i] ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
        }

        createRenderPass();
    }

    /**
     * Creates a framebuffer for this render pass with the given image views.
     *
     * @param colorViews Color attachment VkImageView handles
     * @param depthView  Depth attachment VkImageView (0 if no depth)
     * @param width      Framebuffer width
     * @param height     Framebuffer height
     */
    public void createFramebuffer(long[] colorViews, long depthView, int width, int height) {
        this.width = width;
        this.height = height;

        if (framebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device, framebuffer, null);
        }

        try (MemoryStack stack = stackPush()) {
            int attachmentCount = colorViews.length + (hasDepth ? 1 : 0);
            LongBuffer attachments = stack.mallocLong(attachmentCount);
            for (long view : colorViews) {
                attachments.put(view);
            }
            if (hasDepth) {
                attachments.put(depthView);
            }
            attachments.flip();

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFramebuffer = stack.mallocLong(1);
            checkResult(vkCreateFramebuffer(device, fbInfo, null, pFramebuffer));
            framebuffer = pFramebuffer.get(0);
        }
    }

    /**
     * Records the begin command for this render pass.
     *
     * @param commandBuffer Active VkCommandBuffer
     * @param clearValues   Clear values for each attachment (color + depth)
     */
    public void begin(long commandBuffer, VkClearValue.Buffer clearValues) {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo beginInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer);

            beginInfo.renderArea().offset().set(0, 0);
            beginInfo.renderArea().extent().set(width, height);
            beginInfo.pClearValues(clearValues);

            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, device);
            vkCmdBeginRenderPass(cmd, beginInfo, VK_SUBPASS_CONTENTS_INLINE);
        }
    }

    /**
     * Records the end command for this render pass.
     */
    public void end(long commandBuffer) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, device);
        vkCmdEndRenderPass(cmd);
    }

    // ── Internal ──

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            int totalAttachments = colorFormats.length + (hasDepth ? 1 : 0);

            VkAttachmentDescription.Buffer attachments =
                    VkAttachmentDescription.calloc(totalAttachments, stack);

            // Determine final layout based on pass type
            int colorFinalLayout = passType == PassType.FINAL
                    ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                    : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

            // Color attachments
            for (int i = 0; i < colorFormats.length; i++) {
                attachments.get(i)
                        .format(colorFormats[i])
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(colorLoadOps[i])
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(colorLoadOps[i] == VK_ATTACHMENT_LOAD_OP_LOAD
                                ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                : VK_IMAGE_LAYOUT_UNDEFINED)
                        .finalLayout(colorFinalLayout);
            }

            // Depth attachment
            if (hasDepth) {
                int depthIdx = colorFormats.length;
                attachments.get(depthIdx)
                        .format(depthFormat)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(clearDepth
                                ? VK_IMAGE_LAYOUT_UNDEFINED
                                : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            }

            // Color attachment references for subpass
            VkAttachmentReference.Buffer colorRefs =
                    VkAttachmentReference.calloc(colorFormats.length, stack);
            for (int i = 0; i < colorFormats.length; i++) {
                colorRefs.get(i)
                        .attachment(i)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }

            // Depth reference
            VkAttachmentReference depthRef = null;
            if (hasDepth) {
                depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(colorFormats.length)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            // Single subpass
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colorFormats.length)
                    .pColorAttachments(colorRefs)
                    .pDepthStencilAttachment(depthRef);

            // Subpass dependency — external → subpass 0
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);

            // Before: ensure previous reads/writes complete
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                                  VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                                  VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                   VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);

            // After: ensure writes visible for subsequent reads
            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                                  VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                                   VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);

            // Create render pass
            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependencies);

            LongBuffer pRenderPass = stack.mallocLong(1);
            checkResult(vkCreateRenderPass(device, rpInfo, null, pRenderPass));
            renderPass = pRenderPass.get(0);

            LOGGER.debug("Created MRT render pass: {} color + {} depth, type={}",
                    colorFormats.length, hasDepth ? 1 : 0, passType);
        }
    }

    // ── Getters ──

    public long getRenderPass() { return renderPass; }
    public long getFramebuffer() { return framebuffer; }
    public PassType getPassType() { return passType; }
    public int getColorAttachmentCount() { return colorFormats.length; }
    public boolean hasDepth() { return hasDepth; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    // ── Lifecycle ──

    public void destroy() {
        if (framebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device, framebuffer, null);
            framebuffer = VK_NULL_HANDLE;
        }
        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, renderPass, null);
            renderPass = VK_NULL_HANDLE;
        }
    }
}
