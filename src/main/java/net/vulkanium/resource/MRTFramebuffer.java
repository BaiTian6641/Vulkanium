package net.vulkanium.resource;

import net.vulkanium.core.VulkaniumInstance;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Arrays;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * MRT (Multiple Render Targets) framebuffer supporting up to 8 color attachments + depth.
 *
 * <p>This is one of the key differentiators from VulkanMod, which hard-limits to
 * 1 color attachment. MRT framebuffers enable:</p>
 * <ul>
 *   <li>G-buffer rendering (albedo, normal, depth, light, specular, etc.)</li>
 *   <li>Deferred shading / composite passes</li>
 *   <li>OptiFine shader pack compatibility (colortex0–7)</li>
 * </ul>
 */
public class MRTFramebuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/MRTFramebuffer");
    public static final int MAX_COLOR_ATTACHMENTS = 8;

    private VkDevice device;
    private VulkaniumMemory memory;

    private int width;
    private int height;

    // Color attachments
    private int colorAttachmentCount;
    private int[] colorFormats;
    private long[] colorImages;
    private long[] colorImageViews;
    private long[] colorAllocations;

    // Depth attachment
    private int depthFormat;
    private long depthImage;
    private long depthImageView;
    private long depthAllocation;

    // Render pass and framebuffer
    private long renderPass;
    private long framebuffer;

    /**
     * Creates the MRT framebuffer with the specified color attachment formats.
     *
     * @param device      Vulkan logical device
     * @param memory      Memory manager
     * @param width       Framebuffer width
     * @param height      Framebuffer height
     * @param depthFormat Depth format (e.g., VK_FORMAT_D32_SFLOAT)
     * @param colorFormats Array of formats for each color attachment (e.g., VK_FORMAT_R8G8B8A8_UNORM)
     */
    public void initialize(VkDevice device, VulkaniumMemory memory,
                           int width, int height, int depthFormat, int... colorFormats) {
        this.device = device;
        this.memory = memory;
        this.width = width;
        this.height = height;
        this.depthFormat = depthFormat;
        this.colorAttachmentCount = Math.min(colorFormats.length, MAX_COLOR_ATTACHMENTS);
        this.colorFormats = Arrays.copyOf(colorFormats, colorAttachmentCount);

        createColorAttachments();
        createDepthAttachment();
        createRenderPass();
        createFramebuffer();

        LOGGER.info("MRT framebuffer created: {}x{}, {} color attachments + depth",
                width, height, colorAttachmentCount);
    }

    private void createColorAttachments() {
        colorImages = new long[colorAttachmentCount];
        colorImageViews = new long[colorAttachmentCount];
        colorAllocations = new long[colorAttachmentCount];

        for (int i = 0; i < colorAttachmentCount; i++) {
            VulkaniumMemory.ImageAllocation alloc = memory.createImage(
                    width, height, 1,
                    colorFormats[i],
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            colorImages[i] = alloc.image();
            colorAllocations[i] = alloc.allocation();
            colorImageViews[i] = createImageView(alloc.image(), colorFormats[i], VK_IMAGE_ASPECT_COLOR_BIT);
        }
    }

    private void createDepthAttachment() {
        VulkaniumMemory.ImageAllocation alloc = memory.createImage(
                width, height, 1,
                depthFormat,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        depthImage = alloc.image();
        depthAllocation = alloc.allocation();
        depthImageView = createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT);
    }

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            int totalAttachments = colorAttachmentCount + 1; // +1 for depth

            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(totalAttachments, stack);

            // Color attachments
            VkAttachmentReference.Buffer colorRefs = VkAttachmentReference.calloc(colorAttachmentCount, stack);
            for (int i = 0; i < colorAttachmentCount; i++) {
                attachments.get(i)
                        .format(colorFormats[i])
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

                colorRefs.get(i)
                        .attachment(i)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }

            // Depth attachment (last)
            int depthIndex = colorAttachmentCount;
            attachments.get(depthIndex)
                    .format(depthFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);

            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(depthIndex)
                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            // Single subpass with all color outputs + depth
            VkSubpassDescription.Buffer subpasses = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colorAttachmentCount)
                    .pColorAttachments(colorRefs)
                    .pDepthStencilAttachment(depthRef);

            // Subpass dependencies for external sync
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);

            // Begin dependency: wait for previous frame's color attachment writes
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

            // End dependency: make color outputs available for fragment shader reads
            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpasses)
                    .pDependencies(dependencies);

            LongBuffer pRenderPass = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateRenderPass(device, renderPassInfo, null, pRenderPass);
            checkResult(result, "Failed to create MRT render pass");

            renderPass = pRenderPass.get(0);
        }
    }

    private void createFramebuffer() {
        try (MemoryStack stack = stackPush()) {
            int attachmentCount = colorAttachmentCount + 1;
            LongBuffer attachments = stack.mallocLong(attachmentCount);

            for (int i = 0; i < colorAttachmentCount; i++) {
                attachments.put(i, colorImageViews[i]);
            }
            attachments.put(colorAttachmentCount, depthImageView);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFramebuffer = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer);
            checkResult(result, "Failed to create MRT framebuffer");

            framebuffer = pFramebuffer.get(0);
        }
    }

    private long createImageView(long image, int format, int aspectMask) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);

            viewInfo.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateImageView(device, viewInfo, null, pView);
            checkResult(result, "Failed to create image view");

            return pView.get(0);
        }
    }

    /**
     * Begins the MRT render pass with clear values for all attachments.
     */
    public void beginRenderPass(VkCommandBuffer cmd, VkClearValue.Buffer clearValues) {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
                    .pClearValues(clearValues);

            renderPassInfo.renderArea().offset().set(0, 0);
            renderPassInfo.renderArea().extent().set(width, height);

            vkCmdBeginRenderPass(cmd, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        }
    }

    /**
     * Ends the MRT render pass.
     */
    public void endRenderPass(VkCommandBuffer cmd) {
        vkCmdEndRenderPass(cmd);
    }

    /**
     * Recreates all resources at a new size (e.g., window resize).
     */
    public void resize(int newWidth, int newHeight) {
        destroyResources();

        this.width = newWidth;
        this.height = newHeight;

        createColorAttachments();
        createDepthAttachment();
        createFramebuffer(); // render pass can be reused

        LOGGER.info("MRT framebuffer resized to {}x{}", newWidth, newHeight);
    }

    // === Cleanup ===

    public void destroy() {
        destroyResources();

        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, renderPass, null);
            renderPass = VK_NULL_HANDLE;
        }

        device = null;
    }

    private void destroyResources() {
        if (device == null) return;

        if (framebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device, framebuffer, null);
            framebuffer = VK_NULL_HANDLE;
        }

        // Color attachments
        if (colorImageViews != null) {
            for (int i = 0; i < colorAttachmentCount; i++) {
                if (colorImageViews[i] != VK_NULL_HANDLE) {
                    vkDestroyImageView(device, colorImageViews[i], null);
                }
                if (colorImages[i] != VK_NULL_HANDLE) {
                    memory.freeImageImmediate(new VulkaniumMemory.ImageAllocation(
                            colorImages[i], colorAllocations[i], width, height, colorFormats[i], 1));
                }
            }
        }

        // Depth
        if (depthImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, depthImageView, null);
            depthImageView = VK_NULL_HANDLE;
        }
        if (depthImage != VK_NULL_HANDLE) {
            memory.freeImageImmediate(new VulkaniumMemory.ImageAllocation(
                    depthImage, depthAllocation, width, height, depthFormat, 1));
            depthImage = VK_NULL_HANDLE;
        }
    }

    // === Getters ===

    public long getRenderPass() { return renderPass; }
    public long getFramebuffer() { return framebuffer; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getColorAttachmentCount() { return colorAttachmentCount; }
    public long getColorImageView(int index) { return colorImageViews[index]; }
    public long getColorImage(int index) { return colorImages[index]; }
    public long getDepthImageView() { return depthImageView; }
    public long getDepthImage() { return depthImage; }
    public int getDepthFormat() { return depthFormat; }
}
