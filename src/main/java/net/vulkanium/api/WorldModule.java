package net.vulkanium.api;

/**
 * A pluggable module that hooks into Vulkanium's render pipeline.
 *
 * <p>World modules are the primary extension mechanism for mods that need to
 * participate in the rendering pipeline. Inspired by MCVR's module architecture,
 * each module registers for specific {@link ModulePhase phases} and receives
 * callbacks with a {@link WorldModuleContext} containing frame data.</p>
 *
 * <h3>Module Lifecycle</h3>
 * <pre>
 *   Registration (mod init)
 *     → onWorldLoad(context)   — world entered, allocate resources
 *       → Per frame:
 *         → onFrameBegin(context)
 *         → onPhase(phase, context)   — called for each registered phase
 *         → onFrameEnd(context)
 *     → onWorldUnload(context) — world exited, release resources
 *   Unregistration (mod shutdown)
 * </pre>
 *
 * <h3>Example: Custom Post-Processing Module</h3>
 * <pre>{@code
 *   public class BloomModule implements WorldModule {
 *       @Override public String getModuleId() { return "mymod:bloom"; }
 *       @Override public String getDisplayName() { return "Bloom Effect"; }
 *       @Override public ModulePhase[] getPhases() {
 *           return new ModulePhase[] { ModulePhase.POST_COMPOSITE };
 *       }
 *       @Override public int getPriority() { return 100; }
 *
 *       @Override public void onPhase(ModulePhase phase, WorldModuleContext ctx) {
 *           if (phase == ModulePhase.POST_COMPOSITE) {
 *               // Bind bloom compute shader, dispatch, composite result
 *           }
 *       }
 *   }
 * }</pre>
 *
 * <h3>Example: Ray Tracing Module (Radiance-style)</h3>
 * <pre>{@code
 *   public class PathTracerModule implements WorldModule {
 *       @Override public String getModuleId() { return "radiance:pathtracer"; }
 *       @Override public ModulePhase[] getPhases() {
 *           return new ModulePhase[] { ModulePhase.PRE_TERRAIN, ModulePhase.RT_DISPATCH };
 *       }
 *
 *       @Override public void onPhase(ModulePhase phase, WorldModuleContext ctx) {
 *           if (phase == ModulePhase.PRE_TERRAIN) {
 *               // Update TLAS with current frame's visible sections
 *           } else if (phase == ModulePhase.RT_DISPATCH) {
 *               // Dispatch ray tracing, accumulate into path trace buffer
 *           }
 *       }
 *   }
 * }</pre>
 *
 * @see ModulePhase
 * @see WorldModuleContext
 * @see ModuleRegistry
 */
public interface WorldModule {

    /**
     * Unique identifier for this module.
     * Convention: {@code "modid:module_name"} (e.g., "radiance:pathtracer").
     */
    String getModuleId();

    /**
     * Human-readable display name for UI and logging.
     */
    String getDisplayName();

    /**
     * The render pipeline phases this module participates in.
     * The module's {@link #onPhase} will be called once per frame for each phase.
     */
    ModulePhase[] getPhases();

    /**
     * Execution priority within a phase. Higher values run first.
     * Default: 0. Use negative values for "after everything else" modules.
     */
    default int getPriority() { return 0; }

    /**
     * Whether this module requires ray tracing support (Tier 1+).
     * If RT is not available, the module won't be loaded.
     */
    default boolean requiresRayTracing() { return false; }

    /**
     * Minimum RT tier required (0=none, 1=ray query, 2=full RT pipeline).
     * Only checked if {@link #requiresRayTracing()} returns true.
     */
    default int minimumRTTier() { return 0; }

    /**
     * Whether this module requires compute shader support.
     * Should always be true for compute-only modules (no visual output).
     */
    default boolean requiresCompute() { return false; }

    // ── Lifecycle Callbacks ──

    /**
     * Called when a world is loaded and this module should allocate resources.
     *
     * @param context Provides access to Vulkanium internals for resource allocation
     */
    default void onWorldLoad(WorldModuleContext context) {}

    /**
     * Called when the world is unloaded. Release all GPU resources.
     *
     * @param context Provides access to Vulkanium internals for cleanup
     */
    default void onWorldUnload(WorldModuleContext context) {}

    // ── Per-Frame Callbacks ──

    /**
     * Called at the start of each frame before any phase callbacks.
     * Use for per-frame setup (updating uniforms, camera data, etc.).
     *
     * @param context Current frame context
     */
    default void onFrameBegin(WorldModuleContext context) {}

    /**
     * Called for each registered phase during the frame.
     * This is the primary callback for module rendering work.
     *
     * @param phase   The current pipeline phase
     * @param context Current frame context with command buffer access
     */
    void onPhase(ModulePhase phase, WorldModuleContext context);

    /**
     * Called at the end of each frame after all phase callbacks.
     * Use for per-frame cleanup or statistics collection.
     *
     * @param context Current frame context
     */
    default void onFrameEnd(WorldModuleContext context) {}
}
