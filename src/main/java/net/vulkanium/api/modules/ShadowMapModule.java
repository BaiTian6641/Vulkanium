package net.vulkanium.api.modules;

import net.vulkanium.api.ModulePhase;
import net.vulkanium.api.WorldModule;
import net.vulkanium.api.WorldModuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in module: Shadow map rasterization.
 *
 * <p>Renders the shadow map pass using traditional rasterization into depth
 * targets. This is the default shadow method for all GPUs.</p>
 *
 * <h3>Conflict Group: "shadow"</h3>
 * <p>Mutually exclusive with {@code RTShadowModule}.</p>
 *
 * <h3>Phases</h3>
 * <ul>
 *   <li>{@code SHADOW_TERRAIN} — Render terrain into shadow depth buffer</li>
 *   <li>{@code SHADOW_ENTITIES} — Render entities into shadow depth buffer</li>
 * </ul>
 */
public class ShadowMapModule implements WorldModule {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShadowMap");

    @Override
    public String getModuleId() { return "vulkanium:shadow_map"; }

    @Override
    public String getDisplayName() { return "Shadow Maps (Raster)"; }

    @Override
    public ModulePhase[] getPhases() {
        return new ModulePhase[]{
                ModulePhase.SHADOW_TERRAIN,
                ModulePhase.SHADOW_ENTITIES
        };
    }

    @Override
    public int getPriority() { return 0; }

    @Override
    public void onWorldLoad(WorldModuleContext context) {
        LOGGER.info("Shadow map module loaded");
        // ShadowRenderer.initialize() — create shadow FBO, matrices, etc.
    }

    @Override
    public void onPhase(ModulePhase phase, WorldModuleContext context) {
        switch (phase) {
            case SHADOW_TERRAIN -> renderTerrainShadows(context);
            case SHADOW_ENTITIES -> renderEntityShadows(context);
            default -> {}
        }
    }

    private void renderTerrainShadows(WorldModuleContext context) {
        // 1. Compute shadow matrices from sun angle
        // 2. Bind shadow render pass (depth-only)
        // 3. Set viewport to shadow resolution
        // 4. Draw visible terrain from light's perspective
        // 5. Copy depth for shadowtex0 → shadowtex1 (pre-translucent)
    }

    private void renderEntityShadows(WorldModuleContext context) {
        // 1. Bind shadow entity pipeline
        // 2. Draw entities within shadow frustum
        // 3. Draw block entities within shadow frustum
        // 4. Draw translucent terrain (with shadow color writes)
    }

    @Override
    public void onWorldUnload(WorldModuleContext context) {
        LOGGER.info("Shadow map module unloaded");
    }
}
