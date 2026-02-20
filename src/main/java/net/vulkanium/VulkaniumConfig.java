package net.vulkanium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vulkanium configuration. Persisted as JSON.
 */
public class VulkaniumConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path configPath;

    // === Rendering ===

    /** Rendering mode: 0=Vanilla, 1=Vanilla+RT, 2=Shaderpack */
    public int renderMode = 0;

    /** Whether shaderpack rendering is enabled */
    public boolean shaderpackEnabled = false;

    /** Name of the selected shaderpack (empty = none) */
    public String selectedShaderpack = "";

    /** Number of frames in flight (2 = double buffer, 3 = triple buffer) */
    public int framesInFlight = 3;

    /** Preferred present mode: 0 = FIFO (vsync), 1 = Mailbox, 2 = Immediate */
    public int presentMode = 1;

    /** GPU device index (-1 = auto-select best) */
    public int deviceIndex = -1;

    /** Enable HDR output if display supports it */
    public boolean hdrOutput = false;

    // === Shadows ===

    /** Shadow map resolution override (0 = use pack default) */
    public int shadowResolutionOverride = 0;

    /** Maximum shadow render distance in blocks */
    public float maxShadowDistance = 128.0f;

    // === Performance ===

    /** Enable multi-threaded command recording */
    public boolean multiThreadedCommands = true;

    /** Enable async transfers via dedicated transfer queue */
    public boolean asyncTransfers = true;

    /** Enable GPU-side frustum culling via compute shader */
    public boolean gpuFrustumCulling = true;

    /** Enable GPU-side translucent sorting via compute shader */
    public boolean gpuTranslucentSort = true;

    /** Enable Vulkan pipeline cache persistence to disk */
    public boolean persistPipelineCache = true;

    /** Enable SPIR-V shader cache persistence to disk */
    public boolean persistShaderCache = true;

    // === Debug ===

    /** Enable Vulkan validation layers (requires LunarG SDK) */
    public boolean enableValidationLayers = false;

    /** Enable Vulkan API call logging */
    public boolean enableApiTrace = false;

    /** Enable performance overlay HUD */
    public boolean showPerformanceOverlay = false;

    /** Enable verbose debug logging (per-draw diagnostics, texture params, frame stats).
     *  OFF by default — enabling causes significant performance overhead from LOGGER calls. */
    public boolean debugLogging = true;

    // === ConfigUI-compatible aliases ===
    // These provide field names that ConfigUI expects.

    /** @see #gpuFrustumCulling */
    public boolean useGPUCulling = true;
    /** @see #asyncTransfers */
    public boolean useAsyncTransfer = true;
    /** @see #enableValidationLayers */
    public boolean enableValidation = false;
    /** @see #persistPipelineCache */
    public boolean usePipelineCache = true;
    /** @see #persistShaderCache */
    public boolean useSpirVCache = true;
    /** Dump compiled shaders to disk for debugging */
    public boolean dumpShaders = false;
    /** Worker thread count for chunk building (0 = auto) */
    public int workerThreads = 0;
    /** Shadow map resolution (0 = pack default) */
    public int shadowResolution = 0;
    /** Shadow render distance in chunks */
    public int shadowDistance = 8;

    // === Ray Tracing (default OFF) ===

    /** Enable RT (compute SSAO + optional hardware RT) — ON by default when GPU supports it */
    public boolean rayTracingEnabled = true;

    /** RT quality tier: 0=off, 1=shadows only, 2=shadows+reflections, 3=full path trace */
    public int rayTracingQualityTier = 0;

    /** Enable compute SSAO (works on all GPUs, no RT extensions required) */
    public boolean ssaoEnabled = true;

    /** SSAO sample count: 4–64. Higher = better quality, lower FPS */
    public int ssaoSamples = 16;

    /** SSAO radius in world units: 0.1–10.0 */
    public float ssaoRadius = 1.5f;

    // === Upscaler (default OFF) ===

    /** Enable temporal upscaling (FSR3/DLSS/XeSS) — OFF by default */
    public boolean upscalerEnabled = false;

    /** Upscaler type: 0=FSR3, 1=DLSS, 2=XeSS */
    public int upscalerType = 0;

    /** Upscaler quality preset: 0=ultra-quality ... 4=ultra-performance */
    public int upscalerQuality = 1;

    /**
     * Loads configuration from file, or creates a default if not found.
     * Re-saves after loading to persist any newly added fields with their defaults.
     */
    public static VulkaniumConfig load(Path path) {
        VulkaniumConfig config;

        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                config = GSON.fromJson(json, VulkaniumConfig.class);
                if (config == null) {
                    config = new VulkaniumConfig();
                }
                config.configPath = path;
                // Re-save to persist any new fields added since the last save
                config.save();
                LOGGER.info("Loaded Vulkanium config from {}", path);
                return config;
            } catch (Exception e) {
                LOGGER.warn("Failed to load Vulkanium config, using defaults: {}", e.getMessage());
            }
        }

        config = new VulkaniumConfig();
        config.configPath = path;
        config.save();
        return config;
    }

    /**
     * Saves the current configuration to disk.
     */
    public void save() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.error("Failed to save Vulkanium config: {}", e.getMessage());
        }
    }

    // === Derived Getters ===

    /** Get the current rendering mode as an enum. */
    public net.vulkanium.render.RenderMode getRenderMode() {
        return net.vulkanium.render.RenderMode.fromOrdinal(renderMode);
    }

    /** Set the rendering mode from the enum. */
    public void setRenderMode(net.vulkanium.render.RenderMode mode) {
        this.renderMode = mode.ordinal();
    }

    public int getFramesInFlight() { return Math.max(1, Math.min(framesInFlight, 4)); }

    /**
     * Converts the presentMode config value to a Vulkan VkPresentModeKHR constant.
     * Config: 0 = FIFO (VSync), 1 = Mailbox, 2 = Immediate
     * Vulkan: IMMEDIATE=0, MAILBOX=1, FIFO=2, FIFO_RELAXED=3
     */
    public int getPresentModeVk() {
        return switch (presentMode) {
            case 0 -> 2;  // VK_PRESENT_MODE_FIFO_KHR
            case 1 -> 1;  // VK_PRESENT_MODE_MAILBOX_KHR
            case 2 -> 0;  // VK_PRESENT_MODE_IMMEDIATE_KHR
            default -> 2; // FIFO fallback
        };
    }
}
