package net.vulkanium.render.hdr;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Central HDR configuration — negotiates display HDR capabilities with the GPU
 * and selects the best swapchain format + color space for HDR output.
 *
 * <h3>Supported HDR Modes</h3>
 * <ul>
 *   <li><b>SDR</b> — Standard 8-bit (VK_FORMAT_B8G8R8A8_UNORM + sRGB color space)</li>
 *   <li><b>HDR10</b> — 10-bit PQ (VK_FORMAT_A2B10G10R10_UNORM_PACK32 + ST2084/PQ).
 *       Used by most HDR displays (HDR10, HDR10+, Dolby Vision fallback).</li>
 *   <li><b>scRGB</b> — 16-bit float linear (VK_FORMAT_R16G16B16A16_SFLOAT + EXTENDED_SRGB_LINEAR).
 *       Highest precision, no gamut clipping. Used on Windows HDR and Linux Wayland HDR.</li>
 * </ul>
 *
 * <h3>Reference: Iris ColorSpace</h3>
 * <p>The color space conversion concept is modeled after
 * {@code net.irisshaders.iris.pathways.colorspace.ColorSpace} from the
 * <a href="https://github.com/IrisShaders/Iris">Iris Shaders</a> project (LGPL-3.0).
 * Iris supports SRGB, DCI_P3, DISPLAY_P3, REC2020, ADOBE_RGB color space
 * conversions via compute/fragment shader post-processing. This manager
 * implements the Vulkan-native path: HDR surface formats + color spaces
 * are negotiated at the swapchain level, and a color space converter
 * shader handles gamut mapping when needed.</p>
 */
public class HdrConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/HDR");

    // ═══════════════════════════════════════════════════════════════
    //  HDR Mode Enum
    // ═══════════════════════════════════════════════════════════════

    /** HDR output mode. */
    public enum HdrMode {
        /** Standard dynamic range — classic 8-bit sRGB. */
        SDR,
        /** HDR10 — 10-bit with PQ (Perceptual Quantizer) transfer function. */
        HDR10,
        /** scRGB — 16-bit float with extended linear sRGB gamut. Highest precision. */
        SCRGB,
        /** Auto — pick the best available HDR mode, or SDR if none supported. */
        AUTO
    }

    /**
     * Color space enumerator matching Iris's ColorSpace for shaderpack interop.
     *
     * <p>Reference: Iris ColorSpace enum (Iris Shaders, LGPL-3.0)</p>
     */
    public enum ColorSpaceTarget {
        SRGB(0),
        DCI_P3(1),
        DISPLAY_P3(2),
        REC2020(3),
        ADOBE_RGB(4);

        public final int index;
        ColorSpaceTarget(int index) { this.index = index; }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Known HDR Surface Format + Color Space Pairs
    // ═══════════════════════════════════════════════════════════════

    // VK_EXT_swapchain_colorspace color space constants
    // (For drivers/loaders that don't define them in the KHRSurface constants)

    /** VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008 */
    public static final int VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008;

    /** VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT = 1000104014 (scRGB) */
    public static final int VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT = 1000104014;

    /** VK_COLOR_SPACE_HDR10_HLG_EXT = 1000104010 (HLG / Hybrid Log-Gamma) */
    public static final int VK_COLOR_SPACE_HDR10_HLG_EXT = 1000104010;

    /** VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT = 1000104001 */
    public static final int VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT = 1000104001;

    /** VK_COLOR_SPACE_BT2020_LINEAR_EXT = 1000104007 */
    public static final int VK_COLOR_SPACE_BT2020_LINEAR_EXT = 1000104007;

    // ═══════════════════════════════════════════════════════════════
    //  Instance State
    // ═══════════════════════════════════════════════════════════════

    private static HdrMode activeMode = HdrMode.SDR;
    private static int resolvedFormat = VK_FORMAT_B8G8R8A8_UNORM;
    private static int resolvedColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    private static ColorSpaceTarget colorSpaceTarget = ColorSpaceTarget.SRGB;
    private static boolean hdrAvailable = false;
    private static boolean hdrEnabled = false;

    /** Internal render format for HDR pipeline (used for render targets, custom images). */
    private static int internalHdrFormat = VK_FORMAT_R16G16B16A16_SFLOAT;

    // ═══════════════════════════════════════════════════════════════
    //  Probing
    // ═══════════════════════════════════════════════════════════════

    /**
     * Probes the physical device and surface for HDR capability.
     *
     * <p>Checks all available surface formats for HDR-compatible format + color space
     * combinations. If any are found, marks HDR as available.</p>
     *
     * @param physicalDevice Vulkan physical device
     * @param surface        Vulkan surface
     * @return true if any HDR format is available
     */
    public static boolean probeHdrSupport(VkPhysicalDevice physicalDevice, long surface) {
        hdrAvailable = false;
        List<String> foundFormats = new ArrayList<>();

        try (MemoryStack stack = stackPush()) {
            IntBuffer formatCount = stack.ints(0);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null);
            if (formatCount.get(0) == 0) return false;

            VkSurfaceFormatKHR.Buffer formats =
                    VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, formats);

            for (int i = 0; i < formats.capacity(); i++) {
                VkSurfaceFormatKHR fmt = formats.get(i);
                int f = fmt.format();
                int cs = fmt.colorSpace();

                // scRGB: RGBA16F + Extended sRGB Linear
                if (f == VK_FORMAT_R16G16B16A16_SFLOAT
                        && cs == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
                    foundFormats.add("scRGB (RGBA16F + Extended_sRGB_Linear)");
                    hdrAvailable = true;
                }

                // HDR10: A2B10G10R10 + ST2084 (PQ)
                if (f == VK_FORMAT_A2B10G10R10_UNORM_PACK32
                        && cs == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
                    foundFormats.add("HDR10 (A2B10G10R10 + ST2084)");
                    hdrAvailable = true;
                }

                // HDR10: RGBA16F + ST2084 (PQ) — some drivers expose this
                if (f == VK_FORMAT_R16G16B16A16_SFLOAT
                        && cs == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
                    foundFormats.add("HDR10-16F (RGBA16F + ST2084)");
                    hdrAvailable = true;
                }

                // Display P3
                if (cs == VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT) {
                    foundFormats.add("Display P3 (format=" + f + ")");
                    hdrAvailable = true;
                }

                // BT2020 Linear
                if (cs == VK_COLOR_SPACE_BT2020_LINEAR_EXT) {
                    foundFormats.add("BT2020 Linear (format=" + f + ")");
                    hdrAvailable = true;
                }
            }
        }

        if (hdrAvailable) {
            LOGGER.info("[HDR] Display supports HDR — available formats: {}", foundFormats);
        } else {
            LOGGER.info("[HDR] Display does NOT support HDR — SDR only");
        }

        return hdrAvailable;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Format Selection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolves the best swapchain format + color space for the requested HDR mode.
     *
     * <p>Called during swapchain creation. When HDR is enabled, selects the best
     * HDR format available. Falls back to SDR if the requested mode isn't available.</p>
     *
     * @param physicalDevice Physical device
     * @param surface        Surface
     * @param requestedMode  Desired HDR mode (SDR, HDR10, SCRGB, or AUTO)
     * @param forceEnable    If true, enable HDR even on AUTO if available
     */
    public static void resolveSwapchainFormat(VkPhysicalDevice physicalDevice, long surface,
                                               HdrMode requestedMode, boolean forceEnable) {
        // Default to SDR
        resolvedFormat = VK_FORMAT_B8G8R8A8_UNORM;
        resolvedColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        colorSpaceTarget = ColorSpaceTarget.SRGB;
        activeMode = HdrMode.SDR;
        hdrEnabled = false;

        if (requestedMode == HdrMode.SDR) {
            LOGGER.info("[HDR] Mode=SDR — using standard B8G8R8A8_UNORM + sRGB");
            return;
        }

        if (!hdrAvailable && !forceEnable) {
            LOGGER.info("[HDR] Mode={} requested but HDR not available — falling back to SDR",
                    requestedMode);
            return;
        }

        try (MemoryStack stack = stackPush()) {
            IntBuffer formatCount = stack.ints(0);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null);
            VkSurfaceFormatKHR.Buffer formats =
                    VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, formats);

            // Ranked preference by mode
            boolean resolved = false;

            if (requestedMode == HdrMode.SCRGB || requestedMode == HdrMode.AUTO) {
                // scRGB: highest precision, no gamut clipping
                for (int i = 0; i < formats.capacity(); i++) {
                    VkSurfaceFormatKHR fmt = formats.get(i);
                    if (fmt.format() == VK_FORMAT_R16G16B16A16_SFLOAT
                            && fmt.colorSpace() == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
                        resolvedFormat = fmt.format();
                        resolvedColorSpace = fmt.colorSpace();
                        activeMode = HdrMode.SCRGB;
                        colorSpaceTarget = ColorSpaceTarget.SRGB; // Linear sRGB gamut
                        hdrEnabled = true;
                        resolved = true;
                        LOGGER.info("[HDR] Selected scRGB (RGBA16F + Extended_sRGB_Linear)");
                        break;
                    }
                }
            }

            if (!resolved && (requestedMode == HdrMode.HDR10 || requestedMode == HdrMode.AUTO)) {
                // HDR10: 10-bit PQ, most widely supported
                // Prefer RGBA16F variant, fall back to A2B10G10R10
                for (int i = 0; i < formats.capacity(); i++) {
                    VkSurfaceFormatKHR fmt = formats.get(i);
                    if (fmt.format() == VK_FORMAT_R16G16B16A16_SFLOAT
                            && fmt.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
                        resolvedFormat = fmt.format();
                        resolvedColorSpace = fmt.colorSpace();
                        activeMode = HdrMode.HDR10;
                        colorSpaceTarget = ColorSpaceTarget.REC2020;
                        hdrEnabled = true;
                        resolved = true;
                        LOGGER.info("[HDR] Selected HDR10 (RGBA16F + ST2084/PQ)");
                        break;
                    }
                }

                if (!resolved) {
                    for (int i = 0; i < formats.capacity(); i++) {
                        VkSurfaceFormatKHR fmt = formats.get(i);
                        if (fmt.format() == VK_FORMAT_A2B10G10R10_UNORM_PACK32
                                && fmt.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
                            resolvedFormat = fmt.format();
                            resolvedColorSpace = fmt.colorSpace();
                            activeMode = HdrMode.HDR10;
                            colorSpaceTarget = ColorSpaceTarget.REC2020;
                            hdrEnabled = true;
                            resolved = true;
                            LOGGER.info("[HDR] Selected HDR10 (A2B10G10R10 + ST2084/PQ)");
                            break;
                        }
                    }
                }
            }

            if (!resolved) {
                LOGGER.info("[HDR] No suitable HDR format found — falling back to SDR");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal Render Format
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns the recommended internal render format for HDR-capable render targets.
     *
     * <p>When HDR is enabled, render targets should use a high-precision format
     * (RGBA16F or RGBA32F) to preserve dynamic range through the shaderpack pass chain.
     * This is used as the default format for FullscreenRenderTargets when the shaderpack
     * doesn't explicitly declare per-target formats.</p>
     *
     * <p>Note: Shaderpacks that declare explicit {@code colortexNFormat} directives
     * override this default. The internal format only affects targets without explicit
     * format declarations.</p>
     *
     * @return VkFormat for internal render targets (RGBA16F when HDR, swapchain format when SDR)
     */
    public static int getInternalRenderFormat() {
        if (hdrEnabled) {
            return internalHdrFormat;
        }
        return resolvedFormat;
    }

    /**
     * Returns the recommended format for custom images created by the shaderpack.
     *
     * <p>When HDR is active and the image doesn't specify an explicit format,
     * defaults to RGBA16F instead of RGBA8 to preserve HDR data.</p>
     */
    public static int getDefaultCustomImageFormat() {
        return hdrEnabled ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM;
    }

    // ═══════════════════════════════════════════════════════════════
    //  HDR Metadata (for HDR10 displays)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Populates HDR metadata values typically needed for HDR10 tone mapping.
     * These values inform the display about the content's luminance range.
     *
     * @return float array: [maxContentLuminance, maxFrameAvgLuminance,
     *         minLuminance, whitePointX, whitePointY]
     */
    public static float[] getHdrMetadata() {
        return new float[]{
                1000.0f,  // maxContentLuminance (nits) — shader packs typically assume ~1000
                200.0f,   // maxFrameAvgLuminance (nits)
                0.001f,   // minLuminance (nits)
                0.3127f,  // D65 white point X
                0.3290f   // D65 white point Y
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  Accessors
    // ═══════════════════════════════════════════════════════════════

    /** The active HDR mode after resolution. */
    public static HdrMode getActiveMode() { return activeMode; }

    /** VkFormat for the swapchain (possibly HDR). */
    public static int getResolvedFormat() { return resolvedFormat; }

    /** VkColorSpaceKHR for the swapchain (possibly HDR). */
    public static int getResolvedColorSpace() { return resolvedColorSpace; }

    /** Whether the display supports any HDR mode. */
    public static boolean isHdrAvailable() { return hdrAvailable; }

    /** Whether HDR output is currently active. */
    public static boolean isHdrEnabled() { return hdrEnabled; }

    /** The color space target for shader uniform reporting. */
    public static ColorSpaceTarget getColorSpaceTarget() { return colorSpaceTarget; }

    /**
     * Returns whether the current HDR mode uses a floating-point swapchain format.
     * This is relevant for blend state and clear color precision.
     */
    public static boolean isFloatingPointSwapchain() {
        return resolvedFormat == VK_FORMAT_R16G16B16A16_SFLOAT
                || resolvedFormat == VK_FORMAT_R32G32B32A32_SFLOAT;
    }

    /**
     * Returns whether the resolved format has more than 8 bits per channel.
     */
    public static boolean isHighBitDepth() {
        return resolvedFormat != VK_FORMAT_B8G8R8A8_UNORM
                && resolvedFormat != VK_FORMAT_R8G8B8A8_UNORM
                && resolvedFormat != VK_FORMAT_B8G8R8A8_SRGB
                && resolvedFormat != VK_FORMAT_R8G8B8A8_SRGB;
    }

    /**
     * Returns the Vulkan color space name for logging.
     */
    public static String colorSpaceName(int colorSpace) {
        return switch (colorSpace) {
            case VK_COLOR_SPACE_SRGB_NONLINEAR_KHR -> "SRGB_NONLINEAR";
            case VK_COLOR_SPACE_HDR10_ST2084_EXT -> "HDR10_ST2084 (PQ)";
            case VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT -> "EXTENDED_SRGB_LINEAR (scRGB)";
            case VK_COLOR_SPACE_HDR10_HLG_EXT -> "HDR10_HLG";
            case VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT -> "DISPLAY_P3";
            case VK_COLOR_SPACE_BT2020_LINEAR_EXT -> "BT2020_LINEAR";
            default -> "UNKNOWN(" + colorSpace + ")";
        };
    }

    /**
     * Returns the Vulkan format name for logging.
     */
    public static String formatName(int format) {
        return switch (format) {
            case VK_FORMAT_B8G8R8A8_UNORM -> "B8G8R8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_SRGB -> "B8G8R8A8_SRGB";
            case VK_FORMAT_R8G8B8A8_UNORM -> "R8G8B8A8_UNORM";
            case VK_FORMAT_R16G16B16A16_SFLOAT -> "R16G16B16A16_SFLOAT";
            case VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> "A2B10G10R10_UNORM_PACK32";
            case VK_FORMAT_R32G32B32A32_SFLOAT -> "R32G32B32A32_SFLOAT";
            default -> "VkFormat(" + format + ")";
        };
    }

    /**
     * Resets HDR state (called on display change or option toggle).
     */
    public static void reset() {
        activeMode = HdrMode.SDR;
        resolvedFormat = VK_FORMAT_B8G8R8A8_UNORM;
        resolvedColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        colorSpaceTarget = ColorSpaceTarget.SRGB;
        hdrEnabled = false;
    }
}
