package net.vulkanium.api;

/**
 * A GPU compute task that can be submitted to Vulkanium's compute scheduler.
 *
 * <p>Implement this interface to define GPU compute work. The compute service
 * manages pipeline creation, descriptor binding, command recording, and
 * synchronization automatically.</p>
 *
 * <h3>Example: World Generation Noise</h3>
 * <pre>{@code
 *   public class NoiseComputeTask implements ComputeTask<float[]> {
 *       private final int seed;
 *       private final int chunkX, chunkZ;
 *
 *       @Override
 *       public String getShaderSource() {
 *           return """
 *               #version 450
 *               layout(local_size_x = 16, local_size_y = 16) in;
 *               layout(set = 0, binding = 0) uniform Params {
 *                   int seed; int chunkX; int chunkZ;
 *               };
 *               layout(set = 0, binding = 1) buffer Output { float values[]; };
 *               void main() {
 *                   uvec2 pos = gl_GlobalInvocationID.xy;
 *                   // ... noise calculation ...
 *                   values[pos.y * 16 + pos.x] = noise;
 *               }
 *           """;
 *       }
 *
 *       @Override public int workGroupCountX() { return 1; }
 *       @Override public int workGroupCountY() { return 1; }
 *       @Override public int workGroupCountZ() { return 1; }
 *       @Override public int outputBufferSize() { return 16 * 16 * 4; }
 *       @Override public String getTaskName() { return "noise_gen"; }
 *   }
 * }</pre>
 *
 * <h3>Data Flow</h3>
 * <pre>
 *   1. Task submitted → shader compiled &amp; cached
 *   2. Input data uploaded to GPU (optional uniform buffer + SSBO)
 *   3. Compute dispatch recorded
 *   4. Output SSBO read back to CPU
 *   5. {@link #processResult(java.nio.ByteBuffer)} called with output data
 *   6. Result delivered via {@link ComputeFuture}
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Task instances are accessed from multiple threads. All methods must be
 * safe for concurrent access. The shader source and dispatch parameters are
 * read once at submission time.</p>
 *
 * @param <T> The result type after processing GPU output
 */
public interface ComputeTask<T> {

    /**
     * Task priority levels for GPU scheduling.
     */
    enum Priority {
        LOW, NORMAL, HIGH, CRITICAL;
    }

    /**
     * Returns the GLSL 450 compute shader source.
     *
     * <p>The shader must declare:</p>
     * <ul>
     *   <li>{@code layout(local_size_x=X, local_size_y=Y, local_size_z=Z) in;} — workgroup size</li>
     *   <li>{@code layout(set=0, binding=0) uniform Params { ... };} — optional parameters</li>
     *   <li>{@code layout(set=0, binding=1) buffer InputData { ... };} — optional input SSBO</li>
     *   <li>{@code layout(set=0, binding=2) buffer OutputData { ... };} — output SSBO</li>
     * </ul>
     *
     * <p>The source is compiled to SPIR-V and cached by shader name + content hash.</p>
     */
    String getShaderSource();

    /** Number of workgroups to dispatch in X dimension. */
    default int workGroupCountX() { return 1; }

    /** Number of workgroups to dispatch in Y dimension. */
    default int workGroupCountY() { return 1; }

    /** Number of workgroups to dispatch in Z dimension. Default: 1 */
    default int workGroupCountZ() { return 1; }

    /**
     * Size in bytes of the uniform parameter buffer (binding 0).
     *
     * @return Size in bytes, or 0 if no uniform parameters
     */
    default int uniformBufferSize() { return 0; }

    /**
     * Writes uniform parameters to the given buffer.
     * Called on the submission thread before dispatch.
     *
     * @param buffer Direct ByteBuffer to write parameters into
     */
    default void writeUniforms(java.nio.ByteBuffer buffer) {}

    /**
     * Size in bytes of the input SSBO (binding 1).
     *
     * @return Size in bytes, or 0 if no input data
     */
    default int inputBufferSize() { return 0; }

    /**
     * Writes input data to the given buffer.
     * Called on the submission thread before dispatch.
     *
     * @param buffer Direct ByteBuffer to write input data into
     */
    default void writeInputData(java.nio.ByteBuffer buffer) {}

    /**
     * Size in bytes of the output SSBO (binding 2).
     * This buffer is read back from the GPU after dispatch completes.
     *
     * @return Size in bytes. Must be > 0.
     */
    default int outputBufferSize() { return 0; }

    /**
     * Process the GPU output buffer into a result object.
     * Called on a worker thread after the GPU dispatch completes and
     * the output buffer has been read back.
     *
     * @param outputBuffer Read-only ByteBuffer containing GPU output
     * @return The processed result
     */
    @SuppressWarnings("unchecked")
    default T processResult(java.nio.ByteBuffer outputBuffer) { return (T) outputBuffer; }

    /**
     * A human-readable name for this task (used in profiling and logging).
     */
    default String getTaskName() { return "unnamed_task"; }

    /**
     * Optional priority hint. Higher values = higher priority.
     * Default tasks run at priority 0 (normal).
     *
     * @return Priority value: -1 = low, 0 = normal, 1 = high, 2 = critical
     */
    default int priority() { return 0; }

    /**
     * Whether this task's shader should use relaxed precision (mediump).
     * Can improve performance on some GPUs for tasks that don't need float32.
     *
     * @return true to enable relaxed precision
     */
    default boolean useRelaxedPrecision() { return false; }

    /** Workgroup size as [x, y, z]. Used by compute module anonymous tasks. */
    default int[] getWorkGroupSize() { return new int[]{1, 1, 1}; }

    /** Dispatch size as [x, y, z]. Used by compute module anonymous tasks. */
    default int[] getDispatchSize() { return new int[]{workGroupCountX(), workGroupCountY(), workGroupCountZ()}; }

    /** Task priority for scheduling. */
    default Priority getPriority() { return Priority.NORMAL; }

    /** Tasks this task depends on (must complete before this runs). */
    default java.util.List<ComputeTask<?>> getDependencies() { return java.util.Collections.emptyList(); }
}
