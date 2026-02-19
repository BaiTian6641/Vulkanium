package net.vulkanium.render.gbuffer;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.resource.RenderTarget;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Complete G-buffer render target set for deferred rendering.
 *
 * <p>Manages up to 16 color targets (colortex0–15) and 3 depth targets
 * (depthtex0–2), with per-target ping-pong (main/alt texture pair) for
 * composite pass read-while-write patterns.</p>
 *
 * <h3>Buffer Layout (Typical Shader Pack)</h3>
 * <pre>
 *   colortex0 — Albedo (RGBA8)
 *   colortex1 — Normals (RGBA16F)
 *   colortex2 — Specular / PBR (RGBA16F)
 *   colortex3 — Material ID + Emission (RGBA8)
 *   colortex4 — Light map data (R32F)
 *   colortex5 — TAA velocity (RG16F)
 *   colortex6 — Custom (pack-specific)
 *   colortex7 — Custom (pack-specific)
 *
 *   depthtex0 — Scene depth (D32F, live — updated during rendering)
 *   depthtex1 — Depth copy after opaque (for translucent depth comparison)
 *   depthtex2 — Depth copy after translucent (for hand depth comparison)
 * </pre>
 *
 * <h3>Ping-Pong Mechanism</h3>
 * <p>Each color target has two Vulkan images: main and alt. Composite passes
 * read from one and write to the other. After each pass, affected buffers
 * are "flipped" — the alt becomes the read source. This avoids read-write
 * hazards without explicit barriers per attachment.</p>
 *
 * <h3>Lazy Allocation</h3>
 * <p>Targets are allocated on first use. If a shader pack only uses colortex0–3,
 * colortex4–15 are never allocated, saving significant VRAM.</p>
 */
public class GBufferTargets {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GBuffer");

    /** Maximum color targets (matching Iris extended limit) */
    public static final int MAX_COLOR = RenderTargetSettings.MAX_COLOR_TARGETS;
    public static final int MAX_DEPTH = 3;

    // ── State ──
    private VkDevice device;
    private VulkaniumMemory memory;
    private RenderTargetSettings settings;

    private int width;
    private int height;

    // Color targets: main + alt (ping-pong pair)
    private final RenderTarget[] mainColorTargets = new RenderTarget[MAX_COLOR];
    private final RenderTarget[] altColorTargets = new RenderTarget[MAX_COLOR];

    // Depth targets
    private RenderTarget depthTarget0; // Live scene depth
    private RenderTarget depthTarget1; // Copy at opaque complete
    private RenderTarget depthTarget2; // Copy at translucent complete

    // Ping-pong flip state — true means "alt" is the current read source
    private final boolean[] flipped = new boolean[MAX_COLOR];

    // Track which targets have been created
    private final boolean[] colorCreated = new boolean[MAX_COLOR];
    private boolean depthCreated = false;

    /**
     * Initializes the G-buffer with the given dimensions and pack settings.
     */
    public void initialize(VkDevice device, VulkaniumMemory memory,
                           int width, int height, RenderTargetSettings settings) {
        this.device = device;
        this.memory = memory;
        this.width = (int) (width * settings.getTextureScale());
        this.height = (int) (height * settings.getTextureScale());
        this.settings = settings;

        // Create depth targets immediately (always needed)
        createDepthTargets();

        // Pre-create any color targets that are marked as used
        for (int i = 0; i < MAX_COLOR; i++) {
            if (settings.getColorSettings(i).isUsed()) {
                getOrCreateColorTarget(i);
            }
        }

        LOGGER.info("G-buffer initialized: {}x{} (scale: {}x), {} color targets used",
                this.width, this.height, settings.getTextureScale(),
                settings.getUsedColorTargetCount());
    }

    /**
     * Gets or lazily creates a color target by index.
     * Creates both main and alt textures for ping-pong.
     */
    public RenderTarget getOrCreateColorTarget(int index) {
        if (index < 0 || index >= MAX_COLOR) {
            throw new IndexOutOfBoundsException("Color target index: " + index);
        }

        if (!colorCreated[index]) {
            RenderTargetSettings.BufferSettings bs = settings.getColorSettings(index);

            mainColorTargets[index] = new RenderTarget();
            mainColorTargets[index].initialize(device, memory,
                    "colortex" + index + "_main", width, height, bs.getVkFormat(), false);

            altColorTargets[index] = new RenderTarget();
            altColorTargets[index].initialize(device, memory,
                    "colortex" + index + "_alt", width, height, bs.getVkFormat(), false);

            colorCreated[index] = true;
            LOGGER.debug("Created color target pair: colortex{} (format: {})", index, bs.getVkFormat());
        }

        return flipped[index] ? altColorTargets[index] : mainColorTargets[index];
    }

    /**
     * Returns the read source for a color target (respects ping-pong flip state).
     */
    public RenderTarget getReadTarget(int index) {
        if (!colorCreated[index]) return null;
        return flipped[index] ? altColorTargets[index] : mainColorTargets[index];
    }

    /**
     * Returns the write destination for a color target (opposite of read).
     */
    public RenderTarget getWriteTarget(int index) {
        if (!colorCreated[index]) getOrCreateColorTarget(index);
        return flipped[index] ? mainColorTargets[index] : altColorTargets[index];
    }

    /**
     * Flips a buffer after a composite pass writes to it.
     * Next reads from this buffer will see the newly written data.
     */
    public void flipBuffer(int index) {
        if (index >= 0 && index < MAX_COLOR) {
            flipped[index] = !flipped[index];
        }
    }

    /**
     * Flips all buffers specified in the draw buffer list.
     */
    public void flipBuffers(int[] drawBuffers) {
        for (int index : drawBuffers) {
            flipBuffer(index);
        }
    }

    /**
     * Snapshots the current flip state (used for framebuffer creation).
     */
    public boolean[] snapshotFlipState() {
        return Arrays.copyOf(flipped, flipped.length);
    }

    /**
     * Resets all flip states to false (main = read source). Called at frame end.
     */
    public void resetFlipState() {
        Arrays.fill(flipped, false);
    }

    // ── Depth Targets ──

    private void createDepthTargets() {
        int depthFormat = settings.getDepthFormat();

        depthTarget0 = new RenderTarget();
        depthTarget0.initialize(device, memory, "depthtex0", width, height, depthFormat, true);

        depthTarget1 = new RenderTarget();
        depthTarget1.initialize(device, memory, "depthtex1", width, height, depthFormat, true);

        depthTarget2 = new RenderTarget();
        depthTarget2.initialize(device, memory, "depthtex2", width, height, depthFormat, true);

        depthCreated = true;
    }

    public RenderTarget getDepthTarget(int index) {
        return switch (index) {
            case 0 -> depthTarget0;
            case 1 -> depthTarget1;
            case 2 -> depthTarget2;
            default -> throw new IndexOutOfBoundsException("Depth target index: " + index);
        };
    }

    /**
     * Copies current depth (depthtex0) to depthtex1.
     * Called after opaque terrain rendering, before translucent.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void copyPreTranslucentDepth(long commandBuffer) {
        copyDepth(commandBuffer, depthTarget0, depthTarget1);
    }

    /**
     * Copies current depth (depthtex0) to depthtex2.
     * Called after translucent rendering, before hand rendering.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void copyPreHandDepth(long commandBuffer) {
        copyDepth(commandBuffer, depthTarget0, depthTarget2);
    }

    // ── Clear ──

    /**
     * Records clear commands for all used targets that have clear enabled.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void clearTargets(long commandBuffer) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        for (int i = 0; i < MAX_COLOR; i++) {
            if (!colorCreated[i]) continue;
            RenderTargetSettings.BufferSettings bs = settings.getColorSettings(i);
            if (!bs.shouldClear()) continue;

            float[] cc = bs.getClearColor();
            clearColorTarget(cmd, mainColorTargets[i].getImage(), cc);
            clearColorTarget(cmd, altColorTargets[i].getImage(), cc);
        }

        clearDepthTarget(cmd, depthTarget0.getImage(), VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        clearDepthTarget(cmd, depthTarget1.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        clearDepthTarget(cmd, depthTarget2.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    // ── Resize ──

    /**
     * Resizes all allocated targets. Called on window resize.
     */
    public void resize(int newWidth, int newHeight) {
        int scaledWidth = (int) (newWidth * settings.getTextureScale());
        int scaledHeight = (int) (newHeight * settings.getTextureScale());

        if (scaledWidth == this.width && scaledHeight == this.height) return;

        LOGGER.info("Resizing G-buffer: {}x{} → {}x{}", this.width, this.height, scaledWidth, scaledHeight);
        this.width = scaledWidth;
        this.height = scaledHeight;

        // Destroy and recreate all allocated targets
        destroyAllTargets();
        createDepthTargets();

        for (int i = 0; i < MAX_COLOR; i++) {
            if (settings.getColorSettings(i).isUsed()) {
                colorCreated[i] = false;
                getOrCreateColorTarget(i);
            }
        }
    }

    // ── Getters ──

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public RenderTargetSettings getSettings() { return settings; }

    /**
     * Returns the VkImage handle for the read-side of a color target.
     */
    public long getReadTargetImage(int index) {
        RenderTarget target = getReadTarget(index);
        return target != null ? target.getImage() : 0;
    }

    /**
     * Returns the mip level count for a color target.
     */
    public int getMipLevels(int index) {
        if (index < 0 || index >= MAX_COLOR) {
            throw new IndexOutOfBoundsException("Color target index: " + index);
        }

        RenderTargetSettings.BufferSettings bs = settings.getColorSettings(index);
        if (!bs.isMipmapEnabled()) {
            return 1;
        }

        int maxDim = Math.max(width, height);
        int levels = 1;
        while (maxDim > 1) {
            maxDim >>= 1;
            levels++;
        }
        return levels;
    }

    /**
     * Collects VkImageView handles for all write targets specified by drawBuffers.
     * Used to construct the framebuffer for a render pass.
     */
    public long[] getWriteImageViews(int[] drawBuffers) {
        long[] views = new long[drawBuffers.length];
        for (int i = 0; i < drawBuffers.length; i++) {
            RenderTarget target = getWriteTarget(drawBuffers[i]);
            views[i] = target.getImageView();
        }
        return views;
    }

    /**
     * Collects VkImageView handles + sampler handles for all read targets.
     * Used to populate descriptor sets for composite pass sampling.
     */
    public long[] getReadImageViews(int[] samplerIndices) {
        long[] views = new long[samplerIndices.length];
        for (int i = 0; i < samplerIndices.length; i++) {
            RenderTarget target = getReadTarget(samplerIndices[i]);
            views[i] = target != null ? target.getImageView() : 0;
        }
        return views;
    }

    /**
     * Total VRAM used by all allocated G-buffer targets (approximate).
     */
    public long estimateVRAMUsage() {
        long total = 0;
        for (int i = 0; i < MAX_COLOR; i++) {
            if (!colorCreated[i]) continue;
            int bytesPerPixel = estimateBytesPerPixel(settings.getColorSettings(i).getVkFormat());
            total += (long) width * height * bytesPerPixel * 2; // main + alt
        }
        // Depth targets: D32F = 4 bytes/pixel, 3 targets
        total += (long) width * height * 4 * MAX_DEPTH;
        return total;
    }

    // ── Lifecycle ──

    private void destroyAllTargets() {
        for (int i = 0; i < MAX_COLOR; i++) {
            if (mainColorTargets[i] != null) {
                mainColorTargets[i].destroy();
                mainColorTargets[i] = null;
            }
            if (altColorTargets[i] != null) {
                altColorTargets[i].destroy();
                altColorTargets[i] = null;
            }
            colorCreated[i] = false;
        }

        if (depthTarget0 != null) { depthTarget0.destroy(); depthTarget0 = null; }
        if (depthTarget1 != null) { depthTarget1.destroy(); depthTarget1 = null; }
        if (depthTarget2 != null) { depthTarget2.destroy(); depthTarget2 = null; }
        depthCreated = false;
    }

    public void destroy() {
        destroyAllTargets();
        resetFlipState();
        LOGGER.info("G-buffer destroyed");
    }

    // ── Utility ──

    private static int estimateBytesPerPixel(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_R8_UNORM, VK_FORMAT_R8_SNORM, VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT -> 1;
            case VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8_SINT,
                 VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16_UNORM, VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT,
                 VK_FORMAT_D16_UNORM -> 2;
            case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SNORM, VK_FORMAT_R8G8B8A8_UINT,
                 VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16_UINT,
                 VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT,
                 VK_FORMAT_B10G11R11_UFLOAT_PACK32,
                 VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT -> 4;
            case VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_UNORM,
                 VK_FORMAT_R16G16B16A16_UINT,
                 VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32_UINT -> 8;
            case VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_UINT -> 16;
            default -> 4;
        };
    }

            private void copyDepth(long commandBuffer, RenderTarget src, RenderTarget dst) {
            if (!depthCreated || src == null || dst == null) return;

            VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(2, stack);

                barriers.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(src.getImage())
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
                    .image(dst.getImage())
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barriers.get(1).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

                vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null, null, barriers);

                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffset().set(0, 0, 0);
                region.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstOffset().set(0, 0, 0);
                region.extent().set(width, height, 1);

                vkCmdCopyImage(cmd,
                    src.getImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.getImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);

                barriers.get(0)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                barriers.get(1)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null, null, barriers);
            }
            }

            private static void clearColorTarget(VkCommandBuffer cmd, long image, float[] clearColor) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(1);

                vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null, null, barrier);

                VkClearColorValue clearValue = VkClearColorValue.calloc(stack)
                        .float32(0, clearColor.length > 0 ? clearColor[0] : 0.0f)
                        .float32(1, clearColor.length > 1 ? clearColor[1] : 0.0f)
                        .float32(2, clearColor.length > 2 ? clearColor[2] : 0.0f)
                        .float32(3, clearColor.length > 3 ? clearColor[3] : 1.0f);

                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(1);

                vkCmdClearColorImage(cmd, image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearValue,
                    range);

                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

                vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    0,
                    null, null, barrier);
            }
            }

            private static void clearDepthTarget(VkCommandBuffer cmd, long image, int finalLayout) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

                vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null, null, barrier);

                VkClearDepthStencilValue clearValue = VkClearDepthStencilValue.calloc(stack)
                    .depth(1.0f)
                    .stencil(0);

                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

                vkCmdClearDepthStencilImage(cmd,
                    image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearValue,
                    range);

                int dstStage = finalLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                    ? (VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                int dstAccess = finalLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                    ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                    : VK_ACCESS_SHADER_READ_BIT;

                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(finalLayout)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(dstAccess);

                vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstStage,
                    0,
                    null, null, barrier);
            }
            }
}
