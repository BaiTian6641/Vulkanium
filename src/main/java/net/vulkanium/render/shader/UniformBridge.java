package net.vulkanium.render.shader;

import net.vulkanium.core.VulkaniumMemory;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * CPU-side uniform bridge — fills the VulkaniumUniforms UBO (2048 bytes) every frame.
 *
 * <p>This is the GPU data pump. Each frame, {@link #update()} is called to gather
 * uniform values from Minecraft's rendering state and write them into a host-visible
 * buffer that is then bound as UBO set=0, binding=0 for all shader programs.</p>
 *
 * <h3>Memory Layout (2048 bytes, std140)</h3>
 * <pre>
 *   Bytes   0-767:   12 × mat4 (matrices)
 *   Bytes 768-1023:  16 × vec4 (vectors)
 *   Bytes 1024-2047: 32 × vec4 (packed scalars + reserved)
 * </pre>
 *
 * <h3>Update Frequencies</h3>
 * <ul>
 *   <li><b>Per-frame</b> (60Hz): Matrices, camera position, time, fog, entity color</li>
 *   <li><b>Per-tick</b> (20Hz): Weather, world time, eye brightness, biome</li>
 *   <li><b>On-change</b>: Shadow params, atlas size, render distance</li>
 * </ul>
 *
 * <h3>Double Buffering</h3>
 * <p>Two UBO buffers are maintained (ping-pong) so the GPU can read from one while
 * the CPU writes to the other. {@link #swapBuffers()} switches the active buffer after
 * each frame's GPU submission.</p>
 *
 * <h3>Comparison to VulkanMod</h3>
 * <table>
 *   <tr><th>Feature</th><th>VulkanMod</th><th>Vulkanium</th></tr>
 *   <tr><td>UBO size</td><td>720 bytes (IrisData)</td><td>2048 bytes</td></tr>
 *   <tr><td>Uniform count</td><td>~21</td><td>60+</td></tr>
 *   <tr><td>Previous matrices</td><td>No</td><td>Yes (real prev frame)</td></tr>
 *   <tr><td>Biome data</td><td>No</td><td>Yes</td></tr>
 *   <tr><td>Eye brightness</td><td>No</td><td>Yes (smooth + raw)</td></tr>
 *   <tr><td>Depth params</td><td>No</td><td>Yes</td></tr>
 *   <tr><td>Double-buffered</td><td>No</td><td>Yes (no stalls)</td></tr>
 * </table>
 */
public class UniformBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/UniformBridge");

    /** Total UBO size in bytes */
    public static final int UBO_SIZE = 2048;

    /** Number of mat4 slots (16 floats × 4 bytes = 64 bytes each) */
    private static final int MATRIX_COUNT = 12;
    private static final int MATRIX_REGION_SIZE = MATRIX_COUNT * 64; // 768

    /** Number of vec4 slots in the vector region */
    private static final int VECTOR_COUNT = 16;
    private static final int VECTOR_REGION_OFFSET = MATRIX_REGION_SIZE; // 768
    private static final int VECTOR_REGION_SIZE = VECTOR_COUNT * 16; // 256

    /** Scalar region (packed into vec4s) */
    private static final int SCALAR_REGION_OFFSET = VECTOR_REGION_OFFSET + VECTOR_REGION_SIZE; // 1024
    private static final int SCALAR_REGION_SIZE = UBO_SIZE - SCALAR_REGION_OFFSET; // 1024

    // ── Matrix offsets (bytes) ──
    public static final int OFF_MODEL_VIEW              = 0;
    public static final int OFF_MODEL_VIEW_INV          = 64;
    public static final int OFF_PROJECTION              = 128;
    public static final int OFF_PROJECTION_INV          = 192;
    public static final int OFF_PREV_MODEL_VIEW         = 256;
    public static final int OFF_PREV_PROJECTION         = 320;
    public static final int OFF_SHADOW_MODEL_VIEW       = 384;
    public static final int OFF_SHADOW_PROJECTION       = 448;
    public static final int OFF_SHADOW_MODEL_VIEW_INV   = 512;
    public static final int OFF_SHADOW_PROJECTION_INV   = 576;
    public static final int OFF_NORMAL_MAT4             = 640;
    public static final int OFF_TEXTURE_MATRIX          = 704;

    // ── Vector offsets (bytes) ──
    public static final int OFF_CAMERA_POS              = 768;
    public static final int OFF_PREV_CAMERA_POS         = 784;
    public static final int OFF_SUN_POS                 = 800;
    public static final int OFF_MOON_POS                = 816;
    public static final int OFF_SHADOW_LIGHT_POS        = 832;
    public static final int OFF_UP_POS                  = 848;
    public static final int OFF_SKY_COLOR               = 864;
    public static final int OFF_FOG_COLOR               = 880;
    public static final int OFF_ENTITY_COLOR            = 896;
    public static final int OFF_CHUNK_OFFSET            = 912;
    public static final int OFF_COLOR_MODULATOR         = 928;

    // ── Packed scalar offsets (bytes) ──
    public static final int OFF_SCREEN_SIZE             = 1024;
    public static final int OFF_VIEW_PARAMS             = 1040;
    public static final int OFF_TIME                    = 1056;
    public static final int OFF_FOG_PARAMS              = 1072;
    public static final int OFF_WEATHER                 = 1088;
    public static final int OFF_PLAYER_STATE            = 1104;
    public static final int OFF_EYE_BRIGHTNESS          = 1120;
    public static final int OFF_WORLD_STATE             = 1136;
    public static final int OFF_DEPTH_PARAMS            = 1152;
    public static final int OFF_ATLAS_SIZE              = 1168;
    public static final int OFF_RENDER_STATE            = 1184;
    public static final int OFF_BLOCKLIGHT_COLOR        = 1200;
    public static final int OFF_SHADOW_PARAMS           = 1216;
    public static final int OFF_HELD_ITEMS              = 1232;
    public static final int OFF_BIOME_DATA              = 1248;
    public static final int OFF_ALPHA_TEST_REF          = 1264;

    // ── Double-buffered UBOs ──
    private final long[] uboBuffers = new long[2];
    private final long[] uboAllocations = new long[2];
    private final ByteBuffer[] mappedBuffers = new ByteBuffer[2];
    private int activeBuffer = 0;

    // ── Previous frame matrix storage ──
    private final Matrix4f prevModelView = new Matrix4f();
    private final Matrix4f prevProjection = new Matrix4f();
    private boolean hasPreviousFrame = false;

    // ── Smoothed values ──
    private float eyeBrightnessBlockSmooth = 0;
    private float eyeBrightnessSkySmooth = 0;
    private float centerDepthSmooth = 0;
    private float wetnessSmooth = 0;

    // ── State ──
    private final VulkaniumMemory memory;
    private boolean initialized = false;

    public UniformBridge(VulkaniumMemory memory) {
        this.memory = memory;
    }

    /**
     * Allocates the double-buffered UBOs (host-visible, coherent).
     */
    public void initialize() {
        for (int i = 0; i < 2; i++) {
            long[] result = memory.allocateBuffer(
                    UBO_SIZE,
                    0x00000010, // VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                    0x00000006  // VMA_MEMORY_USAGE_CPU_TO_GPU
            );
            uboBuffers[i] = result[0];
            uboAllocations[i] = result[1];
            mappedBuffers[i] = memory.mapBuffer(uboAllocations[i]);
        }
        initialized = true;
        LOGGER.info("Uniform bridge initialized (2 × {} bytes)", UBO_SIZE);
    }

    /**
     * Returns the VkBuffer handle for the currently active (GPU-readable) UBO.
     */
    public long getActiveBuffer() {
        return uboBuffers[activeBuffer];
    }

    /**
     * Returns the VkBuffer handle for the currently writable UBO.
     */
    public long getWriteBuffer() {
        return uboBuffers[1 - activeBuffer];
    }

    /**
     * Swaps the active and write buffers. Call after GPU submission.
     */
    public void swapBuffers() {
        activeBuffer = 1 - activeBuffer;
    }

    /**
     * Gets the mapped byte buffer for writing (the non-active buffer).
     */
    private ByteBuffer getWriteMapped() {
        return mappedBuffers[1 - activeBuffer];
    }

    // ═══════════════════════════════════════════════════════════════
    //  Per-Frame Update Methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Updates all matrices for the current frame.
     */
    public void updateMatrices(Matrix4f modelView, Matrix4f projection,
                               Matrix4f shadowModelView, Matrix4f shadowProjection) {
        ByteBuffer buf = getWriteMapped();

        // Current frame matrices
        putMatrix(buf, OFF_MODEL_VIEW, modelView);
        putMatrixInverse(buf, OFF_MODEL_VIEW_INV, modelView);
        putMatrix(buf, OFF_PROJECTION, projection);
        putMatrixInverse(buf, OFF_PROJECTION_INV, projection);

        // Previous frame matrices
        if (hasPreviousFrame) {
            putMatrix(buf, OFF_PREV_MODEL_VIEW, prevModelView);
            putMatrix(buf, OFF_PREV_PROJECTION, prevProjection);
        } else {
            putMatrix(buf, OFF_PREV_MODEL_VIEW, modelView);
            putMatrix(buf, OFF_PREV_PROJECTION, projection);
        }

        // Shadow matrices
        putMatrix(buf, OFF_SHADOW_MODEL_VIEW, shadowModelView);
        putMatrix(buf, OFF_SHADOW_PROJECTION, shadowProjection);
        putMatrixInverse(buf, OFF_SHADOW_MODEL_VIEW_INV, shadowModelView);
        putMatrixInverse(buf, OFF_SHADOW_PROJECTION_INV, shadowProjection);

        // Normal matrix (mat3 stored as mat4 for alignment)
        Matrix4f normalMatrix = new Matrix4f(modelView).invert().transpose();
        putMatrix(buf, OFF_NORMAL_MAT4, normalMatrix);

        // Texture matrix (identity — no fixed-function tex-gen in Vulkan)
        putMatrix(buf, OFF_TEXTURE_MATRIX, new Matrix4f());

        // Save for next frame
        prevModelView.set(modelView);
        prevProjection.set(projection);
        hasPreviousFrame = true;
    }

    /**
     * Updates camera and celestial positions.
     */
    public void updatePositions(Vector3f cameraPos, Vector3f prevCameraPos,
                                Vector3f sunPos, Vector3f moonPos,
                                Vector3f shadowLightPos, Vector3f upPos) {
        ByteBuffer buf = getWriteMapped();
        putVec4(buf, OFF_CAMERA_POS, cameraPos.x, cameraPos.y, cameraPos.z, 0);
        putVec4(buf, OFF_PREV_CAMERA_POS, prevCameraPos.x, prevCameraPos.y, prevCameraPos.z, 0);
        putVec4(buf, OFF_SUN_POS, sunPos.x, sunPos.y, sunPos.z, 0);
        putVec4(buf, OFF_MOON_POS, moonPos.x, moonPos.y, moonPos.z, 0);
        putVec4(buf, OFF_SHADOW_LIGHT_POS, shadowLightPos.x, shadowLightPos.y, shadowLightPos.z, 0);
        putVec4(buf, OFF_UP_POS, upPos.x, upPos.y, upPos.z, 0);
    }

    /**
     * Updates sky and fog colors.
     */
    public void updateColors(Vector3f skyColor, Vector4f fogColor,
                             Vector4f entityColor, Vector4f colorModulator) {
        ByteBuffer buf = getWriteMapped();
        putVec4(buf, OFF_SKY_COLOR, skyColor.x, skyColor.y, skyColor.z, 1);
        putVec4(buf, OFF_FOG_COLOR, fogColor.x, fogColor.y, fogColor.z, fogColor.w);
        putVec4(buf, OFF_ENTITY_COLOR, entityColor.x, entityColor.y, entityColor.z, entityColor.w);
        putVec4(buf, OFF_COLOR_MODULATOR, colorModulator.x, colorModulator.y,
                colorModulator.z, colorModulator.w);
    }

    /**
     * Updates the chunk offset for terrain rendering.
     */
    public void updateChunkOffset(float x, float y, float z) {
        putVec4(getWriteMapped(), OFF_CHUNK_OFFSET, x, y, z, 0);
    }

    /**
     * Updates screen/viewport parameters.
     */
    public void updateScreen(float width, float height, float aspectRatio,
                             float near, float far, float fov) {
        ByteBuffer buf = getWriteMapped();
        putVec4(buf, OFF_SCREEN_SIZE, width, height, 1.0f / width, 1.0f / height);
        putVec4(buf, OFF_VIEW_PARAMS, aspectRatio, near, far, fov);
    }

    /**
     * Updates time-related uniforms.
     */
    public void updateTime(float frameTimeCounter, float worldTime,
                           int frameCounter, float sunAngle) {
        putVec4(getWriteMapped(), OFF_TIME, frameTimeCounter, worldTime, frameCounter, sunAngle);
    }

    /**
     * Updates fog parameters.
     */
    public void updateFog(float start, float end, float density, int shape) {
        putVec4(getWriteMapped(), OFF_FOG_PARAMS, start, end, density, shape);
    }

    /**
     * Updates weather parameters (smoothed interpolation on CPU).
     */
    public void updateWeather(float rainStrength, float wetness, float thunderStrength) {
        // Smooth wetness over time (halflife configurable from pack)
        wetnessSmooth = smoothStep(wetnessSmooth, wetness, 0.05f);
        putVec4(getWriteMapped(), OFF_WEATHER, rainStrength, wetnessSmooth, thunderStrength, 0);
    }

    /**
     * Updates player state.
     */
    public void updatePlayerState(float nightVision, float blindness,
                                  float darknessFactor, float playerMood) {
        putVec4(getWriteMapped(), OFF_PLAYER_STATE, nightVision, blindness, darknessFactor, playerMood);
    }

    /**
     * Updates eye brightness (with smoothing).
     */
    public void updateEyeBrightness(int blockLight, int skyLight, float halflife) {
        float smoothFactor = 1.0f - (float) Math.exp(-1.0 / (20.0 * Math.max(halflife, 0.01)));
        eyeBrightnessBlockSmooth += (blockLight - eyeBrightnessBlockSmooth) * smoothFactor;
        eyeBrightnessSkySmooth += (skyLight - eyeBrightnessSkySmooth) * smoothFactor;
        putVec4(getWriteMapped(), OFF_EYE_BRIGHTNESS,
                blockLight, skyLight, eyeBrightnessBlockSmooth, eyeBrightnessSkySmooth);
    }

    /**
     * Updates world state.
     */
    public void updateWorldState(int moonPhase, int isEyeInWater,
                                 float biomeTemp, float biomeRainfall) {
        putVec4(getWriteMapped(), OFF_WORLD_STATE, moonPhase, isEyeInWater, biomeTemp, biomeRainfall);
    }

    /**
     * Updates depth parameters.
     */
    public void updateDepth(float centerDepth, float near, float far) {
        // Smooth center depth
        centerDepthSmooth += (centerDepth - centerDepthSmooth) * 0.1f;
        putVec4(getWriteMapped(), OFF_DEPTH_PARAMS, centerDepthSmooth, near, far, 0);
    }

    /**
     * Updates texture atlas size.
     */
    public void updateAtlas(int width, int height) {
        putVec4(getWriteMapped(), OFF_ATLAS_SIZE, width, height,
                1.0f / width, 1.0f / height);
    }

    /**
     * Updates render state.
     */
    public void updateRenderState(int renderStage) {
        putVec4(getWriteMapped(), OFF_RENDER_STATE, renderStage, 0, 0, 0);
    }

    /**
     * Updates shadow map parameters.
     */
    public void updateShadowParams(int resolution, float distance, float distRenderMul) {
        putVec4(getWriteMapped(), OFF_SHADOW_PARAMS, resolution, distance, distRenderMul, 0);
    }

    /**
     * Updates held item data.
     */
    public void updateHeldItems(int heldId, int heldLight, int heldId2, int heldLight2) {
        putVec4(getWriteMapped(), OFF_HELD_ITEMS, heldId, heldLight, heldId2, heldLight2);
    }

    /**
     * Updates biome data.
     */
    public void updateBiome(int biome, int precipitation, int category) {
        putVec4(getWriteMapped(), OFF_BIOME_DATA, biome, precipitation, category, 0);
    }

    /**
     * Updates alpha test reference value.
     */
    public void updateAlphaTest(float ref) {
        putVec4(getWriteMapped(), OFF_ALPHA_TEST_REF, ref, 0, 0, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Destroys the UBO buffers.
     */
    public void destroy() {
        if (!initialized) return;
        for (int i = 0; i < 2; i++) {
            if (mappedBuffers[i] != null) {
                memory.unmapBuffer(uboAllocations[i]);
            }
            if (uboBuffers[i] != 0) {
                memory.freeBuffer(uboBuffers[i], uboAllocations[i]);
            }
        }
        initialized = false;
        LOGGER.info("Uniform bridge destroyed");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Buffer Write Helpers
    // ═══════════════════════════════════════════════════════════════

    private static void putMatrix(ByteBuffer buf, int offset, Matrix4f mat) {
        mat.get(offset, buf);
    }

    private static void putMatrixInverse(ByteBuffer buf, int offset, Matrix4f mat) {
        Matrix4f inv = new Matrix4f(mat).invert();
        inv.get(offset, buf);
    }

    private static void putVec4(ByteBuffer buf, int offset, float x, float y, float z, float w) {
        buf.putFloat(offset, x);
        buf.putFloat(offset + 4, y);
        buf.putFloat(offset + 8, z);
        buf.putFloat(offset + 12, w);
    }

    private static float smoothStep(float current, float target, float factor) {
        return current + (target - current) * factor;
    }
}
