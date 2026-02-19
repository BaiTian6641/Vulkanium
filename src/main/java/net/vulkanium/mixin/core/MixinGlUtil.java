package net.vulkanium.mixin.core;

import com.mojang.blaze3d.platform.GlUtil;
import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces MC's F3 GPU info (normally from OpenGL) with Vulkan device information.
 *
 * <p>MC's {@code GlUtil} provides static methods that the F3 debug screen queries
 * for vendor/renderer/version strings. Without this mixin, F3 would show stale
 * GL info (or crash, since there's no GL context).</p>
 */
@Mixin(GlUtil.class)
public class MixinGlUtil {

    /** @author Vulkanium @reason Show Vulkan GPU vendor on F3 screen */
    @Overwrite
    public static String getVendor() {
        if (!Vulkanium.isVulkanReady()) return "Vulkanium (initializing)";
        VulkaniumDevice.DeviceInfo info = Vulkanium.getVulkanDevice().getDeviceInfo();
        return vendorName(info.vendorId()) + " (0x" + Integer.toHexString(info.vendorId()) + ")";
    }

    /** @author Vulkanium @reason Show Vulkan GPU name on F3 screen */
    @Overwrite
    public static String getRenderer() {
        if (!Vulkanium.isVulkanReady()) return "Vulkanium";
        VulkaniumDevice.DeviceInfo info = Vulkanium.getVulkanDevice().getDeviceInfo();
        return info.name() + " / Vulkanium " + Vulkanium.getVersion();
    }

    /** @author Vulkanium @reason Show Vulkan API + driver version on F3 screen */
    @Overwrite
    public static String getOpenGLVersion() {
        if (!Vulkanium.isVulkanReady()) return "Vulkan (initializing)";
        VulkaniumDevice.DeviceInfo info = Vulkanium.getVulkanDevice().getDeviceInfo();
        return "Vulkan " + info.apiVersionString()
                + " / Driver " + info.driverVersionString()
                + " / " + info.deviceTypeName();
    }

    /** @author Vulkanium @reason Show CPU info on F3 screen */
    @Overwrite
    public static String getCpuInfo() {
        // Query CPU info from system properties (same approach as VulkanMod)
        String arch = System.getProperty("os.arch", "unknown");
        String os = System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "");
        int cores = Runtime.getRuntime().availableProcessors();
        return cores + "x " + arch + " (" + os.trim() + ")";
    }

    /**
     * Maps PCI vendor IDs to human-readable vendor names.
     */
    private static String vendorName(int vendorId) {
        return switch (vendorId) {
            case 0x1002 -> "AMD";
            case 0x1010 -> "ImgTec";
            case 0x10DE -> "NVIDIA";
            case 0x13B5 -> "ARM";
            case 0x5143 -> "Qualcomm";
            case 0x8086 -> "Intel";
            case 0x1D17 -> "Samsung";
            default -> "Vendor " + vendorId;
        };
    }
}
