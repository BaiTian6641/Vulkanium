package net.vulkanium.core;

import net.vulkanium.render.hdr.HdrConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the Vulkan swap chain — the queue of images that the GPU renders into
 * and the display presents.
 *
 * <p>Improvements over VulkanMod's {@code SwapChain}:</p>
 * <ul>
 *   <li>Clean triple-buffering with configurable present mode</li>
 *   <li>Explicit old-swapchain recycling (no stale handle leaks)</li>
 *   <li>Separate image views per swap image for MRT framebuffer attachment</li>
 *   <li>BGRA / SRGB format negotiation with fallback</li>
 *   <li>On-resize recreation without full device wait</li>
 * </ul>
 */
public class VulkaniumSwapchain {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Swapchain");
    private static final int PREFERRED_IMAGE_COUNT = 3; // triple buffer

    private VkDevice device;
    private VkPhysicalDevice physicalDevice;
    private long surface;
    private long windowHandle;

    // Swapchain state
    private long swapchain = VK_NULL_HANDLE;
    private long[] images;
    private long[] imageViews;
    private int imageCount;

    private int imageFormat;
    private int colorSpace;
    private int width;
    private int height;
    private int currentPresentMode;
    private boolean needsRecreation = false;

    // Depth attachment
    private long depthImage = VK_NULL_HANDLE;
    private long depthImageView = VK_NULL_HANDLE;
    private long depthAllocation;
    private int depthFormat;

    private VulkaniumMemory memory;

    /**
     * Creates the initial swapchain.
     */
    public void initialize(VulkaniumDevice vulkaniumDevice, VulkaniumInstance instance,
                           VulkaniumMemory memory, long windowHandle, int preferredPresentMode) {
        this.device = vulkaniumDevice.getLogicalDevice();
        this.physicalDevice = vulkaniumDevice.getPhysicalDevice();
        this.surface = instance.getSurface();
        this.windowHandle = windowHandle;
        this.memory = memory;

        this.depthFormat = findDepthFormat();

        // ── HDR Initialization ──
        // Probe GPU/display for HDR capability, then resolve format if user enabled HDR
        HdrConfig.probeHdrSupport(physicalDevice, surface);
        boolean wantHdr = false;
        try {
            net.vulkanium.VulkaniumConfig cfg = net.vulkanium.Vulkanium.getConfig();
            if (cfg != null) wantHdr = cfg.hdrOutput;
        } catch (Exception ignored) {}
        if (wantHdr && HdrConfig.isHdrAvailable()) {
            HdrConfig.resolveSwapchainFormat(physicalDevice, surface,
                    HdrConfig.HdrMode.AUTO, true);
        } else {
            HdrConfig.reset();
        }

        createSwapchain(preferredPresentMode, VK_NULL_HANDLE);
        createImageViews();
        createDepthResources();

        LOGGER.info("Swapchain created: {}x{}, {} images, format {} ({}), present mode {}",
                width, height, imageCount,
                HdrConfig.formatName(imageFormat), HdrConfig.colorSpaceName(colorSpace),
                presentModeName(currentPresentMode));
    }

    /**
     * Recreates the swapchain (e.g. on window resize). Reuses old swapchain for efficiency.
     */
    public void recreate(int preferredPresentMode) {
        // Wait for queue idle to avoid destroying in-use images
        vkDeviceWaitIdle(device);

        cleanupImageViews();
        cleanupDepthResources();

        long oldSwapchain = swapchain;
        createSwapchain(preferredPresentMode, oldSwapchain);

        // Destroy old after new is created
        if (oldSwapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, oldSwapchain, null);
        }

        createImageViews();
        createDepthResources();

        LOGGER.info("Swapchain recreated: {}x{}, {} images", width, height, imageCount);
    }

    private void createSwapchain(int preferredPresentMode, long oldSwapchain) {
        try (MemoryStack stack = stackPush()) {
            // Query surface capabilities
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);

            // Choose format
            IntBuffer formatCount = stack.ints(0);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, formatCount, formats);

            VkSurfaceFormatKHR chosenFormat = chooseFormat(formats);
            this.imageFormat = chosenFormat.format();
            this.colorSpace = chosenFormat.colorSpace();

            // Choose present mode
            IntBuffer presentModeCount = stack.ints(0);
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, null);
            IntBuffer presentModes = stack.mallocInt(presentModeCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, presentModeCount, presentModes);

            this.currentPresentMode = choosePresentMode(presentModes, preferredPresentMode);

            // Choose extent
            VkExtent2D extent = chooseExtent(capabilities, stack);
            this.width = extent.width();
            this.height = extent.height();

            if (width == 0 || height == 0) {
                LOGGER.warn("Window minimized, deferring swapchain creation");
                this.swapchain = VK_NULL_HANDLE;
                return;
            }

            // Image count: prefer triple buffer, respect driver limits
            int minImages = capabilities.minImageCount();
            int maxImages = capabilities.maxImageCount();
            int desiredImages = Math.max(PREFERRED_IMAGE_COUNT, minImages);
            if (maxImages > 0) desiredImages = Math.min(desiredImages, maxImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(desiredImages)
                    .imageFormat(imageFormat)
                    .imageColorSpace(colorSpace)
                    .imageExtent(extent)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(currentPresentMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);

            LongBuffer pSwapchain = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateSwapchainKHR(device, createInfo, null, pSwapchain);
            checkResult(result, "Failed to create swapchain");

            this.swapchain = pSwapchain.get(0);

            // Retrieve images
            IntBuffer pImageCount = stack.ints(0);
            vkGetSwapchainImagesKHR(device, swapchain, pImageCount, null);
            this.imageCount = pImageCount.get(0);

            LongBuffer pImages = stack.mallocLong(imageCount);
            vkGetSwapchainImagesKHR(device, swapchain, pImageCount, pImages);

            this.images = new long[imageCount];
            for (int i = 0; i < imageCount; i++) {
                images[i] = pImages.get(i);
            }
        }
    }

    private void createImageViews() {
        if (swapchain == VK_NULL_HANDLE) return;

        imageViews = new long[imageCount];
        for (int i = 0; i < imageCount; i++) {
            imageViews[i] = createImageView(images[i], imageFormat, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        }
    }

    private void createDepthResources() {
        if (width == 0 || height == 0) return;

        VulkaniumMemory.ImageAllocation depthAlloc = memory.createImage(
                width, height, 1,
                depthFormat,
                VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        this.depthImage = depthAlloc.image();
        this.depthAllocation = depthAlloc.allocation();
        this.depthImageView = createImageView(depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, 1);
    }

    // === Format / Present Mode / Extent Selection ===

    private VkSurfaceFormatKHR chooseFormat(VkSurfaceFormatKHR.Buffer formats) {
        // ── HDR path: use HdrConfig-resolved format/colorSpace ──
        if (HdrConfig.isHdrEnabled()) {
            int hdrFmt = HdrConfig.getResolvedFormat();
            int hdrCS  = HdrConfig.getResolvedColorSpace();

            for (int i = 0; i < formats.capacity(); i++) {
                VkSurfaceFormatKHR format = formats.get(i);
                if (format.format() == hdrFmt && format.colorSpace() == hdrCS) {
                    LOGGER.info("[HDR] Swapchain format: {} + {}",
                            HdrConfig.formatName(hdrFmt), HdrConfig.colorSpaceName(hdrCS));
                    return format;
                }
            }
            LOGGER.warn("[HDR] Resolved HDR format not available in surface — falling back to SDR");
        }

        // ── SDR path: Prefer BGRA8 UNORM ──
        // MC's rendering pipeline is NOT gamma-correct,
        // it expects raw byte values passed through without sRGB conversion.
        // Using SRGB format would cause double-gamma (washed out / too bright).
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR format = formats.get(i);
            if (format.format() == VK_FORMAT_B8G8R8A8_UNORM &&
                    format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format;
            }
        }
        // Fallback to BGRA8 UNORM with any color space
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR format = formats.get(i);
            if (format.format() == VK_FORMAT_B8G8R8A8_UNORM) {
                return format;
            }
        }
        // Last resort: first available
        return formats.get(0);
    }

    private int choosePresentMode(IntBuffer availableModes, int preferred) {
        // Check if preferred mode is available
        for (int i = 0; i < availableModes.capacity(); i++) {
            if (availableModes.get(i) == preferred) return preferred;
        }
        // Fallback: MAILBOX → IMMEDIATE → FIFO
        int[] fallbacks = {VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_FIFO_KHR};
        for (int mode : fallbacks) {
            for (int i = 0; i < availableModes.capacity(); i++) {
                if (availableModes.get(i) == mode) return mode;
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR; // guaranteed available
    }

    private VkExtent2D chooseExtent(VkSurfaceCapabilitiesKHR capabilities, MemoryStack stack) {
        if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
            return capabilities.currentExtent();
        }
        // Query actual framebuffer size
        IntBuffer pWidth = stack.ints(0);
        IntBuffer pHeight = stack.ints(0);
        glfwGetFramebufferSize(windowHandle, pWidth, pHeight);

        int w = Math.max(capabilities.minImageExtent().width(),
                Math.min(capabilities.maxImageExtent().width(), pWidth.get(0)));
        int h = Math.max(capabilities.minImageExtent().height(),
                Math.min(capabilities.maxImageExtent().height(), pHeight.get(0)));

        return VkExtent2D.malloc(stack).set(w, h);
    }

    private int findDepthFormat() {
        int[] candidates = {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT};
        for (int format : candidates) {
            try (MemoryStack stack = stackPush()) {
                VkFormatProperties props = VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);
                if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return format;
                }
            }
        }
        throw new RuntimeException("No supported depth format found");
    }

    // === Image View Helper ===

    private long createImageView(long image, int format, int aspectFlags, int mipLevels) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);

            viewInfo.subresourceRange()
                    .aspectMask(aspectFlags)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateImageView(device, viewInfo, null, pView);
            checkResult(result, "Failed to create image view");

            return pView.get(0);
        }
    }

    // === Present ===

    /**
     * Presents the given swap image index to the screen.
     *
     * @return VK_SUCCESS, VK_SUBOPTIMAL_KHR, or VK_ERROR_OUT_OF_DATE_KHR
     */
    public int present(VkQueue presentQueue, int imageIndex, long waitSemaphore) {
        try (MemoryStack stack = stackPush()) {
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(waitSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex));

            return vkQueuePresentKHR(presentQueue, presentInfo);
        }
    }

    /**
     * Acquires the next swap image index.
     *
     * @param signalSemaphore Semaphore to signal when the image is available
     * @param fence           Optional fence (VK_NULL_HANDLE if not needed)
     * @return Image index, or -1 if swapchain is out of date
     */
    public int acquireNextImage(long signalSemaphore, long fence) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pImageIndex = stack.ints(0);
            int result = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, signalSemaphore, fence, pImageIndex);

            if (result == VK_ERROR_OUT_OF_DATE_KHR) return -1;
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
                checkResult(result, "Failed to acquire swap chain image");
            }
            return pImageIndex.get(0);
        }
    }

    // === Cleanup ===

    private void cleanupImageViews() {
        if (imageViews != null) {
            for (long view : imageViews) {
                if (view != VK_NULL_HANDLE) {
                    vkDestroyImageView(device, view, null);
                }
            }
            imageViews = null;
        }
    }

    private void cleanupDepthResources() {
        if (depthImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, depthImageView, null);
            depthImageView = VK_NULL_HANDLE;
        }
        if (depthImage != VK_NULL_HANDLE) {
            memory.freeImageImmediate(new VulkaniumMemory.ImageAllocation(
                    depthImage, depthAllocation, width, height, depthFormat, 1));
            depthImage = VK_NULL_HANDLE;
        }
    }

    public void destroy() {
        cleanupImageViews();
        cleanupDepthResources();

        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, swapchain, null);
            swapchain = VK_NULL_HANDLE;
        }
    }

    // === Getters ===

    public long getSwapchain() { return swapchain; }
    public long[] getImages() { return images; }
    public long[] getImageViews() { return imageViews; }
    public int getImageCount() { return imageCount; }
    public int getImageFormat() { return imageFormat; }
    public int getDepthFormat() { return depthFormat; }
    public long getDepthImage() { return depthImage; }
    public long getDepthImageView() { return depthImageView; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isValid() { return swapchain != VK_NULL_HANDLE && width > 0 && height > 0; }

    // === Utility ===

    public static String presentModeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO (VSync)";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "UNKNOWN(" + mode + ")";
        };
    }

    /**
     * Set the preferred present mode (called when vsync is toggled).
     * Triggers swapchain recreation on the next frame.
     */
    public void setPresentMode(int newPresentMode) {
        if (this.currentPresentMode != newPresentMode) {
            this.currentPresentMode = newPresentMode;
            this.needsRecreation = true;
            LOGGER.info("Present mode changed to {} — swapchain will be recreated",
                    presentModeName(newPresentMode));
        }
    }

    /**
     * Mark the swapchain as needing recreation (e.g., after resize).
     */
    public void setNeedsRecreation(boolean needsRecreation) {
        this.needsRecreation = needsRecreation;
    }

    public boolean needsRecreation() { return needsRecreation; }
    public int getCurrentPresentMode() { return currentPresentMode; }
}
