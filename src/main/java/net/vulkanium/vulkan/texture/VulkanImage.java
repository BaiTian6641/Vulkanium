package net.vulkanium.vulkan.texture;

import net.vulkanium.vulkan.memory.VulkanBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan image wrapper. Replaces OpenGL textures with VkImage + VkImageView.
 */
public class VulkanImage {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Image");

    private long image;
    private long imageView;
    private long allocation;

    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int layerCount;
    private final int format;
    private final int imageType;
    private final int usage;

    private int currentLayout;

    private VulkanImage(Builder builder) {
        this.width = builder.width;
        this.height = builder.height;
        this.depth = builder.depth;
        this.mipLevels = builder.mipLevels;
        this.layerCount = builder.layerCount;
        this.format = builder.format;
        this.imageType = builder.imageType;
        this.usage = builder.usage;
        this.currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    }

    /**
     * Allocate the VkImage via VMA and create the default VkImageView.
     */
    public void create() {
        long allocator = VulkanBuffer.getAllocator();
        long device = VulkanBuffer.getDevice();

        try (MemoryStack stack = stackPush()) {
            // 1. Create image
            VkImageCreateInfo imageCI = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(imageType)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(layerCount)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageCI.extent().width(width).height(height).depth(depth);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pImage = stack.mallocLong(1);
            var pAllocation = stack.mallocPointer(1);

            int result = vmaCreateImage(allocator, imageCI, allocCI, pImage, pAllocation, null);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create VulkanImage %dx%d: VkResult %d"
                        .formatted(width, height, result));
            }
            this.image = pImage.get(0);
            this.allocation = pAllocation.get(0);

            // 2. Create image view
            int aspectMask = ImageUtil.isDepthFormat(format)
                    ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
            int viewType = layerCount == 6 ? VK_IMAGE_VIEW_TYPE_CUBE
                    : (layerCount > 1 ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D);

            VkImageViewCreateInfo viewCI = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(viewType)
                    .format(format);
            viewCI.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(layerCount);

            LongBuffer pView = stack.mallocLong(1);
            VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
            result = vkCreateImageView(vkDevice, viewCI, null, pView);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create image view: VkResult " + result);
            }
            this.imageView = pView.get(0);
        }

        LOGGER.debug("Created VulkanImage {}x{} format={} mips={}", width, height, format, mipLevels);
    }

    /**
     * Transition image layout using a pipeline barrier.
     */
    public void transitionLayout(long commandBuffer, int newLayout) {
        if (currentLayout == newLayout) return;

        try (MemoryStack stack = stackPush()) {
            int srcAccess, dstAccess, srcStage, dstStage;

            // Determine access masks and pipeline stages based on transition type
            switch (currentLayout) {
                case VK_IMAGE_LAYOUT_UNDEFINED -> {
                    srcAccess = 0;
                    srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                }
                case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                    srcAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                }
                case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                    srcAccess = VK_ACCESS_TRANSFER_READ_BIT;
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                }
                case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                    srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                    srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                }
                case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    srcAccess = VK_ACCESS_SHADER_READ_BIT;
                    srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                }
                case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> {
                    srcAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                    srcStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                }
                default -> {
                    srcAccess = 0;
                    srcStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                }
            }

            switch (newLayout) {
                case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                    dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                }
                case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                    dstAccess = VK_ACCESS_TRANSFER_READ_BIT;
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                }
                case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    dstAccess = VK_ACCESS_SHADER_READ_BIT;
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                }
                case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                    dstAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                    dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                }
                case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> {
                    dstAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                              | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                    dstStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                }
                case LAYOUT_PRESENT_SRC_KHR -> {
                    dstAccess = 0;
                    dstStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
                }
                default -> {
                    dstAccess = 0;
                    dstStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                }
            }

            int aspectMask = ImageUtil.isDepthFormat(format)
                    ? VK_IMAGE_ASPECT_DEPTH_BIT
                    : VK_IMAGE_ASPECT_COLOR_BIT;
            if (ImageUtil.hasStencil(format)) {
                aspectMask |= VK_IMAGE_ASPECT_STENCIL_BIT;
            }

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(currentLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            barrier.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(layerCount);

            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                    net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0,
                    null, null, barrier);
        }

        this.currentLayout = newLayout;
    }

    /**
     * Upload pixel data from a staging buffer region to this image.
     */
    public void uploadFromStaging(long commandBuffer, long stagingBuffer,
                                   long stagingOffset, int uploadWidth, int uploadHeight,
                                   int mipLevel, int layer) {
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(stagingOffset)
                    .bufferRowLength(0) // tightly packed
                    .bufferImageHeight(0);
            region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(mipLevel)
                    .baseArrayLayer(layer)
                    .layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(uploadWidth, uploadHeight, 1);

            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                    net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
            vkCmdCopyBufferToImage(cmd, stagingBuffer, image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    /**
     * Generate mipmaps using vkCmdBlitImage.
     */
    public void generateMipmaps(long commandBuffer) {
        if (mipLevels <= 1) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .image(image)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(layerCount);

            int mipWidth = width;
            int mipHeight = height;

            for (int i = 1; i < mipLevels; i++) {
                // Transition mip i-1 to TRANSFER_SRC
                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                       .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                       .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                       .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                        null, null, barrier);

                // Blit from mip i-1 to mip i
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0).set(0, 0, 0);
                blit.srcOffsets(1).set(mipWidth, mipHeight, 1);
                blit.srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i - 1)
                        .baseArrayLayer(0)
                        .layerCount(layerCount);

                int nextWidth = Math.max(1, mipWidth / 2);
                int nextHeight = Math.max(1, mipHeight / 2);

                blit.dstOffsets(0).set(0, 0, 0);
                blit.dstOffsets(1).set(nextWidth, nextHeight, 1);
                blit.dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i)
                        .baseArrayLayer(0)
                        .layerCount(layerCount);

                vkCmdBlitImage(cmd,
                        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit, VK_FILTER_LINEAR);

                // Transition mip i-1 to SHADER_READ_ONLY
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                       .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                       .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                       .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                        null, null, barrier);

                mipWidth = nextWidth;
                mipHeight = nextHeight;
            }

            // Transition last mip level to SHADER_READ_ONLY
            barrier.subresourceRange().baseMipLevel(mipLevels - 1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                   .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                   .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                   .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barrier);
        }

        this.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    /** Destroy the image, view, and free VMA allocation */
    public void destroy() {
        long allocator = VulkanBuffer.getAllocator();
        long device = VulkanBuffer.getDevice();
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        if (imageView != 0) {
            vkDestroyImageView(vkDevice, imageView, null);
            imageView = 0;
        }
        if (image != 0) {
            vmaDestroyImage(allocator, image, allocation);
            image = 0;
            allocation = 0;
        }
    }

    // ─── Getters ───────────────────────────────────────────────────────

    public long getImage() { return image; }
    public long getImageView() { return imageView; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getMipLevels() { return mipLevels; }
    public int getFormat() { return format; }
    public int getCurrentLayout() { return currentLayout; }

    // ─── Builder ───────────────────────────────────────────────────────

    public static Builder builder(int width, int height) {
        return new Builder(width, height);
    }

    public static class Builder {
        private final int width, height;
        private int depth = 1;
        private int mipLevels = 1;
        private int layerCount = 1;
        private int format = 37;     // VK_FORMAT_R8G8B8A8_SRGB
        private int imageType = 1;   // VK_IMAGE_TYPE_2D
        private int usage = 0x06;    // TRANSFER_DST | SAMPLED

        private Builder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public Builder depth(int depth) { this.depth = depth; this.imageType = 2; return this; }
        public Builder mipLevels(int levels) { this.mipLevels = levels; return this; }
        public Builder arrayLayers(int layers) { this.layerCount = layers; return this; }
        public Builder format(int vkFormat) { this.format = vkFormat; return this; }
        public Builder usage(int vkUsageFlags) { this.usage = vkUsageFlags; return this; }
        public Builder colorAttachment() { this.usage |= 0x10; return this; } // COLOR_ATTACHMENT_BIT
        public Builder depthAttachment() { this.usage |= 0x20; return this; } // DEPTH_STENCIL_ATTACHMENT_BIT
        public Builder transferSrc() { this.usage |= 0x01; return this; }     // TRANSFER_SRC_BIT
        public Builder storage() { this.usage |= 0x08; return this; }         // STORAGE_BIT

        /** Auto-calculate mip levels from dimensions */
        public Builder fullMipChain() {
            this.mipLevels = (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2)) + 1;
            this.usage |= 0x01; // Need TRANSFER_SRC for blit-based mip generation
            return this;
        }

        public VulkanImage build() {
            return new VulkanImage(this);
        }
    }

    // ─── Format Constants ──────────────────────────────────────────────

    public static final int FORMAT_R8_UNORM = 9;
    public static final int FORMAT_R8G8_UNORM = 16;
    public static final int FORMAT_R8G8B8A8_UNORM = 37;
    public static final int FORMAT_R8G8B8A8_SRGB = 43;
    public static final int FORMAT_B8G8R8A8_SRGB = 50;
    public static final int FORMAT_R16_SFLOAT = 76;
    public static final int FORMAT_R16G16_SFLOAT = 83;
    public static final int FORMAT_R16G16B16A16_SFLOAT = 97;
    public static final int FORMAT_R32_SFLOAT = 100;
    public static final int FORMAT_R32G32B32A32_SFLOAT = 109;
    public static final int FORMAT_D32_SFLOAT = 126;
    public static final int FORMAT_D24_UNORM_S8_UINT = 129;
    public static final int FORMAT_D32_SFLOAT_S8_UINT = 130;

    // ─── Layout Constants ──────────────────────────────────────────────

    public static final int LAYOUT_UNDEFINED = 0;
    public static final int LAYOUT_GENERAL = 1;
    public static final int LAYOUT_COLOR_ATTACHMENT_OPTIMAL = 2;
    public static final int LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL = 3;
    public static final int LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;
    public static final int LAYOUT_TRANSFER_SRC_OPTIMAL = 6;
    public static final int LAYOUT_TRANSFER_DST_OPTIMAL = 7;
    public static final int LAYOUT_PRESENT_SRC_KHR = 1000001002;
}
