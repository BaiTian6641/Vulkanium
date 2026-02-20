package net.vulkanium.shaderpack.compute;

import net.vulkanium.core.VulkaniumDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages custom images (storage images) declared by shader packs.
 *
 * <p>Shader packs use {@code colorimgN} names to bind render targets as storage
 * images for compute shader image load/store operations. Custom images can also
 * be declared in {@code shaders.properties} for pack-private storage textures.</p>
 *
 * <h3>Reference: Iris GlImage / IrisImages</h3>
 * <p>Modeled after {@code net.irisshaders.iris.gl.image.GlImage} and
 * {@code net.irisshaders.iris.samplers.IrisImages} from the
 * <a href="https://github.com/IrisShaders/Iris">Iris Shaders</a> project (LGPL-3.0).
 * Iris creates OpenGL textures with {@code glTexImage2D}, binds them as image units
 * via {@code glBindImageTexture}, and manages per-program image binding via
 * {@code ProgramImages}. Screen-relative images are resized on resolution change
 * via {@code GlImage.Relative}.</p>
 *
 * <h3>Reference: Iris ImageInformation</h3>
 * <p>Modeled after {@code net.irisshaders.iris.shaderpack.ImageInformation} (Iris Shaders,
 * LGPL-3.0) — record of image name, sampler name, target type, formats, dimensions,
 * and relative sizing.</p>
 *
 * <h3>Vulkan Implementation</h3>
 * <p>Each custom image is backed by a {@code VkImage} with
 * {@code VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT} so it can be
 * used both as a storage image in compute shaders and as a sampled texture in
 * fragment shaders. Images are kept in {@code VK_IMAGE_LAYOUT_GENERAL} for
 * storage image access.</p>
 *
 * <h3>colorimgN Bindings</h3>
 * <p>Reference: Iris IrisImages.addRenderTargetImages() (Iris Shaders, LGPL-3.0)
 * — binds the current render target (respecting flip state) for each {@code colorimgN}
 * that the shader uses. In Vulkanium, this means binding the G-buffer's image views
 * with {@code VK_IMAGE_LAYOUT_GENERAL} layout for compute shader access.</p>
 */
public class ShaderpackImageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShaderpackImage");

    /** Max custom images (matching Iris) */
    public static final int MAX_CUSTOM_IMAGES = 16;

    /** Max colorimgN bindings (one per render target) */
    public static final int MAX_COLOR_IMAGES = 16;

    /**
     * Custom image declaration from shaders.properties.
     *
     * <p>Reference: Iris ImageInformation record (Iris Shaders, LGPL-3.0)</p>
     *
     * @param name          Image name (e.g. "customImage0")
     * @param samplerName   Associated sampler name for fragment shader sampling
     * @param format        Vulkan format (e.g. VK_FORMAT_R32G32B32A32_SFLOAT)
     * @param width         Width in pixels (or 0 if relative)
     * @param height        Height in pixels (or 0 if relative)
     * @param depth         Depth (0 for 2D, >0 for 3D)
     * @param clear         Whether to clear on creation
     * @param relative      Whether dimensions scale with screen size
     * @param relativeWidth Width scale factor (1.0 = screen width)
     * @param relativeHeight Height scale factor (1.0 = screen height)
     */
    public record ImageInfo(String name, String samplerName, int format,
                            int width, int height, int depth,
                            boolean clear, boolean relative,
                            float relativeWidth, float relativeHeight) {

        /**
         * Calculates actual width for the given screen size.
         */
        public int getActualWidth(int screenWidth) {
            return relative ? (int) (screenWidth * relativeWidth) : width;
        }

        /**
         * Calculates actual height for the given screen size.
         */
        public int getActualHeight(int screenHeight) {
            return relative ? (int) (screenHeight * relativeHeight) : height;
        }
    }

    /**
     * A Vulkan storage image backing a shaderpack custom image.
     */
    private static class VulkanImage {
        final ImageInfo info;
        long image = VK_NULL_HANDLE;
        long imageView = VK_NULL_HANDLE;
        long allocation = VK_NULL_HANDLE;
        int currentWidth;
        int currentHeight;

        VulkanImage(ImageInfo info) {
            this.info = info;
        }
    }

    // ── State ──
    private final List<VulkanImage> customImages = new ArrayList<>();
    private final long allocator;
    private int cachedWidth;
    private int cachedHeight;
    private boolean initialized = false;

    /**
     * @param vmaAllocator VMA allocator handle for image creation
     */
    public ShaderpackImageManager(long vmaAllocator) {
        this.allocator = vmaAllocator;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Configuration
    // ═══════════════════════════════════════════════════════════════

    /**
     * Registers a custom image from shaders.properties.
     */
    public void registerImage(ImageInfo info) {
        if (customImages.size() >= MAX_CUSTOM_IMAGES) {
            LOGGER.warn("Maximum custom images ({}) reached, ignoring '{}'",
                    MAX_CUSTOM_IMAGES, info.name());
            return;
        }
        customImages.add(new VulkanImage(info));
        LOGGER.debug("Registered custom image '{}' — {}x{} format={} relative={}",
                info.name(), info.width(), info.height(), info.format(), info.relative());
    }

    /**
     * Creates all registered custom images.
     */
    public void createImages(int screenWidth, int screenHeight) {
        this.cachedWidth = screenWidth;
        this.cachedHeight = screenHeight;

        for (VulkanImage img : customImages) {
            createImage(img, screenWidth, screenHeight);
        }

        initialized = true;
        LOGGER.info("Created {} custom images", customImages.size());
    }

    /**
     * Resizes relative images on screen dimension change.
     *
     * <p>Reference: Iris GlImage.Relative.updateNewSize() (Iris Shaders, LGPL-3.0)</p>
     */
    public void onScreenResize(int screenWidth, int screenHeight) {
        if (screenWidth == cachedWidth && screenHeight == cachedHeight) return;
        cachedWidth = screenWidth;
        cachedHeight = screenHeight;

        for (VulkanImage img : customImages) {
            if (img.info.relative()) {
                destroyImage(img);
                createImage(img, screenWidth, screenHeight);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Image Creation
    // ═══════════════════════════════════════════════════════════════

    private void createImage(VulkanImage img, int screenWidth, int screenHeight) {
        int w = img.info.getActualWidth(screenWidth);
        int h = img.info.getActualHeight(screenHeight);
        if (w <= 0 || h <= 0) return;

        int format = img.info.format();
        if (format == 0) format = net.vulkanium.render.hdr.HdrConfig.getDefaultCustomImageFormat(); // HDR-aware default

        try (MemoryStack stack = stackPush()) {
            // Create VkImage with STORAGE + SAMPLED usage
            VkImageCreateInfo imageCI = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT
                            | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageCI.extent().set(w, h, 1);

            org.lwjgl.util.vma.VmaAllocationCreateInfo allocCI =
                    org.lwjgl.util.vma.VmaAllocationCreateInfo.calloc(stack)
                            .usage(org.lwjgl.util.vma.Vma.VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pImage = stack.mallocLong(1);
            org.lwjgl.PointerBuffer pAlloc = stack.mallocPointer(1);

            int result = org.lwjgl.util.vma.Vma.vmaCreateImage(allocator, imageCI, allocCI,
                    pImage, pAlloc, null);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to create custom image '{}' ({}x{}): {}",
                        img.info.name(), w, h, result);
                return;
            }

            img.image = pImage.get(0);
            img.allocation = pAlloc.get(0);
            img.currentWidth = w;
            img.currentHeight = h;

            // Create image view
            VkImageViewCreateInfo viewCI = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(img.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewCI.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            VkDevice device = VulkaniumDevice.getGlobalDevice();
            result = vkCreateImageView(device, viewCI, null, pView);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to create image view for '{}': {}", img.info.name(), result);
                return;
            }
            img.imageView = pView.get(0);

            LOGGER.debug("Custom image '{}' created — {}x{} format={}", img.info.name(), w, h, format);
        }
    }

    private void destroyImage(VulkanImage img) {
        VkDevice device = VulkaniumDevice.getGlobalDevice();
        if (img.imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, img.imageView, null);
            img.imageView = VK_NULL_HANDLE;
        }
        if (img.image != VK_NULL_HANDLE) {
            org.lwjgl.util.vma.Vma.vmaDestroyImage(allocator, img.image, img.allocation);
            img.image = VK_NULL_HANDLE;
            img.allocation = VK_NULL_HANDLE;
        }
    }

    /**
     * Transitions all custom images to GENERAL layout for compute access.
     */
    public void transitionToGeneral(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            for (VulkanImage img : customImages) {
                if (img.image == VK_NULL_HANDLE) continue;

                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(img.image);
                barrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                vkCmdPipelineBarrier(cmd,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, null, barrier);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Accessors
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gets the image view for a custom image by name.
     */
    public long getImageView(String name) {
        for (VulkanImage img : customImages) {
            if (img.info.name().equals(name)) {
                return img.imageView;
            }
        }
        return VK_NULL_HANDLE;
    }

    /**
     * Gets the VkImage handle for a custom image by name.
     */
    public long getImage(String name) {
        for (VulkanImage img : customImages) {
            if (img.info.name().equals(name)) {
                return img.image;
            }
        }
        return VK_NULL_HANDLE;
    }

    /**
     * Gets all custom image views as an array for descriptor binding.
     */
    public long[] getAllImageViews() {
        long[] views = new long[customImages.size()];
        for (int i = 0; i < customImages.size(); i++) {
            views[i] = customImages.get(i).imageView;
        }
        return views;
    }

    /**
     * Gets custom image info by index.
     */
    public ImageInfo getImageInfo(int index) {
        if (index >= 0 && index < customImages.size()) {
            return customImages.get(index).info;
        }
        return null;
    }

    public boolean hasCustomImages() { return !customImages.isEmpty(); }
    public int getImageCount() { return customImages.size(); }
    public boolean isInitialized() { return initialized; }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    public void destroy() {
        for (VulkanImage img : customImages) {
            destroyImage(img);
        }
        customImages.clear();
        initialized = false;
        LOGGER.info("Shaderpack image manager destroyed");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Parsing
    // ═══════════════════════════════════════════════════════════════

    /**
     * Maps a GL internal format name to a Vulkan format constant.
     *
     * <p>Reference: Iris InternalTextureFormat enum (Iris Shaders, LGPL-3.0)
     * — maps GL format names to GL format constants. We extend this to map
     * the same names to VK_FORMAT values.</p>
     */
    public static int glFormatNameToVkFormat(String formatName) {
        if (formatName == null || formatName.isBlank()) return VK_FORMAT_R8G8B8A8_UNORM;

        return switch (formatName.toUpperCase(Locale.ROOT)) {
            // 8-bit
            case "R8" -> VK_FORMAT_R8_UNORM;
            case "RG8" -> VK_FORMAT_R8G8_UNORM;
            case "RGB8", "RGBA" -> VK_FORMAT_R8G8B8A8_UNORM;
            case "RGBA8" -> VK_FORMAT_R8G8B8A8_UNORM;
            case "R8I" -> VK_FORMAT_R8_SINT;
            case "RG8I" -> VK_FORMAT_R8G8_SINT;
            case "RGBA8I" -> VK_FORMAT_R8G8B8A8_SINT;
            case "R8UI" -> VK_FORMAT_R8_UINT;
            case "RG8UI" -> VK_FORMAT_R8G8_UINT;
            case "RGBA8UI" -> VK_FORMAT_R8G8B8A8_UINT;

            // 16-bit float
            case "R16F" -> VK_FORMAT_R16_SFLOAT;
            case "RG16F" -> VK_FORMAT_R16G16_SFLOAT;
            case "RGB16F" -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case "RGBA16F" -> VK_FORMAT_R16G16B16A16_SFLOAT;

            // 16-bit int
            case "R16" -> VK_FORMAT_R16_UNORM;
            case "RG16" -> VK_FORMAT_R16G16_UNORM;
            case "RGBA16" -> VK_FORMAT_R16G16B16A16_UNORM;
            case "R16I" -> VK_FORMAT_R16_SINT;
            case "RG16I" -> VK_FORMAT_R16G16_SINT;
            case "RGBA16I" -> VK_FORMAT_R16G16B16A16_SINT;
            case "R16UI" -> VK_FORMAT_R16_UINT;
            case "RG16UI" -> VK_FORMAT_R16G16_UINT;
            case "RGBA16UI" -> VK_FORMAT_R16G16B16A16_UINT;

            // 32-bit float
            case "R32F" -> VK_FORMAT_R32_SFLOAT;
            case "RG32F" -> VK_FORMAT_R32G32_SFLOAT;
            case "RGB32F" -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case "RGBA32F" -> VK_FORMAT_R32G32B32A32_SFLOAT;

            // 32-bit int
            case "R32I" -> VK_FORMAT_R32_SINT;
            case "RG32I" -> VK_FORMAT_R32G32_SINT;
            case "RGBA32I" -> VK_FORMAT_R32G32B32A32_SINT;
            case "R32UI" -> VK_FORMAT_R32_UINT;
            case "RG32UI" -> VK_FORMAT_R32G32_UINT;
            case "RGBA32UI" -> VK_FORMAT_R32G32B32A32_UINT;

            // Packed formats
            case "R11F_G11F_B10F" -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case "RGB10_A2" -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;

            default -> {
                LOGGER.warn("Unknown image format '{}', defaulting to RGBA8", formatName);
                yield VK_FORMAT_R8G8B8A8_UNORM;
            }
        };
    }

    /**
     * Parses a custom image declaration from shaders.properties.
     *
     * <p>Format: {@code image.NAME = samplerName format internalFormat type clear relative [relW relH] | [w [h [d]]]}</p>
     *
     * <p>Reference: Iris ShaderProperties image parsing (Iris Shaders, LGPL-3.0)</p>
     */
    public static ImageInfo parseImageDeclaration(String name, String value) {
        return parseImageDeclaration(name, value, null);
    }

    /**
     * Parses a custom image declaration, resolving macro tokens from the
     * provided defines map (e.g. {@code SKYBOX_RESOLUTION_X → 192}).
     */
    public static ImageInfo parseImageDeclaration(String name, String value,
                                                   Map<String, String> defines) {
        if (value == null || value.isBlank()) return null;

        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2) return null;

        try {
            String samplerName = parts[0];
            String formatName = parts.length >= 3 ? parts[2] : "RGBA8";
            int vkFormat = glFormatNameToVkFormat(formatName);
            boolean clear = parts.length >= 5 && Boolean.parseBoolean(parts[4]);
            boolean relative = parts.length >= 6 && Boolean.parseBoolean(parts[5]);

            int width = 0, height = 0, depth = 0;
            float relW = 1.0f, relH = 1.0f;

            if (relative && parts.length >= 8) {
                relW = Float.parseFloat(parts[6]);
                relH = Float.parseFloat(parts[7]);
            } else if (!relative && parts.length >= 7) {
                width = resolveIntToken(parts[6], defines);
                height = parts.length >= 8 ? resolveIntToken(parts[7], defines) : 0;
                depth = parts.length >= 9 ? resolveIntToken(parts[8], defines) : 0;
            }

            return new ImageInfo(name, samplerName, vkFormat,
                    width, height, depth, clear, relative, relW, relH);
        } catch (Exception e) {
            LOGGER.warn("Invalid image declaration: {} = {}", name, value);
            return null;
        }
    }

    /**
     * Attempts to parse a token as an integer.  If it fails, looks it up in the
     * defines map (e.g. {@code SKYBOX_RESOLUTION_X → "192"}).
     */
    private static int resolveIntToken(String token, Map<String, String> defines) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            if (defines != null) {
                String resolved = defines.get(token);
                if (resolved != null) {
                    try {
                        return Integer.parseInt(resolved.trim());
                    } catch (NumberFormatException e2) {
                        LOGGER.warn("Cannot resolve image dimension macro '{}' = '{}'", token, resolved);
                    }
                } else {
                    LOGGER.warn("Unresolved image dimension macro '{}' (not in active defines)", token);
                }
            }
            throw e; // re-throw to let caller handle
        }
    }
}