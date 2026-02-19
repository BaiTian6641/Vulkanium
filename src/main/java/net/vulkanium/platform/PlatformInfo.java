package net.vulkanium.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform detection and system information.
 *
 * <p>Detects the OS, GPU vendor, driver version, and Vulkan capabilities
 * to enable platform-specific optimizations and workarounds.</p>
 *
 * <p>Modeled after Sodium's platform detection layer.</p>
 */
public class PlatformInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Platform");

    private static PlatformInfo instance;

    // ─── OS ────────────────────────────────────────────────────────────

    public enum OS { WINDOWS, LINUX, MACOS, UNKNOWN }

    private final OS os;
    private final String osVersion;
    private final String osArch;

    // ─── GPU ───────────────────────────────────────────────────────────

    public enum GPUVendor { NVIDIA, AMD, INTEL, APPLE, QUALCOMM, ARM, MESA, UNKNOWN }

    private GPUVendor gpuVendor = GPUVendor.UNKNOWN;
    private String gpuName = "Unknown";
    private String driverVersion = "Unknown";
    private int vendorId;
    private int deviceId;
    private int apiVersion;

    // ─── Vulkan capabilities ───────────────────────────────────────────

    private boolean supportsVulkan12;
    private boolean supportsVulkan13;
    private boolean supportsRayTracing;
    private boolean supportsMeshShaders;
    private boolean supportsMultiDrawIndirect;
    private boolean supportsDescriptorIndexing;
    private long maxMemory;

    private PlatformInfo() {
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        if (osName.contains("win")) {
            this.os = OS.WINDOWS;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            this.os = OS.MACOS;
        } else if (osName.contains("linux") || osName.contains("nix")) {
            this.os = OS.LINUX;
        } else {
            this.os = OS.UNKNOWN;
        }

        this.osVersion = System.getProperty("os.version", "unknown");
        this.osArch = System.getProperty("os.arch", "unknown");
    }

    public static PlatformInfo getInstance() {
        if (instance == null) {
            instance = new PlatformInfo();
        }
        return instance;
    }

    /**
     * Initialize GPU info from Vulkan physical device properties.
     * Called during Vulkan initialization.
     */
    public void initFromVulkanDevice(int vendorId, int deviceId, String deviceName,
                                      int driverVer, int apiVer) {
        this.vendorId = vendorId;
        this.deviceId = deviceId;
        this.gpuName = deviceName;
        this.apiVersion = apiVer;

        // Decode vendor
        this.gpuVendor = switch (vendorId) {
            case 0x10DE -> GPUVendor.NVIDIA;
            case 0x1002 -> GPUVendor.AMD;
            case 0x8086 -> GPUVendor.INTEL;
            case 0x106B -> GPUVendor.APPLE;
            case 0x5143 -> GPUVendor.QUALCOMM;
            case 0x13B5 -> GPUVendor.ARM;
            default -> GPUVendor.UNKNOWN;
        };

        // Decode driver version (vendor-specific encoding)
        this.driverVersion = decodeDriverVersion(vendorId, driverVer);

        // Check Vulkan version support
        this.supportsVulkan12 = (apiVer >> 22) >= 1 && ((apiVer >> 12) & 0x3FF) >= 2;
        this.supportsVulkan13 = (apiVer >> 22) >= 1 && ((apiVer >> 12) & 0x3FF) >= 3;

        LOGGER.info("GPU: {} ({}) Driver: {} Vulkan: {}.{}.{}",
            gpuName, gpuVendor,
            driverVersion,
            apiVer >> 22, (apiVer >> 12) & 0x3FF, apiVer & 0xFFF);
    }

    private String decodeDriverVersion(int vendorId, int version) {
        if (vendorId == 0x10DE) {
            // NVIDIA: 10.8.8.6 encoding
            return String.format("%d.%d.%d.%d",
                (version >> 22) & 0x3FF,
                (version >> 14) & 0xFF,
                (version >> 6) & 0xFF,
                version & 0x3F);
        }
        // Standard: major.minor.patch
        return String.format("%d.%d.%d",
            (version >> 22), (version >> 12) & 0x3FF, version & 0xFFF);
    }

    // ─── Feature flags ─────────────────────────────────────────────────

    public void setFeatureSupport(boolean rayTracing, boolean meshShaders,
                                  boolean multiDrawIndirect, boolean descriptorIndexing,
                                  long maxDeviceMemory) {
        this.supportsRayTracing = rayTracing;
        this.supportsMeshShaders = meshShaders;
        this.supportsMultiDrawIndirect = multiDrawIndirect;
        this.supportsDescriptorIndexing = descriptorIndexing;
        this.maxMemory = maxDeviceMemory;
    }

    // ─── Getters ───────────────────────────────────────────────────────

    public OS getOS() { return os; }
    public String getOSVersion() { return osVersion; }
    public String getOSArch() { return osArch; }
    public GPUVendor getGPUVendor() { return gpuVendor; }
    public String getGPUName() { return gpuName; }
    public String getDriverVersion() { return driverVersion; }
    public int getVendorId() { return vendorId; }
    public int getDeviceId() { return deviceId; }
    public boolean supportsVulkan12() { return supportsVulkan12; }
    public boolean supportsVulkan13() { return supportsVulkan13; }
    public boolean supportsRayTracing() { return supportsRayTracing; }
    public boolean supportsMeshShaders() { return supportsMeshShaders; }
    public boolean supportsMultiDrawIndirect() { return supportsMultiDrawIndirect; }
    public boolean supportsDescriptorIndexing() { return supportsDescriptorIndexing; }
    public long getMaxMemory() { return maxMemory; }

    public boolean is64Bit() {
        return osArch.contains("64") || osArch.equals("aarch64");
    }

    /**
     * Get a human-readable summary for the debug overlay.
     */
    public String getSummary() {
        return String.format("%s | %s (%s) | Driver %s | VK %s",
            os, gpuName, gpuVendor, driverVersion,
            supportsVulkan13 ? "1.3" : (supportsVulkan12 ? "1.2" : "1.1"));
    }
}
