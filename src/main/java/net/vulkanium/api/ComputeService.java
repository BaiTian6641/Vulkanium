package net.vulkanium.api;

/**
 * GPU compute service for submitting general-purpose compute tasks.
 *
 * <p>This is the primary API for mods to leverage Vulkan compute shaders.
 * Tasks are compiled, dispatched, and completed asynchronously. Results are
 * delivered via {@link ComputeFuture}.</p>
 *
 * <h3>Design Goals (C2ME-inspired)</h3>
 * <ul>
 *   <li><b>Zero-copy where possible:</b> Input/output buffers are device-local
 *       with staging for readback</li>
 *   <li><b>Pipeline caching:</b> SPIR-V compilation cached on disk,
 *       Vulkan pipeline cache persisted between sessions</li>
 *   <li><b>Batching:</b> Multiple tasks dispatched in a single command buffer
 *       submission for reduced overhead</li>
 *   <li><b>Priority scheduling:</b> Critical tasks (lighting) run before
 *       speculative tasks (pre-generation)</li>
 * </ul>
 *
 * <h3>Resource Limits</h3>
 * <p>The compute service applies per-frame budgets to avoid starving the
 * render pipeline:</p>
 * <ul>
 *   <li>Max outstanding tasks: 128</li>
 *   <li>Max in-flight batches: 3 (pipelined with render frames)</li>
 *   <li>Max output buffer per task: 256 MB</li>
 *   <li>Max uniform buffer per task: 64 KB</li>
 * </ul>
 *
 * @see ComputeTask
 * @see ComputeFuture
 */
public interface ComputeService {

    /** Maximum output buffer size per task (256 MB) */
    long MAX_OUTPUT_BUFFER_SIZE = 256L * 1024 * 1024;

    /** Maximum uniform buffer size per task (64 KB) */
    int MAX_UNIFORM_BUFFER_SIZE = 65536;

    /**
     * Submits a compute task for GPU execution.
     *
     * <p>The task's shader is compiled (or retrieved from cache), input buffers
     * are filled, and the dispatch is queued. The returned future completes when
     * the GPU output has been read back and processed.</p>
     *
     * @param task The compute task to execute
     * @param <T>  Result type
     * @return Future that completes with the processed result
     * @throws IllegalArgumentException if task parameters exceed resource limits
     */
    <T> ComputeFuture<T> submit(ComputeTask<T> task);

    /**
     * Submits a critical-priority compute task.
     *
     * <p>Critical tasks are dispatched in the current frame's batch even if
     * the normal budget is exhausted. Use sparingly for time-sensitive work
     * like lighting updates that block chunk rendering.</p>
     *
     * @param task The compute task to execute
     * @param <T>  Result type
     * @return Future that completes with the processed result
     */
    <T> ComputeFuture<T> submitCritical(ComputeTask<T> task);

    /**
     * Whether the compute service is operational.
     *
     * <p>Returns false if:</p>
     * <ul>
     *   <li>Vulkanium is not loaded</li>
     *   <li>The Vulkan device doesn't support compute queues</li>
     *   <li>Compute was disabled in configuration</li>
     * </ul>
     */
    boolean isAvailable();
}
