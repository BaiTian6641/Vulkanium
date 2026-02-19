package net.vulkanium.api.modules;

import net.vulkanium.api.ModulePhase;
import net.vulkanium.api.WorldModule;
import net.vulkanium.api.WorldModuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in module: Standard terrain rasterization.
 *
 * <p>The default terrain rendering module that uses the Vulkan rasterization
 * pipeline to draw opaque, cutout, and translucent terrain. This is the
 * standard path for all GPUs and is overridden by {@code RTTerrainModule}
 * when ray tracing is active.</p>
 *
 * <h3>Conflict Group: "terrain"</h3>
 * <p>Mutually exclusive with {@code RTTerrainModule}. If both are registered,
 * the pipeline builder selects one based on priority (RT wins if available).</p>
 *
 * <h3>Phases</h3>
 * <ul>
 *   <li>{@code TERRAIN_SOLID} — Opaque blocks, depth pre-pass</li>
 *   <li>{@code TERRAIN_CUTOUT} — Alpha-tested blocks (leaves, flowers, tall grass)</li>
 *   <li>{@code TERRAIN_TRANSLUCENT} — Water, ice, stained glass</li>
 * </ul>
 */
public class RasterTerrainModule implements WorldModule {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RasterTerrain");

    @Override
    public String getModuleId() { return "vulkanium:raster_terrain"; }

    @Override
    public String getDisplayName() { return "Raster Terrain (Standard)"; }

    @Override
    public ModulePhase[] getPhases() {
        return new ModulePhase[]{
                ModulePhase.TERRAIN_SOLID,
                ModulePhase.TERRAIN_CUTOUT,
                ModulePhase.TERRAIN_TRANSLUCENT
        };
    }

    @Override
    public int getPriority() { return 0; } // Default priority — RT overrides with higher

    @Override
    public void onWorldLoad(WorldModuleContext context) {
        LOGGER.info("Raster terrain module loaded");
        // ChunkRenderer initialization happens externally; this module triggers dispatch
    }

    @Override
    public void onPhase(ModulePhase phase, WorldModuleContext context) {
        switch (phase) {
            case TERRAIN_SOLID -> renderSolid(context);
            case TERRAIN_CUTOUT -> renderCutout(context);
            case TERRAIN_TRANSLUCENT -> renderTranslucent(context);
            default -> {} // Ignore unexpected phases
        }
    }

    private void renderSolid(WorldModuleContext context) {
        // Dispatch ChunkRenderer for opaque terrain pass
        // - Bind terrain pipeline (opaque blend, depth write enabled)
        // - Set terrain render pass attachments (GBuffer targets)
        // - Draw visible regions via indirect draw or direct draw
    }

    private void renderCutout(WorldModuleContext context) {
        // Same as solid but with alpha test enabled
        // Pipeline uses ONE_TENTH_ALPHA discard threshold
    }

    private void renderTranslucent(WorldModuleContext context) {
        // Translucent terrain with blending
        // - Uses CPU-sorted or GPU-sorted face order
        // - Writes to GBuffer with alpha blend
        // - Or WBOIT accumulation targets if OIT enabled
    }

    @Override
    public void onWorldUnload(WorldModuleContext context) {
        LOGGER.info("Raster terrain module unloaded");
    }
}
