package net.vulkanium.core;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Orchestrates the per-frame lifecycle with a split begin/end model.
 *
 * <p>Unlike a callback model, the command buffer stays open between
 * {@link #beginFrame()} and {@link #endFrame()} so MC's draw calls can
 * record directly into it (matching VulkanMod's architecture).</p>
 *
 * <ol>
 *   <li>{@link #beginFrame()}: acquire → fence wait → begin cmd buf</li>
 *   <li>  MC's render phase records draw commands into the open cmd buf</li>
 *   <li>{@link #endFrame()}: end cmd buf → submit → present</li>
 * </ol>
 */
public class FrameOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Frame");

    private VulkaniumDevice vulkaniumDevice;
    private VulkaniumQueues queues;
    private VulkaniumSwapchain swapchain;
    private VulkaniumCommand command;
    private VulkaniumSync sync;
    private VulkaniumMemory memory;

    private int framesInFlight;
    private int currentFrame = 0;
    private int currentImageIndex = -1;
    private int lastPresentedImageIndex = -1;
    private boolean framebufferResized = false;
    private boolean recording = false;

    // The currently-recording command buffer (available between beginFrame/endFrame)
    private VkCommandBuffer currentCommandBuffer;

    // Called after swapchain recreation to rebuild dependent resources (framebuffers, etc.)
    private Runnable swapchainRecreationCallback;

    // Statistics
    private long frameCount = 0;
    private long lastFrameTimeNs;
    private double frameTimeMs;
    private long frameStartNs;

    public void initialize(VulkaniumDevice device, VulkaniumQueues queues,
                           VulkaniumSwapchain swapchain, VulkaniumCommand command,
                           VulkaniumSync sync, VulkaniumMemory memory,
                           int framesInFlight) {
        this.vulkaniumDevice = device;
        this.queues = queues;
        this.swapchain = swapchain;
        this.command = command;
        this.sync = sync;
        this.memory = memory;
        this.framesInFlight = framesInFlight;
        this.lastFrameTimeNs = System.nanoTime();

        LOGGER.info("Frame orchestrator initialized ({} frames in flight)", framesInFlight);
    }

    // ─── Split Frame Lifecycle ─────────────────────────────────────────

    /**
     * Phase 1: Acquire swapchain image, wait for fence, begin command buffer recording.
     * After this returns, the command buffer is open and draw commands can be recorded.
     *
     * @return true if frame started successfully, false if swapchain needs recreation
     */
    public boolean beginFrame() {
        if (!swapchain.isValid()) return false;

        frameStartNs = System.nanoTime();

        // 1. Wait for this frame-in-flight fence FIRST — ensures the previous
        //    submit consumed the semaphore, so it's safe to signal again
        sync.waitForFrame(currentFrame);

        // 2. Now acquire — semaphore is guaranteed free
        long imageAvailable = sync.getImageAvailableSemaphore(currentFrame);
        currentImageIndex = swapchain.acquireNextImage(imageAvailable, VK_NULL_HANDLE);

        if (currentImageIndex < 0) {
            handleSwapchainRecreation();
            return false;
        }

        // 3. Process deferred memory frees
        memory.beginFrame(currentFrame);

        // 4. Begin command buffer recording
        currentCommandBuffer = command.beginFrame(currentFrame);
        recording = true;

        return true;
    }

    /**
     * Phase 2: End command buffer recording, submit to GPU, present.
     * All draw commands must have been recorded before this call.
     *
     * @return true if frame was submitted and presented
     */
    public boolean endFrame() {
        if (!recording || currentCommandBuffer == null) return false;

        // 1. End command buffer
        command.endFrame(currentFrame);
        recording = false;

        // 2. Submit to graphics queue
        long imageAvailable = sync.getImageAvailableSemaphore(currentFrame);
        long renderFinished = sync.getRenderFinishedSemaphore(currentFrame);
        long inFlightFence = sync.getInFlightFence(currentFrame);

        queues.submitGraphics(
                new VkCommandBuffer[]{currentCommandBuffer},
                new long[]{imageAvailable},
                new int[]{VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT},
                new long[]{renderFinished},
                inFlightFence
        );

        // 3. Present
        int presentResult = swapchain.present(queues.getPresentQueue(), currentImageIndex, renderFinished);
        lastPresentedImageIndex = currentImageIndex;

        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR
                || framebufferResized || swapchain.needsRecreation()) {
            framebufferResized = false;
            swapchain.setNeedsRecreation(false);
            handleSwapchainRecreation();
        } else if (presentResult != VK_SUCCESS) {
            LOGGER.error("Present failed with result: {}", presentResult);
        }

        // Advance frame index
        currentCommandBuffer = null;
        currentFrame = (currentFrame + 1) % framesInFlight;
        frameCount++;

        // Update frame timing
        long now = System.nanoTime();
        frameTimeMs = (now - frameStartNs) / 1_000_000.0;
        lastFrameTimeNs = now;

        return true;
    }

    /**
     * Called when the framebuffer size changes.
     */
    public void onFramebufferResize(int width, int height) {
        framebufferResized = true;
    }

    private void handleSwapchainRecreation() {
        vkDeviceWaitIdle(vulkaniumDevice.getLogicalDevice());
        swapchain.recreate(swapchain.getCurrentPresentMode());
        LOGGER.info("Swapchain recreated: {}x{}", swapchain.getWidth(), swapchain.getHeight());

        // Notify dependent systems (framebuffers, etc.)
        if (swapchainRecreationCallback != null) {
            swapchainRecreationCallback.run();
        }
    }

    /**
     * Sets a callback to be invoked after swapchain recreation.
     * Use this to rebuild framebuffers and other swapchain-dependent resources.
     */
    public void setSwapchainRecreationCallback(Runnable callback) {
        this.swapchainRecreationCallback = callback;
    }

    /**
     * Waits for all GPU operations to finish. Call before cleanup.
     */
    public void waitIdle() {
        if (vulkaniumDevice != null && vulkaniumDevice.getLogicalDevice() != null) {
            vkDeviceWaitIdle(vulkaniumDevice.getLogicalDevice());
        }
    }

    // === Getters ===

    /** The command buffer currently being recorded. Only valid between beginFrame/endFrame. */
    public VkCommandBuffer getCommandBuffer() { return currentCommandBuffer; }
    public boolean isRecording() { return recording; }

    public int getCurrentFrame() { return currentFrame; }
    public int getCurrentImageIndex() { return currentImageIndex; }
    public int getLastPresentedImageIndex() { return lastPresentedImageIndex; }
    public long getFrameCount() { return frameCount; }
    public double getFrameTimeMs() { return frameTimeMs; }
    public int getFramesInFlight() { return framesInFlight; }
    public VulkaniumSwapchain getSwapchain() { return swapchain; }
}
