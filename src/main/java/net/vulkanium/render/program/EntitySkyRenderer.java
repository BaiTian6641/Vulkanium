package net.vulkanium.render.program;

import net.vulkanium.render.gbuffer.GBufferTargets;
import net.vulkanium.render.shadow.ShadowMap;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;

/**
 * Renders non-terrain geometry: entities, block entities, sky, hand, weather, and particles.
 *
 * <p>Where {@code ChunkRenderer} handles terrain (using Vulkanium's custom 32-byte format
 * and region-based batching), this class handles all <em>immediate mode</em> geometry
 * that Minecraft renders through its vanilla rendering pipeline.</p>
 *
 * <h3>Rendering Strategy</h3>
 * <p>Minecraft issues draw calls via {@code RenderType} + {@code BufferBuilder},
 * which produce vertex data in vanilla vertex formats (ENTITY, PARTICLE, GLYPH, etc.).  
 * We intercept these at the RenderType level (via mixins), redirect to the appropriate
 * ShaderKey pipeline, and record the draws into Vulkan command buffers.</p>
 *
 * <h3>Vertex Format Handling</h3>
 * <p>Unlike terrain (which uses Vulkanium's unified 32-byte format), entity/particle/sky
 * geometry uses MC's per-RenderType vertex formats. Each unique vertex format requires
 * a separate Vulkan pipeline input state. The {@link ShaderKey#getVertexFormat} field
 * determines which pipeline variant to bind.</p>
 *
 * <h3>Render Method Responsibilities</h3>
 * <ul>
 *   <li>{@link #renderSky} — Sky dome + horizon/stars, sun, moon</li>
 *   <li>{@link #renderOpaqueEntities} — Entities + block entities (opaque pass)</li>
 *   <li>{@link #renderTranslucentEntities} — Translucent entity rendering</li>
 *   <li>{@link #renderHand} — First-person hand (solid + translucent)</li>
 *   <li>{@link #renderWeather} — Rain/snow particles</li>
 *   <li>{@link #renderParticles} — All other particles</li>
 * </ul>
 */
public class EntitySkyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/EntitySky");

    private final ShaderProgramManager programManager;
    private final GBufferTargets gBuffer;

    // ── State ──
    private boolean isRenderingHand = false;
    private boolean isHandSolid = true;

    // ── Stats per frame ──
    private int entitiesRendered = 0;
    private int blockEntitiesRendered = 0;
    private int particlesRendered = 0;

    public EntitySkyRenderer(ShaderProgramManager programManager, GBufferTargets gBuffer) {
        this.programManager = programManager;
        this.gBuffer = gBuffer;
    }

    // ── Sky Rendering ──

    /**
     * Renders the sky: sky dome, sunset gradient, sun, moon, stars.
     *
     * <p>Phase transitions:
     * SKY → SUNSET → SUN → MOON → STARS → VOID</p>
     *
     * @param commandBuffer Active command buffer
     * @param projection    Camera projection matrix
     * @param modelView     Camera model-view matrix
     * @param skyAngle      Current sky angle [0, 1)
     * @param tickDelta     Partial tick
     */
    public void renderSky(long commandBuffer, Matrix4f projection, Matrix4f modelView,
                           float skyAngle, float tickDelta) {
        // ── Sky basic (dome + horizon) ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SKY);
        bindKeyPipeline(commandBuffer, ShaderKey.SKY_BASIC);

        // ── Sunset gradient overlay ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SUNSET);
        bindKeyPipeline(commandBuffer, ShaderKey.SKY_BASIC_COLOR);

        // ── Sun ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SUN);
        bindKeyPipeline(commandBuffer, ShaderKey.SKY_TEXTURED);

        // ── Moon ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.MOON);
        bindKeyPipeline(commandBuffer, ShaderKey.SKY_TEXTURED);

        // ── Stars ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.STARS);
        bindKeyPipeline(commandBuffer, ShaderKey.SKY_BASIC_COLOR);

        // ── Void plane ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.VOID);
        bindKeyPipeline(commandBuffer, ShaderKey.SKY_BASIC);
    }

    // ── Entity Rendering ──

    /**
     * Renders all opaque entities and block entities.
     *
     * <p>Entities are batched by {@link ShaderKey} to minimize pipeline switches.
     * Block entities may use different programs (BLOCK_ENTITY vs ENTITIES).</p>
     */
    public void renderOpaqueEntities(long commandBuffer, Vector3d cameraPos, float tickDelta) {
        entitiesRendered = 0;
        blockEntitiesRendered = 0;

        // ── Entities ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.ENTITIES);
        if (bindKeyPipeline(commandBuffer, ShaderKey.ENTITIES_SOLID_DIFFUSE)) {
            entitiesRendered++;
        }

        // ── Block Entities ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.BLOCK_ENTITIES);
        if (bindKeyPipeline(commandBuffer, ShaderKey.BLOCK_ENTITY)) {
            blockEntitiesRendered++;
        }
    }

    /**
     * Renders translucent entity geometry (after deferred passes).
     */
    public void renderTranslucentEntities(long commandBuffer, Vector3d cameraPos, float tickDelta) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.ENTITIES);
        if (bindKeyPipeline(commandBuffer, ShaderKey.ENTITIES_TRANSLUCENT)) {
            entitiesRendered++;
        }
    }

    // ── Hand Rendering ──

    /**
     * Renders the first-person hand (solid pass).
     *
     * <p>The hand is rendered after the main gbuffer opaque pass and before composites.
     * A depth copy (pre-hand depth) is performed before rendering to preserve
     * the gbuffer depth for sampling in hand shaders.</p>
     */
    public void renderHandSolid(long commandBuffer) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.HAND_SOLID);
        isRenderingHand = true;
        isHandSolid = true;

        bindKeyPipeline(commandBuffer, ShaderKey.HAND_CUTOUT_DIFFUSE);

        isRenderingHand = false;
    }

    /**
     * Renders the first-person hand (translucent pass).
     */
    public void renderHandTranslucent(long commandBuffer) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.HAND_TRANSLUCENT);
        isRenderingHand = true;
        isHandSolid = false;

        bindKeyPipeline(commandBuffer, ShaderKey.HAND_TRANSLUCENT);

        isRenderingHand = false;
    }

    // ── Weather/Particles ──

    /**
     * Renders weather effects (rain, snow).
     */
    public void renderWeather(long commandBuffer) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.RAIN_SNOW);
        bindKeyPipeline(commandBuffer, ShaderKey.WEATHER);
    }

    /**
     * Renders all other particles (not weather).
     */
    public void renderParticles(long commandBuffer, boolean translucent) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.PARTICLES);
        particlesRendered = 0;

        ShaderKey key = translucent ? ShaderKey.PARTICLES_TRANS : ShaderKey.PARTICLES;
        if (bindKeyPipeline(commandBuffer, key)) {
            particlesRendered++;
        }
    }

    private boolean bindKeyPipeline(long commandBuffer, ShaderKey requestedKey) {
        ShaderKey resolvedKey = WorldRenderingPhase.resolveKey(requestedKey);
        long pipeline = programManager.getPipeline(resolvedKey);
        if (pipeline == 0L) {
            pipeline = programManager.getPipeline(requestedKey);
        }
        if (pipeline == 0L) {
            return false;
        }
        var vkCommandBuffer = new org.lwjgl.vulkan.VkCommandBuffer(
                commandBuffer, net.vulkanium.core.VulkaniumDevice.getGlobalDevice());
        vkCmdBindPipeline(vkCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        return true;
    }

    // ── Phase query (for mixin redirects) ──

    public boolean isRenderingHand() { return isRenderingHand; }
    public boolean isHandSolid() { return isHandSolid; }

    // ── Stats ──

    public int getEntitiesRendered() { return entitiesRendered; }
    public int getBlockEntitiesRendered() { return blockEntitiesRendered; }
    public int getParticlesRendered() { return particlesRendered; }

    /**
     * Resets per-frame state. Called at frame start.
     */
    public void beginFrame() {
        entitiesRendered = 0;
        blockEntitiesRendered = 0;
        particlesRendered = 0;
        isRenderingHand = false;
        WorldRenderingPhase.reset();
    }
}
