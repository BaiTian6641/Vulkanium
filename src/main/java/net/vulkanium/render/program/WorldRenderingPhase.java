package net.vulkanium.render.program;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the current rendering phase to select appropriate shader programs.
 *
 * <p>Minecraft's rendering loop goes through distinct phases. Iris redirects vanilla
 * shader selection based on the current phase — the same vanilla render call
 * (e.g., {@code getRendertypeEntitySolidShader()}) maps to different ShaderKeys
 * depending on whether we're rendering entities, block entities, hands, or shadows.</p>
 *
 * <h3>Phase Order (single frame)</h3>
 * <pre>
 *   BEGIN
 *   SHADOW → shadow terrain → entities → block entities → depth copy → translucent → shadow composites
 *   PREPARE
 *   GBUFFERS_OPAQUE:
 *     SKY → TERRAIN_SOLID → TERRAIN_CUTOUT → ENTITIES → BLOCK_ENTITIES → PARTICLES
 *   DEPTH_COPY (pre-translucent)
 *   DEFERRED
 *   GBUFFERS_TRANSLUCENT:
 *     TERRAIN_TRANSLUCENT → PARTICLES_TRANS → ENTITIES_TRANS → WEATHER
 *   DEPTH_COPY (pre-hand)
 *   HAND_SOLID
 *   HAND_TRANSLUCENT
 *   COMPOSITE
 *   FINAL
 * </pre>
 */
public class WorldRenderingPhase {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Phase");

    public enum Phase {
        NONE,

        // Setup
        BEGIN,

        // Shadow pass
        SHADOW,

        // Prepare (before gbuffers)
        PREPARE,

        // Sky rendering
        CUSTOM_SKY,
        SKY,
        SUNSET,
        SUN,
        MOON,
        STARS,
        VOID,

        // Opaque gbuffers
        TERRAIN_SOLID,
        TERRAIN_CUTOUT,

        // Entities
        ENTITIES,
        BLOCK_ENTITIES,

        // Particles (opaque)
        PARTICLES,

        // Translucent gbuffers
        TERRAIN_TRANSLUCENT,
        WEATHER,
        RAIN_SNOW,

        // Hand
        HAND_SOLID,
        HAND_TRANSLUCENT,

        // Post-processing
        DEFERRED,
        COMPOSITE,
        FINAL_PASS,

        // Misc
        DEBUG
    }

    // ── Current state ──
    private static Phase currentPhase = Phase.NONE;
    private static boolean isBeforeTranslucent = true;

    // ── Phase management ──

    public static void setPhase(Phase phase) {
        currentPhase = phase;
    }

    public static Phase getPhase() {
        return currentPhase;
    }

    public static void setBeforeTranslucent(boolean before) {
        isBeforeTranslucent = before;
    }

    public static boolean isBeforeTranslucent() {
        return isBeforeTranslucent;
    }

    /**
     * Returns true if the current phase is a sky rendering phase.
     */
    public static boolean isSky() {
        return switch (currentPhase) {
            case CUSTOM_SKY, SKY, SUNSET, SUN, MOON, STARS, VOID -> true;
            default -> false;
        };
    }

    /**
     * Returns true if the current phase is rendering shadow geometry.
     */
    public static boolean isShadow() {
        return currentPhase == Phase.SHADOW;
    }

    /**
     * Returns true if the current phase is rendering first-person hand.
     */
    public static boolean isHand() {
        return currentPhase == Phase.HAND_SOLID || currentPhase == Phase.HAND_TRANSLUCENT;
    }

    /**
     * Returns true if the current phase is rendering block entities.
     */
    public static boolean isBlockEntities() {
        return currentPhase == Phase.BLOCK_ENTITIES;
    }

    /**
     * Returns true if the current phase is rendering entities.
     */
    public static boolean isEntities() {
        return currentPhase == Phase.ENTITIES;
    }

    /**
     * Returns true if the current phase is rendering weather (rain/snow).
     */
    public static boolean isWeather() {
        return currentPhase == Phase.RAIN_SNOW || currentPhase == Phase.WEATHER;
    }

    /**
     * Resolves a vanilla render call to the appropriate ShaderKey based on current phase.
     *
     * <p>Priority order:</p>
     * <ol>
     *   <li>Sky → SKY_* variant</li>
     *   <li>Shadow → SHADOW_* variant</li>
     *   <li>Hand → HAND_* variant</li>
     *   <li>Block entity → BLOCK_ENTITY* variant</li>
     *   <li>Entity phase → appropriate entity key</li>
     *   <li>Default → direct mapping</li>
     * </ol>
     *
     * @param defaultKey The ShaderKey that would be used without phase context
     * @return The phase-adjusted ShaderKey
     */
    public static ShaderKey resolveKey(ShaderKey defaultKey) {
        if (isSky()) {
            return resolveSkyKey(defaultKey);
        }
        if (isShadow()) {
            return resolveShadowKey(defaultKey);
        }
        if (isHand()) {
            return resolveHandKey(defaultKey);
        }
        if (isBlockEntities()) {
            return resolveBlockEntityKey(defaultKey);
        }
        return defaultKey;
    }

    private static ShaderKey resolveSkyKey(ShaderKey key) {
        return switch (key) {
            case BASIC -> ShaderKey.SKY_BASIC;
            case BASIC_COLOR -> ShaderKey.SKY_BASIC_COLOR;
            case TEXTURED -> ShaderKey.SKY_TEXTURED;
            case TEXTURED_COLOR -> ShaderKey.SKY_TEXTURED_COLOR;
            default -> key;
        };
    }

    private static ShaderKey resolveShadowKey(ShaderKey key) {
        return switch (key) {
            case TERRAIN_CUTOUT -> ShaderKey.SHADOW_TERRAIN_CUTOUT;
            case ENTITIES_CUTOUT, ENTITIES_CUTOUT_DIFFUSE -> ShaderKey.SHADOW_ENTITIES_CUTOUT;
            case BEACON -> ShaderKey.SHADOW_BEACON_BEAM;
            case BASIC -> ShaderKey.SHADOW_BASIC;
            case BASIC_COLOR, LIGHTNING -> ShaderKey.SHADOW_BASIC_COLOR;
            case TEXTURED -> ShaderKey.SHADOW_TEX;
            case TEXTURED_COLOR -> ShaderKey.SHADOW_TEX_COLOR;
            case CLOUDS -> ShaderKey.SHADOW_CLOUDS;
            case LINES -> ShaderKey.SHADOW_LINES;
            case LEASH -> ShaderKey.SHADOW_LEASH;
            case PARTICLES, PARTICLES_TRANS -> ShaderKey.SHADOW_PARTICLES;
            case TEXT, TEXT_BE -> ShaderKey.SHADOW_TEXT;
            case TEXT_BG -> ShaderKey.SHADOW_TEXT_BG;
            case TEXT_INTENSITY, TEXT_INTENSITY_BE -> ShaderKey.SHADOW_TEXT_INTENSITY;
            default -> ShaderKey.SHADOW_BASIC;
        };
    }

    private static ShaderKey resolveHandKey(ShaderKey key) {
        boolean isSolid = currentPhase == Phase.HAND_SOLID;
        return switch (key) {
            case ENTITIES_CUTOUT, ENTITIES_CUTOUT_DIFFUSE ->
                    isSolid ? ShaderKey.HAND_CUTOUT : ShaderKey.HAND_TRANSLUCENT;
            case ENTITIES_SOLID_DIFFUSE ->
                    isSolid ? ShaderKey.HAND_CUTOUT_DIFFUSE : ShaderKey.HAND_WATER_DIFFUSE;
            case ENTITIES_SOLID_BRIGHT ->
                    isSolid ? ShaderKey.HAND_CUTOUT_BRIGHT : ShaderKey.HAND_WATER_BRIGHT;
            case TEXT -> ShaderKey.HAND_TEXT;
            case TEXT_INTENSITY -> ShaderKey.HAND_TEXT_INTENSITY;
            case ENTITIES_TRANSLUCENT ->
                    isSolid ? ShaderKey.HAND_CUTOUT : ShaderKey.HAND_TRANSLUCENT;
            default -> isSolid ? ShaderKey.HAND_CUTOUT : ShaderKey.HAND_TRANSLUCENT;
        };
    }

    private static ShaderKey resolveBlockEntityKey(ShaderKey key) {
        return switch (key) {
            case ENTITIES_CUTOUT, ENTITIES_CUTOUT_DIFFUSE -> ShaderKey.BLOCK_ENTITY_DIFFUSE;
            case ENTITIES_SOLID, ENTITIES_SOLID_BRIGHT -> ShaderKey.BLOCK_ENTITY_BRIGHT;
            case ENTITIES_SOLID_DIFFUSE -> ShaderKey.BLOCK_ENTITY_DIFFUSE;
            case ENTITIES_TRANSLUCENT -> ShaderKey.BE_TRANSLUCENT;
            case TEXT -> ShaderKey.TEXT_BE;
            case TEXT_INTENSITY -> ShaderKey.TEXT_INTENSITY_BE;
            case BEACON -> ShaderKey.BEACON;
            default -> ShaderKey.BLOCK_ENTITY;
        };
    }

    /**
     * Reset phase at frame start.
     */
    public static void reset() {
        currentPhase = Phase.NONE;
        isBeforeTranslucent = true;
    }
}
