package net.vulkanium.api.modules;

import net.vulkanium.api.ModulePhase;
import net.vulkanium.api.WorldModule;
import net.vulkanium.api.WorldModuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in module: Ray-traced shadows.
 *
 * <p>Replaces rasterized shadow maps with hardware ray-traced shadow rays.
 * Provides pixel-perfect shadows with no resolution artifacts, penumbra,
 * and proper contact-hardening.</p>
 *
 * <h3>Conflict Group: "shadow"</h3>
 * <p>Mutually exclusive with {@code ShadowMapModule}. Wins by higher priority
 * when RT hardware is available.</p>
 *
 * <h3>Phases</h3>
 * <ul>
 *   <li>{@code PRE_SHADOW} — Update acceleration structures</li>
 *   <li>{@code RT_DISPATCH} — Dispatch shadow ray tracing</li>
 * </ul>
 */
public class RTShadowModule implements WorldModule {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTShadow");
    private static final int MINIMUM_RT_TIER = 1;

    @Override
    public String getModuleId() { return "vulkanium:rt_shadow"; }

    @Override
    public String getDisplayName() { return "Shadow (Ray Traced)"; }

    @Override
    public ModulePhase[] getPhases() {
        return new ModulePhase[]{
                ModulePhase.PRE_SHADOW,
                ModulePhase.RT_DISPATCH
        };
    }

    @Override
    public int getPriority() { return 100; }

    @Override
    public boolean requiresRayTracing() { return true; }

    @Override
    public int minimumRTTier() { return MINIMUM_RT_TIER; }

    @Override
    public void onWorldLoad(WorldModuleContext context) {
        LOGGER.info("RT shadow module loaded — ray traced shadows enabled");
        // Allocate shadow ray result buffer (R8 visibility or RGBA16F with penumbra data)
    }

    @Override
    public void onPhase(ModulePhase phase, WorldModuleContext context) {
        switch (phase) {
            case PRE_SHADOW -> prepareAccelerationStructures(context);
            case RT_DISPATCH -> dispatchShadowRays(context);
            default -> {}
        }
    }

    private void prepareAccelerationStructures(WorldModuleContext context) {
        // 1. Ensure TLAS is up-to-date (already done by RTModuleManager if shared)
        // 2. Upload sun/moon direction to push constants
    }

    private void dispatchShadowRays(WorldModuleContext context) {
        // 1. Bind shadow ray generation pipeline
        // 2. Bind TLAS descriptor
        // 3. Set push constants: light direction, max shadow distance, penumbra scale
        // 4. vkCmdTraceRaysKHR(width, height, 1)
        // 5. Transition result image for sampling in deferred pass
    }

    @Override
    public void onWorldUnload(WorldModuleContext context) {
        LOGGER.info("RT shadow module unloaded");
    }
}
