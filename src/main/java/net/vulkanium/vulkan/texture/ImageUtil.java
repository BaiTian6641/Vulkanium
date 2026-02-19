package net.vulkanium.vulkan.texture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Utility class for image format conversion, pixel manipulation,
 * and upload helpers.
 *
 * <p>Handles conversions needed when moving data between OpenGL
 * (BGRA, packed pixel formats) and Vulkan (strict format matching).</p>
 */
public final class ImageUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ImageUtil");

    private ImageUtil() {}

    // ─── Format properties ─────────────────────────────────────────────

    /**
     * Get bytes per pixel for a VkFormat.
     */
    public static int bytesPerPixel(int vkFormat) {
        return switch (vkFormat) {
            case VulkanImage.FORMAT_R8_UNORM -> 1;
            case VulkanImage.FORMAT_R8G8_UNORM -> 2;
            case VulkanImage.FORMAT_R8G8B8A8_UNORM,
                 VulkanImage.FORMAT_R8G8B8A8_SRGB,
                 VulkanImage.FORMAT_B8G8R8A8_SRGB -> 4;
            case VulkanImage.FORMAT_R16_SFLOAT -> 2;
            case VulkanImage.FORMAT_R16G16_SFLOAT -> 4;
            case VulkanImage.FORMAT_R16G16B16A16_SFLOAT -> 8;
            case VulkanImage.FORMAT_R32_SFLOAT -> 4;
            case VulkanImage.FORMAT_R32G32B32A32_SFLOAT -> 16;
            case VulkanImage.FORMAT_D32_SFLOAT -> 4;
            case VulkanImage.FORMAT_D24_UNORM_S8_UINT -> 4;
            case VulkanImage.FORMAT_D32_SFLOAT_S8_UINT -> 5;
            default -> 4;
        };
    }

    /**
     * Check if a format is a depth format.
     */
    public static boolean isDepthFormat(int vkFormat) {
        return vkFormat == VulkanImage.FORMAT_D32_SFLOAT
            || vkFormat == VulkanImage.FORMAT_D24_UNORM_S8_UINT
            || vkFormat == VulkanImage.FORMAT_D32_SFLOAT_S8_UINT;
    }

    /**
     * Check if a format has a stencil component.
     */
    public static boolean hasStencil(int vkFormat) {
        return vkFormat == VulkanImage.FORMAT_D24_UNORM_S8_UINT
            || vkFormat == VulkanImage.FORMAT_D32_SFLOAT_S8_UINT;
    }

    /**
     * Check if a format is sRGB.
     */
    public static boolean isSRGB(int vkFormat) {
        return vkFormat == VulkanImage.FORMAT_R8G8B8A8_SRGB
            || vkFormat == VulkanImage.FORMAT_B8G8R8A8_SRGB;
    }

    /**
     * Get the VkImageAspectFlags for a format.
     */
    public static int getAspectMask(int vkFormat) {
        if (hasStencil(vkFormat)) {
            return 0x03; // VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
        }
        if (isDepthFormat(vkFormat)) {
            return 0x02; // VK_IMAGE_ASPECT_DEPTH_BIT
        }
        return 0x01; // VK_IMAGE_ASPECT_COLOR_BIT
    }

    // ─── Pixel conversion ──────────────────────────────────────────────

    /**
     * Convert RGBA → BGRA in-place.
     * Some Vulkan implementations prefer BGRA for swapchain.
     */
    public static void swizzleRGBAtoBGRA(ByteBuffer pixels, int pixelCount) {
        for (int i = 0; i < pixelCount; i++) {
            int offset = i * 4;
            byte r = pixels.get(offset);
            byte b = pixels.get(offset + 2);
            pixels.put(offset, b);
            pixels.put(offset + 2, r);
        }
    }

    /**
     * Convert RGB → RGBA by adding alpha=255.
     */
    public static ByteBuffer expandRGBtoRGBA(ByteBuffer rgb, int pixelCount) {
        ByteBuffer rgba = ByteBuffer.allocateDirect(pixelCount * 4);
        for (int i = 0; i < pixelCount; i++) {
            rgba.put(rgb.get());
            rgba.put(rgb.get());
            rgba.put(rgb.get());
            rgba.put((byte) 0xFF);
        }
        rgba.flip();
        return rgba;
    }

    /**
     * Flip image vertically (OpenGL has Y-up, Vulkan has Y-down for upload).
     */
    public static void flipVertically(ByteBuffer pixels, int width, int height, int bpp) {
        int rowBytes = width * bpp;
        byte[] topRow = new byte[rowBytes];
        byte[] botRow = new byte[rowBytes];

        for (int y = 0; y < height / 2; y++) {
            int topOffset = y * rowBytes;
            int botOffset = (height - 1 - y) * rowBytes;

            pixels.position(topOffset);
            pixels.get(topRow);
            pixels.position(botOffset);
            pixels.get(botRow);

            pixels.position(topOffset);
            pixels.put(botRow);
            pixels.position(botOffset);
            pixels.put(topRow);
        }
        pixels.rewind();
    }

    // ─── Mip size calculation ──────────────────────────────────────────

    /** Calculate dimension at a given mip level. */
    public static int mipSize(int baseSize, int level) {
        return Math.max(1, baseSize >> level);
    }

    /** Calculate the total number of mip levels for a dimension. */
    public static int calcMipLevels(int width, int height) {
        return (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2)) + 1;
    }

    /** Calculate total byte size of all mip levels. */
    public static long totalMipBytes(int width, int height, int bpp, int mipLevels) {
        long total = 0;
        for (int i = 0; i < mipLevels; i++) {
            total += (long) mipSize(width, i) * mipSize(height, i) * bpp;
        }
        return total;
    }

    // ─── Alignment helpers ─────────────────────────────────────────────

    /** Align a value up to the given alignment. */
    public static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    /**
     * Calculate the required staging buffer offset alignment for an image upload.
     * Vulkan requires buffer offsets to be aligned to texel block size.
     */
    public static long getStagingAlignment(int vkFormat) {
        // For non-compressed formats, alignment = bytes per pixel
        // For compressed formats (BC1..BC7), alignment = block byte size
        return bytesPerPixel(vkFormat);
    }

    // ─── GL format helpers ─────────────────────────────────────────────

    /**
     * Determine the number of channels from a GL format enum.
     */
    public static int glFormatChannels(int glFormat) {
        return switch (glFormat) {
            case 0x1903 /* GL_RED */   -> 1;
            case 0x8227 /* GL_RG */    -> 2;
            case 0x1907 /* GL_RGB */   -> 3;
            case 0x1908 /* GL_RGBA */  -> 4;
            case 0x80E0 /* GL_BGR */   -> 3;
            case 0x80E1 /* GL_BGRA */  -> 4;
            default -> 4;
        };
    }

    /**
     * Determine the data type size from a GL type enum.
     */
    public static int glTypeSize(int glType) {
        return switch (glType) {
            case 0x1401 /* GL_UNSIGNED_BYTE  */ -> 1;
            case 0x1406 /* GL_FLOAT          */ -> 4;
            case 0x140B /* GL_HALF_FLOAT     */ -> 2;
            case 0x1405 /* GL_UNSIGNED_INT   */ -> 4;
            case 0x1403 /* GL_UNSIGNED_SHORT */ -> 2;
            default -> 1;
        };
    }
}
