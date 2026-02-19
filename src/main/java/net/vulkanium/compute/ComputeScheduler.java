package net.vulkanium.compute;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compute task scheduler — the core of Vulkanium's GP-Computing platform.
 *
 * <p>Inspired by C2ME's per-concern threading model, this scheduler provides a
 * Vulkan-native compute pipeline that external mods can use to offload parallel
 * workloads to the GPU. Unlike C2ME which uses CPU thread pools, this leverages
 * the GPU's thousands of cores for embarrassingly parallel tasks.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   ┌────────────────────────────────────────────────────┐
 *   │  Mod API (ComputeTask interface)                   │
 *   │  submit(task) → ComputeFuture                      │
 *   └────────────┬───────────────────────────────────────┘
 *                 │
 *   ┌────────────▼───────────────────────────────────────┐
 *   │  ComputeScheduler                                  │
 *   │  ┌──────────────┐  ┌───────────────┐              │
 *   │  │ Priority Queue│  │ Timeline Sema │              │
 *   │  │ (pending)     │  │ (completion)  │              │
 *   │  └──────┬───────┘  └───────────────┘              │
 *   │         │                                          │
 *   │  ┌──────▼───────────────────────────┐              │
 *   │  │ Batch Builder                     │              │
 *   │  │ Groups tasks by pipeline/layout   │              │
 *   │  │ Records command buffer            │              │
 *   │  └──────┬───────────────────────────┘              │
 *   │         │                                          │
 *   │  ┌──────▼───────────────────────────┐              │
 *   │  │ Queue Submit (compute queue)      │              │
 *   │  │ Timeline semaphore signaling      │              │
 *   │  └──────────────────────────────────┘              │
 *   └────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Task Priorities</h3>
 * <ul>
 *   <li><b>CRITICAL:</b> Must complete this frame (frustum culling, indirect build)</li>
 *   <li><b>HIGH:</b> Should complete this frame (translucent sort, lighting)</li>
 *   <li><b>NORMAL:</b> Complete within a few frames (pathfinding, physics)</li>
 *   <li><b>LOW:</b> Background work (world gen assistance, analysis)</li>
 * </ul>
 *
 * <h3>Batching Strategy</h3>
 * <p>Tasks submitted in the same frame are grouped by pipeline and recorded into
 * a single command buffer. This minimizes pipeline state changes and command buffer
 * overhead. Timeline semaphores track completion per-batch without host-device sync.</p>
 *
 * <h3>C2ME Integration Points</h3>
 * <p>The compute platform exposes tasks that map to C2ME's parallelization concerns:</p>
 * <ul>
 *   <li>Chunk lighting calculation (on GPU: compute shader with 3D workgroup)</li>
 *   <li>Chunk noise generation (seed → noise map in compute shader)</li>
 *   <li>Pathfinding (A* on GPU: compute shader with graph SSBO)</li>
 *   <li>Physics simulation (entity collision broad-phase via compute)</li>
 * </ul>
 */
public class ComputeScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ComputeScheduler");

    /** Maximum tasks per batch */
    private static final int MAX_BATCH_SIZE = 128;

    /** Maximum batches in flight */
    private static final int MAX_IN_FLIGHT = 3;

    /**
     * Task priority levels.
     */
    public enum Priority {
        CRITICAL(0), HIGH(1), NORMAL(2), LOW(3);

        public final int order;
        Priority(int order) { this.order = order; }
    }

    /**
     * A compute task ready for GPU dispatch.
     */
    public static class ComputeTask implements Comparable<ComputeTask> {
        /** Unique task ID */
        public final long id;
        /** Human-readable name */
        public final String name;
        /** Pipeline to use */
        public final long pipeline;
        /** Pipeline layout */
        public final long pipelineLayout;
        /** Descriptor sets to bind */
        public final long[] descriptorSets;
        /** Workgroup counts (x, y, z) */
        public final int groupCountX, groupCountY, groupCountZ;
        /** Optional push constant data (null if none) */
        public final java.nio.ByteBuffer pushConstants;
        /** Task priority */
        public final Priority priority;
        /** Completion callback (called on GPU completion) */
        public final CompletableFuture<Void> future;

        public ComputeTask(long id, String name, long pipeline, long pipelineLayout,
                           long[] descriptorSets, int groupCountX, int groupCountY,
                           int groupCountZ, java.nio.ByteBuffer pushConstants,
                           Priority priority) {
            this.id = id;
            this.name = name;
            this.pipeline = pipeline;
            this.pipelineLayout = pipelineLayout;
            this.descriptorSets = descriptorSets;
            this.groupCountX = groupCountX;
            this.groupCountY = groupCountY;
            this.groupCountZ = groupCountZ;
            this.pushConstants = pushConstants;
            this.priority = priority;
            this.future = new CompletableFuture<>();
        }

        @Override
        public int compareTo(ComputeTask other) {
            return Integer.compare(this.priority.order, other.priority.order);
        }
    }

    /**
     * A batch of compute tasks submitted together.
     */
    private static class ComputeBatch {
        final long commandBuffer;
        final long timelineValue;
        final List<ComputeTask> tasks;

        ComputeBatch(long commandBuffer, long timelineValue, List<ComputeTask> tasks) {
            this.commandBuffer = commandBuffer;
            this.timelineValue = timelineValue;
            this.tasks = tasks;
        }
    }

    // ── State ──
    private final VkDevice device;
    private final VulkaniumQueues queues;
    private final VulkaniumMemory memory;

    /** Pending tasks (priority queue) */
    private final PriorityBlockingQueue<ComputeTask> pendingTasks = new PriorityBlockingQueue<>();

    /** In-flight batches */
    private final Deque<ComputeBatch> inFlightBatches = new ArrayDeque<>();

    /** Timeline semaphore for batch completion tracking */
    private long timelineSemaphore = 0;
    private final AtomicLong nextTimelineValue = new AtomicLong(1);
    private long lastCompletedValue = 0;

    /** Command pool for compute command buffers */
    private long computeCommandPool = 0;

    /** Pre-allocated command buffers */
    private final Deque<Long> freeCommandBuffers = new ArrayDeque<>();

    /** Task ID generator */
    private final AtomicLong nextTaskId = new AtomicLong(1);

    /** Statistics */
    private long totalTasksSubmitted = 0;
    private long totalTasksCompleted = 0;
    private long totalBatchesSubmitted = 0;

    public ComputeScheduler(VkDevice device, VulkaniumQueues queues, VulkaniumMemory memory) {
        this.device = device;
        this.queues = queues;
        this.memory = memory;
    }

    /**
     * Initializes the compute scheduler with timeline semaphore and command pool.
     */
    public void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create timeline semaphore
            VkSemaphoreTypeCreateInfo timelineInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0);

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(timelineInfo);

            LongBuffer pSemaphore = stack.mallocLong(1);
            VK12.vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore);
            timelineSemaphore = pSemaphore.get(0);

            // Create command pool for compute queue
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queues.getIndices().computeFamily());

            LongBuffer pPool = stack.mallocLong(1);
            VK12.vkCreateCommandPool(device, poolInfo, null, pPool);
            computeCommandPool = pPool.get(0);

            // Pre-allocate command buffers
            preallocateCommandBuffers(MAX_IN_FLIGHT);
        }

        LOGGER.info("Compute scheduler initialized (timeline semaphore, {} cmd buffers)",
                freeCommandBuffers.size());
    }

    /**
     * Submits a compute task for GPU execution.
     *
     * @return CompletableFuture that completes when the GPU finishes the task
     */
    public CompletableFuture<Void> submit(String name, long pipeline, long pipelineLayout,
                                           long[] descriptorSets,
                                           int groupX, int groupY, int groupZ,
                                           java.nio.ByteBuffer pushConstants,
                                           Priority priority) {
        ComputeTask task = new ComputeTask(
                nextTaskId.getAndIncrement(), name, pipeline, pipelineLayout,
                descriptorSets, groupX, groupY, groupZ, pushConstants, priority);
        pendingTasks.add(task);
        totalTasksSubmitted++;
        return task.future;
    }

    /**
     * Submits a critical-priority task (must complete this frame).
     */
    public CompletableFuture<Void> submitCritical(String name, long pipeline, long pipelineLayout,
                                                   long[] descriptorSets,
                                                   int groupX, int groupY, int groupZ) {
        return submit(name, pipeline, pipelineLayout, descriptorSets,
                groupX, groupY, groupZ, null, Priority.CRITICAL);
    }

    /**
     * Flushes pending tasks into a batch and submits to the compute queue.
     * Call once per frame after all tasks have been submitted.
     */
    public void flush() {
        // Poll completed batches
        pollCompletedBatches();

        // Don't submit if too many batches in flight
        if (inFlightBatches.size() >= MAX_IN_FLIGHT) {
            LOGGER.debug("Max in-flight batches reached, deferring {} tasks", pendingTasks.size());
            return;
        }

        if (pendingTasks.isEmpty()) return;

        // Drain tasks into a batch
        List<ComputeTask> batch = new ArrayList<>(MAX_BATCH_SIZE);
        int count = 0;
        while (!pendingTasks.isEmpty() && count < MAX_BATCH_SIZE) {
            batch.add(pendingTasks.poll());
            count++;
        }

        if (batch.isEmpty()) return;

        // Record command buffer
        long cmdBuffer = acquireCommandBuffer();
        recordBatch(cmdBuffer, batch);

        // Submit with timeline semaphore
        long signalValue = nextTimelineValue.getAndIncrement();
        submitToComputeQueue(cmdBuffer, signalValue);

        inFlightBatches.addLast(new ComputeBatch(cmdBuffer, signalValue, batch));
        totalBatchesSubmitted++;

        LOGGER.debug("Submitted compute batch: {} tasks, timeline value {}", batch.size(), signalValue);
    }

    /**
     * Waits for all in-flight compute work to complete. Call on shutdown or sync points.
     */
    public void waitIdle() {
        if (timelineSemaphore == 0 || inFlightBatches.isEmpty()) return;

        long maxValue = 0;
        for (ComputeBatch batch : inFlightBatches) {
            maxValue = Math.max(maxValue, batch.timelineValue);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO)
                    .pSemaphores(stack.longs(timelineSemaphore))
                    .pValues(stack.longs(maxValue));

            VK12.vkWaitSemaphores(device, waitInfo, Long.MAX_VALUE);
        }

        // Complete all futures
        for (ComputeBatch batch : inFlightBatches) {
            for (ComputeTask task : batch.tasks) {
                task.future.complete(null);
                totalTasksCompleted++;
            }
            recycleCommandBuffer(batch.commandBuffer);
        }
        inFlightBatches.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal
    // ═══════════════════════════════════════════════════════════════

    private void pollCompletedBatches() {
        if (timelineSemaphore == 0 || inFlightBatches.isEmpty()) return;

        // Query current timeline value
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] counterValue = new long[1];
            VK12.vkGetSemaphoreCounterValue(device, timelineSemaphore, counterValue);
            long currentValue = counterValue[0];

            if (currentValue <= lastCompletedValue) return;
            lastCompletedValue = currentValue;

            // Complete batches whose timeline value ≤ current
            while (!inFlightBatches.isEmpty()) {
                ComputeBatch batch = inFlightBatches.peekFirst();
                if (batch.timelineValue > currentValue) break;

                inFlightBatches.pollFirst();
                for (ComputeTask task : batch.tasks) {
                    task.future.complete(null);
                    totalTasksCompleted++;
                }
                recycleCommandBuffer(batch.commandBuffer);
            }
        }
    }

    private void recordBatch(long cmdBuffer, List<ComputeTask> tasks) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer cmd = new VkCommandBuffer(cmdBuffer, device);
            VK12.vkBeginCommandBuffer(cmd, beginInfo);

            long lastPipeline = 0;
            long lastLayout = 0;

            for (ComputeTask task : tasks) {
                // Only rebind pipeline if it changed
                if (task.pipeline != lastPipeline) {
                    VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, task.pipeline);
                    lastPipeline = task.pipeline;
                }

                // Bind descriptor sets
                if (task.descriptorSets != null && task.descriptorSets.length > 0) {
                    LongBuffer pDescSets = stack.mallocLong(task.descriptorSets.length);
                    for (long ds : task.descriptorSets) {
                        pDescSets.put(ds);
                    }
                    pDescSets.flip();
                    VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                            task.pipelineLayout, 0, pDescSets, null);
                }

                // Push constants
                if (task.pushConstants != null) {
                    VK12.vkCmdPushConstants(cmd, task.pipelineLayout,
                            VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, task.pushConstants);
                }

                // Dispatch
                VK12.vkCmdDispatch(cmd, task.groupCountX, task.groupCountY, task.groupCountZ);

                // Memory barrier between dispatches (WAW hazard)
                if (tasks.indexOf(task) < tasks.size() - 1) {
                    VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack)
                            .sType(VK12.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                            .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                            .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
                    VK12.vkCmdPipelineBarrier(cmd,
                            VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            0, memBarrier, null, null);
                }
            }

            VK12.vkEndCommandBuffer(cmd);
        }
    }

    private void submitToComputeQueue(long cmdBuffer, long signalValue) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO)
                    .pSignalSemaphoreValues(stack.longs(signalValue));

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pNext(timelineInfo)
                    .pCommandBuffers(stack.pointers(cmdBuffer))
                    .pSignalSemaphores(stack.longs(timelineSemaphore));

            VkSubmitInfo.Buffer submitBuf = VkSubmitInfo.calloc(1, stack);
            submitBuf.put(0, submitInfo);
            VK12.vkQueueSubmit(queues.getComputeQueue(), submitBuf, VK12.VK_NULL_HANDLE);
        }
    }

    private long acquireCommandBuffer() {
        if (!freeCommandBuffers.isEmpty()) {
            return freeCommandBuffers.pollFirst();
        }
        preallocateCommandBuffers(4);
        return freeCommandBuffers.pollFirst();
    }

    private void recycleCommandBuffer(long cmdBuffer) {
        freeCommandBuffers.addLast(cmdBuffer);
    }

    private void preallocateCommandBuffers(int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(computeCommandPool)
                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(count);

            var pBuffers = stack.mallocPointer(count);
            VK12.vkAllocateCommandBuffers(device, allocInfo, pBuffers);

            for (int i = 0; i < count; i++) {
                freeCommandBuffers.addLast(pBuffers.get(i));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Statistics
    // ═══════════════════════════════════════════════════════════════

    public long getTotalTasksSubmitted() { return totalTasksSubmitted; }
    public long getTotalTasksCompleted() { return totalTasksCompleted; }
    public long getTotalBatchesSubmitted() { return totalBatchesSubmitted; }
    public int getPendingTaskCount() { return pendingTasks.size(); }
    public int getInFlightBatchCount() { return inFlightBatches.size(); }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    public void destroy() {
        waitIdle();

        if (timelineSemaphore != 0) {
            VK12.vkDestroySemaphore(device, timelineSemaphore, null);
            timelineSemaphore = 0;
        }
        if (computeCommandPool != 0) {
            VK12.vkDestroyCommandPool(device, computeCommandPool, null);
            computeCommandPool = 0;
        }
        freeCommandBuffers.clear();
        pendingTasks.clear();

        LOGGER.info("Compute scheduler destroyed. Tasks: {} submitted, {} completed, {} batches",
                totalTasksSubmitted, totalTasksCompleted, totalBatchesSubmitted);
    }
}
