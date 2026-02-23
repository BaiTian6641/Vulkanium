package net.vulkanium.shaderpack;

import net.vulkanium.core.VulkaniumCommand;
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
 * Manages the fullscreen color/depth render targets for shaderpack composite/deferred/final passes.
 *
 * <p>Following Iris's architecture:</p>
 * <ul>
 *   <li>16 color targets (colortex0–colortex15), each <b>double-buffered</b> (main/alt)
 *       for ping-pong reading between passes</li>
 *   <li>3 depth targets (depthtex0–depthtex2):
 *       depthtex0 = current depth, depthtex1 = pre-translucent, depthtex2 = pre-hand</li>
 *   <li>A {@link BufferFlipper} tracks which side (main/alt) of each color target is
 *       currently the "read" side for sampling</li>
 * </ul>
 *
 * <p>Also manages MRT render passes and per-pass framebuffers, cached by the set of
 * render targets each pass writes to.</p>
 */
public class FullscreenRenderTargets {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/FSTargets");

    /** Maximum color targets (OptiFine 8, Iris extended 16). */
    public static final int MAX_COLOR_TARGETS = 16;

    /** Maximum depth targets (depthtex0, depthtex1, depthtex2). */
    public static final int MAX_DEPTH_TARGETS = 3;

    /** Diagnostic budget for blit logging. */
    private int blitDiagBudget = 5;

    // ── Double-buffered color targets ──
    // [targetIndex][0=main, 1=alt]
    private final RenderTarget[][] colorTargets = new RenderTarget[MAX_COLOR_TARGETS][2];

    // ── Depth targets ──
    private final RenderTarget[] depthTargets = new RenderTarget[MAX_DEPTH_TARGETS];

    // ── Flip state: true = alt is the "write" side (main is "read") ──
    private final boolean[] flipped = new boolean[MAX_COLOR_TARGETS];

    // ── Image layout tracking (to avoid redundant transitions) ──
    // [targetIndex][0=main, 1=alt]
    private final int[][] colorLayouts = new int[MAX_COLOR_TARGETS][2];

    // ── MRT render pass cache: keyed by target configuration string ──
    private final Map<String, Long> mrtRenderPasses = new HashMap<>();

    // ── Per-pass framebuffer cache: keyed by "targets_flipstate_width_height" ──
    private final Map<String, Long> framebufferCache = new HashMap<>();

    private VkDevice device;
    private VulkaniumMemory memory;
    private int width;
    private int height;
    private int colorFormat;
    private int depthFormat;
    private boolean initialized = false;

    /** Per-target VkFormat overrides (from shaderpack const int colortexNFormat directives).
     *  Index i = VkFormat for colortex_i. 0 = use default colorFormat. */
    private final int[] targetFormats = new int[MAX_COLOR_TARGETS];

    /**
     * Sets a per-target Vulkan format override. Call BEFORE ensureSize().
     * @param index  colortex index (0-15)
     * @param vkFormat VK_FORMAT_* constant (0 to use default)
     */
    public void setTargetFormat(int index, int vkFormat) {
        if (index >= 0 && index < MAX_COLOR_TARGETS) {
            targetFormats[index] = vkFormat;
        }
    }

    /**
     * Returns the effective VkFormat for the given colortex index,
     * considering per-target overrides and the default colorFormat.
     */
    public int getEffectiveFormat(int index) {
        if (index >= 0 && index < MAX_COLOR_TARGETS && targetFormats[index] != 0) {
            return targetFormats[index];
        }
        return colorFormat;
    }

    /**
     * Initializes or resizes all render targets to the given dimensions.
     */
    public void ensureSize(VkDevice device, VulkaniumMemory memory,
                           int width, int height, int colorFormat, int depthFormat) {
        if (initialized && this.width == width && this.height == height
                && this.colorFormat == colorFormat && this.depthFormat == depthFormat) {
            return; // Already correct size
        }

        this.device = device;
        this.memory = memory;
        this.width = width;
        this.height = height;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;

        // Destroy old framebuffers (they reference old image views)
        destroyFramebuffers();

        // Create/resize color targets (double-buffered)
        for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
            int fmt = getEffectiveFormat(i);
            for (int side = 0; side < 2; side++) {
                if (colorTargets[i][side] != null) {
                    colorTargets[i][side].destroy();
                }
                colorTargets[i][side] = new RenderTarget();
                colorTargets[i][side].initialize(device, memory,
                        "colortex" + i + (side == 0 ? "_main" : "_alt"),
                        width, height, fmt, false);
                colorLayouts[i][side] = VK_IMAGE_LAYOUT_UNDEFINED;
            }
        }

        // Create/resize depth targets
        for (int i = 0; i < MAX_DEPTH_TARGETS; i++) {
            if (depthTargets[i] != null) {
                depthTargets[i].destroy();
            }
            depthTargets[i] = new RenderTarget();
            depthTargets[i].initialize(device, memory,
                    "depthtex" + i, width, height, depthFormat, true);
        }

        // Reset flip state
        Arrays.fill(flipped, false);

        // Mark that images need initial layout transition before first use
        needsInitialTransition = true;

        initialized = true;
        LOGGER.info("Fullscreen render targets initialized: {}x{}, color={}, depth={}, {} color targets (double-buffered)",
                width, height, colorFormat, depthFormat, MAX_COLOR_TARGETS);
    }

    /** Whether newly created images still need their initial layout transition. */
    private boolean needsInitialTransition = false;

    /**
     * Transitions all color targets from UNDEFINED → SHADER_READ_ONLY_OPTIMAL
     * so they can be safely sampled even before any pass writes to them.
     * Must be called outside a render pass on the first frame after creation.
     */
    public void initializeImageLayouts(VkCommandBuffer cmd) {
        if (!needsInitialTransition) return;
        needsInitialTransition = false;

        for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
            for (int side = 0; side < 2; side++) {
                if (colorTargets[i][side] == null) continue;
                VulkaniumCommand.transitionImageLayout(cmd, colorTargets[i][side].getImage(),
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        0, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT);
                colorLayouts[i][side] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ping-pong access
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns the RenderTarget to <b>read/sample</b> from for the given colortex index.
     * This is the side that was most recently written by a previous pass.
     */
    public RenderTarget getReadTarget(int index) {
        return colorTargets[index][flipped[index] ? 1 : 0];
    }

    /**
     * Returns the RenderTarget to <b>write</b> to for the given colortex index.
     * This is the opposite side from what's currently being read.
     */
    public RenderTarget getWriteTarget(int index) {
        return colorTargets[index][flipped[index] ? 0 : 1];
    }

    /**
     * Flips the buffer for the given colortex index after a pass writes to it.
     * The next pass will read from the side that was just written.
     */
    public void flip(int index) {
        flipped[index] = !flipped[index];
    }

    /**
     * Returns the depth target for the given index (0–2).
     */
    public RenderTarget getDepthTarget(int index) {
        return depthTargets[index];
    }

    /**
     * Resets flip state for all targets at the beginning of each frame.
     */
    public void resetFlips() {
        Arrays.fill(flipped, false);
    }

    // ═══════════════════════════════════════════════════════════════
    //  MRT Render Pass creation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gets or creates a VkRenderPass for the given render target indices.
     * Handles non-contiguous targets (e.g., DRAWBUFFERS:0567) by using
     * VK_ATTACHMENT_UNUSED for gap locations, so fragment shader outputs
     * at layout(location=5) correctly map to the colortex5 attachment.
     *
     * <p>Reference: Iris {@code IrisRenderingPipeline} maps DRAWBUFFERS digits
     * directly to framebuffer attachment indices. Pipeline color attachment
     * count equals {@code max_target + 1}, with VK_ATTACHMENT_UNUSED for gaps.</p>
     */
    public long getOrCreateMrtRenderPass(int[] renderTargets) {
        String key = Arrays.toString(renderTargets);
        Long cached = mrtRenderPasses.get(key);
        if (cached != null) return cached;

        // Compact layout: attachment count == renderTargets.length.
        // Fragment shader uses layout(location=i) where i is the index into
        // renderTargets[], NOT the colortex index.  This ensures we never
        // exceed maxColorAttachments (typically 8).
        int attachmentCount = renderTargets.length;

        try (MemoryStack stack = stackPush()) {
            // One attachment description per target (compact 1:1)
            VkAttachmentDescription.Buffer attachments =
                    VkAttachmentDescription.calloc(attachmentCount, stack);
            for (int i = 0; i < attachmentCount; i++) {
                attachments.get(i)
                        .format(getEffectiveFormat(renderTargets[i]))
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }

            // Compact color attachment references: location i → attachment i
            // Fragment shader layout(location=0) → attachment 0 → colortex[renderTargets[0]]
            // Fragment shader layout(location=1) → attachment 1 → colortex[renderTargets[1]]
            VkAttachmentReference.Buffer colorRefs =
                    VkAttachmentReference.calloc(attachmentCount, stack);
            for (int i = 0; i < attachmentCount; i++) {
                colorRefs.get(i)
                        .attachment(i)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(attachmentCount)
                    .pColorAttachments(colorRefs)
                    .pDepthStencilAttachment(null);

            // Dependency: ensure previous fragment shader reads complete before we write
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateRenderPass(device, rpInfo, null, pRenderPass);
            checkResult(result, "Failed to create MRT render pass for targets " + key);

            long rp = pRenderPass.get(0);
            mrtRenderPasses.put(key, rp);
            LOGGER.info("Created compact MRT render pass: targets={}, attachments={}",
                    key, attachmentCount);
            return rp;
        }
    }

    /**
     * Returns the subpass color attachment count for the given render targets.
     * Uses compact mapping: count == renderTargets.length (always ≤ maxColorAttachments).
     */
    public static int getSubpassColorCount(int[] renderTargets) {
        return renderTargets.length;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Per-pass Framebuffer creation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gets or creates a VkFramebuffer for a pass that writes to the specified render targets.
     * Uses the WRITE side of each target (opposite of read for ping-pong).
     *
     * @param renderTargets Array of colortex indices this pass writes to (from DRAWBUFFERS)
     * @param renderPass    Compatible VkRenderPass handle
     * @return VkFramebuffer handle
     */
    public long getOrCreateFramebuffer(int[] renderTargets, long renderPass) {
        // Build cache key from targets + current flip state
        StringBuilder keyBuilder = new StringBuilder();
        for (int t : renderTargets) {
            keyBuilder.append(t).append(flipped[t] ? 'A' : 'M').append('_');
        }
        String key = keyBuilder.toString();

        Long cached = framebufferCache.get(key);
        if (cached != null) return cached;

        try (MemoryStack stack = stackPush()) {
            LongBuffer attachments = stack.mallocLong(renderTargets.length);
            for (int i = 0; i < renderTargets.length; i++) {
                RenderTarget writeTarget = getWriteTarget(renderTargets[i]);
                attachments.put(i, writeTarget.getImageView());
            }

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFb = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateFramebuffer(device, fbInfo, null, pFb);
            checkResult(result, "Failed to create MRT framebuffer");

            long fb = pFb.get(0);
            framebufferCache.put(key, fb);
            return fb;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Image layout transitions
    // ═══════════════════════════════════════════════════════════════

    /**
     * Transitions write targets for a pass to COLOR_ATTACHMENT_OPTIMAL.
     */
    public void transitionWriteTargetsToAttachment(VkCommandBuffer cmd, int[] renderTargets) {
        for (int target : renderTargets) {
            RenderTarget write = getWriteTarget(target);
            int side = flipped[target] ? 0 : 1; // write side
            int currentLayout = colorLayouts[target][side];
            if (currentLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) continue;

            int srcAccess = 0;
            int srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            if (currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                srcAccess = VK_ACCESS_SHADER_READ_BIT;
                srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            }

            VulkaniumCommand.transitionImageLayout(cmd, write.getImage(),
                    currentLayout, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    srcAccess, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    srcStage, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
            colorLayouts[target][side] = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        }
    }

    /**
     * Transitions written targets to SHADER_READ_ONLY_OPTIMAL after a pass completes,
     * and flips them so the next pass reads the freshly-written data.
     */
    public void transitionAndFlipAfterPass(VkCommandBuffer cmd, int[] renderTargets) {
        for (int target : renderTargets) {
            RenderTarget write = getWriteTarget(target);
            int side = flipped[target] ? 0 : 1; // write side (before flip)

            VulkaniumCommand.transitionImageLayout(cmd, write.getImage(),
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
            colorLayouts[target][side] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

            // Flip: next pass reads from the side we just wrote
            flip(target);
        }
    }

    /**
     * Transitions read-side color targets to VK_IMAGE_LAYOUT_GENERAL for compute
     * shader storage image (imageLoad/imageStore) access.
     *
     * <p>Compute shaders bind colortex images as both combined-image-samplers (read)
     * and storage images (read-write). VK_IMAGE_LAYOUT_GENERAL is compatible with
     * both access types. After compute dispatch, call
     * {@link #transitionReadTargetsFromGeneral(VkCommandBuffer, int)} to restore
     * SHADER_READ_ONLY_OPTIMAL for subsequent fragment sampling.</p>
     *
     * @param cmd    Active command buffer
     * @param count  Number of targets to transition (from index 0)
     */
    public void transitionReadTargetsToGeneral(VkCommandBuffer cmd, int count) {
        for (int i = 0; i < Math.min(count, MAX_COLOR_TARGETS); i++) {
            int side = flipped[i] ? 1 : 0; // read side
            int currentLayout = colorLayouts[i][side];
            if (currentLayout == VK_IMAGE_LAYOUT_GENERAL) continue;

            RenderTarget read = getReadTarget(i);
            if (read == null || read.getImage() == VK_NULL_HANDLE) continue;

            int srcAccess = 0;
            int srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            if (currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                srcAccess = VK_ACCESS_SHADER_READ_BIT;
                srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            } else if (currentLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            }

            VulkaniumCommand.transitionImageLayout(cmd, read.getImage(),
                    currentLayout, VK_IMAGE_LAYOUT_GENERAL,
                    srcAccess,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    srcStage,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
            colorLayouts[i][side] = VK_IMAGE_LAYOUT_GENERAL;
        }
    }

    /**
     * Transitions read-side color targets back from VK_IMAGE_LAYOUT_GENERAL to
     * VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL after compute dispatch completes.
     *
     * @param cmd    Active command buffer
     * @param count  Number of targets to transition (from index 0)
     */
    public void transitionReadTargetsFromGeneral(VkCommandBuffer cmd, int count) {
        for (int i = 0; i < Math.min(count, MAX_COLOR_TARGETS); i++) {
            int side = flipped[i] ? 1 : 0; // read side
            int currentLayout = colorLayouts[i][side];
            if (currentLayout != VK_IMAGE_LAYOUT_GENERAL) continue;

            RenderTarget read = getReadTarget(i);
            if (read == null || read.getImage() == VK_NULL_HANDLE) continue;

            VulkaniumCommand.transitionImageLayout(cmd, read.getImage(),
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
            colorLayouts[i][side] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }
    }

    /**
     * Transitions the read side of colortex0 to TRANSFER_SRC for blitting to swapchain.
     */
    public void transitionReadTarget0ToTransferSrc(VkCommandBuffer cmd) {
        RenderTarget read0 = getReadTarget(0);
        int side = flipped[0] ? 1 : 0; // read side

        int currentLayout = colorLayouts[0][side];
        int srcAccess = 0;
        int srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        if (currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcAccess = VK_ACCESS_SHADER_READ_BIT;
            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        } else if (currentLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        }

        VulkaniumCommand.transitionImageLayout(cmd, read0.getImage(),
                currentLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                srcAccess, VK_ACCESS_TRANSFER_READ_BIT,
                srcStage, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
        colorLayouts[0][side] = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    }

    /**
     * After blitting colortex0 to swapchain, transition it back.
     */
    public void transitionReadTarget0AfterBlit(VkCommandBuffer cmd) {
        RenderTarget read0 = getReadTarget(0);
        int side = flipped[0] ? 1 : 0;

        VulkaniumCommand.transitionImageLayout(cmd, read0.getImage(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
        colorLayouts[0][side] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Scene initialization (copy swapchain → colortex0)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Copies the current swapchain color image into colortex0 (write side),
     * then flips so subsequent reads of colortex0 see the captured scene.
     */
    public void captureSceneToColorTarget0(VkCommandBuffer cmd,
                                           long swapchainImage, int swapchainLayout) {
        RenderTarget write0 = getWriteTarget(0);
        int writeSide = flipped[0] ? 0 : 1;

        // Transition swapchain → TRANSFER_SRC
        VulkaniumCommand.transitionImageLayout(cmd, swapchainImage,
                swapchainLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Transition colortex0 write → TRANSFER_DST
        VulkaniumCommand.transitionImageLayout(cmd, write0.getImage(),
                colorLayouts[0][writeSide], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Copy
        try (var stack = stackPush()) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffset().set(0, 0, 0);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(width, height, 1);

            vkCmdCopyImage(cmd,
                    swapchainImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    write0.getImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);
        }

        // Transition colortex0 write → SHADER_READ_ONLY
        VulkaniumCommand.transitionImageLayout(cmd, write0.getImage(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
        colorLayouts[0][writeSide] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        // Transition swapchain back
        VulkaniumCommand.transitionImageLayout(cmd, swapchainImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapchainLayout,
                VK_ACCESS_TRANSFER_READ_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Flip: colortex0 read now returns the scene capture
        flip(0);
    }

    /**
     * Copies the current depth buffer into depthtex0.
     */
    public void captureDepthToTarget0(VkCommandBuffer cmd, long srcDepthImage) {
        RenderTarget dst = depthTargets[0];

        VulkaniumCommand.transitionImageLayout(cmd, srcDepthImage,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);

        VulkaniumCommand.transitionImageLayout(cmd, dst.getImage(),
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);

        try (var stack = stackPush()) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffset().set(0, 0, 0);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(width, height, 1);

            vkCmdCopyImage(cmd,
                    srcDepthImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.getImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region);
        }

        VulkaniumCommand.transitionImageLayout(cmd, dst.getImage(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);

        VulkaniumCommand.transitionImageLayout(cmd, srcDepthImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);

        // Also copy to depthtex1 and depthtex2 (same initial depth)
        // In a full implementation these are captured at different rendering phases:
        //   depthtex1 = depth before translucents
        //   depthtex2 = depth before hand
        // For now we initialize all three with the same depth snapshot.
        for (int i = 1; i < MAX_DEPTH_TARGETS; i++) {
            RenderTarget dstI = depthTargets[i];

            VulkaniumCommand.transitionImageLayout(cmd, depthTargets[0].getImage(),
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);

            VulkaniumCommand.transitionImageLayout(cmd, dstI.getImage(),
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);

            try (var stack = stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffset().set(0, 0, 0);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstOffset().set(0, 0, 0);
                region.extent().set(width, height, 1);

                vkCmdCopyImage(cmd,
                        depthTargets[0].getImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        dstI.getImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region);
            }

            VulkaniumCommand.transitionImageLayout(cmd, dstI.getImage(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);

            VulkaniumCommand.transitionImageLayout(cmd, depthTargets[0].getImage(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);
        }
    }

    /**
     * Copies a G-buffer color target (already in SHADER_READ_ONLY_OPTIMAL) into a
     * fullscreen colortex slot, then flips so subsequent reads see the captured data.
     *
     * @param cmd            Active command buffer (outside any render pass)
     * @param srcImage       G-buffer image handle (must be in SHADER_READ_ONLY_OPTIMAL)
     * @param colortexIndex  Destination colortex slot (0–15)
     */
    public void captureGBufferColorTarget(VkCommandBuffer cmd, long srcImage, int colortexIndex) {
        if (colortexIndex < 0 || colortexIndex >= MAX_COLOR_TARGETS) return;

        RenderTarget write = getWriteTarget(colortexIndex);
        int writeSide = flipped[colortexIndex] ? 0 : 1;

        // Transition GBuffer image: SHADER_READ_ONLY → TRANSFER_SRC
        VulkaniumCommand.transitionImageLayout(cmd, srcImage,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Transition fsTargets write side → TRANSFER_DST
        VulkaniumCommand.transitionImageLayout(cmd, write.getImage(),
                colorLayouts[colortexIndex][writeSide], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Blit (not copy!) — vkCmdBlitImage performs format conversion between
        // differing formats (e.g. GBuffer R8G8B8A8 → fsTargets B8G8R8A8).
        // vkCmdCopyImage does raw byte copy which swaps red↔blue channels.
        try (var stack = stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(width, height, 1);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffsets(0).set(0, 0, 0);
            region.dstOffsets(1).set(width, height, 1);

            vkCmdBlitImage(cmd,
                    srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    write.getImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region, VK_FILTER_NEAREST);
        }

        // Transition fsTargets write → SHADER_READ_ONLY
        VulkaniumCommand.transitionImageLayout(cmd, write.getImage(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
        colorLayouts[colortexIndex][writeSide] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        // Transition GBuffer image back: TRANSFER_SRC → SHADER_READ_ONLY
        VulkaniumCommand.transitionImageLayout(cmd, srcImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Flip so reads of this colortex see the captured G-buffer data
        flip(colortexIndex);
    }

    /**
     * Copies a G-buffer depth target (already in SHADER_READ_ONLY_OPTIMAL) into
     * all depthtex slots (0, 1, 2).
     */
    public void captureGBufferDepthTarget(VkCommandBuffer cmd, long srcDepthImage) {
        for (int i = 0; i < MAX_DEPTH_TARGETS; i++) {
            RenderTarget dst = depthTargets[i];

            // Transition GBuffer depth: SHADER_READ_ONLY → TRANSFER_SRC
            VulkaniumCommand.transitionImageLayout(cmd, srcDepthImage,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);

            VulkaniumCommand.transitionImageLayout(cmd, dst.getImage(),
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);

            try (var stack = stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffset().set(0, 0, 0);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstOffset().set(0, 0, 0);
                region.extent().set(width, height, 1);

                vkCmdCopyImage(cmd,
                        srcDepthImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        dst.getImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region);
            }

            VulkaniumCommand.transitionImageLayout(cmd, dst.getImage(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);

            // Transition GBuffer depth back: TRANSFER_SRC → SHADER_READ_ONLY
            VulkaniumCommand.transitionImageLayout(cmd, srcDepthImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);
        }
    }

    /**
     * Blits the current colortex0 READ side to the swapchain image.
     *
     * @param cmd             Active command buffer
     * @param swapchainImage  Swapchain image handle
     * @param swapchainLayout Current layout of the swapchain image
     * @param dstWidth        Swapchain image width (may differ from fsTargets width during resize)
     * @param dstHeight       Swapchain image height
     */
    public void blitColorTarget0ToSwapchain(VkCommandBuffer cmd,
                                            long swapchainImage, int swapchainLayout,
                                            int dstWidth, int dstHeight) {
        RenderTarget read0 = getReadTarget(0);

        // colortex0 read → TRANSFER_SRC
        transitionReadTarget0ToTransferSrc(cmd);

        // swapchain → TRANSFER_DST
        VulkaniumCommand.transitionImageLayout(cmd, swapchainImage,
                swapchainLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Blit (handles format conversion + rescale if src/dst sizes differ)
        // Y-FLIP: the G-buffer and composite passes use positive viewport,
        // storing the scene with OpenGL texture convention (V=0 = ground at top
        // of Vulkan image, V=1 = sky at bottom).  The display swapchain needs
        // sky at the top of the screen (row 0), so we flip the source Y:
        //   srcOffset[0].y = height  (start from bottom of source image = sky)
        //   srcOffset[1].y = 0       (end at top of source image = ground)
        // This copies the source upside-down into the destination, correcting
        // the on-screen orientation.
        if (blitDiagBudget > 0) {
            blitDiagBudget--;
            LOGGER.info("[BLIT] Y-flip blit: src={}x{} (img=0x{}) → dst={}x{} (img=0x{})",
                    width, height, Long.toHexString(read0.getImage()),
                    dstWidth, dstHeight, Long.toHexString(swapchainImage));
        }
        try (var stack = stackPush()) {
            VkImageBlit.Buffer blitRegion = VkImageBlit.calloc(1, stack);
            blitRegion.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            blitRegion.srcOffsets(0).set(0, height, 0);
            blitRegion.srcOffsets(1).set(width, 0, 1);
            blitRegion.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            blitRegion.dstOffsets(0).set(0, 0, 0);
            blitRegion.dstOffsets(1).set(dstWidth, dstHeight, 1);

            vkCmdBlitImage(cmd,
                    read0.getImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    swapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blitRegion, VK_FILTER_LINEAR);
        }

        // colortex0 → back to SHADER_READ
        transitionReadTarget0AfterBlit(cmd);

        // swapchain → back to original layout
        VulkaniumCommand.transitionImageLayout(cmd, swapchainImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, swapchainLayout,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
    }

    /**
     * @deprecated Use {@link #blitColorTarget0ToSwapchain(VkCommandBuffer, long, int, int, int)} instead.
     */
    @Deprecated
    public void blitColorTarget0ToSwapchain(VkCommandBuffer cmd,
                                            long swapchainImage, int swapchainLayout) {
        blitColorTarget0ToSwapchain(cmd, swapchainImage, swapchainLayout, width, height);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════

    private void destroyFramebuffers() {
        for (long fb : framebufferCache.values()) {
            if (fb != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(device, fb, null);
            }
        }
        framebufferCache.clear();
    }

    public void destroy() {
        destroyFramebuffers();

        for (long rp : mrtRenderPasses.values()) {
            if (rp != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device, rp, null);
            }
        }
        mrtRenderPasses.clear();

        for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
            for (int side = 0; side < 2; side++) {
                if (colorTargets[i][side] != null) {
                    colorTargets[i][side].destroy();
                    colorTargets[i][side] = null;
                }
            }
        }

        for (int i = 0; i < MAX_DEPTH_TARGETS; i++) {
            if (depthTargets[i] != null) {
                depthTargets[i].destroy();
                depthTargets[i] = null;
            }
        }

        initialized = false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Accessors
    // ═══════════════════════════════════════════════════════════════

    public boolean isInitialized() { return initialized; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
