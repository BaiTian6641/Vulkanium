package net.vulkanium.shaderpack;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Bridge providing Minecraft game-state uniforms to shaderpacks.
 *
 * <p>Shaderpacks expect a standard set of uniforms matching OptiFine/Iris
 * conventions (e.g., {@code sunPosition}, {@code worldTime}, {@code viewWidth}).
 * This interface provides those values from the current game state.</p>
 *
 * <p>Implementations should query MC's game state (via {@code Minecraft.getInstance()},
 * {@code Level}, {@code Camera}, etc.) to produce the uniform values.</p>
 */
public interface ShaderpackUniforms {

    // ─── Matrices ──────────────────────────────────────────────────────

    /** Model-view matrix (camera transformation) */
    Matrix4f getModelViewMatrix();

    /** Projection matrix */
    Matrix4f getProjectionMatrix();

    /** Model-view matrix of the previous frame (for motion vectors) */
    Matrix4f getPreviousModelViewMatrix();

    /** Projection matrix of the previous frame */
    Matrix4f getPreviousProjectionMatrix();

    /** Shadow projection matrix (orthographic from sun) */
    Matrix4f getShadowProjectionMatrix();

    /** Shadow model-view matrix (looking from sun toward origin) */
    Matrix4f getShadowModelViewMatrix();

    // ─── Vectors ───────────────────────────────────────────────────────

    /** Sun position in eye-space (normalized direction) */
    Vector3f getSunPosition();

    /** Moon position in eye-space (normalized direction) */
    Vector3f getMoonPosition();

    /** Up vector in eye-space */
    Vector3f getUpPosition();

    /** Camera position in world-space */
    Vector3f getCameraPosition();

    /** Camera position from previous frame */
    Vector3f getPreviousCameraPosition();

    // ─── Time ──────────────────────────────────────────────────────────

    /** World time in ticks (0–24000) */
    int getWorldTime();

    /** World day count */
    int getWorldDay();

    /** Frame time delta in seconds */
    float getFrameTimeCounter();

    /** Sun angle (0.0 = noon, 0.5 = midnight) */
    float getSunAngle();

    /** Shadow angle (sun angle for shadow mapping) */
    float getShadowAngle();

    // ─── Atmosphere ────────────────────────────────────────────────────

    /** Rain strength (0.0 = clear, 1.0 = heavy rain) */
    float getRainStrength();

    /** Wetness (accumulated rain effect, 0.0–1.0) */
    float getWetness();

    /** Current fog color RGBA */
    float[] getFogColor();

    /** Current sky color RGB */
    float[] getSkyColor();

    // ─── Screen info ───────────────────────────────────────────────────

    /** Screen width in pixels */
    float getViewWidth();

    /** Screen height in pixels */
    float getViewHeight();

    /** Aspect ratio (width / height) */
    float getAspectRatio();

    /** Near plane distance */
    float getNear();

    /** Far plane distance (render distance) */
    float getFar();

    // ─── Held item / eye status ────────────────────────────────────────

    /** Held item light level for main hand (-1 if no light source) */
    int getHeldItemId();

    /** Held item light level for off hand */
    int getHeldItemId2();

    /** Whether the camera is in water (0 = no, 1 = yes) */
    int getIsEyeInWater();

    // ─── Misc ──────────────────────────────────────────────────────────

    /** Render distance in chunks */
    int getRenderDistance();

    /** Current biome temperature at camera position */
    float getBiomeTemperature();

    /** Current biome rainfall at camera position */
    float getBiomeRainfall();
}
