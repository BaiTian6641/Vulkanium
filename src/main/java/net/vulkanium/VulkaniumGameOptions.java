package net.vulkanium;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanium.gui.options.TextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vulkanium global options — persisted as JSON.
 *
 * <p>Modeled after Sodium's {@code SodiumGameOptions} with additional Vulkan-specific
 * and shader-engine settings. Organized into setting groups that map 1:1 to option pages
 * in the GUI.</p>
 */
public class VulkaniumGameOptions {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Options");
    private static final String DEFAULT_FILE_NAME = "vulkanium-options.json";

    // ─── Setting Groups ────────────────────────────────────────────────

    public final VideoSettings video = new VideoSettings();
    public final QualitySettings quality = new QualitySettings();
    public final PerformanceSettings performance = new PerformanceSettings();
    public final VulkanSettings vulkan = new VulkanSettings();
    public final ShaderSettings shader = new ShaderSettings();
    public final RayTracingSettings rayTracing = new RayTracingSettings();
    public final DebugSettings debug = new DebugSettings();
    public final NotificationSettings notifications = new NotificationSettings();

    private boolean readOnly;

    private VulkaniumGameOptions() {}

    public static VulkaniumGameOptions defaults() {
        return new VulkaniumGameOptions();
    }

    // ─── Video ─────────────────────────────────────────────────────────

    public static class VideoSettings {
        /** Primary render mode selector exposed in Video Settings. */
        @SerializedName("render_mode")
        public RenderModeSetting renderMode = RenderModeSetting.VANILLA;

        /** Frames in flight: 2=double buffer, 3=triple buffer */
        public int framesInFlight = 3;

        /** Present mode: FIFO(vsync), MAILBOX, IMMEDIATE */
        @SerializedName("present_mode")
        public PresentMode presentMode = PresentMode.MAILBOX;

        /** Preferred GPU device index (-1 = auto) */
        public int deviceIndex = -1;

        /** Enable HDR output if display supports it */
        public boolean hdrOutput = false;

        /** Screen-space color space target for final output */
        @SerializedName("color_space")
        public ScreenColorSpace colorSpace = ScreenColorSpace.SRGB;

        /** Full-screen gamma correction value (1.0 = linear, 2.2 = sRGB standard) */
        public float outputGamma = 2.2f;

        /** Enable dithering on final output to reduce color banding */
        public boolean ditheringEnabled = true;

        public enum PresentMode implements TextProvider {
            FIFO("VSync"),
            MAILBOX("Mailbox (Triple Buffer)"),
            IMMEDIATE("Immediate (Uncapped)");

            private final String name;
            PresentMode(String name) { this.name = name; }

            @Override
            public String getLocalizedName() { return name; }

            public int toVkPresentMode() {
                return switch (this) {
                    case FIFO -> 2;       // VK_PRESENT_MODE_FIFO_KHR
                    case MAILBOX -> 1;    // VK_PRESENT_MODE_MAILBOX_KHR
                    case IMMEDIATE -> 0;  // VK_PRESENT_MODE_IMMEDIATE_KHR
                };
            }
        }

        public enum RenderModeSetting implements TextProvider {
            VANILLA("Vanilla"),
            VANILLA_RT("Vanilla + RT"),
            SHADERPACK("Shaderpack");

            private final String name;

            RenderModeSetting(String name) {
                this.name = name;
            }

            @Override
            public String getLocalizedName() {
                return name;
            }

            public net.vulkanium.render.RenderMode toCoreMode() {
                return switch (this) {
                    case VANILLA -> net.vulkanium.render.RenderMode.VANILLA;
                    case VANILLA_RT -> net.vulkanium.render.RenderMode.VANILLA_RT;
                    case SHADERPACK -> net.vulkanium.render.RenderMode.SHADERPACK;
                };
            }

            public static RenderModeSetting fromCoreMode(net.vulkanium.render.RenderMode mode) {
                return switch (mode) {
                    case VANILLA -> VANILLA;
                    case VANILLA_RT -> VANILLA_RT;
                    case SHADERPACK -> SHADERPACK;
                };
            }
        }

        public enum ScreenColorSpace implements TextProvider {
            SRGB("sRGB"),
            DCI_P3("DCI-P3"),
            DISPLAY_P3("Display P3"),
            REC2020("Rec. 2020"),
            ADOBE_RGB("Adobe RGB");

            private final String name;
            ScreenColorSpace(String name) { this.name = name; }

            @Override
            public String getLocalizedName() { return name; }

            /** Maps to HdrConfig.ColorSpaceTarget index */
            public int toColorSpaceIndex() {
                return switch (this) {
                    case SRGB -> 0;
                    case DCI_P3 -> 1;
                    case DISPLAY_P3 -> 2;
                    case REC2020 -> 3;
                    case ADOBE_RGB -> 4;
                };
            }
        }
    }

    // ─── Quality ───────────────────────────────────────────────────────

    public static class QualitySettings {
        /** Shadow map resolution override (0 = pack default) */
        public int shadowResolution = 0;

        /** Maximum shadow render distance in blocks */
        public float maxShadowDistance = 128.0f;

        public GraphicsQuality weatherQuality = GraphicsQuality.DEFAULT;
        public GraphicsQuality leavesQuality = GraphicsQuality.DEFAULT;

        public boolean enableVignette = true;

        public enum GraphicsQuality implements TextProvider {
            DEFAULT("Default"),
            FANCY("Fancy"),
            FAST("Fast");

            private final String name;
            GraphicsQuality(String name) { this.name = name; }

            @Override
            public String getLocalizedName() { return name; }
        }
    }

    // ─── Performance ───────────────────────────────────────────────────

    public static class PerformanceSettings {
        /** Chunk builder thread count (0 = auto based on CPU cores) */
        public int chunkBuilderThreads = 0;

        @SerializedName("always_defer_chunk_updates_v2")
        public boolean alwaysDeferChunkUpdates = true;

        /** Only animate textures visible to camera */
        public boolean animateOnlyVisibleTextures = true;

        /** Cull entities outside the camera frustum */
        public boolean useEntityCulling = true;

        /** Cull sections behind fog */
        public boolean useFogOcclusion = true;

        /** Cull invisible block faces during mesh building */
        public boolean useBlockFaceCulling = true;

        /** GPU-side frustum culling via compute shader */
        public boolean gpuFrustumCulling = true;

        /** GPU-side translucent sort via compute shader */
        public boolean gpuTranslucentSort = true;

        /** Indirect draw commands for terrain */
        public boolean useIndirectDraw = true;

        /** CPU render-ahead frame limit */
        public int cpuRenderAheadLimit = 3;
    }

    // ─── Vulkan-Specific ───────────────────────────────────────────────

    public static class VulkanSettings {
        /** Multi-threaded secondary command buffer recording */
        public boolean multiThreadedRecording = true;

        /** Async uploads via dedicated transfer queue */
        public boolean asyncTransfers = true;

        /** Persist Vulkan pipeline cache to disk */
        public boolean persistPipelineCache = true;

        /** Persist compiled SPIR-V to disk */
        public boolean persistShaderCache = true;

        /** Vulkan advanced culling aggressiveness: 1=aggressive, 2=normal, 3=conservative, 10=off */
        public int advancedCulling = 2;

        /** Staging buffer size in MB */
        public int stagingBufferSizeMB = 32;
    }

    // ─── Shader Engine ─────────────────────────────────────────────────

    public static class ShaderSettings {
        /** Whether to use the selected shaderpack for rendering */
        public boolean enableShaderpack = false;

        /** Name of the selected shaderpack (empty = none) */
        public String selectedShaderpack = "";

        /** Maximum render targets for MRT (capped by GPU limits) */
        public int maxRenderTargets = 8;

        /** Force all render targets to RGBA8 (compatibility mode) */
        public boolean forceRGBA8 = false;

        /** Disable geometry shader emulation (faster, breaks some packs) */
        public boolean disableGeometryShaders = false;

        /** Enable shader debug output to disk on compilation failure */
        public boolean dumpShadersOnError = true;

        /** Auto-generate debug report on shader compilation failure */
        public boolean autoDebugReport = true;

        /** SPIR-V optimization level: 0=none, 1=size, 2=performance */
        public int spirvOptimizationLevel = 2;

        /** Active shaderpack option overrides (macro name -> selected value). */
        public Map<String, String> shaderpackOptionOverrides = new HashMap<>();
    }

    // ─── Ray Tracing ───────────────────────────────────────────────────

    public static class RayTracingSettings {
        /** Enable hardware ray tracing (requires supported GPU) */
        public boolean enabled = false;

        /** RT quality tier: 0=off, 1=shadows only, 2=shadows+reflections, 3=full path trace */
        public int qualityTier = 0;

        /** SVGF denoiser à-trous iteration count (1-5) */
        public int denoiserIterations = 3;

        /** Enable compute SSAO (works on all GPUs, no RT extensions required) */
        public boolean ssaoEnabled = true;

        /** SSAO sample count per pixel (4–64) */
        public int ssaoSamples = 16;

        /** Enable temporal upscaling (FSR3/DLSS/XeSS) */
        public boolean enableUpscaler = false;

        /** Upscaler type: FSR3=0, DLSS=1, XESS=2 */
        public int upscalerType = 0;

        /** Upscaler quality preset: 0=ultra-quality ... 4=ultra-performance */
        public int upscalerQuality = 1;
    }

    // ─── Debug ─────────────────────────────────────────────────────────

    public static class DebugSettings {
        /** Enable Vulkan validation layers */
        public boolean enableValidationLayers = false;

        /** Enable Vulkan API call tracing */
        public boolean enableApiTrace = false;

        /** Show F3-style performance overlay */
        public boolean showPerformanceOverlay = false;

        /** Show GPU memory usage in overlay */
        public boolean showMemoryOverlay = false;

        /** Enable Vulkan debug markers for renderdoc/nsight */
        public boolean enableDebugMarkers = false;

        /** Enable memory tracing (tracks all allocations) */
        public boolean enableMemoryTracing = false;

        /** Enable verbose debug logging (per-draw diagnostics, texture params, frame stats).
         *  OFF by default — causes significant performance overhead. */
        public boolean debugLogging = false;
    }

    // ─── Notifications ─────────────────────────────────────────────────

    public static class NotificationSettings {
        public boolean hasClearedDonationButton = false;
        public boolean hasSeenFirstRunPrompt = false;
    }

    // ─── Serialization ─────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VulkaniumGameOptions loadFromDisk() {
        Path path = getConfigPath();
        VulkaniumGameOptions config;

        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                config = GSON.fromJson(json, VulkaniumGameOptions.class);
                if (config == null) config = new VulkaniumGameOptions();
            } catch (Exception e) {
                LOGGER.error("Failed to load options, using defaults: {}", e.getMessage());
                config = new VulkaniumGameOptions();
            }
        } else {
            config = new VulkaniumGameOptions();
        }

        try {
            writeToDisk(config);
        } catch (IOException e) {
            LOGGER.error("Failed to write default config: {}", e.getMessage());
        }

        return config;
    }

    public static void writeToDisk(VulkaniumGameOptions config) throws IOException {
        if (config.isReadOnly()) {
            throw new IllegalStateException("Config file is read-only");
        }

        Path path = getConfigPath();
        Path dir = path.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        Files.writeString(path, GSON.toJson(config));
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(DEFAULT_FILE_NAME);
    }

    public boolean isReadOnly() { return this.readOnly; }
    public void setReadOnly() { this.readOnly = true; }
}
