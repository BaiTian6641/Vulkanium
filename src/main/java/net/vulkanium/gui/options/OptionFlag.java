package net.vulkanium.gui.options;

/**
 * Flags that indicate what kind of reload is required when an option changes.
 * The GUI collects all flags from changed options and applies the most
 * expensive reload necessary.
 */
public enum OptionFlag {
    /** Requires full renderer rebuild (destroy + recreate pipelines, framebuffers) */
    REQUIRES_RENDERER_RELOAD,

    /** Requires renderer state update (re-sort chunks, recull, etc.) but not full rebuild */
    REQUIRES_RENDERER_UPDATE,

    /** Requires reloading textures and assets */
    REQUIRES_ASSET_RELOAD,

    /** Requires full game restart (e.g. changing Vulkan device) */
    REQUIRES_GAME_RESTART,

    /** Requires shader pack recompilation */
    REQUIRES_SHADER_RELOAD,

    /** Requires swapchain recreation (present mode, HDR toggle) */
    REQUIRES_SWAPCHAIN_RECREATE
}
