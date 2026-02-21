package net.vulkanium.gui;

import net.vulkanium.Vulkanium;
import net.vulkanium.VulkaniumConfig;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.gui.options.*;
import net.vulkanium.gui.options.storage.OptionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * The Vulkanium settings screen.
 *
 * <p>Modeled after Sodium's {@code SodiumOptionsGUI} / VulkanMod's {@code VOptionScreen}.
 * This class manages the full lifecycle of the settings GUI:</p>
 * <ol>
 *   <li>Builds option pages from {@link VulkaniumOptionPages}</li>
 *   <li>Renders tabbed pages with option rows</li>
 *   <li>Tracks pending changes</li>
 *   <li>Applies changes with appropriate reload flags</li>
 * </ol>
 *
 * <p>In the Minecraft integration, this extends {@code Screen} and overrides
 * {@code init()}, {@code render()}, {@code mouseClicked()}, etc. Here we
 * define the controller logic that will back the MC Screen subclass.</p>
 */
public class VulkaniumOptionsScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/OptionsGUI");

    private final VulkaniumGameOptions options;
    private final List<OptionPage> pages;
    private OptionPage currentPage;

    private boolean hasPendingChanges;

    public VulkaniumOptionsScreen(VulkaniumGameOptions options) {
        this.options = options;

        VulkaniumOptionPages pageBuilder = new VulkaniumOptionPages(options);
        this.pages = pageBuilder.getAllPages();

        if (!this.pages.isEmpty()) {
            this.currentPage = this.pages.get(0);
        }
    }

    // ─── Page Navigation ───────────────────────────────────────────────

    public List<OptionPage> getPages() { return this.pages; }
    public OptionPage getCurrentPage() { return this.currentPage; }

    public int getCurrentPageIndex() {
        return Math.max(0, pages.indexOf(currentPage));
    }

    public void setPage(OptionPage page) {
        this.currentPage = page;
    }

    public void setPageByIndex(int index) {
        if (index >= 0 && index < pages.size()) {
            this.currentPage = pages.get(index);
        }
    }

    // ─── Change Tracking ───────────────────────────────────────────────

    /** Check all options for unsaved changes */
    public boolean hasPendingChanges() {
        for (OptionPage page : pages) {
            for (Option<?> option : page.getOptions()) {
                if (option.hasChanged()) return true;
            }
        }
        return false;
    }

    /** Undo all pending changes (revert to stored values) */
    public void undoChanges() {
        for (OptionPage page : pages) {
            for (Option<?> option : page.getOptions()) {
                option.reset();
            }
        }
        this.hasPendingChanges = false;
    }

    /**
     * Apply all pending changes.
     * Returns the set of flags needed to properly reload affected systems.
     */
    public EnumSet<OptionFlag> applyChanges() {
        EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        Set<OptionStorage<?>> storages = new HashSet<>();

        for (OptionPage page : pages) {
            for (Option<?> option : page.getOptions()) {
                if (option.hasChanged()) {
                    option.applyChanges();
                    flags.addAll(option.getFlags());
                }
            }
        }

        // Save all affected storages
        // In the real implementation, collect unique storages from OptionImpl instances
        // and call save() on each
        saveOptions();

        // Process reload flags
        processReloadFlags(flags);

        this.hasPendingChanges = false;
        return flags;
    }

    // ─── Reload Processing ─────────────────────────────────────────────

    private void processReloadFlags(EnumSet<OptionFlag> flags) {
        // Save vanilla MC options too (render distance, brightness, GUI scale, etc.)
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.options != null) {
                mc.options.save();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save vanilla MC options: {}", e.getMessage());
        }

        if (flags.contains(OptionFlag.REQUIRES_GAME_RESTART)) {
            LOGGER.warn("Option change requires game restart");
            // Show restart prompt to user
        }

        if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD)) {
            LOGGER.info("Triggering full renderer reload");
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.levelRenderer != null) {
                    mc.levelRenderer.allChanged();
                }
            } catch (Exception e) {
                LOGGER.error("Failed to trigger renderer reload: {}", e.getMessage());
            }
        } else if (flags.contains(OptionFlag.REQUIRES_RENDERER_UPDATE)) {
            LOGGER.info("Triggering renderer update");
            // FrameOrchestrator.scheduleUpdate()
        }

        if (flags.contains(OptionFlag.REQUIRES_SHADER_RELOAD)) {
            LOGGER.info("Triggering shader pack reload");
            try {
                net.vulkanium.shaderpack.ShaderpackManager manager = Vulkanium.getShaderpackManager();
                if (manager != null) {
                    String activePack = manager.getActivePackName();
                    if (activePack != null && !activePack.isEmpty()) {
                        LOGGER.info("Reloading active shaderpack: {}", activePack);
                        manager.loadPack(activePack);
                    }
                }
                // Also trigger renderer reload so the world re-renders with/without shaderpack
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.levelRenderer != null) {
                    mc.levelRenderer.allChanged();
                }
            } catch (Exception e) {
                LOGGER.error("Failed to reload shaderpack: {}", e.getMessage());
            }
        }

        if (flags.contains(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)) {
            LOGGER.info("Scheduling swapchain recreation");
            // VulkaniumSwapchain.scheduleRecreate()
        }

        if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD)) {
            LOGGER.info("Triggering asset reload");
            // MinecraftClient.getInstance().reloadResources()
        }
    }

    private void saveOptions() {
        try {
            VulkaniumGameOptions.writeToDisk(this.options);
            LOGGER.info("Saved Vulkanium options to disk");

            // Sync GUI options back to the live VulkaniumConfig so runtime reads see the new values
            VulkaniumConfig config = Vulkanium.getConfig();
            if (config != null) {
                config.framesInFlight = this.options.video.framesInFlight;
                config.presentMode = this.options.video.presentMode.ordinal();
                config.deviceIndex = this.options.video.deviceIndex;

                config.shadowResolutionOverride = this.options.quality.shadowResolution;
                config.shadowResolution = this.options.quality.shadowResolution;
                config.maxShadowDistance = this.options.quality.maxShadowDistance;

                config.multiThreadedCommands = this.options.vulkan.multiThreadedRecording;
                config.asyncTransfers = this.options.vulkan.asyncTransfers;
                config.useAsyncTransfer = this.options.vulkan.asyncTransfers;
                config.persistPipelineCache = this.options.vulkan.persistPipelineCache;
                config.usePipelineCache = this.options.vulkan.persistPipelineCache;
                config.persistShaderCache = this.options.vulkan.persistShaderCache;
                config.useSpirVCache = this.options.vulkan.persistShaderCache;
                config.gpuFrustumCulling = this.options.performance.gpuFrustumCulling;
                config.useGPUCulling = this.options.performance.gpuFrustumCulling;
                config.gpuTranslucentSort = this.options.performance.gpuTranslucentSort;
                config.workerThreads = this.options.performance.chunkBuilderThreads;

                config.rayTracingEnabled = this.options.rayTracing.enabled;
                config.rayTracingQualityTier = this.options.rayTracing.qualityTier;
                config.ssaoEnabled = this.options.rayTracing.ssaoEnabled;
                config.ssaoSamples = this.options.rayTracing.ssaoSamples;
                config.upscalerEnabled = this.options.rayTracing.enableUpscaler;
                config.upscalerType = this.options.rayTracing.upscalerType;
                config.upscalerQuality = this.options.rayTracing.upscalerQuality;

                config.enableValidationLayers = this.options.debug.enableValidationLayers;
                config.enableValidation = this.options.debug.enableValidationLayers;
                config.enableApiTrace = this.options.debug.enableApiTrace;
                config.showPerformanceOverlay = this.options.debug.showPerformanceOverlay;
                config.dumpShaders = this.options.shader.dumpShadersOnError;
                config.debugLogging = this.options.debug.debugLogging;

                // Apply color space setting to HdrConfig
                net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget csTarget =
                    switch (this.options.video.colorSpace) {
                        case SRGB -> net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget.SRGB;
                        case DCI_P3 -> net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget.DCI_P3;
                        case DISPLAY_P3 -> net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget.DISPLAY_P3;
                        case REC2020 -> net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget.REC2020;
                        case ADOBE_RGB -> net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget.ADOBE_RGB;
                    };
                net.vulkanium.render.hdr.HdrConfig.setColorSpaceTarget(csTarget);

                config.save();
                LOGGER.info("Synced options to live VulkaniumConfig");

                // Apply SSAO/RT changes to the live RT renderer immediately
                net.vulkanium.rt.RayTracingRenderer rtRenderer = Vulkanium.getRTRenderer();
                if (rtRenderer != null) {
                    rtRenderer.setEnabled(config.rayTracingEnabled);
                    rtRenderer.setSSAOEnabled(config.ssaoEnabled);
                    rtRenderer.setAOSamples(config.ssaoSamples);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save options: {}", e.getMessage());
        }
    }

    // ─── GPU Info (for display in settings screen) ─────────────────────

    /**
     * Returns GPU information strings for display in the settings UI.
     * In real implementation this queries VulkaniumDevice.
     */
    public List<String> getGPUInfo() {
        return List.of(
                "Vulkan API: 1.2+",
                "GPU: (probed at runtime)",
                "VRAM: (probed at runtime)",
                "Driver: (probed at runtime)"
        );
    }
}
