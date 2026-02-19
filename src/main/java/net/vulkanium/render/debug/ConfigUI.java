package net.vulkanium.render.debug;

import net.vulkanium.VulkaniumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Configuration UI bridge for Vulkanium settings.
 *
 * <p>Provides structured access to all user-facing configuration options
 * so that GUI screens (Iris shader config screen, Mod Menu) can read and
 * write settings. This class does not own a GUI toolkit; it exposes a
 * model that the actual screen classes bind to.</p>
 *
 * <h3>Setting Categories</h3>
 * <ul>
 *   <li><b>Quality</b> — shadow resolution, OIT mode, sample count</li>
 *   <li><b>Performance</b> — GPU culling, async transfer, worker threads</li>
 *   <li><b>Debug</b> — validation layers, debug overlay, shader dump</li>
 *   <li><b>Compatibility</b> — fallback paths, format overrides</li>
 * </ul>
 */
public class ConfigUI {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ConfigUI");

    // ── Setting categories ──

    public enum Category {
        QUALITY("Quality"),
        PERFORMANCE("Performance"),
        DEBUG("Debug"),
        COMPATIBILITY("Compatibility");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    // ── Setting descriptors ──

    public enum Setting {
        // Quality
        SHADOW_RESOLUTION(Category.QUALITY, "Shadow Resolution", SettingType.ENUM,
                "Resolution of shadow maps. Higher = sharper shadows, lower FPS.",
                new String[]{"512", "1024", "2048", "4096"}, "1024"),

        TRANSLUCENT_SORTING(Category.QUALITY, "Translucent Sorting", SettingType.ENUM,
                "Method for rendering translucent geometry.",
                new String[]{"CPU Sort", "GPU Radix Sort", "Weighted Blended OIT"}, "CPU Sort"),

        SHADOW_DISTANCE(Category.QUALITY, "Shadow Distance", SettingType.SLIDER,
                "Maximum shadow render distance in blocks.", null, "160"),

        // Performance
        GPU_CULLING(Category.PERFORMANCE, "GPU Frustum Culling", SettingType.BOOLEAN,
                "Use compute shaders for frustum culling. Faster on modern GPUs.", null, "true"),

        ASYNC_TRANSFER(Category.PERFORMANCE, "Async Transfer Queue", SettingType.BOOLEAN,
                "Use dedicated transfer queue for buffer uploads.", null, "true"),

        WORKER_THREADS(Category.PERFORMANCE, "Worker Threads", SettingType.SLIDER,
                "Number of chunk build worker threads. 0 = auto.",
                null, "0"),

        PARALLEL_RECORDING(Category.PERFORMANCE, "Parallel Recording", SettingType.BOOLEAN,
                "Record command buffers on multiple threads.", null, "true"),

        PIPELINE_CACHE(Category.PERFORMANCE, "Pipeline Cache", SettingType.BOOLEAN,
                "Cache compiled pipelines to disk for faster startup.", null, "true"),

        SPIRV_CACHE(Category.PERFORMANCE, "SPIR-V Cache", SettingType.BOOLEAN,
                "Cache compiled SPIR-V to disk.", null, "true"),

        // Debug
        VALIDATION_LAYERS(Category.DEBUG, "Validation Layers", SettingType.BOOLEAN,
                "Enable Vulkan validation layers. Severe performance impact.", null, "false"),

        PERFORMANCE_OVERLAY(Category.DEBUG, "Performance Overlay", SettingType.BOOLEAN,
                "Show real-time performance overlay.", null, "false"),

        SHADER_DUMP(Category.DEBUG, "Dump Shaders", SettingType.BOOLEAN,
                "Write transformed GLSL and SPIR-V to disk.", null, "false"),

        DEBUG_REPORT_ON_ERROR(Category.DEBUG, "Auto Debug Report", SettingType.BOOLEAN,
                "Automatically generate debug report on shader errors.", null, "true"),

        // Compatibility
        FORCE_FALLBACK_FORMAT(Category.COMPATIBILITY, "Force RGBA8 Targets", SettingType.BOOLEAN,
                "Force all render targets to RGBA8. May fix pack issues.", null, "false"),

        DISABLE_GEOMETRY_SHADERS(Category.COMPATIBILITY, "Disable Geometry Shaders", SettingType.BOOLEAN,
                "Skip geometry shader stages. Fixes some packs on older GPUs.", null, "false"),

        MAX_RENDER_TARGETS(Category.COMPATIBILITY, "Max Render Targets", SettingType.ENUM,
                "Limit the number of simultaneous render targets.",
                new String[]{"4", "8", "16"}, "16");

        private final Category category;
        private final String displayName;
        private final SettingType type;
        private final String description;
        private final String[] options;
        private final String defaultValue;

        Setting(Category category, String displayName, SettingType type,
                String description, String[] options, String defaultValue) {
            this.category = category;
            this.displayName = displayName;
            this.type = type;
            this.description = description;
            this.options = options;
            this.defaultValue = defaultValue;
        }

        public Category getCategory() { return category; }
        public String getDisplayName() { return displayName; }
        public SettingType getType() { return type; }
        public String getDescription() { return description; }
        public String[] getOptions() { return options; }
        public String getDefaultValue() { return defaultValue; }
    }

    public enum SettingType {
        BOOLEAN, ENUM, SLIDER
    }

    // ── State ──

    private final Map<Setting, String> currentValues = new EnumMap<>(Setting.class);
    private Consumer<Setting> onSettingChanged;

    public ConfigUI() {
        // Initialize with defaults
        for (Setting setting : Setting.values()) {
            currentValues.put(setting, setting.getDefaultValue());
        }
    }

    /**
     * Loads current values from the config file.
     */
    public void loadFrom(VulkaniumConfig config) {
        currentValues.put(Setting.GPU_CULLING, String.valueOf(config.useGPUCulling));
        currentValues.put(Setting.ASYNC_TRANSFER, String.valueOf(config.useAsyncTransfer));
        currentValues.put(Setting.VALIDATION_LAYERS, String.valueOf(config.enableValidation));
        currentValues.put(Setting.PIPELINE_CACHE, String.valueOf(config.usePipelineCache));
        currentValues.put(Setting.SPIRV_CACHE, String.valueOf(config.useSpirVCache));
        currentValues.put(Setting.PERFORMANCE_OVERLAY, String.valueOf(config.showPerformanceOverlay));
        currentValues.put(Setting.SHADER_DUMP, String.valueOf(config.dumpShaders));
        currentValues.put(Setting.WORKER_THREADS, String.valueOf(config.workerThreads));
        currentValues.put(Setting.SHADOW_RESOLUTION, String.valueOf(config.shadowResolution));
        currentValues.put(Setting.SHADOW_DISTANCE, String.valueOf(config.shadowDistance));
    }

    /**
     * Saves current values back to the config file.
     */
    public void saveTo(VulkaniumConfig config) {
        config.useGPUCulling = getBool(Setting.GPU_CULLING);
        config.useAsyncTransfer = getBool(Setting.ASYNC_TRANSFER);
        config.enableValidation = getBool(Setting.VALIDATION_LAYERS);
        config.usePipelineCache = getBool(Setting.PIPELINE_CACHE);
        config.useSpirVCache = getBool(Setting.SPIRV_CACHE);
        config.showPerformanceOverlay = getBool(Setting.PERFORMANCE_OVERLAY);
        config.dumpShaders = getBool(Setting.SHADER_DUMP);
        config.workerThreads = getInt(Setting.WORKER_THREADS);
        config.shadowResolution = getInt(Setting.SHADOW_RESOLUTION);
        config.shadowDistance = getInt(Setting.SHADOW_DISTANCE);
        config.save();
    }

    // ── Getters / Setters ──

    public String getValue(Setting setting) {
        return currentValues.getOrDefault(setting, setting.getDefaultValue());
    }

    public void setValue(Setting setting, String value) {
        currentValues.put(setting, value);
        LOGGER.debug("Setting changed: {} = {}", setting.getDisplayName(), value);
        if (onSettingChanged != null) {
            onSettingChanged.accept(setting);
        }
    }

    public boolean getBool(Setting setting) {
        return Boolean.parseBoolean(getValue(setting));
    }

    public int getInt(Setting setting) {
        try {
            return Integer.parseInt(getValue(setting));
        } catch (NumberFormatException e) {
            return Integer.parseInt(setting.getDefaultValue());
        }
    }

    /**
     * Returns all settings for a given category.
     */
    public Setting[] getSettingsForCategory(Category category) {
        return java.util.Arrays.stream(Setting.values())
                .filter(s -> s.getCategory() == category)
                .toArray(Setting[]::new);
    }

    /**
     * Sets a callback for setting changes (for live-updating UI elements).
     */
    public void setOnSettingChanged(Consumer<Setting> callback) {
        this.onSettingChanged = callback;
    }

    /**
     * Resets all settings to their defaults.
     */
    public void resetToDefaults() {
        for (Setting setting : Setting.values()) {
            currentValues.put(setting, setting.getDefaultValue());
        }
    }
}
