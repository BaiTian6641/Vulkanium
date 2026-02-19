package net.vulkanium.render;

/**
 * Defines the three rendering modes supported by Vulkanium.
 *
 * <ul>
 *   <li>{@link #VANILLA} — Standard rasterization pipeline (Vulkan replacement
 *       for vanilla MC's GL rendering). No ray tracing or custom shaders.</li>
 *   <li>{@link #VANILLA_RT} — Vanilla rasterization + real-time ray tracing
 *       enhancements (SSAO, RT shadows, reflections via RTModuleManager).</li>
 *   <li>{@link #SHADERPACK} — External shaderpack pipeline. Routes rendering
 *       through a shaderpack interface compatible with Iris/OptiFine shader
 *       conventions (gbuffers, composite, deferred, final passes).</li>
 * </ul>
 */
public enum RenderMode {
    /**
     * Vanilla rasterization only — no RT extensions, no shaderpack.
     * Equivalent to vanilla MC rendering but on Vulkan.
     */
    VANILLA("Vanilla", "Standard Vulkan rasterization"),

    /**
     * Vanilla + ray tracing. Uses RTModuleManager for hardware RT
     * (SSAO compute, RT shadows, reflections, GI) layered on top of
     * the vanilla raster pass.
     */
    VANILLA_RT("Vanilla + RT", "Rasterization with ray tracing enhancements"),

    /**
     * Shaderpack mode. Renders through a {@link net.vulkanium.shaderpack.ShaderpackPipeline}
     * that implements the conventional shader stages (gbuffers_terrain,
     * gbuffers_entities, composite, deferred, final) similar to Iris/OptiFine.
     */
    SHADERPACK("Shaderpack", "External shaderpack rendering pipeline");

    private final String displayName;
    private final String description;

    RenderMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /** Human-readable name for GUI display. */
    public String getDisplayName() { return displayName; }

    /** Short description for tooltips. */
    public String getDescription() { return description; }

    /** Whether this mode uses ray tracing extensions. */
    public boolean usesRayTracing() {
        return this == VANILLA_RT;
    }

    /** Whether this mode uses an external shaderpack pipeline. */
    public boolean usesShaderpack() {
        return this == SHADERPACK;
    }

    /** Cycle to the next render mode. */
    public RenderMode next() {
        RenderMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Get a RenderMode by ordinal, defaulting to VANILLA if out of range. */
    public static RenderMode fromOrdinal(int ordinal) {
        RenderMode[] values = values();
        if (ordinal >= 0 && ordinal < values.length) return values[ordinal];
        return VANILLA;
    }
}
