package net.vulkanium.render.perf;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Dedicated transfer queue for async data uploads.
 *
 * <p>Many GPUs expose separate transfer-only queues that can operate concurrently with
 * the graphics queue. This class manages a dedicated transfer queue for uploading
 * chunk mesh data, texture data, and staging buffer→device-local copies without
 * stalling the graphics pipeline.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Graphics Queue:  render frame N ─────────────── render frame N+1
 *   Transfer Queue:  upload chunk data ──── signal semaphore ──↗
 * </pre>
 *
 * <h3>Synchronization</h3>
 * <ul>
 *   <li>Transfer→Graphics ownership transfer via pipeline barrier</li>
 *   <li>Timeline semaphore signals when transfer batch is complete</li>
 *   <li>Graphics queue waits on semaphore before using uploaded data</li>
 * </ul>
 *
 * <h3>Queue Family Ownership Transfer</h3>
 * <p>When transfer and graphics are on different queue families, a two-step barrier
 * is needed — release on transfer queue, acquire on graphics queue — to transfer
 * ownership of the buffer/image.</p>
 */
public class AsyncTransferQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/AsyncXfer");

    /** Maximum pending transfers before we force a sync */
    public static final int MAX_PENDING_TRANSFERS = 64;

    /** Maximum bytes per transfer batch (128 MB) */
    public static final long MAX_BATCH_SIZE = 128L * 1024 * 1024;

    // ── Vulkan state ──
    private final long device;
    private long transferQueue = VK_NULL_HANDLE;
    private int transferQueueFamily = -1;
    private int graphicsQueueFamily = -1;

    /** Command pool for the transfer queue */
    private long commandPool = VK_NULL_HANDLE;

    /** Double-buffered command buffers for overlap */
    private final long[] commandBuffers = new long[2];
    private int currentBuffer = 0;

    /** Timeline semaphore for transfer-graphics sync */
    private long timelineSemaphore = VK_NULL_HANDLE;
    private long completedValue = 0;
    private long pendingValue = 0;

    /** Whether the GPU has a separate transfer queue family */
    private boolean hasDedicatedTransfer = false;
    private boolean needsOwnershipTransfer = false;

    // ── Stats ──
    private long totalBytesTransferred = 0;
    private int transfersThisFrame = 0;

    public AsyncTransferQueue(long device, int graphicsQueueFamily) {
        this.device = device;
        this.graphicsQueueFamily = graphicsQueueFamily;
    }

    /**
     * Initializes the async transfer queue.
     *
     * @param transferQueueFamily Queue family index for transfers (-1 to use graphics queue)
     * @param transferQueue       Queue handle (VK_NULL_HANDLE to use graphics queue)
     */
    public void initialize(int transferQueueFamily, long transferQueue) {
        this.transferQueueFamily = transferQueueFamily;
        this.transferQueue = transferQueue;
        this.hasDedicatedTransfer = (transferQueueFamily != graphicsQueueFamily)
                && transferQueue != VK_NULL_HANDLE;
        this.needsOwnershipTransfer = hasDedicatedTransfer;

        if (hasDedicatedTransfer) {
            createCommandPool();
            allocateCommandBuffers();
            createTimelineSemaphore();
            LOGGER.info("Async transfer queue initialized (family {})", transferQueueFamily);
        } else {
            LOGGER.info("No dedicated transfer queue — using graphics queue for transfers");
        }
    }

    private void createCommandPool() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(transferQueueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateCommandPool(vkDevice, ci, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create transfer command pool: VkResult " + result);
            }
            this.commandPool = pPool.get(0);
        }
    }

    private void allocateCommandBuffers() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(2);

            PointerBuffer pBuffers = stack.mallocPointer(2);
            int result = vkAllocateCommandBuffers(vkDevice, allocInfo, pBuffers);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate transfer command buffers: VkResult " + result);
            }
            commandBuffers[0] = pBuffers.get(0);
            commandBuffers[1] = pBuffers.get(1);
        }
    }

    private void createTimelineSemaphore() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreTypeCreateInfo typeCI = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0);

            VkSemaphoreCreateInfo ci = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(typeCI);

            LongBuffer pSemaphore = stack.mallocLong(1);
            int result = vkCreateSemaphore(vkDevice, ci, null, pSemaphore);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create timeline semaphore: VkResult " + result);
            }
            this.timelineSemaphore = pSemaphore.get(0);
        }
    }

    /**
     * Begins a new transfer batch.
     */
    public void beginBatch() {
        if (!hasDedicatedTransfer) return;
        transfersThisFrame = 0;

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffers[currentBuffer], vkDevice);

        vkResetCommandBuffer(cmd, 0);

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);
        }
    }

    /**
     * Records a buffer copy operation (staging → device-local).
     *
     * @param srcBuffer  Staging buffer handle
     * @param dstBuffer  Device-local buffer handle
     * @param srcOffset  Offset in staging buffer
     * @param dstOffset  Offset in device buffer
     * @param size       Number of bytes to copy
     */
    public void copyBuffer(long srcBuffer, long dstBuffer,
                            long srcOffset, long dstOffset, long size) {
        if (!hasDedicatedTransfer) {
            return;
        }

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffers[currentBuffer], vkDevice);

        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(srcOffset)
                    .dstOffset(dstOffset)
                    .size(size);
            vkCmdCopyBuffer(cmd, srcBuffer, dstBuffer, region);
        }

        totalBytesTransferred += size;
        transfersThisFrame++;
    }

    /**
     * Records a buffer-to-image copy (for texture uploads).
     *
     * @param srcBuffer  Staging buffer with pixel data
     * @param dstImage   Destination VkImage
     * @param width      Image width
     * @param height     Image height
     * @param format     Image format
     */
    public void copyBufferToImage(long srcBuffer, long dstImage,
                                   int width, int height, int format) {
        if (!hasDedicatedTransfer) {
            return;
        }

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffers[currentBuffer], vkDevice);

        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer toTransferDst = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(dstImage);
            toTransferDst.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                null,
                null,
                toTransferDst);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);

            vkCmdCopyBufferToImage(cmd, srcBuffer, dstImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            VkImageMemoryBarrier.Buffer toShaderRead = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(dstImage);
            toShaderRead.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0,
                null,
                null,
                toShaderRead);
        }

        long estimatedBytes = Math.max(1L, (long) width * height * 4L);
        totalBytesTransferred += estimatedBytes;
        transfersThisFrame++;
    }

    /**
     * Records an ownership release barrier on the transfer queue.
     * Must be paired with {@link #recordAcquireBarrier} on the graphics queue.
     */
    public void recordReleaseBarrier(long buffer, long offset, long size) {
        if (!needsOwnershipTransfer) return;

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffers[currentBuffer], vkDevice);

        try (MemoryStack stack = stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(0)
                    .srcQueueFamilyIndex(transferQueueFamily)
                    .dstQueueFamilyIndex(graphicsQueueFamily)
                    .buffer(buffer)
                    .offset(offset)
                    .size(size);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0, null, barrier, null);
        }
    }

    /**
     * Records an ownership acquire barrier on the graphics command buffer.
     *
     * @param graphicsCommandBuffer Active graphics command buffer
     * @param buffer                Buffer being transferred
     * @param offset                Buffer offset
     * @param size                  Buffer size
     */
    public void recordAcquireBarrier(long graphicsCommandBuffer, long buffer,
                                      long offset, long size) {
        if (!needsOwnershipTransfer) return;

        VkCommandBuffer cmd = new VkCommandBuffer(graphicsCommandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT)
                    .srcQueueFamilyIndex(transferQueueFamily)
                    .dstQueueFamilyIndex(graphicsQueueFamily)
                    .buffer(buffer)
                    .offset(offset)
                    .size(size);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                    0, null, barrier, null);
        }
    }

    /**
     * Submits the transfer batch and signals the timeline semaphore.
     *
     * @return The timeline value that will be signaled on completion
     */
    public long submitBatch() {
        if (!hasDedicatedTransfer || transfersThisFrame == 0) return completedValue;

        pendingValue++;

        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffers[currentBuffer], vkDevice);
        vkEndCommandBuffer(cmd);

        try (MemoryStack stack = stackPush()) {
            // Timeline semaphore signal info
            VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO)
                    .pSignalSemaphoreValues(stack.longs(pendingValue));

            PointerBuffer pCmdBuf = stack.mallocPointer(1);
            pCmdBuf.put(0, commandBuffers[currentBuffer]);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pNext(timelineInfo)
                    .pCommandBuffers(pCmdBuf)
                    .pSignalSemaphores(stack.longs(timelineSemaphore));

            VkQueue queue = new VkQueue(transferQueue, vkDevice);
            int result = vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to submit transfer batch: VkResult {}", result);
            }
        }

        currentBuffer = (currentBuffer + 1) % 2;
        return pendingValue;
    }

    /**
     * Returns the semaphore and value for graphics queue to wait on.
     */
    public long getTimelineSemaphore() { return timelineSemaphore; }
    public long getPendingValue() { return pendingValue; }
    public boolean hasDedicatedTransfer() { return hasDedicatedTransfer; }

    // ── Stats ──

    public long getTotalBytesTransferred() { return totalBytesTransferred; }
    public int getTransfersThisFrame() { return transfersThisFrame; }

    // ── Lifecycle ──

    public void destroy() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        if (timelineSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(vkDevice, timelineSemaphore, null);
        }
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(vkDevice, commandPool, null);
        }
        transferQueue = VK_NULL_HANDLE;
        LOGGER.debug("Async transfer queue destroyed");
    }
}
