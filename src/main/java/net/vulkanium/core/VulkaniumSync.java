package net.vulkanium.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages synchronisation primitives: fences, semaphores.
 *
 * <p>Provides per-frame-in-flight sync objects for the frame loop:</p>
 * <ul>
 *   <li><b>imageAvailable</b> semaphore: signalled when swapchain image is acquired</li>
 *   <li><b>renderFinished</b> semaphore: signalled when rendering completes</li>
 *   <li><b>inFlight</b> fence: CPU waits on this before reusing the frame's resources</li>
 * </ul>
 */
public class VulkaniumSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Sync");

    private VkDevice device;
    private int framesInFlight;

    // Per-frame sync objects
    private long[] imageAvailableSemaphores;
    private long[] renderFinishedSemaphores;
    private long[] inFlightFences;

    /**
     * Creates all sync objects for the configured frames-in-flight count.
     */
    public void initialize(VulkaniumDevice vulkaniumDevice, int framesInFlight) {
        this.device = vulkaniumDevice.getLogicalDevice();
        this.framesInFlight = framesInFlight;

        imageAvailableSemaphores = new long[framesInFlight];
        renderFinishedSemaphores = new long[framesInFlight];
        inFlightFences = new long[framesInFlight];

        for (int i = 0; i < framesInFlight; i++) {
            imageAvailableSemaphores[i] = createSemaphore();
            renderFinishedSemaphores[i] = createSemaphore();
            inFlightFences[i] = createFence(true); // start signalled
        }

        LOGGER.info("Created {} sets of sync objects", framesInFlight);
    }

    /**
     * Waits for the in-flight fence of the given frame index, then resets it.
     */
    public void waitForFrame(int frameIndex) {
        vkWaitForFences(device, inFlightFences[frameIndex], true, Long.MAX_VALUE);
        vkResetFences(device, inFlightFences[frameIndex]);
    }

    // === Factory Methods ===

    public long createSemaphore() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer pSemaphore = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore);
            checkResult(result, "Failed to create semaphore");

            return pSemaphore.get(0);
        }
    }

    /**
     * @param signalled If true, fence starts in signalled state (useful for first frame).
     */
    public long createFence(boolean signalled) {
        try (MemoryStack stack = stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(signalled ? VK_FENCE_CREATE_SIGNALED_BIT : 0);

            LongBuffer pFence = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateFence(device, fenceInfo, null, pFence);
            checkResult(result, "Failed to create fence");

            return pFence.get(0);
        }
    }

    // === Cleanup ===

    public void destroy() {
        if (device == null) return;

        for (int i = 0; i < framesInFlight; i++) {
            destroySemaphore(imageAvailableSemaphores[i]);
            destroySemaphore(renderFinishedSemaphores[i]);
            destroyFence(inFlightFences[i]);
        }

        device = null;
    }

    public void destroySemaphore(long semaphore) {
        if (semaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, semaphore, null);
        }
    }

    public void destroyFence(long fence) {
        if (fence != VK_NULL_HANDLE) {
            vkDestroyFence(device, fence, null);
        }
    }

    // === Getters ===

    public long getImageAvailableSemaphore(int frame) { return imageAvailableSemaphores[frame]; }
    public long getRenderFinishedSemaphore(int frame) { return renderFinishedSemaphores[frame]; }
    public long getInFlightFence(int frame) { return inFlightFences[frame]; }
}
