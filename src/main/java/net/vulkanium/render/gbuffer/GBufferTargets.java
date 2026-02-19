package net.vulkanium.render.gbuffer;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.resource.RenderTarget;
import org.lwjgl.vulkan.VkDevice;
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
        // TODO: Record image blit/copy from depthTarget0 → depthTarget1
        // Requires layout transitions: DEPTH_ATTACHMENT → TRANSFER_SRC, then
        // SHADER_READ_ONLY → TRANSFER_DST, copy, then transition back.
    }

    /**
     * Copies current depth (depthtex0) to depthtex2.
     * Called after translucent rendering, before hand rendering.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void copyPreHandDepth(long commandBuffer) {
        // TODO: Record image blit/copy from depthTarget0 → depthTarget2
    }

    // ── Clear ──

    /**
     * Records clear commands for all used targets that have clear enabled.
     *
     * @param commandBuffer Active VkCommandBuffer
     */
    public void clearTargets(long commandBuffer) {
        for (int i = 0; i < MAX_COLOR; i++) {
            if (!colorCreated[i]) continue;
            RenderTargetSettings.BufferSettings bs = settings.getColorSettings(i);
            if (!bs.shouldClear()) continue;

            // TODO: Record vkCmdClearColorImage for main + alt targets
            // with bs.getClearColor() values
        }

        // Depth targets always cleared to 1.0
        // TODO: Record vkCmdClearDepthStencilImage for all 3 depth targets
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
        return 1; // TODO: track mip levels per render target
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
}
