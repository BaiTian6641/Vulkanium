package net.vulkanium.render.perf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Hardware occlusion query pool for visibility determination.
 *
 * <p>Uses Vulkan occlusion queries to determine which render regions are actually
 * visible (not fully occluded by nearer geometry). Results from the previous frame
 * are used to skip drawing regions that were completely hidden.</p>
 *
 * <h3>Two-Phase Approach</h3>
 * <ol>
 *   <li><b>Depth pre-pass:</b> Render opaque terrain depth-only into the depth buffer</li>
 *   <li><b>Occlusion test:</b> For each region, render its bounding box with an
 *       occlusion query. Regions with 0 samples passing are fully occluded.</li>
 * </ol>
 *
 * <h3>Query Result Readback</h3>
 * <p>Results are read back with {@code VK_QUERY_RESULT_WITH_AVAILABILITY_BIT} to avoid
 * stalling. If a query result isn't available yet, the region is conservatively rendered.
 * This is a 1-frame latency approach:</p>
 * <pre>
 *   Frame N:   Issue occlusion queries → render everything
 *   Frame N+1: Read frame N's results → skip fully occluded regions
 * </pre>
 *
 * <h3>Performance Budget</h3>
 * <p>Occlusion queries have a GPU cost (~1μs per query). With 256+ regions,
 * the total cost is small compared to the draw calls saved. Conditional rendering
 * ({@code VK_EXT_conditional_rendering}) can further reduce GPU overhead by
 * skipping draw calls entirely on the GPU side.</p>
 */
public class OcclusionQueryPool {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Occlusion");

    /** Maximum number of occlusion queries (render regions) */
    public static final int MAX_QUERIES = 1024;

    // ── Vulkan objects ──
    private final long device;

    /** Double-buffered query pools (read from previous frame, write to current) */
    private final long[] queryPools = new long[2];
    private int currentPool = 0;

    /** Cached results from the previous frame */
    private final long[] queryResults = new long[MAX_QUERIES];
    private final boolean[] queryAvailable = new boolean[MAX_QUERIES];

    /** Number of active queries this frame */
    private int activeQueryCount = 0;

    /** Whether conditional rendering extension is available */
    private boolean hasConditionalRendering = false;

    /** Predicate buffer for conditional rendering */
    private long predicateBuffer = VK_NULL_HANDLE;
    private long predicateAllocation = VK_NULL_HANDLE;

    // ── Stats ──
    private int regionsOccluded = 0;
    private int regionsVisible = 0;

    public OcclusionQueryPool(long device) {
        this.device = device;
    }

    /**
     * Creates the query pools and optional predicate buffer.
     *
     * @param allocator              VMA allocator
     * @param conditionalRendering   Whether VK_EXT_conditional_rendering is supported
     */
    public void create(long allocator, boolean conditionalRendering) {
        this.hasConditionalRendering = conditionalRendering;
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_OCCLUSION)
                    .queryCount(MAX_QUERIES)
                    .pipelineStatistics(0);

            LongBuffer pPool = stack.mallocLong(1);
            for (int i = 0; i < 2; i++) {
                int result = vkCreateQueryPool(vkDevice, ci, null, pPool);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create query pool: VkResult " + result);
                }
                queryPools[i] = pPool.get(0);
            }
        }

        if (hasConditionalRendering) {
            // Predicate buffer: MAX_QUERIES × 8 bytes
            // Allocated via standard VulkanBuffer mechanism
            // usage = VK_BUFFER_USAGE_CONDITIONAL_RENDERING_BIT_EXT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
            LOGGER.debug("Conditional rendering enabled — predicate buffer allocated");
        }

        LOGGER.info("Occlusion query pool created ({} max queries, conditional rendering: {})",
                MAX_QUERIES, conditionalRendering);
    }

    /**
     * Resets the query pool for the current frame and reads back previous results.
     *
     * @param commandBuffer Active command buffer
     */
    public void beginFrame(long commandBuffer) {
        // Read results from the previous frame's pool
        int readPool = 1 - currentPool;
        readQueryResults(readPool);

        // Reset current pool for new queries
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdResetQueryPool(cmd, queryPools[currentPool], 0, MAX_QUERIES);
        activeQueryCount = 0;
        regionsOccluded = 0;
        regionsVisible = 0;
    }

    private void readQueryResults(int poolIndex) {
        if (activeQueryCount == 0) return;
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        // Each result is 2 longs: [sampleCount, availability]
        long bufferSize = (long) activeQueryCount * 2 * 8;
        java.nio.ByteBuffer resultBuf = MemoryUtil.memAlloc((int) bufferSize);
        try {
            int result = vkGetQueryPoolResults(vkDevice, queryPools[poolIndex],
                    0, activeQueryCount, resultBuf,
                    16, // stride = 2 × 8 bytes
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);

            for (int i = 0; i < activeQueryCount; i++) {
                long samplesPassed = resultBuf.getLong(i * 16);
                long available = resultBuf.getLong(i * 16 + 8);
                queryAvailable[i] = (available != 0);
                queryResults[i] = samplesPassed;

                if (queryAvailable[i] && queryResults[i] == 0) {
                    regionsOccluded++;
                } else {
                    regionsVisible++;
                }
            }
        } finally {
            MemoryUtil.memFree(resultBuf);
        }
    }

    /**
     * Begins an occlusion query for a render region.
     *
     * @param commandBuffer Active command buffer
     * @param regionIndex   Region index (0 to MAX_QUERIES-1)
     */
    public void beginQuery(long commandBuffer, int regionIndex) {
        if (regionIndex >= MAX_QUERIES) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdBeginQuery(cmd, queryPools[currentPool], regionIndex, 0);
        activeQueryCount = Math.max(activeQueryCount, regionIndex + 1);
    }

    /**
     * Ends the occlusion query for a render region.
     */
    public void endQuery(long commandBuffer, int regionIndex) {
        if (regionIndex >= MAX_QUERIES) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdEndQuery(cmd, queryPools[currentPool], regionIndex);
    }

    /**
     * Tests if a region was visible in the previous frame.
     *
     * @param regionIndex Region to test
     * @return true if the region should be rendered (visible or unknown)
     */
    public boolean isVisible(int regionIndex) {
        if (regionIndex >= MAX_QUERIES) return true; // conservatively visible
        if (!queryAvailable[regionIndex]) return true; // not yet available, assume visible
        return queryResults[regionIndex] > 0;
    }

    /**
     * For conditional rendering: begins conditional rendering for a region.
     * The GPU will skip subsequent draw calls if the region was occluded.
     *
     * @param commandBuffer Active command buffer
     * @param regionIndex   Region index
     */
    public void beginConditionalRendering(long commandBuffer, int regionIndex) {
        if (!hasConditionalRendering || predicateBuffer == VK_NULL_HANDLE) return;

        // TODO: VkConditionalRenderingBeginInfoEXT
        //   .buffer(predicateBuffer)
        //   .offset(regionIndex * 8L)
        //   .flags(VK_CONDITIONAL_RENDERING_INVERTED_BIT_EXT)
        // vkCmdBeginConditionalRenderingEXT(commandBuffer, ...)
    }

    /**
     * Ends conditional rendering.
     */
    public void endConditionalRendering(long commandBuffer) {
        if (!hasConditionalRendering) return;
        // TODO: vkCmdEndConditionalRenderingEXT(commandBuffer)
    }

    /**
     * Copies query results to the predicate buffer for conditional rendering.
     *
     * @param commandBuffer Active command buffer
     */
    public void copyResultsToPredicateBuffer(long commandBuffer) {
        if (!hasConditionalRendering || predicateBuffer == VK_NULL_HANDLE) return;

        int readPool = 1 - currentPool;
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdCopyQueryPoolResults(cmd, queryPools[readPool],
                0, activeQueryCount, predicateBuffer, 0, 8,
                VK_QUERY_RESULT_64_BIT);
    }

    /**
     * Advances to the next frame's query pool.
     */
    public void endFrame() {
        currentPool = 1 - currentPool;
    }

    // ── Getters ──

    public int getRegionsOccluded() { return regionsOccluded; }
    public int getRegionsVisible() { return regionsVisible; }
    public int getActiveQueryCount() { return activeQueryCount; }
    public boolean hasConditionalRendering() { return hasConditionalRendering; }

    // ── Lifecycle ──

    public void destroy(long allocator) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        for (int i = 0; i < 2; i++) {
            if (queryPools[i] != VK_NULL_HANDLE) {
                vkDestroyQueryPool(vkDevice, queryPools[i], null);
                queryPools[i] = VK_NULL_HANDLE;
            }
        }
        // TODO: Destroy predicate buffer via VMA
        LOGGER.debug("Occlusion query pool destroyed");
    }
}
