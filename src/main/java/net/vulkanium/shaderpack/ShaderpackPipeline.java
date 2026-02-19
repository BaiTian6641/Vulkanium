package net.vulkanium.shaderpack;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Interface for a shaderpack rendering pipeline.
 *
 * <p>Implementations route MC's render phases through shaderpack-defined shader
 * programs. This follows the same pass structure as Iris/OptiFine: geometry
 * passes (gbuffers), shadow pass, deferred passes (lighting from G-buffer),
 * composite passes (post-processing effects), and a final pass.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #load(ShaderpackSource)} — Parse and compile shaderpack</li>
 *   <li>{@link #onFrameBegin(VkCommandBuffer, int)} — Per-frame setup (clear targets, update uniforms)</li>
 *   <li>{@link #beginPhase(ShaderPhase, VkCommandBuffer)} — Bind pipeline for a render phase</li>
 *   <li>MC issues draw calls for that phase (terrain, entities, etc.)</li>
 *   <li>{@link #endPhase(ShaderPhase, VkCommandBuffer)} — Finalize the phase</li>
 *   <li>Repeat for all phases</li>
 *   <li>{@link #onFrameEnd(VkCommandBuffer, int)} — Final pass, blit to swapchain</li>
 * </ol>
 *
 * <h2>Required Render Targets</h2>
 * <ul>
 *   <li>colortex0 — Main color (RGBA8)</li>
 *   <li>colortex1 — Normal buffer (RGB16F or RGB8)</li>
 *   <li>colortex2 — Specular/PBR data</li>
 *   <li>colortex3..colortex15 — Additional color attachments</li>
 *   <li>depthtex0 — Main depth buffer</li>
 *   <li>depthtex1 — Depth buffer without translucent geometry</li>
 *   <li>shadowtex0, shadowtex1 — Shadow depth buffers</li>
 *   <li>shadowcolor0, shadowcolor1 — Shadow color buffers</li>
 * </ul>
 */
public interface ShaderpackPipeline {

    /**
     * Loads and compiles a shaderpack from a source (directory or ZIP).
     *
     * <p>Parses {@code shaders.properties}, discovers shader programs,
     * compiles GLSL → SPIR-V, creates VkPipeline objects, and allocates
     * framebuffer attachments (colortex, depthtex, shadowtex, etc.).</p>
     *
     * @param source The shaderpack source to load from
     * @return true if the shaderpack loaded successfully
     */
    boolean load(ShaderpackSource source);

    /**
     * Unloads the current shaderpack and frees all GPU resources.
     */
    void unload();

    /**
     * Returns whether a shaderpack is currently loaded and active.
     */
    boolean isLoaded();

    /**
     * Called at the start of each frame before any render phases.
     *
     * <p>Typically: update per-frame uniforms (matrices, time, camera),
     * clear framebuffer attachments, begin the shadow render pass.</p>
     *
     * @param cmd         Active Vulkan command buffer
     * @param frameIndex  Current frame-in-flight index
     */
    void onFrameBegin(VkCommandBuffer cmd, int frameIndex);

    /**
     * Called at the end of each frame after all render phases.
     *
     * <p>Runs composite and final passes. The final pass renders the
     * composed image to the swapchain framebuffer.</p>
     *
     * @param cmd         Active Vulkan command buffer
     * @param frameIndex  Current frame-in-flight index
     */
    void onFrameEnd(VkCommandBuffer cmd, int frameIndex);

    /**
     * Begins a render phase: binds the shaderpack's pipeline for this phase.
     *
     * <p>If the shaderpack doesn't provide a program for this phase, a
     * fallback/passthrough pipeline is used. MC's draw calls will be
     * issued between {@code beginPhase} and {@code endPhase}.</p>
     *
     * @param phase The render phase to begin
     * @param cmd   Active Vulkan command buffer
     * @return true if the phase is active (has a shader program), false to skip
     */
    boolean beginPhase(ShaderPhase phase, VkCommandBuffer cmd);

    /**
     * Ends a render phase. Called after all draws for this phase are recorded.
     *
     * @param phase The render phase to end
     * @param cmd   Active Vulkan command buffer
     */
    void endPhase(ShaderPhase phase, VkCommandBuffer cmd);

    /**
     * Returns the Vulkan pipeline handle for the given phase.
     * Used by the draw system to bind the correct pipeline for each draw.
     *
     * @param phase The shader phase
     * @return VkPipeline handle, or 0 if no program for this phase
     */
    long getPipelineForPhase(ShaderPhase phase);

    /**
     * Returns the pipeline layout for the given phase (for descriptor set binding).
     *
     * @param phase The shader phase
     * @return VkPipelineLayout handle
     */
    long getPipelineLayoutForPhase(ShaderPhase phase);

    /**
     * Returns the uniform provider for this shaderpack.
     * Used to upload per-frame uniform data.
     */
    ShaderpackUniforms getUniforms();

    /**
     * Returns the name of the currently loaded shaderpack.
     */
    String getName();

    /**
     * Returns parsed shaderpack properties ({@code shaders.properties}).
     */
    ShaderpackProperties getProperties();
}
