package net.vulkanium.render.program;

import net.vulkanium.render.gbuffer.GBufferTargets;
import net.vulkanium.render.shadow.ShadowMap;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // TODO: Bind SKY_BASIC pipeline
        // TODO: Record sky dome draws (vanilla uses position-only vertices)
        // The sky dome is rendered with a solid color from the time-of-day gradient

        // ── Sunset gradient overlay ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SUNSET);
        // TODO: Bind SKY_BASIC_COLOR pipeline
        // TODO: Record sunset triangle fan

        // ── Sun ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SUN);
        // TODO: Bind SKY_TEXTURED pipeline
        // TODO: Record sun quad (POSITION_TEX format)

        // ── Moon ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.MOON);
        // TODO: Bind SKY_TEXTURED pipeline (same as sun)
        // TODO: Record moon quad with correct phase texture coords

        // ── Stars ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.STARS);
        // TODO: Bind SKY_BASIC_COLOR pipeline
        // TODO: Stars are rendered as position+color quad-strips

        // ── Void plane ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.VOID);
        // TODO: Only rendered when camera Y < 63, dark plane below everything
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

        // TODO: For each visible entity:
        //   1. Determine ShaderKey from entity render type:
        //      - Solid entity: ENTITIES_SOLID_DIFFUSE
        //      - Cutout entity: ENTITIES_CUTOUT_DIFFUSE
        //      - Translucent entity: skip (handled in renderTranslucentEntities)
        //      - Eyes (spider, enderman): ENTITIES_EYES
        //      - Lightning bolt: LIGHTNING
        //      - Glowing outline: ENTITIES_GLOWING (rendered separately)
        //   2. Resolve through WorldRenderingPhase.resolveKey()
        //   3. Bind pipeline from programManager
        //   4. Upload entity model matrix + color + overlay via push constants or UBO
        //   5. Record draw call with entity mesh data

        // ── Block Entities ──
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.BLOCK_ENTITIES);

        // TODO: For each visible block entity:
        //   1. Types: chest, sign, skull, enchanting table, conduit, bell, etc.
        //   2. ShaderKey resolved by phase → BLOCK_ENTITY, BLOCK_ENTITY_DIFFUSE, etc.
        //   3. Special: beacon beam → BEACON (fullscreen column with BLOCK vertex format)
        //   4. Text rendering (sign text) → TEXT_BE
    }

    /**
     * Renders translucent entity geometry (after deferred passes).
     */
    public void renderTranslucentEntities(long commandBuffer, Vector3d cameraPos, float tickDelta) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.ENTITIES);

        // TODO: For each translucent entity:
        //   - ShaderKey: ENTITIES_TRANSLUCENT or BE_TRANSLUCENT
        //   - Must be rendered back-to-front for correct alpha blending
        //   - Translucent block entities: slime blocks, honey blocks, stained glass entities
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

        // TODO: Render hand with HAND_CUTOUT / HAND_CUTOUT_DIFFUSE / HAND_CUTOUT_BRIGHT
        //   depending on the render type MC uses for the held item
        //   - Bare hand: HAND_CUTOUT_DIFFUSE
        //   - Held block item: HAND_CUTOUT (uses terrain-like rendering)
        //   - Enchanted item: HAND_CUTOUT + GLINT overlay
        //   - Text on maps: HAND_TEXT / HAND_TEXT_INTENSITY

        isRenderingHand = false;
    }

    /**
     * Renders the first-person hand (translucent pass).
     */
    public void renderHandTranslucent(long commandBuffer) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.HAND_TRANSLUCENT);
        isRenderingHand = true;
        isHandSolid = false;

        // TODO: Render hand with HAND_TRANSLUCENT / HAND_WATER_DIFFUSE / HAND_WATER_BRIGHT
        //   - Translucent held items (potions, stained glass)
        //   - Water in bucket uses gbuffers_hand_water

        isRenderingHand = false;
    }

    // ── Weather/Particles ──

    /**
     * Renders weather effects (rain, snow).
     */
    public void renderWeather(long commandBuffer) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.RAIN_SNOW);

        // TODO: Bind WEATHER pipeline (uses PARTICLE vertex format)
        // Rain/snow are rendered as billboard quads with lightmap
        // Weather detection: when phase is RAIN_SNOW, particle shader → WEATHER instead of PARTICLES
    }

    /**
     * Renders all other particles (not weather).
     */
    public void renderParticles(long commandBuffer, boolean translucent) {
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.PARTICLES);
        particlesRendered = 0;

        ShaderKey key = translucent ? ShaderKey.PARTICLES_TRANS : ShaderKey.PARTICLES;

        // TODO: Bind appropriate PARTICLES / PARTICLES_TRANS pipeline
        // Particle vertex format: pos(12) + uv(8) + color(4) + light(4) = 28 bytes
        // Particles are sorted back-to-front for translucent, unsorted for opaque
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
