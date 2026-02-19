package net.vulkanium.api;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Context passed to {@link WorldModule} callbacks during frame rendering.
 *
 * <p>Provides read-only access to the current frame's state and limited write
 * access for recording GPU commands. Modules should not cache this reference
 * across frames — a new context is provided each frame.</p>
 *
 * <h3>Available Data</h3>
 * <ul>
 *   <li><b>Matrices:</b> Projection, modelView, combined MVP, and their inverses</li>
 *   <li><b>Camera:</b> Position, direction, FOV, near/far planes</li>
 *   <li><b>Frame:</b> Frame counter, delta time, game time, render tick</li>
 *   <li><b>World:</b> Dimension, biome, weather, time of day</li>
 *   <li><b>Screen:</b> Resolution, aspect ratio, GUI scale</li>
 *   <li><b>RT:</b> TLAS handle (if RT phases), acceleration structure state</li>
 * </ul>
 *
 * <h3>Command Buffer Access</h3>
 * <p>The context provides the current command buffer only during phase callbacks.
 * Modules can record Vulkan commands directly (advanced) or use the higher-level
 * helpers for common operations.</p>
 */
public interface WorldModuleContext {

    // ── Matrices ──

    /** Current frame's projection matrix. */
    Matrix4f getProjectionMatrix();

    /** Current frame's model-view matrix. */
    Matrix4f getModelViewMatrix();

    /** Projection × ModelView (pre-multiplied). */
    Matrix4f getMVPMatrix();

    /** Inverse projection matrix. */
    Matrix4f getInverseProjectionMatrix();

    /** Inverse model-view matrix. */
    Matrix4f getInverseModelViewMatrix();

    /** Previous frame's model-view matrix (for motion vectors / TAA). */
    Matrix4f getPreviousModelViewMatrix();

    /** Previous frame's projection matrix. */
    Matrix4f getPreviousProjectionMatrix();

    /** Shadow projection matrix (if in shadow phase). */
    Matrix4f getShadowProjectionMatrix();

    /** Shadow model-view matrix (if in shadow phase). */
    Matrix4f getShadowModelViewMatrix();

    // ── Camera ──

    /** Camera world position (double precision). */
    Vector3d getCameraPosition();

    /** Camera look direction (normalized). */
    org.joml.Vector3f getCameraDirection();

    /** Horizontal field of view in radians. */
    float getFOV();

    /** Near plane distance. */
    float getNearPlane();

    /** Far plane distance. */
    float getFarPlane();

    // ── Frame Timing ──

    /** Monotonically increasing frame counter. */
    long getFrameCounter();

    /** Time since last frame in seconds. */
    float getDeltaTime();

    /** Game time in ticks (20 ticks/second). */
    int getGameTime();

    /** Render tick delta (0–1 between game ticks). */
    float getRenderTickDelta();

    /** World time of day (0–24000). */
    int getWorldTime();

    // ── World State ──

    /** Current dimension ID (e.g., "minecraft:overworld"). */
    String getDimensionId();

    /** Rain strength (0.0–1.0, smoothed). */
    float getRainStrength();

    /** Thunder strength (0.0–1.0, smoothed). */
    float getThunderStrength();

    /** Sky brightness based on celestial angle. */
    float getSkyBrightness();

    /** Block light at camera position (0–15). */
    int getCameraBlockLight();

    /** Sky light at camera position (0–15). */
    int getCameraSkyLight();

    // ── Screen ──

    /** Render width in pixels. */
    int getScreenWidth();

    /** Render height in pixels. */
    int getScreenHeight();

    /** Aspect ratio (width / height). */
    float getAspectRatio();

    // ── GPU Resources ──

    /**
     * Current frame's active command buffer handle (VkCommandBuffer).
     * Only valid during phase callbacks. Returns 0 outside of phased rendering.
     */
    long getCommandBuffer();

    /**
     * The Vulkanium uniform buffer handle for this frame.
     * Contains all standard uniforms (matrices, positions, time, etc.).
     * Bind at set=0, binding=0 for shader access.
     */
    long getUniformBuffer();

    /**
     * Descriptor set containing the standard uniforms.
     * Pre-bound for convenience — modules can use additional descriptor sets
     * starting at set=1.
     */
    long getUniformDescriptorSet();

    // ── Ray Tracing (only valid during RT phases, 0 otherwise) ──

    /**
     * The TLAS VkAccelerationStructureKHR handle for the current frame.
     * Only available during {@code ModulePhase.RT_DISPATCH} and
     * {@code ModulePhase.RT_DENOISE}.
     *
     * @return TLAS handle, or 0 if RT is not available
     */
    long getTLASHandle();

    /**
     * Number of instances (BLASes) in the current frame's TLAS.
     */
    int getTLASInstanceCount();

    // ── Helpers ──

    /**
     * Records a pipeline barrier in the current command buffer.
     *
     * @param srcStage Source pipeline stage mask
     * @param dstStage Destination pipeline stage mask
     * @param srcAccess Source access mask
     * @param dstAccess Destination access mask
     */
    void pipelineBarrier(int srcStage, int dstStage, int srcAccess, int dstAccess);

    /**
     * Binds a compute pipeline for dispatch.
     *
     * @param pipeline VkPipeline handle
     */
    void bindComputePipeline(long pipeline);

    /**
     * Dispatches a compute shader.
     *
     * @param groupCountX Workgroup count X
     * @param groupCountY Workgroup count Y
     * @param groupCountZ Workgroup count Z
     */
    void dispatch(int groupCountX, int groupCountY, int groupCountZ);
}
