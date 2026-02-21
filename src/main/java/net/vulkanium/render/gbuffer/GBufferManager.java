package net.vulkanium.render.gbuffer;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.resource.RenderTarget;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.*;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the G-buffer render targets, MRT render pass, and framebuffers
 * for gbuffers programs during world rendering.
 *
 * <p>When a shaderpack is active, world draws (terrain, entities, sky, etc.)
 * render into this G-buffer instead of the swapchain. The G-buffer contains
 * multiple color attachments (colortex0–15) matching the pack's DRAWBUFFERS
 * directives, plus a depth attachment.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #initialize} — Create render targets based on pack's used buffers</li>
 *   <li>{@link #beginWorldPass} — End main render pass, begin MRT pass</li>
 *   <li>MC issues draw calls (terrain, entities, etc.) targeting the MRT framebuffer</li>
 *   <li>{@link #endWorldPass} — End MRT pass, blit colortex0 → swapchain, begin main pass</li>
 *   <li>Composite/deferred passes read from G-buffer colortex targets</li>
 * </ol>
 */
public class GBufferManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GBufMgr");

    // ── Render targets ──
    private VkDevice device;
    private VulkaniumMemory memory;
    private int width, height;

    /** All color targets that ANY gbuffers program writes to (union of all DRAWBUFFERS). */
    private int[] usedColorTargets;

    /** VkFormat for each used color target. */
    private int[] colorFormats;

    /** Depth format (D32_SFLOAT). */
    private int depthFormat;

    /** Color target images (indexed by colortex index 0–15). */
    private final RenderTarget[] colorTargets = new RenderTarget[RenderTargetSettings.MAX_COLOR_TARGETS];

    /** Depth target image. */
    private RenderTarget depthTarget;

    // ── MRT render pass ──
    /** The VkRenderPass for gbuffers MRT rendering. All color targets are attached. */
    private long gbufferRenderPass = VK_NULL_HANDLE;

    /** The VkFramebuffer for the gbuffers pass. */
    private long gbufferFramebuffer = VK_NULL_HANDLE;

    /** Number of actual color attachment descriptions (real images) in the render pass. */
    private int colorAttachmentCount;

    /**
     * Number of color attachment references in the subpass (= max colortex index + 1).
     * Unused indices use VK_ATTACHMENT_UNUSED so that fragment shader layout(location=N)
     * can use the colortex index N directly.
     */
    private int subpassColorRefCount;

    /**
     * Maps a colortex index (0–15) to its position in the render pass attachment list.
     * E.g., if usedColorTargets = {0, 3, 5}, then:
     *   colortexToAttachmentIndex[0] = 0
     *   colortexToAttachmentIndex[3] = 1
     *   colortexToAttachmentIndex[5] = 2
     */
    private final int[] colortexToAttachmentIndex = new int[RenderTargetSettings.MAX_COLOR_TARGETS];

    private boolean initialized = false;
    private boolean worldPassActive = false;

    /** Stored per-target settings for clear colors and other properties. */
    private RenderTargetSettings renderTargetSettings;

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initializes the G-buffer with all color targets used by ANY gbuffers program.
     *
     * @param device         Vulkan logical device
     * @param memory         Memory allocator
     * @param width          Swapchain/framebuffer width
     * @param height         Swapchain/framebuffer height
     * @param settings       Per-target format/clear settings from pack
     * @param allGbufferTargets Union of all DRAWBUFFERS/RENDERTARGETS across all gbuffers programs
     * @param swapchainDepthFormat VkFormat for the depth attachment
     */
    public void initialize(VkDevice device, VulkaniumMemory memory,
                           int width, int height,
                           RenderTargetSettings settings,
                           Set<Integer> allGbufferTargets,
                           int swapchainDepthFormat) {
        destroy(); // Clean up any previous state

        this.device = device;
        this.memory = memory;
        this.width = width;
        this.height = height;
        this.depthFormat = swapchainDepthFormat;
        this.renderTargetSettings = settings;

        // Always include colortex0 (the main color target)
        allGbufferTargets.add(0);

        // Sort and store the used targets
        this.usedColorTargets = allGbufferTargets.stream()
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
        this.colorAttachmentCount = usedColorTargets.length;

        // subpassColorRefCount = max colortex index + 1 (so fragment shader can use
        // layout(location = colortexIndex) directly, with VK_ATTACHMENT_UNUSED for gaps).
        // GBuffer programs always use colortex 0-7, so this never exceeds maxColorAttachments (8).
        this.subpassColorRefCount = usedColorTargets.length > 0
                ? Math.min(usedColorTargets[usedColorTargets.length - 1] + 1, 8)
                : 1;

        // Build the colortex → attachment index mapping
        Arrays.fill(colortexToAttachmentIndex, -1);
        for (int i = 0; i < usedColorTargets.length; i++) {
            colortexToAttachmentIndex[usedColorTargets[i]] = i;
        }

        // Collect formats for each used target
        this.colorFormats = new int[colorAttachmentCount];
        for (int i = 0; i < colorAttachmentCount; i++) {
            int colortexIdx = usedColorTargets[i];
            colorFormats[i] = settings.getColorSettings(colortexIdx).getVkFormat();
        }

        // Create color target images
        for (int i = 0; i < colorAttachmentCount; i++) {
            int colortexIdx = usedColorTargets[i];
            colorTargets[colortexIdx] = new RenderTarget();
            colorTargets[colortexIdx].initialize(device, memory,
                    "gbuf_colortex" + colortexIdx, width, height, colorFormats[i], false);
        }

        // Create depth target
        depthTarget = new RenderTarget();
        depthTarget.initialize(device, memory, "gbuf_depth", width, height, depthFormat, true);

        // Create the MRT render pass
        createRenderPass(settings);

        // Create the framebuffer
        createFramebuffer();

        initialized = true;
        LOGGER.info("G-buffer initialized: {}x{}, {} color targets {}, subpassRefs={}, depth={}",
                width, height, colorAttachmentCount, Arrays.toString(usedColorTargets),
                subpassColorRefCount, String.format("0x%X", depthFormat));
    }

    /**
     * Ensures the G-buffer matches the given dimensions, recreating if needed.
     */
    public void ensureSize(int newWidth, int newHeight) {
        if (!initialized) return;
        if (newWidth == width && newHeight == height) return;

        LOGGER.info("G-buffer resize: {}x{} → {}x{}", width, height, newWidth, newHeight);

        // Destroy framebuffer (references old image views)
        if (gbufferFramebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device, gbufferFramebuffer, null);
            gbufferFramebuffer = VK_NULL_HANDLE;
        }

        this.width = newWidth;
        this.height = newHeight;

        // Recreate all targets at new size
        for (int colortexIdx : usedColorTargets) {
            if (colorTargets[colortexIdx] != null) {
                colorTargets[colortexIdx].destroy();
            }
            int attachIdx = colortexToAttachmentIndex[colortexIdx];
            colorTargets[colortexIdx] = new RenderTarget();
            colorTargets[colortexIdx].initialize(device, memory,
                    "gbuf_colortex" + colortexIdx, width, height,
                    colorFormats[attachIdx], false);
        }

        if (depthTarget != null) depthTarget.destroy();
        depthTarget = new RenderTarget();
        depthTarget.initialize(device, memory, "gbuf_depth", width, height, depthFormat, true);

        createFramebuffer();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Render Pass Management
    // ═══════════════════════════════════════════════════════════════

    private void createRenderPass(RenderTargetSettings settings) {
        try (MemoryStack stack = stackPush()) {
            int totalAttachments = colorAttachmentCount + 1; // actual color images + depth

            VkAttachmentDescription.Buffer attachments =
                    VkAttachmentDescription.calloc(totalAttachments, stack);

            // Color attachments (compact: only used targets get real attachment descriptions)
            for (int i = 0; i < colorAttachmentCount; i++) {
                int colortexIdx = usedColorTargets[i];
                boolean shouldClear = settings.getColorSettings(colortexIdx).shouldClear();
                attachments.get(i)
                        .format(colorFormats[i])
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(shouldClear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }

            // Depth attachment (last in the compact attachment array)
            attachments.get(colorAttachmentCount)
                    .format(depthFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            // Subpass: color references use VK_ATTACHMENT_UNUSED for gap indices
            // so that fragment shader layout(location=N) maps to colortex N directly.
            // GBuffer targets are always 0-7, so subpassColorRefCount <= 8.
            VkAttachmentReference.Buffer colorRefs =
                    VkAttachmentReference.calloc(subpassColorRefCount, stack);
            for (int loc = 0; loc < subpassColorRefCount; loc++) {
                int attachIdx = colortexToAttachmentIndex[loc];
                if (attachIdx >= 0) {
                    colorRefs.get(loc)
                            .attachment(attachIdx)
                            .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                } else {
                    colorRefs.get(loc)
                            .attachment(VK_ATTACHMENT_UNUSED)
                            .layout(VK_IMAGE_LAYOUT_UNDEFINED);
                }
            }

            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(colorAttachmentCount)
                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(subpassColorRefCount)
                    .pColorAttachments(colorRefs)
                    .pDepthStencilAttachment(depthRef);

            // Dependencies for write→read visibility
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);

            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT
                            | VK_ACCESS_TRANSFER_READ_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);

            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependencies);

            LongBuffer pRenderPass = stack.mallocLong(1);
            checkResult(vkCreateRenderPass(device, rpInfo, null, pRenderPass));
            gbufferRenderPass = pRenderPass.get(0);

            LOGGER.debug("Created MRT render pass: {} color attachments, {} subpass color refs + depth, handle=0x{}",
                    colorAttachmentCount, subpassColorRefCount, Long.toHexString(gbufferRenderPass));
        }
    }

    private void createFramebuffer() {
        try (MemoryStack stack = stackPush()) {
            int totalAttachments = colorAttachmentCount + 1;
            LongBuffer attachments = stack.mallocLong(totalAttachments);

            for (int i = 0; i < colorAttachmentCount; i++) {
                int colortexIdx = usedColorTargets[i];
                attachments.put(colorTargets[colortexIdx].getImageView());
            }
            attachments.put(depthTarget.getImageView());
            attachments.flip();

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(gbufferRenderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFramebuffer = stack.mallocLong(1);
            checkResult(vkCreateFramebuffer(device, fbInfo, null, pFramebuffer));
            gbufferFramebuffer = pFramebuffer.get(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  World Rendering Control
    // ═══════════════════════════════════════════════════════════════

    /**
     * Transitions G-buffer images and begins the MRT render pass for world rendering.
     * Called from {@code onWorldRenderStart()}.
     *
     * @param cmd              Active command buffer
     * @param mainRenderPass   The main (swapchain) render pass to end first
     */
    public void beginWorldPass(VkCommandBuffer cmd,
                               net.vulkanium.render.pipeline.BasicRenderPass mainRenderPass) {
        if (!initialized || worldPassActive) return;

        // End the main swapchain render pass (we'll restart it after world rendering)
        mainRenderPass.end(cmd);

        // Transition all color targets to COLOR_ATTACHMENT_OPTIMAL
        transitionColorTargetsForRendering(cmd);

        // Transition depth to DEPTH_STENCIL_ATTACHMENT_OPTIMAL
        transitionDepthForRendering(cmd);

        // Begin MRT render pass
        try (MemoryStack stack = stackPush()) {
            int totalAttachments = colorAttachmentCount + 1;
            VkClearValue.Buffer clearValues = VkClearValue.calloc(totalAttachments, stack);
            for (int i = 0; i < colorAttachmentCount; i++) {
                int colortexIdx = usedColorTargets[i];
                // Use per-target clear colors from shaderpack settings.
                // Default is (0,0,0,0) — alpha=0 is critical for shaderpacks
                // that check alpha to distinguish sky pixels from geometry.
                float[] cc = (renderTargetSettings != null)
                        ? renderTargetSettings.getColorSettings(colortexIdx).getClearColor()
                        : new float[]{0.0f, 0.0f, 0.0f, 0.0f};
                clearValues.get(i).color()
                        .float32(0, cc.length > 0 ? cc[0] : 0.0f)
                        .float32(1, cc.length > 1 ? cc[1] : 0.0f)
                        .float32(2, cc.length > 2 ? cc[2] : 0.0f)
                        .float32(3, cc.length > 3 ? cc[3] : 0.0f);
            }
            clearValues.get(colorAttachmentCount).depthStencil()
                    .depth(1.0f)
                    .stencil(0);

            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(gbufferRenderPass)
                    .framebuffer(gbufferFramebuffer);
            rpBegin.renderArea().offset().set(0, 0);
            rpBegin.renderArea().extent().set(width, height);
            rpBegin.pClearValues(clearValues);

            vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Set Y-flipped viewport (to match OpenGL convention)
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f)
                    .y((float) height)
                    .width((float) width)
                    .height((float) -height)
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);
        }

        worldPassActive = true;
    }

    /**
     * Ends the MRT render pass and transitions all targets to SHADER_READ_ONLY
     * for composite/deferred pass sampling.
     *
     * <p>Does NOT blit to swapchain or restart the main render pass — those
     * responsibilities belong to the caller (Vulkanium frame lifecycle) so that
     * fullscreen passes can run between MRT end and GUI start.</p>
     *
     * @param cmd Active command buffer
     */
    public void endWorldPass(VkCommandBuffer cmd) {
        if (!initialized || !worldPassActive) return;

        // End MRT render pass
        vkCmdEndRenderPass(cmd);
        worldPassActive = false;

        // Transition all color targets → SHADER_READ for composite sampling
        for (int colortexIdx : usedColorTargets) {
            RenderTarget target = colorTargets[colortexIdx];
            if (target != null) {
                transitionImage(cmd, target.getImage(),
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT);
            }
        }

        // Transition depth → SHADER_READ for composite sampling
        if (depthTarget != null) {
            transitionImage(cmd, depthTarget.getImage(),
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Accessors for Composite/Deferred Pass Integration
    // ═══════════════════════════════════════════════════════════════

    /** Returns the MRT render pass handle (for creating compatible VkPipelines). */
    public long getRenderPass() { return gbufferRenderPass; }

    /** Returns the number of actual color attachment images. */
    public int getColorAttachmentCount() { return colorAttachmentCount; }

    /**
     * Returns the subpass color attachment reference count (= max colortex index + 1).
     * This is what the pipeline blend state array size must be.
     */
    public int getSubpassColorRefCount() { return subpassColorRefCount; }

    /** Returns the list of used colortex indices. */
    public int[] getUsedColorTargets() { return usedColorTargets; }

    /**
     * Returns the render pass attachment index for a given colortex index.
     * Returns -1 if the colortex is not part of the G-buffer.
     */
    public int getAttachmentIndex(int colortexIndex) {
        if (colortexIndex < 0 || colortexIndex >= colortexToAttachmentIndex.length) return -1;
        return colortexToAttachmentIndex[colortexIndex];
    }

    /** Returns the RenderTarget for a given colortex index, or null. */
    public RenderTarget getColorTarget(int colortexIndex) {
        if (colortexIndex < 0 || colortexIndex >= colorTargets.length) return null;
        return colorTargets[colortexIndex];
    }

    /** Returns the depth target. */
    public RenderTarget getDepthTarget() { return depthTarget; }

    /** Whether the G-buffer is initialized and ready. */
    public boolean isInitialized() { return initialized; }

    /** Whether the MRT world pass is currently active. */
    public boolean isWorldPassActive() { return worldPassActive; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    public void destroy() {
        if (gbufferFramebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device, gbufferFramebuffer, null);
            gbufferFramebuffer = VK_NULL_HANDLE;
        }
        if (gbufferRenderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, gbufferRenderPass, null);
            gbufferRenderPass = VK_NULL_HANDLE;
        }
        for (int i = 0; i < colorTargets.length; i++) {
            if (colorTargets[i] != null) {
                colorTargets[i].destroy();
                colorTargets[i] = null;
            }
        }
        if (depthTarget != null) {
            depthTarget.destroy();
            depthTarget = null;
        }
        initialized = false;
        worldPassActive = false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Image Transition Utilities
    // ═══════════════════════════════════════════════════════════════

    private void transitionColorTargetsForRendering(VkCommandBuffer cmd) {
        for (int colortexIdx : usedColorTargets) {
            RenderTarget target = colorTargets[colortexIdx];
            if (target == null) continue;
            transitionImage(cmd, target.getImage(),
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    0,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
        }
    }

    private void transitionDepthForRendering(VkCommandBuffer cmd) {
        if (depthTarget == null) return;
        transitionImage(cmd, depthTarget.getImage(),
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                0,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);
    }

    private static void transitionImage(VkCommandBuffer cmd, long image,
                                         int oldLayout, int newLayout,
                                         int srcAccess, int dstAccess,
                                         int srcStage, int dstStage,
                                         int aspectMask) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            barrier.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            vkCmdPipelineBarrier(cmd, srcStage, dstStage,
                    VK_DEPENDENCY_BY_REGION_BIT,
                    null, null, barrier);
        }
    }

    private static void blitImage(VkCommandBuffer cmd, long src, long dst,
                                   int srcW, int srcH, int dstW, int dstH) {
        try (MemoryStack stack = stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(srcW, srcH, 1);

            region.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.dstOffsets(0).set(0, 0, 0);
            region.dstOffsets(1).set(dstW, dstH, 1);

            vkCmdBlitImage(cmd,
                    src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region, VK_FILTER_LINEAR);
        }
    }
}
