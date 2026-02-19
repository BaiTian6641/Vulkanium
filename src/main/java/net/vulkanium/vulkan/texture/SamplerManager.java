package net.vulkanium.vulkan.texture;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.vulkanium.vulkan.memory.VulkanBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Sampler cache manager. Re-uses VkSampler objects for identical filter/wrap settings.
 *
 * <p>Vulkan requires explicit sampler objects (unlike OpenGL where sampler state
 * lives on the texture object). Since many textures share the same filter modes,
 * we cache and share samplers.</p>
 *
 * <p>Corresponds to VulkanMod's sampler handling and replaces OpenGL's
 * glTexParameteri(GL_TEXTURE_MIN_FILTER, ...) etc.</p>
 */
public class SamplerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Sampler");

    private static SamplerManager instance;

    /** Pack filter/wrap into long key → VkSampler handle */
    private final Long2LongOpenHashMap samplerCache = new Long2LongOpenHashMap();

    // ─── Filter modes ──────────────────────────────────────────────────

    public static final int FILTER_NEAREST = 0;     // VK_FILTER_NEAREST
    public static final int FILTER_LINEAR = 1;       // VK_FILTER_LINEAR

    public static final int MIPMAP_MODE_NEAREST = 0; // VK_SAMPLER_MIPMAP_MODE_NEAREST
    public static final int MIPMAP_MODE_LINEAR = 1;  // VK_SAMPLER_MIPMAP_MODE_LINEAR

    // ─── Wrap modes ────────────────────────────────────────────────────

    public static final int WRAP_REPEAT = 0;         // VK_SAMPLER_ADDRESS_MODE_REPEAT
    public static final int WRAP_MIRRORED_REPEAT = 1; // VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT
    public static final int WRAP_CLAMP_TO_EDGE = 2;  // VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    public static final int WRAP_CLAMP_TO_BORDER = 3; // VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER

    private SamplerManager() {
        samplerCache.defaultReturnValue(0);
    }

    public static SamplerManager getInstance() {
        if (instance == null) {
            instance = new SamplerManager();
        }
        return instance;
    }

    /**
     * Get or create a sampler matching the given parameters.
     *
     * @param minFilter        VK_FILTER_NEAREST or VK_FILTER_LINEAR
     * @param magFilter        VK_FILTER_NEAREST or VK_FILTER_LINEAR
     * @param mipmapMode       VK_SAMPLER_MIPMAP_MODE_*
     * @param addressModeU     VK_SAMPLER_ADDRESS_MODE_*
     * @param addressModeV     VK_SAMPLER_ADDRESS_MODE_*
     * @param maxAnisotropy    0 = disabled, 1-16 = aniso level
     * @param maxLod           Maximum LOD clamp (for mip limiting)
     * @param borderColorWhite true = white border, false = transparent black
     * @return VkSampler handle
     */
    public long getOrCreate(int minFilter, int magFilter, int mipmapMode,
                            int addressModeU, int addressModeV,
                            float maxAnisotropy, float maxLod,
                            boolean borderColorWhite) {
        long key = packKey(minFilter, magFilter, mipmapMode, addressModeU, addressModeV,
                          maxAnisotropy, maxLod, borderColorWhite);
        long sampler = samplerCache.get(key);
        if (sampler != 0) return sampler;

        sampler = createSampler(minFilter, magFilter, mipmapMode,
                               addressModeU, addressModeV,
                               maxAnisotropy, maxLod, borderColorWhite);
        samplerCache.put(key, sampler);
        LOGGER.debug("Created sampler: min={} mag={} mip={} wrapU={} wrapV={} aniso={} lod={}",
                     minFilter, magFilter, mipmapMode, addressModeU, addressModeV, maxAnisotropy, maxLod);
        return sampler;
    }

    /**
     * Convenience: get sampler for common Minecraft texture settings.
     */
    public long getDefault(boolean linear, boolean mipmap) {
        return getOrCreate(
            linear ? FILTER_LINEAR : FILTER_NEAREST,
            linear ? FILTER_LINEAR : FILTER_NEAREST,
            mipmap ? MIPMAP_MODE_LINEAR : MIPMAP_MODE_NEAREST,
            WRAP_REPEAT, WRAP_REPEAT,
            0.0f, mipmap ? 16.0f : 0.0f,
            false
        );
    }

    /** Nearest-neighbor, clamp-to-edge, no mips — for framebuffer sampling. */
    public long getFramebufferSampler() {
        return getOrCreate(FILTER_NEAREST, FILTER_NEAREST, MIPMAP_MODE_NEAREST,
                          WRAP_CLAMP_TO_EDGE, WRAP_CLAMP_TO_EDGE,
                          0.0f, 0.0f, false);
    }

    /** Linear, clamp-to-edge — for post-processing passes. */
    public long getLinearClampSampler() {
        return getOrCreate(FILTER_LINEAR, FILTER_LINEAR, MIPMAP_MODE_NEAREST,
                          WRAP_CLAMP_TO_EDGE, WRAP_CLAMP_TO_EDGE,
                          0.0f, 0.0f, false);
    }

    /** Shadow map sampler: nearest, clamp-to-border (white = max depth). */
    public long getShadowSampler() {
        return getOrCreate(FILTER_NEAREST, FILTER_NEAREST, MIPMAP_MODE_NEAREST,
                          WRAP_CLAMP_TO_BORDER, WRAP_CLAMP_TO_BORDER,
                          0.0f, 0.0f, true);
    }

    /** Linear shadow sampler with comparison for PCF. */
    public long getShadowPCFSampler() {
        return getOrCreate(FILTER_LINEAR, FILTER_LINEAR, MIPMAP_MODE_NEAREST,
                          WRAP_CLAMP_TO_BORDER, WRAP_CLAMP_TO_BORDER,
                          0.0f, 0.0f, true);
    }

    private long createSampler(int minFilter, int magFilter, int mipmapMode,
                               int addressModeU, int addressModeV,
                               float maxAnisotropy, float maxLod,
                               boolean borderColorWhite) {
        long device = VulkanBuffer.getDevice();
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(magFilter)
                    .minFilter(minFilter)
                    .mipmapMode(mipmapMode)
                    .addressModeU(addressModeU)
                    .addressModeV(addressModeV)
                    .addressModeW(addressModeV)
                    .mipLodBias(0.0f)
                    .anisotropyEnable(maxAnisotropy > 0.0f)
                    .maxAnisotropy(maxAnisotropy)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .minLod(0.0f)
                    .maxLod(maxLod)
                    .borderColor(borderColorWhite
                            ? VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE
                            : VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                    .unnormalizedCoordinates(false);

            LongBuffer pSampler = stack.mallocLong(1);
            int result = vkCreateSampler(vkDevice, ci, null, pSampler);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create VkSampler: VkResult " + result);
            }
            return pSampler.get(0);
        }
    }

    private long packKey(int min, int mag, int mip, int wrapU, int wrapV,
                         float aniso, float maxLod, boolean border) {
        // Pack into 64-bit key:
        // bits 0-2:   minFilter
        // bits 3-5:   magFilter
        // bits 6-8:   mipmapMode
        // bits 9-12:  addressModeU
        // bits 13-16: addressModeV
        // bits 17-21: anisotropy (0-16 mapped)
        // bits 22-30: maxLod (integer part)
        // bit  31:    borderColor
        return ((long) min)
             | ((long) mag << 3)
             | ((long) mip << 6)
             | ((long) wrapU << 9)
             | ((long) wrapV << 13)
             | ((long) Math.round(aniso) << 17)
             | ((long) Math.round(maxLod) << 22)
             | (border ? (1L << 31) : 0);
    }

    /**
     * Translate GL filter constant → Vulkan filter + mipmap mode.
     */
    public static int[] glFilterToVk(int glFilter) {
        return switch (glFilter) {
            case 0x2600 /* GL_NEAREST */ -> new int[]{FILTER_NEAREST, MIPMAP_MODE_NEAREST};
            case 0x2601 /* GL_LINEAR  */ -> new int[]{FILTER_LINEAR, MIPMAP_MODE_NEAREST};
            case 0x2700 /* GL_NEAREST_MIPMAP_NEAREST */ -> new int[]{FILTER_NEAREST, MIPMAP_MODE_NEAREST};
            case 0x2701 /* GL_LINEAR_MIPMAP_NEAREST  */ -> new int[]{FILTER_LINEAR, MIPMAP_MODE_NEAREST};
            case 0x2702 /* GL_NEAREST_MIPMAP_LINEAR  */ -> new int[]{FILTER_NEAREST, MIPMAP_MODE_LINEAR};
            case 0x2703 /* GL_LINEAR_MIPMAP_LINEAR   */ -> new int[]{FILTER_LINEAR, MIPMAP_MODE_LINEAR};
            default -> new int[]{FILTER_NEAREST, MIPMAP_MODE_NEAREST};
        };
    }

    /**
     * Translate GL wrap constant → Vulkan address mode.
     */
    public static int glWrapToVk(int glWrap) {
        return switch (glWrap) {
            case 0x2901 /* GL_REPEAT          */ -> WRAP_REPEAT;
            case 0x8370 /* GL_MIRRORED_REPEAT */ -> WRAP_MIRRORED_REPEAT;
            case 0x812F /* GL_CLAMP_TO_EDGE   */ -> WRAP_CLAMP_TO_EDGE;
            case 0x812D /* GL_CLAMP_TO_BORDER */ -> WRAP_CLAMP_TO_BORDER;
            default -> WRAP_REPEAT;
        };
    }

    /** Destroy all cached samplers. */
    public void destroy() {
        long device = VulkanBuffer.getDevice();
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

        for (long sampler : samplerCache.values()) {
            if (sampler != 0) {
                vkDestroySampler(vkDevice, sampler, null);
            }
        }
        samplerCache.clear();
        LOGGER.info("Destroyed all cached samplers");
    }

    public int getCacheSize() {
        return samplerCache.size();
    }

    public static void shutdown() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }
}
