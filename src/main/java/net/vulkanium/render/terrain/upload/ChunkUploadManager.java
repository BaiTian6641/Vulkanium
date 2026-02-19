package net.vulkanium.render.terrain.upload;

import net.vulkanium.core.VulkaniumCommand;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import net.vulkanium.render.terrain.ChunkVertexFormat;
import net.vulkanium.render.terrain.pass.TerrainPassType;
import net.vulkanium.render.terrain.region.RegionGPUBuffers;
import net.vulkanium.render.terrain.region.RenderRegion;
import net.vulkanium.render.terrain.section.RenderSection;
import net.vulkanium.render.terrain.section.SectionData;
import net.vulkanium.resource.StagingRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages asynchronous GPU upload of chunk mesh data via the transfer queue.
 *
 * <p>Build results (vertex/index buffers) are uploaded from CPU memory to device-local
 * GPU memory using the dedicated transfer queue, avoiding stalls on the graphics queue.</p>
 *
 * <h3>Upload Pipeline</h3>
 * <pre>
 * 1. Build results arrive from ChunkBuildWorkerPool
 * 2. ChunkUploadManager batches them into upload commands
 * 3. Data is copied to staging ring buffer (CPU → host-visible GPU memory)
 * 4. Transfer queue copies staging → device-local (vkCmdCopyBuffer)
 * 5. Timeline semaphore signals when transfer is complete
 * 6. Graphics queue waits for semaphore → data is renderable
 * </pre>
 *
 * <h3>Batching</h3>
 * <p>Multiple section uploads are batched into a single command buffer submission
 * to amortize submission overhead. Batches are flushed when the staging ring is full
 * or when the frame ends.</p>
 */
public class ChunkUploadManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ChunkUpload");

    /** Maximum sections to upload per frame. */
    private static final int MAX_UPLOADS_PER_FRAME = 32;

    private final VulkaniumMemory memory;
    private final VulkaniumQueues queues;
    private final StagingRing stagingRing;

    /** Pending uploads waiting to be batched. */
    private final List<PendingUpload> pendingUploads = new ArrayList<>();

    /** Statistics. */
    private int uploadsThisFrame = 0;
    private long bytesUploadedThisFrame = 0;
    private int totalUploads = 0;
    private long totalBytesUploaded = 0;

    public ChunkUploadManager(VulkaniumMemory memory, VulkaniumQueues queues, StagingRing stagingRing) {
        this.memory = memory;
        this.queues = queues;
        this.stagingRing = stagingRing;
    }

    /**
     * Queues a section's mesh data for GPU upload.
     *
     * <p>Called from the main thread after ChunkBuildWorkerPool delivers a result.</p>
     *
     * @param section The section whose data is ready
     * @param data    The built mesh data (will be freed after upload)
     */
    public void enqueueUpload(RenderSection section, SectionData data) {
        if (data.isEmpty()) {
            section.applyBuildResults(data);
            data.free();
            return;
        }
        pendingUploads.add(new PendingUpload(section, data));
    }

    /**
     * Processes pending uploads up to the per-frame limit.
     * Called once per frame from the main render thread.
     *
     * @return Number of sections uploaded
     */
    public int processUploads() {
        uploadsThisFrame = 0;
        bytesUploadedThisFrame = 0;

        while (!pendingUploads.isEmpty() && uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
            PendingUpload upload = pendingUploads.remove(0);
            uploadSection(upload.section(), upload.data());
            uploadsThisFrame++;
        }

        return uploadsThisFrame;
    }

    /**
     * Uploads a single section's mesh data to GPU.
     */
    private void uploadSection(RenderSection section, SectionData data) {
        section.applyBuildResults(data);

        if (data.isEmpty()) {
            data.free();
            return;
        }

        RenderRegion region = section.getRegion();
        if (region == null) {
            if (section.getBuildState() != RenderSection.SectionBuildState.DISCARDED) {
                LOGGER.warn("Section [{}, {}, {}] has no region during upload; rescheduling rebuild",
                        section.getSectionX(), section.getSectionY(), section.getSectionZ());
                section.markDirty();
            }
            data.free();
            return;
        }

        // Upload per-pass data
        for (TerrainPassType pass : TerrainPassType.values()) {
            SectionData.PassMeshData meshData = data.getPassData(pass);
            if (meshData == null) continue;

            RegionGPUBuffers buffers = region.getOrCreatePassBuffers(pass, memory);

            // Ensure GPU buffers have capacity
            long vbNeeded = buffers.getVertexUsedBytes() + meshData.vertexData().remaining();
            long ibNeeded = buffers.getIndexUsedBytes() + meshData.indexData().remaining();
            buffers.ensureVertexCapacity(vbNeeded);
            buffers.ensureIndexCapacity(ibNeeded);

            // Record offsets for this section within the region buffers
            long vertexOffset = buffers.appendVertexData(meshData.vertexData().remaining());
            long indexOffset = buffers.appendIndexData(meshData.indexData().remaining());

            // Copy data via staging ring → device-local transfer
            uploadBuffer(meshData.vertexData(), buffers.getVertexBuffer(), vertexOffset);
            uploadBuffer(meshData.indexData(), buffers.getIndexBuffer(), indexOffset);

            // Record GPU slot for draw commands
            section.setGPUSlot(pass, vertexOffset, indexOffset,
                    meshData.vertexCount(), meshData.indexCount());

            bytesUploadedThisFrame += meshData.vertexData().remaining() + meshData.indexData().remaining();
        }

        section.markReady();
        data.free();

        totalUploads++;
        totalBytesUploaded += bytesUploadedThisFrame;
    }

    /**
     * Copies data from a CPU ByteBuffer to a device-local buffer via staging.
     *
     * <p>In the current implementation, uses synchronous staging ring copy.
     * Phase 7 will upgrade this to use the dedicated transfer queue for
     * truly asynchronous uploads.</p>
     */
    private void uploadBuffer(ByteBuffer srcData, long dstBuffer, long dstOffset) {
        // TODO: Phase 7 — Use dedicated transfer queue for truly async upload
        // Current path: staging ring copy (still fast, but stalls graphics queue briefly)

        long size = srcData.remaining();
        StagingRing.StagingRegion region = stagingRing.claim(size, 16);
        if (region == null) {
            throw new IllegalStateException("Staging ring full: cannot upload " + size + " bytes");
        }
        org.lwjgl.system.MemoryUtil.memCopy(
                org.lwjgl.system.MemoryUtil.memAddress(srcData), region.hostPtr(), size);

        // Record copy command (will be part of the current frame's command buffer)
        // The actual vkCmdCopyBuffer is recorded during the frame's command recording phase
        // For now, track the pending copy
        pendingCopies.add(new CopyRecord(stagingRing.getBuffer(), region.offset(),
                dstBuffer, dstOffset, size));
    }

    /** Pending buffer copy operations to be recorded in the command buffer. */
    private final List<CopyRecord> pendingCopies = new ArrayList<>();

    /**
     * Records all pending buffer copies into the given command buffer.
     * Called during command buffer recording phase.
     *
     * @param cmd Active command buffer
     */
    public void recordCopyCommands(org.lwjgl.vulkan.VkCommandBuffer cmd) {
        if (pendingCopies.isEmpty()) return;

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            for (CopyRecord copy : pendingCopies) {
                org.lwjgl.vulkan.VkBufferCopy.Buffer region =
                        org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                                .srcOffset(copy.srcOffset)
                                .dstOffset(copy.dstOffset)
                                .size(copy.size);

                org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmd,
                        copy.srcBuffer, copy.dstBuffer, region);
            }
        }

        pendingCopies.clear();
    }

    /**
     * Returns whether there are pending copies that need to be recorded.
     */
    public boolean hasPendingCopies() {
        return !pendingCopies.isEmpty();
    }

    /**
     * Resets per-frame statistics.
     */
    public void resetFrameStats() {
        uploadsThisFrame = 0;
        bytesUploadedThisFrame = 0;
    }

    // ============== Statistics ==============

    public int getUploadsThisFrame() { return uploadsThisFrame; }
    public long getBytesUploadedThisFrame() { return bytesUploadedThisFrame; }
    public int getPendingUploadCount() { return pendingUploads.size(); }
    public int getTotalUploads() { return totalUploads; }
    public long getTotalBytesUploaded() { return totalBytesUploaded; }

    private record PendingUpload(RenderSection section, SectionData data) {}
    private record CopyRecord(long srcBuffer, long srcOffset, long dstBuffer, long dstOffset, long size) {}
}
