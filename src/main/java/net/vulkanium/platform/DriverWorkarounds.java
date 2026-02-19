package net.vulkanium.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * GPU driver workarounds for known bugs and quirks.
 *
 * <p>Different GPU vendors and driver versions have various bugs that
 * require specific workarounds. This class centralizes all driver-specific
 * behavior adjustments.</p>
 *
 * <p>Modeled after Sodium's compatibility/workaround system and VulkanMod's
 * device-specific handling.</p>
 */
public class DriverWorkarounds {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Workarounds");

    private static DriverWorkarounds instance;

    /** Active workarounds */
    private final List<Workaround> activeWorkarounds = new ArrayList<>();

    // ─── Workaround flags ──────────────────────────────────────────────

    /** NVIDIA: Older drivers have pipeline cache corruption issues */
    private boolean disablePipelineCache;

    /** AMD: Some drivers have issues with VK_KHR_dynamic_rendering */
    private boolean forceLegacyRenderPass;

    /** Intel: iGPU may not support 32-bit index buffers for large meshes */
    private boolean force16BitIndices;

    /** NVIDIA: Some driver versions have shader compilation stutter */
    private boolean precompileAllPipelines;

    /** AMD RADV: Suboptimal descriptor indexing on older Mesa versions */
    private boolean limitDescriptorCount;

    /** Intel: Missing VK_EXT_extended_dynamic_state on older drivers */
    private boolean disableExtendedDynamicState;

    /** All: MoltenVK (macOS) has various Vulkan limitations */
    private boolean moltenVKMode;

    /** AMD Windows: Some versions mishandle subgroup operations */
    private boolean disableSubgroupOps;

    /** NVIDIA: VK_EXT_mesh_shader crashes on certain driver versions */
    private boolean disableMeshShaders;

    /** General: Limit staging buffer size for low-VRAM GPUs */
    private boolean limitStagingBufferSize;
    private long maxStagingBufferMB = 256;

    private DriverWorkarounds() {}

    public static DriverWorkarounds getInstance() {
        if (instance == null) {
            instance = new DriverWorkarounds();
        }
        return instance;
    }

    /**
     * Detect and enable applicable workarounds based on platform info.
     */
    public void detect(PlatformInfo platform) {
        activeWorkarounds.clear();
        PlatformInfo.GPUVendor vendor = platform.getGPUVendor();
        String driver = platform.getDriverVersion();

        // NVIDIA-specific
        if (vendor == PlatformInfo.GPUVendor.NVIDIA) {
            detectNvidiaWorkarounds(driver, platform);
        }

        // AMD-specific
        if (vendor == PlatformInfo.GPUVendor.AMD) {
            detectAmdWorkarounds(driver, platform);
        }

        // Intel-specific
        if (vendor == PlatformInfo.GPUVendor.INTEL) {
            detectIntelWorkarounds(driver, platform);
        }

        // MoltenVK (macOS)
        if (platform.getOS() == PlatformInfo.OS.MACOS) {
            detectMoltenVKWorkarounds(platform);
        }

        // Low VRAM detection
        if (platform.getMaxMemory() > 0 && platform.getMaxMemory() < 2L * 1024 * 1024 * 1024) {
            limitStagingBufferSize = true;
            maxStagingBufferMB = 64;
            addWorkaround("LOW_VRAM", "Limiting staging buffer to 64MB for low-VRAM GPU");
        }

        LOGGER.info("Driver workarounds active: {}", activeWorkarounds.size());
        for (Workaround w : activeWorkarounds) {
            LOGGER.info("  - {}: {}", w.id, w.description);
        }
    }

    private void detectNvidiaWorkarounds(String driver, PlatformInfo platform) {
        // Pipeline cache corruption on pre-535 drivers
        // NVIDIA 535.x+ generally fine
        if (isDriverOlderThan(driver, "535.0.0.0")) {
            disablePipelineCache = true;
            addWorkaround("NV_PIPELINE_CACHE", "Disabling pipeline cache for NVIDIA < 535");
        }

        // Shader compilation stutter on pre-530 drivers
        if (isDriverOlderThan(driver, "530.0.0.0")) {
            precompileAllPipelines = true;
            addWorkaround("NV_PRECOMPILE", "Pre-compiling all pipelines for NVIDIA < 530");
        }
    }

    private void detectAmdWorkarounds(String driver, PlatformInfo platform) {
        if (platform.getOS() == PlatformInfo.OS.LINUX) {
            // RADV (Mesa) specific
            limitDescriptorCount = true;
            addWorkaround("AMD_RADV_DESC", "Limiting descriptor count for RADV");
        }

        if (platform.getOS() == PlatformInfo.OS.WINDOWS) {
            // AMD Windows driver subgroup issues
            disableSubgroupOps = true;
            addWorkaround("AMD_WIN_SUBGROUP", "Disabling subgroup ops on AMD Windows");
        }
    }

    private void detectIntelWorkarounds(String driver, PlatformInfo platform) {
        // Intel iGPU: limited VRAM, prefer 16-bit indices
        if (platform.getMaxMemory() > 0 && platform.getMaxMemory() < 4L * 1024 * 1024 * 1024) {
            force16BitIndices = true;
            addWorkaround("INTEL_IDX16", "Forcing 16-bit indices for Intel iGPU");
        }

        disableExtendedDynamicState = true;
        addWorkaround("INTEL_DYN_STATE", "Disabling extended dynamic state for Intel");
    }

    private void detectMoltenVKWorkarounds(PlatformInfo platform) {
        moltenVKMode = true;
        forceLegacyRenderPass = true;
        disableMeshShaders = true;
        addWorkaround("MVK_COMPAT", "MoltenVK compatibility mode enabled");
    }

    private boolean isDriverOlderThan(String current, String threshold) {
        // Simplified version comparison
        try {
            String[] curParts = current.split("\\.");
            String[] thrParts = threshold.split("\\.");
            for (int i = 0; i < Math.min(curParts.length, thrParts.length); i++) {
                int c = Integer.parseInt(curParts[i]);
                int t = Integer.parseInt(thrParts[i]);
                if (c < t) return true;
                if (c > t) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void addWorkaround(String id, String description) {
        activeWorkarounds.add(new Workaround(id, description));
    }

    // ─── Getters ───────────────────────────────────────────────────────

    public boolean shouldDisablePipelineCache() { return disablePipelineCache; }
    public boolean shouldForceLegacyRenderPass() { return forceLegacyRenderPass; }
    public boolean shouldForce16BitIndices() { return force16BitIndices; }
    public boolean shouldPrecompileAllPipelines() { return precompileAllPipelines; }
    public boolean shouldLimitDescriptorCount() { return limitDescriptorCount; }
    public boolean shouldDisableExtendedDynamicState() { return disableExtendedDynamicState; }
    public boolean isMoltenVKMode() { return moltenVKMode; }
    public boolean shouldDisableSubgroupOps() { return disableSubgroupOps; }
    public boolean shouldDisableMeshShaders() { return disableMeshShaders; }
    public boolean shouldLimitStagingBufferSize() { return limitStagingBufferSize; }
    public long getMaxStagingBufferMB() { return maxStagingBufferMB; }

    public List<Workaround> getActiveWorkarounds() { return activeWorkarounds; }

    public record Workaround(String id, String description) {}
}
