package net.vulkanium.render.texture;

import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumCommand;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Represents a Vulkan-backed texture (VkImage + VkImageView + VkSampler).
 *
 * <p>Each MC pseudo-GL texture ID maps to one of these. Replaces the stub
 * entries in GlStateInterceptor.textureMap.</p>
 */
public class VulkanTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Texture");

    private final int pseudoId;       // The pseudo-GL texture ID
    private int width;
    private int height;
    private int mipLevels;
    private int vkFormat;

    // Vulkan handles
    private long image = VK_NULL_HANDLE;
    private long allocation = 0;      // VMA allocation
    private long imageView = VK_NULL_HANDLE;
    private long sampler = VK_NULL_HANDLE;

    // Sampler state
    private boolean blur = false;
    private boolean clamp = false; // Default to REPEAT — matches OpenGL default (GL_REPEAT).
                                   // Tiling textures (dirt background, blocks, etc.) need REPEAT.
                                   // Textures that need CLAMP_TO_EDGE will be set explicitly via
                                   // glTexParameteri interception in GlStateInterceptor.
    private boolean mipmap = false;

    // Layout tracking
    private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    // Reference to device for view/sampler creation
    private VkDevice device;

    public VulkanTexture(int pseudoId) {
        this.pseudoId = pseudoId;
    }

    /**
     * Allocates or reallocates the VkImage for this texture. 
     * Called when texImage2D or NativeImage._upload() first provides dimensions.
     *
     * @param width      Texture width
     * @param height     Texture height
     * @param vkFormat   Vulkan format (e.g. VK_FORMAT_R8G8B8A8_UNORM)
     * @param mipLevels  Number of mip levels
     */
    public void allocate(int width, int height, int vkFormat, int mipLevels) {
        VulkaniumMemory memory = Vulkanium.getVulkanMemory();
        this.device = Vulkanium.getVulkanDevice().getLogicalDevice();

        // If already allocated with same dimensions/format, skip
        if (this.image != VK_NULL_HANDLE &&
                this.width == width && this.height == height &&
                this.vkFormat == vkFormat && this.mipLevels == mipLevels) {
            return;
        }

        // Free old resources if reallocating
        if (this.image != VK_NULL_HANDLE) {
            freeGpuResources();
        }

        this.width = width;
        this.height = height;
        this.vkFormat = vkFormat;
        this.mipLevels = mipLevels;
        this.currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        // Create VkImage via VMA
        VulkaniumMemory.ImageAllocation img = memory.createImage(
                width, height, mipLevels,
                vkFormat,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        this.image = img.image();
        this.allocation = img.allocation();

        // Create image view
        createImageView();

        // Create sampler with current filter state
        createSampler();

        LOGGER.debug("Texture {} allocated: {}x{} fmt={} mips={}", pseudoId, width, height, vkFormat, mipLevels);
    }

    /**
     * Uploads pixel data to this texture via a staging buffer + one-shot command buffer.
     *
     * @param level     Mip level
     * @param xOffset   X offset in texture
     * @param yOffset   Y offset in texture
     * @param uploadW   Width of region to upload
     * @param uploadH   Height of region to upload
     * @param pixels    Native pointer to pixel data
     * @param rowStride Row stride in pixels (not bytes)
     * @param pixelSize Bytes per pixel
     */
    public void upload(int level, int xOffset, int yOffset,
                       int uploadW, int uploadH,
                       long pixels, int rowStride, int pixelSize) {
        if (image == VK_NULL_HANDLE) {
            LOGGER.warn("Texture {} upload called but not allocated", pseudoId);
            return;
        }
        if (pixels == 0) {
            LOGGER.debug("Texture {} upload with null pixels (clearing?)", pseudoId);
            return;
        }

        trackUpload(uploadW, uploadH, pixelSize);

        VulkaniumMemory memory = Vulkanium.getVulkanMemory();
        VulkaniumCommand command = Vulkanium.getVulkanCommand();

        // Calculate data size: uploadW * uploadH * pixelSize
        long dataSize = (long) uploadW * uploadH * pixelSize;
        if (dataSize <= 0) return;

        // Create a staging buffer and copy pixel data
        VulkaniumMemory.BufferAllocation staging = memory.createStagingBuffer(dataSize);
        long mapped = memory.map(staging.allocation());

        // Copy row by row if rowStride != uploadW (partial upload from larger image)
        if (rowStride != uploadW && rowStride > 0) {
            long srcRowBytes = (long) uploadW * pixelSize;
            long srcStride = (long) rowStride * pixelSize;
            for (int y = 0; y < uploadH; y++) {
                MemoryUtil.memCopy(
                        pixels + y * srcStride,
                        mapped + y * srcRowBytes,
                        srcRowBytes);
            }
        } else {
            // Contiguous copy
            MemoryUtil.memCopy(pixels, mapped, dataSize);
        }
        memory.unmap(staging.allocation());

        // Record upload commands
        VkCommandBuffer cmd = command.beginSingleTimeCommand();

        // Transition to TRANSFER_DST
        VulkaniumCommand.transitionImageLayout(cmd, image,
                currentLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Copy buffer to image
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            region.bufferRowLength(0);   // tightly packed in staging
            region.bufferImageHeight(0);
            region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.imageOffset().set(xOffset, yOffset, 0);
            region.imageExtent().set(uploadW, uploadH, 1);

            vkCmdCopyBufferToImage(cmd, staging.buffer(), image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }

        // Transition to SHADER_READ_ONLY
        VulkaniumCommand.transitionImageLayout(cmd, image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
        currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        command.endSingleTimeCommand(cmd);
        memory.freeBufferImmediate(staging);
    }

    /**
     * Uploads pixel data from a ByteBuffer (used by texImage2D with IntBuffer/etc).
     */
    public void upload(int level, int xOffset, int yOffset,
                       int uploadW, int uploadH,
                       long pixelPtr, int pixelSize) {
        upload(level, xOffset, yOffset, uploadW, uploadH, pixelPtr, uploadW, pixelSize);
    }

    /**
     * Updates the sampler filter/clamp state.
     */
    public void updateFilter(boolean blur, boolean clamp, boolean mipmap) {
        if (this.blur == blur && this.clamp == clamp && this.mipmap == mipmap) return;
        this.blur = blur;
        this.clamp = clamp;
        this.mipmap = mipmap;
        recreateSampler();
    }

    /**
     * Sets the texture wrapping mode (clamp-to-edge vs repeat).
     * Called when MC sets GL_TEXTURE_WRAP_S/T.
     */
    public void setClamp(boolean clamp) {
        if (this.clamp == clamp) return;
        this.clamp = clamp;
        recreateSampler();
    }

    private void recreateSampler() {
        if (device != null && image != VK_NULL_HANDLE) {
            if (sampler != VK_NULL_HANDLE) {
                vkDestroySampler(device, sampler, null);
                sampler = VK_NULL_HANDLE;
            }
            createSampler();
        }
    }

    private void createImageView() {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(vkFormat);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateImageView(device, viewInfo, null, pView),
                    "Failed to create texture image view");
            imageView = pView.get(0);
        }
    }

    private void createSampler() {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(blur ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .minFilter(blur ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .addressModeU(clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .mipmapMode(mipmap ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .maxLod(mipmap ? (float) mipLevels : 0.0f)
                    .minLod(0.0f);

            LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateSampler(device, samplerInfo, null, pSampler),
                    "Failed to create texture sampler");
            sampler = pSampler.get(0);
        }
    }

    /**
     * Frees all GPU resources associated with this texture.
     */
    public void freeGpuResources() {
        if (device == null) return;

        if (sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, sampler, null);
            sampler = VK_NULL_HANDLE;
        }
        if (imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, imageView, null);
            imageView = VK_NULL_HANDLE;
        }
        if (image != VK_NULL_HANDLE && allocation != 0) {
            VulkaniumMemory memory = Vulkanium.getVulkanMemory();
            if (memory != null) {
                memory.freeImageImmediate(
                        new VulkaniumMemory.ImageAllocation(image, allocation, width, height, vkFormat, mipLevels));
            }
            image = VK_NULL_HANDLE;
            allocation = 0;
        }
    }

    // ─── Upload tracking ───────────────────────────────────

    private int uploadCount = 0;
    private long totalUploadBytes = 0;

    /** Called by upload() to track upload statistics. */
    private void trackUpload(int uploadW, int uploadH, int pixelSize) {
        uploadCount++;
        totalUploadBytes += (long) uploadW * uploadH * pixelSize;
    }

    // ─── Getters ───────────────────────────────────────────

    public int getPseudoId() { return pseudoId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getMipLevels() { return mipLevels; }
    public int getVkFormat() { return vkFormat; }
    public long getImage() { return image; }
    public long getImageView() { return imageView; }
    public long getSampler() { return sampler; }
    public boolean isAllocated() { return image != VK_NULL_HANDLE; }
    public boolean isBlur() { return blur; }
    public boolean isClamp() { return clamp; }
    public boolean isMipmap() { return mipmap; }
    public int getUploadCount() { return uploadCount; }
    public long getTotalUploadBytes() { return totalUploadBytes; }
}
