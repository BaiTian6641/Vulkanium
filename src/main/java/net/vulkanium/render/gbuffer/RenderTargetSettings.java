package net.vulkanium.render.gbuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-render-target configuration parsed from shader pack directives.
 *
 * <p>Shader packs can override the default format, clear behavior, and filtering
 * for each color buffer (colortex0–15) via {@code const int} declarations:</p>
 * <pre>
 *   const int colortex0Format = RGBA8;           // default
 *   const int colortex1Format = RGBA16F;         // normal G-buffer
 *   const int colortex2Format = RGBA16F;         // specular G-buffer
 *   const int colortex4Format = R32F;            // AO / custom
 *   const int colortex5Format = RGBA32F;         // velocity / custom
 *   const bool colortex1Clear = false;           // don't clear between frames
 *   const vec4 colortex3ClearColor = vec4(0, 0, 0, 1);
 * </pre>
 *
 * <p>Also supports legacy OptiFine names: gcolor(0), gdepth(1), gnormal(2),
 * composite(3), gaux1(4)–gaux4(7).</p>
 */
public class RenderTargetSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTSettings");

    /** Maximum number of color targets (OptiFine: 8, Iris extended: 16) */
    public static final int MAX_COLOR_TARGETS = 16;

    /** Maximum depth targets */
    public static final int MAX_DEPTH_TARGETS = 3;

    // Legacy OptiFine names → index mapping
    private static final Map<String, Integer> LEGACY_NAMES = Map.of(
            "gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3,
            "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7
    );

    // ── Per-Target Settings ──

    /**
     * Configuration for a single render target buffer.
     */
    public static class BufferSettings {
        private final int index;
        private int vkFormat;
        private boolean clear;
        private float[] clearColor;
        private boolean mipmapEnabled;
        private boolean linearFiltering;
        private boolean used;

        public BufferSettings(int index) {
            this.index = index;
            this.vkFormat = VK_FORMAT_R8G8B8A8_UNORM; // RGBA8 default
            this.clear = true;
            this.clearColor = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
            this.mipmapEnabled = false;
            this.linearFiltering = true;
            this.used = false;
        }

        public int getIndex() { return index; }
        public int getVkFormat() { return vkFormat; }
        public boolean shouldClear() { return clear; }
        public float[] getClearColor() { return clearColor; }
        public boolean isMipmapEnabled() { return mipmapEnabled; }
        public boolean isLinearFiltering() { return linearFiltering; }
        public boolean isUsed() { return used; }

        void setVkFormat(int format) { this.vkFormat = format; }
        void setClear(boolean clear) { this.clear = clear; }
        void setClearColor(float[] color) { this.clearColor = color; }
        void setMipmapEnabled(boolean enabled) { this.mipmapEnabled = enabled; }
        void setLinearFiltering(boolean linear) { this.linearFiltering = linear; }
        void markUsed() { this.used = true; }
    }

    // ── Format Name → VkFormat Mapping ──

    private static final Map<String, Integer> FORMAT_MAP = new LinkedHashMap<>();
    static {
        // Integer formats
        FORMAT_MAP.put("R8", VK_FORMAT_R8_UNORM);
        FORMAT_MAP.put("RG8", VK_FORMAT_R8G8_UNORM);
        FORMAT_MAP.put("RGB8", VK_FORMAT_R8G8B8A8_UNORM);   // No VK RGB8, use RGBA8
        FORMAT_MAP.put("RGBA8", VK_FORMAT_R8G8B8A8_UNORM);
        FORMAT_MAP.put("R8_SNORM", VK_FORMAT_R8_SNORM);
        FORMAT_MAP.put("RG8_SNORM", VK_FORMAT_R8G8_SNORM);
        FORMAT_MAP.put("RGB8_SNORM", VK_FORMAT_R8G8B8A8_SNORM);
        FORMAT_MAP.put("RGBA8_SNORM", VK_FORMAT_R8G8B8A8_SNORM);

        // 16-bit float formats
        FORMAT_MAP.put("R16F", VK_FORMAT_R16_SFLOAT);
        FORMAT_MAP.put("RG16F", VK_FORMAT_R16G16_SFLOAT);
        FORMAT_MAP.put("RGB16F", VK_FORMAT_R16G16B16A16_SFLOAT);
        FORMAT_MAP.put("RGBA16F", VK_FORMAT_R16G16B16A16_SFLOAT);

        // 32-bit float formats
        FORMAT_MAP.put("R32F", VK_FORMAT_R32_SFLOAT);
        FORMAT_MAP.put("RG32F", VK_FORMAT_R32G32_SFLOAT);
        FORMAT_MAP.put("RGB32F", VK_FORMAT_R32G32B32A32_SFLOAT);
        FORMAT_MAP.put("RGBA32F", VK_FORMAT_R32G32B32A32_SFLOAT);

        // 16-bit integer formats
        FORMAT_MAP.put("R16", VK_FORMAT_R16_UNORM);
        FORMAT_MAP.put("RG16", VK_FORMAT_R16G16_UNORM);
        FORMAT_MAP.put("RGB16", VK_FORMAT_R16G16B16A16_UNORM);
        FORMAT_MAP.put("RGBA16", VK_FORMAT_R16G16B16A16_UNORM);

        // Unsigned integer formats
        FORMAT_MAP.put("R8UI", VK_FORMAT_R8_UINT);
        FORMAT_MAP.put("RG8UI", VK_FORMAT_R8G8_UINT);
        FORMAT_MAP.put("RGBA8UI", VK_FORMAT_R8G8B8A8_UINT);
        FORMAT_MAP.put("R16UI", VK_FORMAT_R16_UINT);
        FORMAT_MAP.put("RG16UI", VK_FORMAT_R16G16_UINT);
        FORMAT_MAP.put("RGBA16UI", VK_FORMAT_R16G16B16A16_UINT);
        FORMAT_MAP.put("R32UI", VK_FORMAT_R32_UINT);
        FORMAT_MAP.put("RG32UI", VK_FORMAT_R32G32_UINT);
        FORMAT_MAP.put("RGBA32UI", VK_FORMAT_R32G32B32A32_UINT);

        // Signed integer formats
        FORMAT_MAP.put("R8I", VK_FORMAT_R8_SINT);
        FORMAT_MAP.put("RG8I", VK_FORMAT_R8G8_SINT);
        FORMAT_MAP.put("R16I", VK_FORMAT_R16_SINT);
        FORMAT_MAP.put("R32I", VK_FORMAT_R32_SINT);

        // 11/10 packed float
        FORMAT_MAP.put("R11F_G11F_B10F", VK_FORMAT_B10G11R11_UFLOAT_PACK32);

        // Depth formats
        FORMAT_MAP.put("DEPTH_COMPONENT32F", VK_FORMAT_D32_SFLOAT);
        FORMAT_MAP.put("DEPTH_COMPONENT24", VK_FORMAT_D24_UNORM_S8_UINT);
        FORMAT_MAP.put("DEPTH_COMPONENT16", VK_FORMAT_D16_UNORM);
    }

    // ── State ──

    private final BufferSettings[] colorSettings;
    private final int depthFormat;

    /** Scale factor for render target resolution (from pack directives) */
    private float textureScale = 1.0f;

    public RenderTargetSettings() {
        this.colorSettings = new BufferSettings[MAX_COLOR_TARGETS];
        for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
            colorSettings[i] = new BufferSettings(i);
        }
        this.depthFormat = VK_FORMAT_D32_SFLOAT;

        // Default format for colortex1 (normals at higher precision)
        colorSettings[1].setVkFormat(VK_FORMAT_R16G16B16A16_SFLOAT);
    }

    /**
     * Parses render target directives from shader pack constant declarations.
     *
     * @param directives Map of directive name → value from shader source
     */
    public void parseDirectives(Map<String, String> directives) {
        for (Map.Entry<String, String> entry : directives.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().trim().toUpperCase(Locale.ROOT);

            // colortexNFormat
            if (key.matches("colortex(\\d+)Format") || key.matches("COLORTEX(\\d+)FORMAT")) {
                int index = Integer.parseInt(key.replaceAll("\\D+", ""));
                if (index >= 0 && index < MAX_COLOR_TARGETS) {
                    Integer vkFormat = FORMAT_MAP.get(value);
                    if (vkFormat != null) {
                        colorSettings[index].setVkFormat(vkFormat);
                        LOGGER.debug("colortex{} format: {} (VkFormat {})", index, value, vkFormat);
                    } else {
                        LOGGER.warn("Unknown format '{}' for colortex{}, using default", value, index);
                    }
                }
            }

            // Legacy names (gdepthFormat, gnormalFormat, etc.)
            for (Map.Entry<String, Integer> legacy : LEGACY_NAMES.entrySet()) {
                String pattern = legacy.getKey() + "Format";
                if (key.equalsIgnoreCase(pattern)) {
                    Integer vkFormat = FORMAT_MAP.get(value);
                    if (vkFormat != null) {
                        colorSettings[legacy.getValue()].setVkFormat(vkFormat);
                    }
                }
            }

            // colortexNClear
            if (key.matches("(?i)colortex(\\d+)Clear")) {
                int index = Integer.parseInt(key.replaceAll("\\D+", ""));
                if (index >= 0 && index < MAX_COLOR_TARGETS) {
                    colorSettings[index].setClear(Boolean.parseBoolean(entry.getValue().trim()));
                }
            }

            // colortexNClearColor (vec4)
            if (key.matches("(?i)colortex(\\d+)ClearColor")) {
                int index = Integer.parseInt(key.replaceAll("\\D+", ""));
                if (index >= 0 && index < MAX_COLOR_TARGETS) {
                    float[] color = parseVec4(entry.getValue().trim());
                    if (color != null) {
                        colorSettings[index].setClearColor(color);
                    }
                }
            }

            // colortexNMipmapEnabled
            if (key.matches("(?i)colortex(\\d+)MipmapEnabled")) {
                int index = Integer.parseInt(key.replaceAll("\\D+", ""));
                if (index >= 0 && index < MAX_COLOR_TARGETS) {
                    colorSettings[index].setMipmapEnabled(Boolean.parseBoolean(entry.getValue().trim()));
                }
            }

            // Texture scale
            if (key.equalsIgnoreCase("texture.resolution") || key.equalsIgnoreCase("textureScale")) {
                try {
                    textureScale = Float.parseFloat(entry.getValue().trim());
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid textureScale: {}", entry.getValue());
                }
            }
        }
    }

    /**
     * Marks a buffer as used by a program's DRAWBUFFERS/RENDERTARGETS directive.
     *
     * @param index Buffer index (0–15)
     */
    public void markUsed(int index) {
        if (index >= 0 && index < MAX_COLOR_TARGETS) {
            colorSettings[index].markUsed();
        }
    }

    /**
     * Marks multiple buffers as used.
     *
     * @param indices Buffer indices from RENDERTARGETS
     */
    public void markUsed(int[] indices) {
        for (int index : indices) {
            markUsed(index);
        }
    }

    // ── Getters ──

    public BufferSettings getColorSettings(int index) { return colorSettings[index]; }
    public int getDepthFormat() { return depthFormat; }
    public float getTextureScale() { return textureScale; }

    /** Returns indices of all buffers that have been marked as used. */
    public int[] getUsedBufferIndices() {
        return java.util.stream.IntStream.range(0, MAX_COLOR_TARGETS)
                .filter(i -> colorSettings[i].isUsed())
                .toArray();
    }

    /** Returns the number of used color targets. */
    public int getUsedColorTargetCount() {
        int count = 0;
        for (BufferSettings s : colorSettings) if (s.isUsed()) count++;
        return count;
    }

    /**
     * Whether any integer-format targets are used (affects blend state — no blending for integer).
     */
    public boolean hasIntegerTargets() {
        for (BufferSettings s : colorSettings) {
            if (!s.isUsed()) continue;
            int fmt = s.getVkFormat();
            if (fmt == VK_FORMAT_R8_UINT || fmt == VK_FORMAT_R8G8_UINT ||
                fmt == VK_FORMAT_R8G8B8A8_UINT || fmt == VK_FORMAT_R16_UINT ||
                fmt == VK_FORMAT_R16G16_UINT || fmt == VK_FORMAT_R8_SINT ||
                fmt == VK_FORMAT_R8G8_SINT || fmt == VK_FORMAT_R16_SINT ||
                fmt == VK_FORMAT_R32_SINT || fmt == VK_FORMAT_R32_UINT ||
                fmt == VK_FORMAT_R32G32_UINT || fmt == VK_FORMAT_R32G32B32A32_UINT ||
                fmt == VK_FORMAT_R16G16B16A16_UINT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves an OptiFine format name to a VkFormat constant.
     */
    public static int resolveFormat(String name) {
        Integer fmt = FORMAT_MAP.get(name.toUpperCase(Locale.ROOT));
        return fmt != null ? fmt : VK_FORMAT_R8G8B8A8_UNORM;
    }

    // ── Utility ──

    private static float[] parseVec4(String value) {
        // Parses "vec4(R, G, B, A)" or "R G B A" or "R, G, B, A"
        String cleaned = value.replaceAll("[vec4()\\s]", "");
        String[] parts = cleaned.split(",");
        if (parts.length == 4) {
            try {
                return new float[]{
                        Float.parseFloat(parts[0].trim()),
                        Float.parseFloat(parts[1].trim()),
                        Float.parseFloat(parts[2].trim()),
                        Float.parseFloat(parts[3].trim())
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
