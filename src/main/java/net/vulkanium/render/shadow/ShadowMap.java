package net.vulkanium.render.shadow;

import net.vulkanium.resource.RenderTarget;
import org.joml.Vector4f;
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
 * Manages all shadow map render targets: 2 depth + up to 8 color attachments.
 *
 * <p>Mirrors Iris's {@code ShadowRenderTargets} with Vulkan equivalents.
 * All targets share the same resolution (square shadow map).</p>
 *
 * <h3>Depth Targets</h3>
 * <ul>
 *   <li>{@code shadowtex0} — Main shadow depth (written by all shadow geometry)</li>
 *   <li>{@code shadowtex1} — Pre-translucent depth copy (snapshot before translucent
 *       geometry is rendered). Provides depth values unaffected by water/stained glass.</li>
 * </ul>
 *
 * <h3>Color Targets (shadowcolor0-7)</h3>
 * <p>Used by shadow shaders to write auxiliary data (e.g., shadow color for
 * stained glass tinting). Lazily allocated — only created when referenced.</p>
 *
 * <h3>Hardware Depth Filtering</h3>
 * <p>When hardware filtering is enabled, the depth sampler uses
 * {@code VK_COMPARE_OP_LESS_OR_EQUAL} with a combined image sampler that
 * performs depth comparison in hardware. This provides PCF-compatible shadow
 * sampling without shader changes.</p>
 */
public class ShadowMap {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShadowMap");

    public static final int MAX_DEPTH_TARGETS = 2;
    public static final int MAX_COLOR_TARGETS = 8;

    // ── Config ──
    private final ShadowDirectives directives;
    private int resolution;

    // ── Depth targets ──
    /** shadowtex0 — main depth, written by all geometry */
    private long mainDepthImage = VK_NULL_HANDLE;
    private long mainDepthView = VK_NULL_HANDLE;
    private long mainDepthSampler = VK_NULL_HANDLE;

    /** shadowtex1 — pre-translucent depth copy */
    private long noTranslucentsDepthImage = VK_NULL_HANDLE;
    private long noTranslucentsDepthView = VK_NULL_HANDLE;
    private long noTranslucentsDepthSampler = VK_NULL_HANDLE;

    /** Hardware depth comparison samplers (separate from regular samplers) */
    private long mainDepthHwSampler = VK_NULL_HANDLE;
    private long noTranslucentsHwSampler = VK_NULL_HANDLE;

    /** Depth format — D32_SFLOAT for precision, D24_UNORM_S8_UINT if stencil needed */
    private int depthFormat = VK_FORMAT_D32_SFLOAT;

    // ── Color targets ──
    private final long[] colorImages = new long[MAX_COLOR_TARGETS];
    private final long[] colorViews = new long[MAX_COLOR_TARGETS];
    private final long[] colorSamplers = new long[MAX_COLOR_TARGETS];
    private final boolean[] colorAllocated = new boolean[MAX_COLOR_TARGETS];
    private final int[] colorFormats = new int[MAX_COLOR_TARGETS];

    // ── Ping-pong (color targets only) ──
    private final long[] colorAltImages = new long[MAX_COLOR_TARGETS];
    private final long[] colorAltViews = new long[MAX_COLOR_TARGETS];
    private final boolean[] flipped = new boolean[MAX_COLOR_TARGETS];

    // ── VMA ──
    private final long[] depthAllocations = new long[MAX_DEPTH_TARGETS];
    private final long[] colorAllocations = new long[MAX_COLOR_TARGETS];
    private final long[] colorAltAllocations = new long[MAX_COLOR_TARGETS];

    /** Whether pre-translucent depth needs copying this frame */
    private boolean translucentDepthDirty = true;

    public ShadowMap(ShadowDirectives directives) {
        this.directives = directives;
        this.resolution = directives.getResolution();
    }

    /**
     * Allocates core depth targets. Called during shader pack initialization.
     *
     * @param device    Logical device handle
     * @param allocator VMA allocator handle
     */
    public void create(long device, long allocator) {
        createDepthTargets(device, allocator);
        LOGGER.info("Shadow map created: {}x{} (format: {})",
                resolution, resolution,
                directives.isOrthographic() ? "ortho" : "perspective fov=" + directives.getFov());
    }

    private void createDepthTargets(long device, long allocator) {
        try (MemoryStack stack = stackPush()) {
            VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
            int mips0 = directives.getDepthSettings(0).mipmap ? calculateMipLevels() : 1;
            int mips1 = directives.getDepthSettings(1).mipmap ? calculateMipLevels() : 1;

            // ── shadowtex0 — main depth ──
            mainDepthImage = createImage(stack, allocator, depthFormat, resolution, resolution, mips0,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    depthAllocations, 0);
            mainDepthView = createImageView(stack, vkDevice, mainDepthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, mips0);

            ShadowDirectives.DepthSamplingSettings ds0 = directives.getDepthSettings(0);
            mainDepthSampler = createDepthSampler(stack, vkDevice, ds0, mips0, false);
            if (ds0.isHardwareFiltering()) {
                mainDepthHwSampler = createDepthSampler(stack, vkDevice, ds0, mips0, true);
            }

            // ── shadowtex1 — pre-translucent depth copy ──
            noTranslucentsDepthImage = createImage(stack, allocator, depthFormat, resolution, resolution, mips1,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    depthAllocations, 1);
            noTranslucentsDepthView = createImageView(stack, vkDevice, noTranslucentsDepthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, mips1);

            ShadowDirectives.DepthSamplingSettings ds1 = directives.getDepthSettings(1);
            noTranslucentsDepthSampler = createDepthSampler(stack, vkDevice, ds1, mips1, false);
            if (ds1.isHardwareFiltering()) {
                noTranslucentsHwSampler = createDepthSampler(stack, vkDevice, ds1, mips1, true);
            }
        }
    }

    private long createImage(MemoryStack stack, long allocator, int format, int w, int h,
                              int mipLevels, int usage, long[] allocations, int allocIdx) {
        VkImageCreateInfo imageCI = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(mipLevels)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageCI.extent().width(w).height(h).depth(1);

        VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_GPU_ONLY);

        LongBuffer pImage = stack.mallocLong(1);
        var pAlloc = stack.mallocPointer(1);
        int result = vmaCreateImage(allocator, imageCI, allocCI, pImage, pAlloc, null);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to create shadow image: VkResult " + result);
        }
        allocations[allocIdx] = pAlloc.get(0);
        return pImage.get(0);
    }

    private long createImageView(MemoryStack stack, VkDevice device, long image,
                                  int format, int aspectMask, int mipLevels) {
        VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        ci.subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(mipLevels)
                .baseArrayLayer(0)
                .layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        int result = vkCreateImageView(device, ci, null, pView);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to create shadow image view: VkResult " + result);
        }
        return pView.get(0);
    }

    private long createDepthSampler(MemoryStack stack, VkDevice device,
                                     ShadowDirectives.DepthSamplingSettings settings,
                                     int mipLevels, boolean hwCompare) {
        int filter = settings.getVkFilter();
        VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(filter)
                .minFilter(filter)
                .mipmapMode(settings.mipmap ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                .mipLodBias(0.0f)
                .anisotropyEnable(false)
                .compareEnable(hwCompare)
                .compareOp(hwCompare ? VK_COMPARE_OP_LESS_OR_EQUAL : VK_COMPARE_OP_ALWAYS)
                .minLod(0.0f)
                .maxLod(settings.mipmap ? (float) mipLevels : 0.0f)
                .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                .unnormalizedCoordinates(false);

        LongBuffer pSampler = stack.mallocLong(1);
        int result = vkCreateSampler(device, ci, null, pSampler);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to create shadow depth sampler: VkResult " + result);
        }
        return pSampler.get(0);
    }

    /**
     * Lazily creates a shadow color target when first referenced.
     */
    public long getOrCreateColorTarget(int index, long device, long allocator) {
        if (index < 0 || index >= MAX_COLOR_TARGETS) return VK_NULL_HANDLE;
        if (colorAllocated[index]) return colorViews[index];

        ShadowDirectives.ColorSamplingSettings settings = directives.getColorSettings(index);
        colorFormats[index] = settings.format;
        int mips = settings.mipmap ? calculateMipLevels() : 1;
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

        try (MemoryStack stack = stackPush()) {
            VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

            // Primary image
            colorImages[index] = createImage(stack, allocator, settings.format, resolution, resolution,
                    mips, usage, colorAllocations, index);
            colorViews[index] = createImageView(stack, vkDevice, colorImages[index],
                    settings.format, VK_IMAGE_ASPECT_COLOR_BIT, mips);

            // Sampler
            int filter = settings.getVkFilter();
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(settings.mipmap ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(settings.mipmap ? (float) mips : 0.0f)
                    .unnormalizedCoordinates(false);
            LongBuffer pSampler = stack.mallocLong(1);
            vkCreateSampler(vkDevice, ci, null, pSampler);
            colorSamplers[index] = pSampler.get(0);

            // Alt (ping-pong) image
            colorAltImages[index] = createImage(stack, allocator, settings.format, resolution, resolution,
                    mips, usage, colorAltAllocations, index);
            colorAltViews[index] = createImageView(stack, vkDevice, colorAltImages[index],
                    settings.format, VK_IMAGE_ASPECT_COLOR_BIT, mips);
        }

        colorAllocated[index] = true;
        LOGGER.debug("Shadow color target {} created (format: {})", index, settings.format);
        return colorViews[index];
    }

    /**
     * Copies shadowtex0 → shadowtex1 (pre-translucent depth snapshot).
     *
     * <p>Called just before translucent shadow geometry is rendered. Uses
     * vkCmdCopyImage for GPU-side depth blit.</p>
     */
    public void copyPreTranslucentDepth(long commandBuffer) {
        if (!translucentDepthDirty) return;
        translucentDepthDirty = false;
        if (mainDepthImage == VK_NULL_HANDLE || noTranslucentsDepthImage == VK_NULL_HANDLE) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            // Transition mainDepth: DEPTH_STENCIL_ATTACHMENT → TRANSFER_SRC
            // Transition noTransDepth: SHADER_READ_ONLY (or UNDEFINED) → TRANSFER_DST
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(2, stack);

            barriers.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(mainDepthImage)
                    .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            barriers.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            barriers.get(1)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(noTranslucentsDepthImage)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barriers.get(1).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, barriers);

            // Copy depth image
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffset().set(0, 0, 0);
            region.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(resolution, resolution, 1);

            vkCmdCopyImage(cmd,
                    mainDepthImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    noTranslucentsDepthImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);

            // Transition back: mainDepth → DEPTH_STENCIL_ATTACHMENT, noTransDepth → SHADER_READ_ONLY
            barriers.get(0)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            barriers.get(1)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barriers);
        }
    }

    /**
     * Clears color targets that have clear=true set in directives.
     */
    public void clearColorTargets(long commandBuffer) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
                if (!colorAllocated[i]) continue;
                ShadowDirectives.ColorSamplingSettings settings = directives.getColorSettings(i);
                if (!settings.clear) continue;

                // Transition to TRANSFER_DST for clearing
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(colorImages[i])
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                        .baseArrayLayer(0).layerCount(1);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                        null, null, barrier);

                // Clear
                Vector4f cc = settings.clearColor;
                VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
                clearColor.float32(0, cc.x).float32(1, cc.y).float32(2, cc.z).float32(3, cc.w);

                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                        .baseArrayLayer(0).layerCount(1);

                vkCmdClearColorImage(cmd, colorImages[i],
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, range);

                // Transition to COLOR_ATTACHMENT for rendering
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                       .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                       .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                       .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                        null, null, barrier);
            }
        }
    }

        /**
         * Generates mipmaps for shadow targets that have mipmap=true in directives.
         */
        public void generateMipmaps(long commandBuffer) {
                VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

                if (directives.getDepthSettings(0).mipmap) {
                        int mipLevels = calculateMipLevels();
                        if (mipLevels > 1 && mainDepthImage != VK_NULL_HANDLE) {
                                generateDepthMipmaps(cmd, mainDepthImage, mipLevels);
                        }
                }

                if (directives.getDepthSettings(1).mipmap) {
                        int mipLevels = calculateMipLevels();
                        if (mipLevels > 1 && noTranslucentsDepthImage != VK_NULL_HANDLE) {
                                generateDepthMipmaps(cmd, noTranslucentsDepthImage, mipLevels);
                        }
                }

                for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
                        if (!colorAllocated[i]) continue;
                        ShadowDirectives.ColorSamplingSettings settings = directives.getColorSettings(i);
                        if (!settings.mipmap) continue;

                        int mipLevels = calculateMipLevels();
                        if (mipLevels <= 1) continue;

                        int filter = settings.isIntegerFormat() ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
                        generateColorMipmaps(cmd, colorImages[i], mipLevels, filter);
                        generateColorMipmaps(cmd, colorAltImages[i], mipLevels, filter);
                }
        }

    // ── Ping-pong ──

    public void flip(int index) {
        if (index >= 0 && index < MAX_COLOR_TARGETS && colorAllocated[index]) {
            flipped[index] = !flipped[index];
        }
    }

    public long getReadColorView(int index) {
        if (!colorAllocated[index]) return VK_NULL_HANDLE;
        return flipped[index] ? colorAltViews[index] : colorViews[index];
    }

    public long getWriteColorView(int index) {
        if (!colorAllocated[index]) return VK_NULL_HANDLE;
        return flipped[index] ? colorViews[index] : colorAltViews[index];
    }

    public boolean isFlipped(int index) {
        return index >= 0 && index < MAX_COLOR_TARGETS && flipped[index];
    }

    // ── Getters ──

    public int getResolution() { return resolution; }
    public int getDepthFormat() { return depthFormat; }
    public ShadowDirectives getDirectives() { return directives; }

    public long getMainDepthView() { return mainDepthView; }
    public long getMainDepthSampler() { return mainDepthSampler; }
    public long getMainDepthImage() { return mainDepthImage; }

    public long getNoTranslucentsDepthView() { return noTranslucentsDepthView; }
    public long getNoTranslucentsDepthSampler() { return noTranslucentsDepthSampler; }
    public long getNoTranslucentsDepthImage() { return noTranslucentsDepthImage; }

    /** Hardware comparison sampler for shadowtex0 (PCF-compatible) */
    public long getMainDepthHwSampler() { return mainDepthHwSampler; }
    /** Hardware comparison sampler for shadowtex1 (PCF-compatible) */
    public long getNoTranslucentsHwSampler() { return noTranslucentsHwSampler; }

    public long getColorView(int index) { return colorAllocated[index] ? colorViews[index] : VK_NULL_HANDLE; }
    public long getColorSampler(int index) { return colorAllocated[index] ? colorSamplers[index] : VK_NULL_HANDLE; }
    public boolean isColorAllocated(int index) { return colorAllocated[index]; }
    public int getColorFormat(int index) { return colorFormats[index]; }

    /** Mark depth as needing copy before next translucent pass */
    public void markTranslucentDepthDirty() { translucentDepthDirty = true; }

    private int calculateMipLevels() {
        return (int) Math.floor(Math.log(resolution) / Math.log(2)) + 1;
    }

    private void generateColorMipmaps(VkCommandBuffer cmd, long image, int mipLevels, int filter) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .image(image)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);

            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseArrayLayer(0)
                    .layerCount(1)
                    .levelCount(1);

            int mipWidth = resolution;
            int mipHeight = resolution;

            for (int i = 1; i < mipLevels; i++) {
                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        null,
                        null,
                        barrier);

                barrier.subresourceRange().baseMipLevel(i);
                barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        null,
                        null,
                        barrier);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i - 1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                blit.srcOffsets(0).set(0, 0, 0);
                blit.srcOffsets(1).set(mipWidth, mipHeight, 1);

                int nextWidth = Math.max(1, mipWidth / 2);
                int nextHeight = Math.max(1, mipHeight / 2);

                blit.dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i)
                        .baseArrayLayer(0)
                        .layerCount(1);
                blit.dstOffsets(0).set(0, 0, 0);
                blit.dstOffsets(1).set(nextWidth, nextHeight, 1);

                vkCmdBlitImage(cmd,
                        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit,
                        filter);

                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0,
                        null,
                        null,
                        barrier);

                mipWidth = nextWidth;
                mipHeight = nextHeight;
            }

            barrier.subresourceRange().baseMipLevel(mipLevels - 1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier);
        }
    }

    private void generateDepthMipmaps(VkCommandBuffer cmd, long image, int mipLevels) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .image(image)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);

            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseArrayLayer(0)
                    .layerCount(1)
                    .levelCount(1);

            int mipWidth = resolution;
            int mipHeight = resolution;

            for (int i = 1; i < mipLevels; i++) {
                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        null,
                        null,
                        barrier);

                barrier.subresourceRange().baseMipLevel(i);
                barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        null,
                        null,
                        barrier);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .mipLevel(i - 1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                blit.srcOffsets(0).set(0, 0, 0);
                blit.srcOffsets(1).set(mipWidth, mipHeight, 1);

                int nextWidth = Math.max(1, mipWidth / 2);
                int nextHeight = Math.max(1, mipHeight / 2);

                blit.dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .mipLevel(i)
                        .baseArrayLayer(0)
                        .layerCount(1);
                blit.dstOffsets(0).set(0, 0, 0);
                blit.dstOffsets(1).set(nextWidth, nextHeight, 1);

                vkCmdBlitImage(cmd,
                        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit,
                        VK_FILTER_NEAREST);

                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0,
                        null,
                        null,
                        barrier);

                mipWidth = nextWidth;
                mipHeight = nextHeight;
            }

            barrier.subresourceRange().baseMipLevel(mipLevels - 1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    barrier);
        }
    }

    // ── Lifecycle ──

    public void destroy(long device, long allocator) {
        // Destroy samplers
        destroySampler(device, mainDepthSampler);
        destroySampler(device, noTranslucentsDepthSampler);
        destroySampler(device, mainDepthHwSampler);
        destroySampler(device, noTranslucentsHwSampler);

        // Destroy depth views and images
        destroyImageView(device, mainDepthView);
        destroyImageView(device, noTranslucentsDepthView);
                if (mainDepthImage != VK_NULL_HANDLE && depthAllocations[0] != 0L) {
                        vmaDestroyImage(allocator, mainDepthImage, depthAllocations[0]);
                }
                if (noTranslucentsDepthImage != VK_NULL_HANDLE && depthAllocations[1] != 0L) {
                        vmaDestroyImage(allocator, noTranslucentsDepthImage, depthAllocations[1]);
                }

        // Destroy color targets
        for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
            if (!colorAllocated[i]) continue;
            destroySampler(device, colorSamplers[i]);
            destroyImageView(device, colorViews[i]);
            destroyImageView(device, colorAltViews[i]);
                        if (colorImages[i] != VK_NULL_HANDLE && colorAllocations[i] != 0L) {
                                vmaDestroyImage(allocator, colorImages[i], colorAllocations[i]);
                        }
                        if (colorAltImages[i] != VK_NULL_HANDLE && colorAltAllocations[i] != 0L) {
                                vmaDestroyImage(allocator, colorAltImages[i], colorAltAllocations[i]);
                        }
        }

        mainDepthImage = mainDepthView = mainDepthSampler = VK_NULL_HANDLE;
        noTranslucentsDepthImage = noTranslucentsDepthView = noTranslucentsDepthSampler = VK_NULL_HANDLE;
        mainDepthHwSampler = noTranslucentsHwSampler = VK_NULL_HANDLE;
        java.util.Arrays.fill(colorImages, VK_NULL_HANDLE);
        java.util.Arrays.fill(colorAltImages, VK_NULL_HANDLE);
        java.util.Arrays.fill(colorViews, VK_NULL_HANDLE);
        java.util.Arrays.fill(colorAltViews, VK_NULL_HANDLE);
        java.util.Arrays.fill(colorSamplers, VK_NULL_HANDLE);
        java.util.Arrays.fill(depthAllocations, 0L);
        java.util.Arrays.fill(colorAllocations, 0L);
        java.util.Arrays.fill(colorAltAllocations, 0L);
        java.util.Arrays.fill(colorAllocated, false);

        LOGGER.debug("Shadow map destroyed");
    }

    private void destroySampler(long device, long sampler) {
        if (sampler != VK_NULL_HANDLE) vkDestroySampler(net.vulkanium.core.VulkaniumDevice.getGlobalDevice(), sampler, null);
    }

    private void destroyImageView(long device, long view) {
        if (view != VK_NULL_HANDLE) vkDestroyImageView(net.vulkanium.core.VulkaniumDevice.getGlobalDevice(), view, null);
    }
}
