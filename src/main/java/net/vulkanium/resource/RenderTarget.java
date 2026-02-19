package net.vulkanium.resource;

import net.vulkanium.core.VulkaniumInstance;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A single color or depth render target (image + view + sampler).
 *
 * <p>Maps to OptiFine/Iris shader pack concepts:</p>
 * <ul>
 *   <li>colortex0–colortex7 (G-buffer color targets)</li>
 *   <li>depthtex0–depthtex2 (depth buffers)</li>
 *   <li>shadowtex0–shadowtex1 (shadow map depth)</li>
 *   <li>shadowcolor0–shadowcolor1 (shadow map color)</li>
 * </ul>
 */
public class RenderTarget {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RenderTarget");

    private VkDevice device;
    private VulkaniumMemory memory;

    private String name;
    private int width;
    private int height;
    private int format;
    private int mipLevels;

    private long image;
    private long imageView;
    private long sampler;
    private long allocation;

    private boolean isDepth;

    /**
     * Creates a render target.
     *
     * @param name    Human-readable name (e.g., "colortex0", "shadowtex0")
     * @param width   Width in pixels
     * @param height  Height in pixels
     * @param format  Vulkan format
     * @param isDepth Whether this is a depth target
     */
    public void initialize(VkDevice device, VulkaniumMemory memory,
                           String name, int width, int height, int format, boolean isDepth) {
        this.device = device;
        this.memory = memory;
        this.name = name;
        this.width = width;
        this.height = height;
        this.format = format;
        this.mipLevels = 1;
        this.isDepth = isDepth;

        createImage();
        createImageView();
        createSampler(true, true); // linear, clamp-to-edge

        LOGGER.debug("Render target '{}' created: {}x{}, format {}", name, width, height, format);
    }

    private void createImage() {
        int aspectMask = isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        int usage = isDepth
                ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                    | VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

        VulkaniumMemory.ImageAllocation alloc = memory.createImage(
                width, height, mipLevels,
                format,
                VK_IMAGE_TILING_OPTIMAL,
                usage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        this.image = alloc.image();
        this.allocation = alloc.allocation();
    }

    private void createImageView() {
        try (MemoryStack stack = stackPush()) {
            int aspectMask = isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);

            viewInfo.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateImageView(device, viewInfo, null, pView);
            checkResult(result, "Failed to create image view for " + name);

            imageView = pView.get(0);
        }
    }

    private void createSampler(boolean linear, boolean clampToEdge) {
        try (MemoryStack stack = stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            int addressMode = clampToEdge ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(linear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(addressMode)
                    .addressModeV(addressMode)
                    .addressModeW(addressMode)
                    .mipLodBias(0.0f)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .minLod(0.0f)
                    .maxLod((float) mipLevels)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                    .unnormalizedCoordinates(false);

            LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateSampler(device, samplerInfo, null, pSampler);
            checkResult(result, "Failed to create sampler for " + name);

            sampler = pSampler.get(0);
        }
    }

    /**
     * Recreates at new dimensions (e.g., window resize).
     */
    public void resize(int newWidth, int newHeight) {
        destroyResources();
        this.width = newWidth;
        this.height = newHeight;
        createImage();
        createImageView();
        createSampler(true, true);
    }

    // === Cleanup ===

    private void destroyResources() {
        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, sampler, null);
            sampler = VK_NULL_HANDLE;
        }
        if (imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, imageView, null);
            imageView = VK_NULL_HANDLE;
        }
        if (image != VK_NULL_HANDLE) {
            memory.freeImageImmediate(new VulkaniumMemory.ImageAllocation(
                    image, allocation, width, height, format, mipLevels));
            image = VK_NULL_HANDLE;
        }
    }

    public void destroy() {
        destroyResources();
        device = null;
    }

    // === Getters ===

    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getFormat() { return format; }
    public long getImage() { return image; }
    public long getImageView() { return imageView; }
    public long getSampler() { return sampler; }
    public boolean isDepth() { return isDepth; }
}
