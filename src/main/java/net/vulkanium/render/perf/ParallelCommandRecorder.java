package net.vulkanium.render.perf;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Records Vulkan commands in parallel using secondary command buffers.
 *
 * <p>Vulkan's explicit threading model allows command buffers to be recorded simultaneously
 * on multiple CPU threads. This class manages a pool of secondary command buffers —
 * one per worker thread — that record draw calls in parallel and then get executed
 * by a primary command buffer.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Thread 0 (main):     Primary CB → vkCmdExecuteCommands(secondary0, secondary1, ...)
 *   Thread 1 (worker 1): Secondary CB 0 → record terrain region draws
 *   Thread 2 (worker 2): Secondary CB 1 → record terrain region draws
 *   Thread N (worker N): Secondary CB N → record terrain region draws
 * </pre>
 *
 * <h3>Usage Pattern</h3>
 * <ol>
 *   <li>{@link #begin} — Reset and begin all secondary command buffers</li>
 *   <li>{@link #getSecondaryBuffer} — Workers grab their thread-local buffer</li>
 *   <li>Workers record draw calls into their secondary buffers (parallel)</li>
 *   <li>{@link #end} — End all secondary buffers</li>
 *   <li>{@link #executeAll} — Primary buffer executes all secondaries</li>
 * </ol>
 *
 * <h3>Benefits</h3>
 * <ul>
 *   <li>Command recording scales with CPU cores (major bottleneck in GL)</li>
 *   <li>Each thread has its own command buffer — no synchronization needed during recording</li>
 *   <li>Secondary buffers can inherit render pass state from primary</li>
 * </ul>
 */
public class ParallelCommandRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ParallelCmd");

    /** Maximum worker threads for parallel recording */
    public static final int MAX_WORKERS = 8;

    // ── State ──
    private final long device;
    private final int queueFamilyIndex;
    private int workerCount;

    /** One command pool per thread (VK mandates pool-per-thread) */
    private final long[] commandPools = new long[MAX_WORKERS];

    /** One secondary command buffer per thread per frame-in-flight */
    private final long[][] secondaryBuffers;  // [frame][worker]
    private final boolean[] bufferActive;

    /** Current frame index for double/triple buffering */
    private int currentFrame = 0;
    private int framesInFlight;

    /** Work distribution */
    private final AtomicInteger nextWorkItem = new AtomicInteger(0);
    private int totalWorkItems = 0;

    public ParallelCommandRecorder(long device, int queueFamilyIndex,
                                    int workerCount, int framesInFlight) {
        this.device = device;
        this.queueFamilyIndex = queueFamilyIndex;
        this.workerCount = Math.min(workerCount, MAX_WORKERS);
        this.framesInFlight = framesInFlight;
        this.secondaryBuffers = new long[framesInFlight][this.workerCount];
        this.bufferActive = new boolean[this.workerCount];

        createCommandPools();
        allocateSecondaryBuffers();

        LOGGER.info("Parallel command recorder: {} workers, {} frames in flight",
                this.workerCount, framesInFlight);
    }

    private void createCommandPools() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < workerCount; i++) {
                VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .queueFamilyIndex(queueFamilyIndex)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

                LongBuffer pPool = stack.mallocLong(1);
                int result = vkCreateCommandPool(vkDevice, ci, null, pPool);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create worker command pool " + i + ": VkResult " + result);
                }
                commandPools[i] = pPool.get(0);
            }
        }
    }

    private void allocateSecondaryBuffers() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            for (int frame = 0; frame < framesInFlight; frame++) {
                for (int worker = 0; worker < workerCount; worker++) {
                    VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                            .commandPool(commandPools[worker])
                            .level(VK_COMMAND_BUFFER_LEVEL_SECONDARY)
                            .commandBufferCount(1);

                    PointerBuffer pBuffer = stack.mallocPointer(1);
                    int result = vkAllocateCommandBuffers(vkDevice, allocInfo, pBuffer);
                    if (result != VK_SUCCESS) {
                        throw new RuntimeException("Failed to allocate secondary CB: VkResult " + result);
                    }
                    secondaryBuffers[frame][worker] = pBuffer.get(0);
                }
            }
        }
    }

    /**
     * Begins all secondary command buffers for recording.
     *
     * @param frameIndex       Current frame-in-flight index
     * @param renderPass       Render pass to inherit
     * @param framebuffer      Framebuffer to inherit
     * @param totalWorkItems   Number of work items to distribute across workers
     */
    public void begin(int frameIndex, long renderPass, long framebuffer, int totalWorkItems) {
        this.currentFrame = frameIndex;
        this.totalWorkItems = totalWorkItems;
        this.nextWorkItem.set(0);

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferInheritanceInfo inheritance = VkCommandBufferInheritanceInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            | VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT)
                    .pInheritanceInfo(inheritance);

            for (int i = 0; i < workerCount; i++) {
                VkCommandBuffer cmd = new VkCommandBuffer(secondaryBuffers[currentFrame][i], vkDevice);
                vkBeginCommandBuffer(cmd, beginInfo);
                bufferActive[i] = true;
            }
        }
    }

    /**
     * Gets the secondary command buffer for a specific worker thread.
     * Thread-safe — each worker should only access its own buffer.
     */
    public long getSecondaryBuffer(int workerIndex) {
        if (workerIndex < 0 || workerIndex >= workerCount) return VK_NULL_HANDLE;
        return secondaryBuffers[currentFrame][workerIndex];
    }

    /**
     * Claims the next work item atomically (for work-stealing pattern).
     *
     * @return Work item index, or -1 if all items claimed
     */
    public int claimNextWorkItem() {
        int item = nextWorkItem.getAndIncrement();
        return item < totalWorkItems ? item : -1;
    }

    /**
     * Ends all active secondary command buffers.
     */
    public void end() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        for (int i = 0; i < workerCount; i++) {
            if (bufferActive[i]) {
                VkCommandBuffer cmd = new VkCommandBuffer(secondaryBuffers[currentFrame][i], vkDevice);
                vkEndCommandBuffer(cmd);
                bufferActive[i] = false;
            }
        }
    }

    /**
     * Executes all secondary command buffers via the primary command buffer.
     *
     * @param primaryBuffer The primary command buffer (must be in a render pass)
     */
    public void executeAll(long primaryBuffer) {
        VkCommandBuffer primary = new VkCommandBuffer(primaryBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pSecondaries = stack.mallocPointer(workerCount);
            int count = 0;
            for (int i = 0; i < workerCount; i++) {
                if (secondaryBuffers[currentFrame][i] != VK_NULL_HANDLE) {
                    pSecondaries.put(count++, secondaryBuffers[currentFrame][i]);
                }
            }
            if (count > 0) {
                pSecondaries.limit(count);
                vkCmdExecuteCommands(primary, pSecondaries);
            }
        }
    }

    // ── Getters ──

    public int getWorkerCount() { return workerCount; }

    // ── Lifecycle ──

    public void destroy() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        for (int i = 0; i < workerCount; i++) {
            if (commandPools[i] != VK_NULL_HANDLE) {
                vkDestroyCommandPool(vkDevice, commandPools[i], null);
                commandPools[i] = VK_NULL_HANDLE;
            }
        }
        LOGGER.debug("Parallel command recorder destroyed");
    }
}
