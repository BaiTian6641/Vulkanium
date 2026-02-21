package net.vulkanium.render.shadow;

import net.vulkanium.render.gbuffer.RenderTargetSettings;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Configurable shadow properties parsed from shader pack directives.
 *
 * <p>Mirrors Iris's PackShadowDirectives — all shadow-related const int / comment
 * directives that shader packs can set to control shadow mapping behavior.</p>
 *
 * <h3>Directive Sources</h3>
 * <ul>
 *   <li>Comment form: {@code // SHADOWRES 2048}, {@code // SHADOWHPL 128.0}</li>
 *   <li>Const form: {@code const int shadowMapResolution = 2048;}</li>
 * </ul>
 *
 * <h3>Per-Sampler Settings</h3>
 * <p>Shadow depth and color buffers each have configurable sampling parameters:</p>
 * <ul>
 *   <li>Depth textures (0-1): hardware filtering, mipmaps, nearest filtering</li>
 *   <li>Color textures (0-7): format, clear, clearColor, mipmaps, nearest filtering</li>
 * </ul>
 */
public class ShadowDirectives {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShadowDir");

    // ── Iris-compatible defaults ──
    public static final int DEFAULT_RESOLUTION = 1024;
    public static final int MAX_SAFE_RESOLUTION = 4096;
    public static final float DEFAULT_DISTANCE = 160.0f;
    public static final float DEFAULT_NEAR = -100.05f;
    public static final float DEFAULT_FAR = 156.0f;
    public static final float DEFAULT_INTERVAL_SIZE = 2.0f;
    public static final float DEFAULT_DISTANCE_RENDER_MUL = -1.0f;
    public static final float DEFAULT_ENTITY_SHADOW_DIST_MUL = 1.0f;

    public static final int MAX_SHADOW_COLOR_BUFFERS = 8;
    public static final int MAX_SHADOW_DEPTH_BUFFERS = 2;

    // ── Core properties ──
    private int resolution = DEFAULT_RESOLUTION;
    private Float fov = null;  // null = orthographic projection
    private float distance = DEFAULT_DISTANCE;
    private float nearPlane = DEFAULT_NEAR;
    private float farPlane = DEFAULT_FAR;
    private float voxelDistance = 0.0f;
    private float distanceRenderMul = DEFAULT_DISTANCE_RENDER_MUL;
    private float entityShadowDistanceMul = DEFAULT_ENTITY_SHADOW_DIST_MUL;
    private float intervalSize = DEFAULT_INTERVAL_SIZE;

    // ── Render toggles ──
    private boolean shouldRenderTerrain = true;
    private boolean shouldRenderTranslucent = true;
    private boolean shouldRenderEntities = true;
    private boolean shouldRenderPlayer = false;
    private boolean shouldRenderBlockEntities = true;
    private boolean shouldRenderLightBlockEntities = false;

    // ── Shadow culling ──
    private ShadowCullState cullState = ShadowCullState.DEFAULT;

    // ── Enable/disable ──
    private Boolean shadowEnabled = null;  // null = pack default (enabled)

    // ── Per-depth-buffer settings (shadowtex0, shadowtex1) ──
    private final DepthSamplingSettings[] depthSettings = new DepthSamplingSettings[MAX_SHADOW_DEPTH_BUFFERS];

    // ── Per-color-buffer settings (shadowcolor0..7) ──
    private final ColorSamplingSettings[] colorSettings = new ColorSamplingSettings[MAX_SHADOW_COLOR_BUFFERS];

    public ShadowDirectives() {
        for (int i = 0; i < MAX_SHADOW_DEPTH_BUFFERS; i++) {
            depthSettings[i] = new DepthSamplingSettings();
        }
        for (int i = 0; i < MAX_SHADOW_COLOR_BUFFERS; i++) {
            colorSettings[i] = new ColorSamplingSettings();
        }
    }

    // ── Parsing from shader pack ──

    /**
     * Applies const int / comment directives found in shader source files.
     */
    public void acceptDirectives(Map<String, String> directives) {
        // Resolution
        applyInt(directives, "shadowMapResolution", v -> resolution = v);
        applyComment(directives, "SHADOWRES", v -> resolution = Integer.parseInt(v));

        // FOV (perspective shadow)
        applyFloat(directives, "shadowMapFov", v -> fov = v);
        applyComment(directives, "SHADOWFOV", v -> fov = Float.parseFloat(v));

        // Distance
        applyFloat(directives, "shadowDistance", v -> distance = v);
        applyComment(directives, "SHADOWHPL", v -> distance = Float.parseFloat(v));

        // Near / Far
        applyFloat(directives, "shadowNearPlane", v -> nearPlane = v);
        applyFloat(directives, "shadowFarPlane", v -> farPlane = v);

        // Misc
        applyFloat(directives, "voxelDistance", v -> voxelDistance = v);
        applyFloat(directives, "shadowDistanceRenderMul", v -> distanceRenderMul = v);
        applyFloat(directives, "entityShadowDistanceMul", v -> entityShadowDistanceMul = v);
        applyFloat(directives, "shadowIntervalSize", v -> intervalSize = v);

        // Per-depth-buffer settings
        parseDepthSettings(directives);

        // Per-color-buffer settings
        parseColorSettings(directives);

        if (resolution < 64) {
            LOGGER.warn("Shadow resolution {} is invalid; using default {}", resolution, DEFAULT_RESOLUTION);
            resolution = DEFAULT_RESOLUTION;
        } else if (resolution > MAX_SAFE_RESOLUTION) {
            LOGGER.warn("Shadow resolution {} is too high; clamping to {}", resolution, MAX_SAFE_RESOLUTION);
            resolution = MAX_SAFE_RESOLUTION;
        }

        LOGGER.debug("Shadow directives: {}x{}, dist={}, interval={}, fov={}",
                resolution, resolution, distance, intervalSize, fov);
    }

    private void parseDepthSettings(Map<String, String> directives) {
        // Hardware filtering: shadowHardwareFiltering, shadowHardwareFiltering0/1
        applyBool(directives, "shadowHardwareFiltering",
                v -> depthSettings[0].hardwareFiltering = v);
        for (int i = 0; i < MAX_SHADOW_DEPTH_BUFFERS; i++) {
            final int idx = i;
            applyBool(directives, "shadowHardwareFiltering" + i,
                    v -> depthSettings[idx].hardwareFiltering = v);

            // Mipmaps: shadowtex0Mipmap, shadowtex1Mipmap, generateShadowMipmap (alias for 0)
            applyBool(directives, "shadowtex" + i + "Mipmap",
                    v -> depthSettings[idx].mipmap = v);

            // Nearest filtering: shadowtex0Nearest, shadowtex1Nearest
            applyBool(directives, "shadowtex" + i + "Nearest",
                    v -> depthSettings[idx].nearest = v);
            applyBool(directives, "shadow" + i + "MinMagNearest",
                    v -> depthSettings[idx].nearest = v);
        }

        // Aliases
        applyBool(directives, "generateShadowMipmap", v -> depthSettings[0].mipmap = v);
        applyBool(directives, "shadowtexMipmap", v -> depthSettings[0].mipmap = v);
        applyBool(directives, "shadowtexNearest", v -> depthSettings[0].nearest = v);
    }

    private void parseColorSettings(Map<String, String> directives) {
        for (int i = 0; i < MAX_SHADOW_COLOR_BUFFERS; i++) {
            final int idx = i;
            String lowerName = "shadowcolor" + i;
            String camelName = "shadowColor" + i;

            // Format
            String formatKey = lowerName + "Format";
            if (directives.containsKey(formatKey)) {
                int vkFormat = RenderTargetSettings.resolveFormat(directives.get(formatKey));
                if (vkFormat != VK_FORMAT_UNDEFINED) {
                    colorSettings[idx].format = vkFormat;
                }
            }

            // Mipmaps
            applyBool(directives, lowerName + "Mipmap", v -> colorSettings[idx].mipmap = v);
            applyBool(directives, camelName + "Mipmap", v -> colorSettings[idx].mipmap = v);

            // Nearest filtering
            applyBool(directives, lowerName + "Nearest", v -> colorSettings[idx].nearest = v);
            applyBool(directives, camelName + "Nearest", v -> colorSettings[idx].nearest = v);
            applyBool(directives, camelName + "MinMagNearest", v -> colorSettings[idx].nearest = v);

            // Clear
            applyBool(directives, lowerName + "Clear", v -> colorSettings[idx].clear = v);
            applyBool(directives, camelName + "Clear", v -> colorSettings[idx].clear = v);

            // Clear color
            String clearColorKey = lowerName + "ClearColor";
            if (directives.containsKey(clearColorKey)) {
                colorSettings[idx].clearColor = parseVec4(directives.get(clearColorKey));
            }
        }

        applyBool(directives, "generateShadowColorMipmap",
                v -> colorSettings[0].mipmap = v);
    }

    // ── Utility ──

    private void applyInt(Map<String, String> map, String key, java.util.function.IntConsumer setter) {
        String val = map.get(key);
        if (val != null) {
            try { setter.accept(Integer.parseInt(val.trim())); }
            catch (NumberFormatException ignored) {}
        }
    }

    private void applyFloat(Map<String, String> map, String key, java.util.function.Consumer<Float> setter) {
        String val = map.get(key);
        if (val != null) {
            try { setter.accept(Float.parseFloat(val.trim())); }
            catch (NumberFormatException ignored) {}
        }
    }

    private void applyBool(Map<String, String> map, String key, java.util.function.Consumer<Boolean> setter) {
        String val = map.get(key);
        if (val != null) {
            setter.accept(Boolean.parseBoolean(val.trim()));
        }
    }

    private void applyComment(Map<String, String> map, String key, java.util.function.Consumer<String> setter) {
        String val = map.get(key);
        if (val != null) {
            try { setter.accept(val.trim()); }
            catch (Exception ignored) {}
        }
    }

    private Vector4f parseVec4(String value) {
        try {
            String[] parts = value.replace("vec4(", "").replace(")", "").split(",");
            return new Vector4f(
                    Float.parseFloat(parts[0].trim()),
                    Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()),
                    Float.parseFloat(parts[3].trim())
            );
        } catch (Exception e) {
            return new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    // ── Getters ──

    public int getResolution() { return resolution; }
    public Float getFov() { return fov; }
    public boolean isOrthographic() { return fov == null; }
    public float getDistance() { return distance; }
    public float getNearPlane() { return nearPlane; }
    public float getFarPlane() { return farPlane; }
    public float getVoxelDistance() { return voxelDistance; }
    public float getDistanceRenderMul() { return distanceRenderMul; }
    public float getEntityShadowDistanceMul() { return entityShadowDistanceMul; }
    public float getIntervalSize() { return intervalSize; }

    public boolean shouldRenderTerrain() { return shouldRenderTerrain; }
    public boolean shouldRenderTranslucent() { return shouldRenderTranslucent; }
    public boolean shouldRenderEntities() { return shouldRenderEntities; }
    public boolean shouldRenderPlayer() { return shouldRenderPlayer; }
    public boolean shouldRenderBlockEntities() { return shouldRenderBlockEntities; }
    public boolean shouldRenderLightBlockEntities() { return shouldRenderLightBlockEntities; }

    public ShadowCullState getCullState() { return cullState; }
    public Boolean isShadowEnabled() { return shadowEnabled; }

    public DepthSamplingSettings getDepthSettings(int index) { return depthSettings[index]; }
    public ColorSamplingSettings getColorSettings(int index) { return colorSettings[index]; }

    // ── Setters (from ShaderProperties) ──

    public void setShouldRenderTerrain(boolean v) { shouldRenderTerrain = v; }
    public void setShouldRenderTranslucent(boolean v) { shouldRenderTranslucent = v; }
    public void setShouldRenderEntities(boolean v) { shouldRenderEntities = v; }
    public void setShouldRenderPlayer(boolean v) { shouldRenderPlayer = v; }
    public void setShouldRenderBlockEntities(boolean v) { shouldRenderBlockEntities = v; }
    public void setShouldRenderLightBlockEntities(boolean v) { shouldRenderLightBlockEntities = v; }
    public void setCullState(ShadowCullState state) { cullState = state; }
    public void setShadowEnabled(Boolean enabled) { shadowEnabled = enabled; }

    // ── Inner classes ──

    public static class DepthSamplingSettings {
        public boolean hardwareFiltering = false;
        public boolean mipmap = false;
        public boolean nearest = false;

        /**
         * Returns the Vulkan filter for this depth sampler.
         * Integer formats always use nearest (no linear filtering support).
         */
        public int getVkFilter() {
            return nearest ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
        }

        /**
         * Returns the Vulkan compare op for hardware depth filtering.
         */
        public int getCompareOp() {
            return hardwareFiltering ? VK_COMPARE_OP_LESS_OR_EQUAL : 0;
        }

        public boolean isHardwareFiltering() { return hardwareFiltering; }
    }

    public static class ColorSamplingSettings {
        public int format = VK_FORMAT_R8G8B8A8_UNORM;  // default RGBA
        public boolean mipmap = false;
        public boolean nearest = false;
        public boolean clear = true;
        public Vector4f clearColor = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

        /**
         * Returns the Vulkan filter — integer formats always use nearest.
         */
        public int getVkFilter() {
            boolean isInteger = isIntegerFormat();
            return (nearest || isInteger) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
        }

        public boolean isIntegerFormat() {
            return format == VK_FORMAT_R8_UINT || format == VK_FORMAT_R16_UINT
                    || format == VK_FORMAT_R32_UINT || format == VK_FORMAT_R8G8B8A8_UINT
                    || format == VK_FORMAT_R16G16B16A16_UINT || format == VK_FORMAT_R32G32B32A32_UINT
                    || format == VK_FORMAT_R8_SINT || format == VK_FORMAT_R16_SINT
                    || format == VK_FORMAT_R32_SINT || format == VK_FORMAT_R8G8B8A8_SINT
                    || format == VK_FORMAT_R16G16B16A16_SINT || format == VK_FORMAT_R32G32B32A32_SINT;
        }
    }

    /**
     * Shadow culling strategies matching Iris's ShadowCullState.
     */
    public enum ShadowCullState {
        /** Standard Iris culling (pack default) */
        DEFAULT,
        /** Advanced frustum-based shadow culling */
        ADVANCED,
        /** Reversed culling (for packs that render behind the camera) */
        REVERSED,
        /** Distance-only culling (for voxelization packs) */
        DISTANCE
    }
}
