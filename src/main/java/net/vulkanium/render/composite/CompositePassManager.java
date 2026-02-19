package net.vulkanium.render.composite;

import net.vulkanium.render.gbuffer.GBufferSamplers;
import net.vulkanium.render.gbuffer.GBufferTargets;
import net.vulkanium.render.gbuffer.MRTRenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Executes a sequence of composite/deferred fullscreen passes.
 *
 * <p>Manages an ordered list of {@link CompositePass} objects, each representing
 * one fullscreen shader pass. Handles ping-pong buffer flipping, mipmap generation,
 * compute shader dispatch, and pipeline barriers between passes.</p>
 *
 * <h3>Execution Model (per renderAll call)</h3>
 * <pre>
 *   For each enabled pass in sequence:
 *     1. Run associated compute shader (if present):
 *        a. Bind compute pipeline
 *        b. Bind descriptors (UBO + samplers)
 *        c. vkCmdDispatch(groupsX, groupsY, 1)
 *        d. Memory barrier (compute write → fragment read)
 *
 *     2. Generate mipmaps for requested buffers
 *
 *     3. Update sampler descriptors (respect current flip state)
 *
 *     4. Begin MRT render pass (write targets from RENDERTARGETS)
 *        - Color attachments from getWriteTarget() (opposite of flip)
 *        - No depth attachment (composite passes don't write depth)
 *
 *     5. Set viewport (may be scaled for lower-res passes)
 *
 *     6. Bind graphics pipeline + descriptors
 *
 *     7. Draw fullscreen triangle (3 vertices, no vertex buffer):
 *        vkCmdDraw(cmd, 3, 1, 0, 0)
 *
 *     8. End render pass
 *
 *     9. Flip affected buffers in GBufferTargets
 * </pre>
 *
 * <h3>Fullscreen Triangle</h3>
 * <p>Vertices are computed from gl_VertexIndex in the vertex shader —
 * no vertex buffer binding needed. The triangle covers the entire NDC space
 * with correct UV mapping:</p>
 * <pre>
 *   Vertex 0: (-1, -1)  UV (0, 0)
 *   Vertex 1: ( 3, -1)  UV (2, 0)
 *   Vertex 2: (-1,  3)  UV (0, 2)
 * </pre>
 */
public class CompositePassManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/CompositeMgr");

    /** Maximum passes per category (matching Iris) */
    public static final int MAX_PASSES = 100;

    // ── State ──
    private final CompositePass.Category category;
    private final List<CompositePass> passes = new ArrayList<>();
    private final GBufferTargets gBuffer;

    /** Reusable MRT render passes, keyed by attachment count */
    private final MRTRenderPass[] renderPassCache = new MRTRenderPass[17]; // 0-16 attachments

    /** Statistics */
    private int activePassCount = 0;
    private long lastRenderTimeNs = 0;

    public CompositePassManager(CompositePass.Category category, GBufferTargets gBuffer) {
        this.category = category;
        this.gBuffer = gBuffer;
    }

    /**
     * Adds a pass to the sequence. Called during shader pack compilation.
     */
    public void addPass(CompositePass pass) {
        passes.add(pass);
        if (pass.isEnabled()) activePassCount++;
    }

    /**
     * Sets up all passes — snapshots flip state and creates render passes.
     * Called after all passes are added and pipelines compiled.
     *
     * @param initialFlipState The buffer flip state when this category starts
     */
    public void prepare(boolean[] initialFlipState) {
        boolean[] currentFlip = initialFlipState.clone();

        for (CompositePass pass : passes) {
            if (!pass.isEnabled()) continue;

            // Record what flip state this pass reads from
            pass.setReadFlipState(currentFlip);

            // After this pass, flip the written buffers
            for (int buf : pass.getBuffersToFlip()) {
                currentFlip[buf] = !currentFlip[buf];
            }
        }

        LOGGER.info("{} pass manager prepared: {}/{} passes active",
                category, activePassCount, passes.size());
    }

    /**
     * Returns the flip state after all passes in this category have executed.
     * Used to initialize the next category's flip state.
     */
    public boolean[] getFinalFlipState() {
        boolean[] state = new boolean[16];
        for (CompositePass pass : passes) {
            if (!pass.isEnabled()) continue;
            for (int buf : pass.getBuffersToFlip()) {
                state[buf] = !state[buf];
            }
        }
        return state;
    }

    /**
     * Renders all enabled passes in sequence.
     *
     * @param commandBuffer    Active VkCommandBuffer
     * @param uniformDescSet   Descriptor set for UBO (set 0)
     * @param screenWidth      Current render width
     * @param screenHeight     Current render height
     */
    public void renderAll(long commandBuffer, long uniformDescSet,
                          int screenWidth, int screenHeight) {
        if (activePassCount == 0) return;

        long startNs = System.nanoTime();

        for (CompositePass pass : passes) {
            if (!pass.isEnabled()) continue;
            renderPass(commandBuffer, pass, uniformDescSet, screenWidth, screenHeight);
        }

        lastRenderTimeNs = System.nanoTime() - startNs;
    }

    private void renderPass(long commandBuffer, CompositePass pass,
                            long uniformDescSet, int screenWidth, int screenHeight) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        // Step 1: Run associated compute shader
        if (pass.hasComputeShader()) {
            dispatchCompute(cmd, pass, uniformDescSet);
            recordComputeToFragmentBarrier(cmd);
        }

        // Step 2: Generate mipmaps for requested buffers
        for (int buf : pass.getMipmapBuffers()) {
            generateMipmaps(cmd, buf);
        }

        // Step 3: Apply flip state to G-buffer for correct read targets
        boolean[] readState = pass.getReadFlipState();

        // Step 4: Calculate viewport dimensions
        int vpWidth = (int) (screenWidth * pass.getViewportScaleX());
        int vpHeight = (int) (screenHeight * pass.getViewportScaleY());

        // Step 5-9: Begin render pass, draw fullscreen triangle, end render pass
        try (MemoryStack stack = stackPush()) {
            // Set viewport + scissor
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f)
                    .width(vpWidth).height(vpHeight)
                    .minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(vpWidth, vpHeight);
            vkCmdSetScissor(cmd, 0, scissor);

            // Bind graphics pipeline
            if (pass.getPipeline() != VK_NULL_HANDLE) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.getPipeline());
            }

            // Bind descriptor sets (UBO + samplers)
            long samplerDescSet = pass.getSamplerDescriptorSet();
            if (uniformDescSet != 0 || samplerDescSet != 0) {
                LongBuffer pSets = stack.mallocLong(2);
                pSets.put(0, uniformDescSet);
                pSets.put(1, samplerDescSet);
                if (pass.getPipelineLayout() != VK_NULL_HANDLE) {
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            pass.getPipelineLayout(), 0, pSets, null);
                }
            }

            // Draw fullscreen triangle (3 vertices, no vertex buffer)
            // Vertex shader computes positions from gl_VertexIndex
            vkCmdDraw(cmd, 3, 1, 0, 0);
        }

        // Step 10: Flip affected buffers
        gBuffer.flipBuffers(pass.getBuffersToFlip());
    }

    private void dispatchCompute(VkCommandBuffer cmd, CompositePass pass, long uniformDescSet) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pass.getComputePipeline());

            long samplerDescSet = pass.getSamplerDescriptorSet();
            LongBuffer pSets = stack.mallocLong(2);
            pSets.put(0, uniformDescSet);
            pSets.put(1, samplerDescSet);
            if (pass.getComputePipelineLayout() != VK_NULL_HANDLE) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                        pass.getComputePipelineLayout(), 0, pSets, null);
            }

            vkCmdDispatch(cmd, pass.getComputeWorkGroupsX(), pass.getComputeWorkGroupsY(), 1);
        }
    }

    private void recordComputeToFragmentBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    private void generateMipmaps(VkCommandBuffer cmd, int bufferIndex) {
        long imageHandle = gBuffer.getReadTargetImage(bufferIndex);
        if (imageHandle == VK_NULL_HANDLE) return;

        int targetWidth = gBuffer.getWidth();
        int targetHeight = gBuffer.getHeight();
        int mipLevels = gBuffer.getMipLevels(bufferIndex);
        if (mipLevels <= 1) return;

        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .image(imageHandle)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            int mipW = targetWidth;
            int mipH = targetHeight;

            for (int i = 1; i < mipLevels; i++) {
                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                       .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                       .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                       .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                        null, null, barrier);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0).set(0, 0, 0);
                blit.srcOffsets(1).set(mipW, mipH, 1);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i - 1).baseArrayLayer(0).layerCount(1);

                int nextW = Math.max(1, mipW / 2);
                int nextH = Math.max(1, mipH / 2);

                blit.dstOffsets(0).set(0, 0, 0);
                blit.dstOffsets(1).set(nextW, nextH, 1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i).baseArrayLayer(0).layerCount(1);

                vkCmdBlitImage(cmd,
                        imageHandle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        imageHandle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit, VK_FILTER_LINEAR);

                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                       .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                       .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                       .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                        null, null, barrier);

                mipW = nextW;
                mipH = nextH;
            }

            // Last mip: TRANSFER_DST → SHADER_READ_ONLY
            barrier.subresourceRange().baseMipLevel(mipLevels - 1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                   .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                   .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                   .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barrier);
        }
    }

    // ── Getters ──

    public CompositePass.Category getCategory() { return category; }
    public int getActivePassCount() { return activePassCount; }
    public int getTotalPassCount() { return passes.size(); }
    public long getLastRenderTimeNs() { return lastRenderTimeNs; }
    public List<CompositePass> getPasses() { return passes; }

    /**
     * Gets a pass by index.
     */
    public CompositePass getPass(int index) {
        if (index >= 0 && index < passes.size()) {
            return passes.get(index);
        }
        return null;
    }

    // ── Lifecycle ──

    public void destroy() {
        for (MRTRenderPass rp : renderPassCache) {
            if (rp != null) rp.destroy();
        }
        passes.clear();
        activePassCount = 0;
        LOGGER.debug("{} pass manager destroyed", category);
    }
}
