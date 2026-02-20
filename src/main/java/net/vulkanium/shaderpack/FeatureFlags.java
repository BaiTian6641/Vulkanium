package net.vulkanium.shaderpack;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Shader pack feature flags that indicate hardware/pipeline capabilities.
 *
 * <p>Packs declare required/optional features via {@code iris.features.required}
 * and {@code iris.features.optional} in {@code shaders.properties}. Vulkanium
 * validates these at load time and warns/disables packs that need unsupported features.</p>
 *
 * <h3>Reference: Iris FeatureFlags</h3>
 * <p>This enum is modeled after
 * {@code net.irisshaders.iris.features.FeatureFlags} from the
 * <a href="https://github.com/IrisShaders/Iris">Iris Shaders</a> project (LGPL-3.0).
 * The Iris implementation checks both software readiness ({@code irisRequirement})
 * and hardware capability ({@code hardwareRequirement}) per flag. Vulkanium mirrors
 * the same flag names and semantics for shaderpack compatibility.</p>
 */
public enum FeatureFlags {

    // ── Flags Vulkanium supports ──

    /** Separate hardware texture samplers (always supported in Vulkan). */
    SEPARATE_HARDWARE_SAMPLERS(true),

    /** More than 2 shadow color textures (shadowcolor2..N). */
    HIGHER_SHADOWCOLOR(true),

    /** Per-buffer blending — different blend modes per MRT attachment. */
    PER_BUFFER_BLENDING(true),

    /** Translucent entity rendering separated from opaque entities. */
    ENTITY_TRANSLUCENT(true),

    /** Reversed front-face culling (used for shadow passes). */
    REVERSED_CULLING(true),

    /** Block emission attribute in vertex data. */
    BLOCK_EMISSION_ATTRIBUTE(true),

    /** Extended shadow map features (shadow distance, resolution overrides). */
    EXTENDED_SHADOW(true),

    // ── Flags Vulkanium does NOT yet support ──

    /**
     * Custom image textures (image load/store) for compute shaders.
     * <p>Iris reference: {@code CUSTOM_IMAGES} — requires {@code GL_ARB_shader_image_load_store}.
     * Vulkanium: Implemented via VkImage (VK_IMAGE_USAGE_STORAGE_BIT) + storage image
     * descriptors in compute pipeline. Managed by {@link net.vulkanium.shaderpack.compute.ShaderpackImageManager}.</p>
     */
    CUSTOM_IMAGES(true),

    /**
     * Compute shader dispatch support.
     * <p>Iris reference: {@code COMPUTE_SHADERS} — requires {@code glDispatchCompute}.
     * Vulkanium: Implemented via {@code vkCmdDispatch} + compute pipeline layout.
     * Managed by {@link net.vulkanium.shaderpack.compute.ShaderpackComputeManager}.</p>
     */
    COMPUTE_SHADERS(true),

    /**
     * Tessellation shader stages (hull + domain).
     * <p>Iris reference: {@code TESSELLATION_SHADERS} — requires {@code GL_ARB_tessellation_shader}.
     * Vulkanium equivalent: requires VkPipeline tessellation state. NOT YET IMPLEMENTED.</p>
     */
    TESSELLATION_SHADERS(false),

    /**
     * Shader Storage Buffer Objects.
     * <p>Iris reference: {@code SSBO} — requires {@code GL_ARB_shader_storage_buffer_object}.
     * Vulkanium: Implemented via VMA-backed VkBuffer (VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
     * + storage buffer descriptors. Managed by {@link net.vulkanium.shaderpack.compute.ShaderpackSSBOManager}.</p>
     */
    SSBO(true),

    /** Placeholder for unrecognized feature names. */
    UNKNOWN(false);

    private final boolean supported;

    FeatureFlags(boolean supported) {
        this.supported = supported;
    }

    /** Whether Vulkanium currently supports this feature. */
    public boolean isSupported() {
        return supported;
    }

    /**
     * Looks up a feature flag by name (case-insensitive).
     * Returns {@link #UNKNOWN} for unrecognized names.
     *
     * <p>Handles Iris's legacy typo: {@code TESSELATION_SHADERS} →
     * {@code TESSELLATION_SHADERS}.</p>
     *
     * @param name Feature name from {@code iris.features.required/optional}
     * @return The matching flag, or {@link #UNKNOWN}
     */
    public static FeatureFlags fromName(String name) {
        if (name == null) return UNKNOWN;

        // Iris compatibility: fix legacy typo
        // Reference: Iris FeatureFlags.getValue() handles this same case
        if (name.equalsIgnoreCase("TESSELATION_SHADERS")) {
            return TESSELLATION_SHADERS;
        }

        try {
            return valueOf(name.toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /**
     * Checks if a named feature is invalid (unsupported or unknown).
     *
     * @param name Feature name from shaders.properties
     * @return true if the feature is not usable
     */
    public static boolean isInvalid(String name) {
        FeatureFlags flag = fromName(name);
        return flag == UNKNOWN || !flag.supported;
    }

    /**
     * Validates a set of required feature names and returns those that are unsupported.
     *
     * @param requiredNames Feature names from {@code iris.features.required}
     * @return List of unsupported feature names (empty if all supported)
     */
    public static List<String> getUnsupportedFeatures(Collection<String> requiredNames) {
        List<String> unsupported = new ArrayList<>();
        for (String name : requiredNames) {
            FeatureFlags flag = fromName(name);
            if (flag == UNKNOWN || !flag.supported) {
                unsupported.add(name);
            }
        }
        return unsupported;
    }

    /**
     * Returns a human-readable description of unsupported features for UI display.
     */
    public static String getUnsupportedMessage(Collection<String> requiredNames) {
        List<String> unsupported = getUnsupportedFeatures(requiredNames);
        if (unsupported.isEmpty()) return null;
        return "Unsupported features: " + String.join(", ", unsupported);
    }
}
