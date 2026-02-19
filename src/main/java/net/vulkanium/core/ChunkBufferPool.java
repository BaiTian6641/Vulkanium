package net.vulkanium.core;

import net.vulkanium.Vulkanium;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Pool of persistently-mapped HOST_VISIBLE VkBuffers for chunk vertex data,
 * bucketed by power-of-2 size.
 *
 * <p>When chunks are rebuilt during player movement, dozens of new vertex buffers
 * are needed per frame. Without pooling, each upload triggers a VMA allocation
 * which can stall the render thread (causing "can't turn head while moving").</p>
 *
 * <p><b>Persistent mapping</b>: All pooled buffers remain mapped for their entire
 * lifetime. This eliminates the per-upload {@code vmaMapMemory}/{@code vmaUnmapMemory}
 * overhead, which was the primary cause of render thread stalls during movement.
 * Uploads only need a {@code memCopy} to the pre-mapped pointer.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #acquire(int)} — returns a pooled buffer ≥ requested size, or creates one</li>
 *   <li>Buffer is used for a chunk's vertex data (memCopy to mappedPtr, bind in draws)</li>
 *   <li>{@link #deferRelease(long, long, int, long)} — queues for return AFTER in-flight frames complete</li>
 *   <li>{@link #flushDeferredReleases(long)} — returns deferred buffers to pool</li>
 * </ol>
 *
 * <h3>Size Bucketing</h3>
 * <p>Buffers are rounded up to the next power-of-2 (min 4KB, max 2MB).
 * Requests larger than 2MB bypass the pool (direct VMA alloc/free).</p>
 */
public class ChunkBufferPool {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/BufferPool");

    /** Minimum buffer size (4 KB) */
    private static final int MIN_SIZE = 4096;

    /** Maximum poolable buffer size (2 MB) — larger buffers bypass pool */
    private static final int MAX_POOLABLE_SIZE = 2 * 1024 * 1024;

    /** Maximum buffers per bucket (prevents unbounded memory use) */
    private static final int MAX_PER_BUCKET = 128;

    /** Deferred release delay (number of frames before a buffer can be reused).
     *  Set to framesInFlight + 1 safety margin to prevent GPU accessing
     *  a buffer that was returned to the pool too early (VulkanMod avoids this
     *  entirely by using sub-allocated AreaBuffers that are never freed). */
    private static final int RELEASE_DELAY_FRAMES = 4;

    /**
     * Bucket key → free buffer stack.
     * Key = log2(bucketSize).
     */
    private final Map<Integer, Deque<PooledBuffer>> freeBuckets = new ConcurrentHashMap<>();

    /** Buffers waiting to be returned to the pool after in-flight frames complete */
    private final java.util.List<DeferredRelease> deferredReleases =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private VulkaniumMemory memory;
    private boolean initialized = false;

    // Stats
    private long acquireHits = 0;
    private long acquireMisses = 0;
    private long totalPooled = 0;
    private long totalFreed = 0;

    /**
     * A pooled buffer with a persistently-mapped host pointer.
     * The {@code mappedPtr} remains valid for the entire lifetime of the buffer,
     * allowing uploads via {@code MemoryUtil.memCopy} without any VMA map/unmap calls.
     */
    public record PooledBuffer(long buffer, long allocation, int capacity, long mappedPtr) {}
    private record DeferredRelease(long buffer, long allocation, int capacity, long mappedPtr, long queuedAtFrame) {}

    public void initialize(VulkaniumMemory memory) {
        this.memory = memory;
        this.initialized = true;
        LOGGER.info("Chunk buffer pool initialized (buckets: {}B–{}B)",
                MIN_SIZE, MAX_POOLABLE_SIZE);
    }

    /**
     * Acquires a persistently-mapped HOST_VISIBLE VkBuffer of at least
     * {@code requestedSize} bytes. Returns a pooled buffer if available,
     * otherwise allocates and maps a new one.
     *
     * <p>The returned {@link PooledBuffer#mappedPtr()} is ready for direct
     * {@code MemoryUtil.memCopy} — no map/unmap calls needed.</p>
     *
     * @param requestedSize Minimum required size in bytes
     * @return Pooled buffer with capacity ≥ requestedSize and valid mappedPtr
     */
    public PooledBuffer acquire(int requestedSize) {
        if (!initialized || requestedSize <= 0) return null;

        int bucketSize = bucketize(requestedSize);

        // Try to get from pool
        if (bucketSize <= MAX_POOLABLE_SIZE) {
            int bucketKey = Integer.numberOfTrailingZeros(bucketSize);
            Deque<PooledBuffer> bucket = freeBuckets.get(bucketKey);
            if (bucket != null) {
                PooledBuffer pooled;
                synchronized (bucket) {
                    pooled = bucket.pollFirst();
                }
                if (pooled != null) {
                    acquireHits++;
                    return pooled;
                }
            }
        }

        // Pool miss — allocate new buffer from VMA and persistently map it
        acquireMisses++;
        VulkaniumMemory.BufferAllocation alloc = memory.createBuffer(
                bucketSize,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        // Persistently map — this pointer stays valid for the buffer's entire lifetime
        long mappedPtr = memory.map(alloc.allocation());

        return new PooledBuffer(alloc.buffer(), alloc.allocation(), bucketSize, mappedPtr);
    }

    /**
     * Queues a buffer for deferred return to the pool.
     * The buffer won't be reused until {@code RELEASE_DELAY_FRAMES} frames
     * have elapsed, ensuring in-flight command buffers have finished reading.
     *
     * @param buffer     VkBuffer handle
     * @param allocation VMA allocation handle
     * @param capacity   Buffer capacity in bytes
     * @param mappedPtr  Persistently mapped host pointer (0 for non-pool buffers)
     */
    public void deferRelease(long buffer, long allocation, int capacity, long mappedPtr) {
        if (buffer == VK_NULL_HANDLE) return;
        long currentFrame = Vulkanium.getFrameCounter();
        deferredReleases.add(new DeferredRelease(buffer, allocation, capacity, mappedPtr, currentFrame));
    }

    /**
     * Returns deferred buffers to the pool once they're safe to reuse.
     * Called once per frame from {@code Vulkanium.onFrameEnd()}.
     *
     * @param currentFrame Current frame counter
     */
    public void flushDeferredReleases(long currentFrame) {
        long safeFrame = currentFrame - RELEASE_DELAY_FRAMES;
        deferredReleases.removeIf(entry -> {
            if (entry.queuedAtFrame <= safeFrame) {
                returnToPool(entry.buffer, entry.allocation, entry.capacity, entry.mappedPtr);
                return true;
            }
            return false;
        });
    }

    /**
     * Returns a buffer to the appropriate pool bucket.
     * If the bucket is full or the buffer is too large, it's freed immediately.
     */
    private void returnToPool(long buffer, long allocation, int capacity, long mappedPtr) {
        int bucketSize = bucketize(capacity);
        if (bucketSize > MAX_POOLABLE_SIZE || mappedPtr == 0) {
            // Too large for pool or not persistently mapped — unmap and free
            if (mappedPtr != 0) {
                memory.unmap(allocation);
            }
            memory.freeBufferImmediate(
                    new VulkaniumMemory.BufferAllocation(buffer, allocation, capacity, 0));
            totalFreed++;
            return;
        }

        int bucketKey = Integer.numberOfTrailingZeros(bucketSize);
        Deque<PooledBuffer> bucket = freeBuckets.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());

        synchronized (bucket) {
            if (bucket.size() >= MAX_PER_BUCKET) {
                // Bucket full — unmap and free to prevent unbounded memory
                memory.unmap(allocation);
                memory.freeBufferImmediate(
                        new VulkaniumMemory.BufferAllocation(buffer, allocation, capacity, 0));
                totalFreed++;
                return;
            }
            bucket.addFirst(new PooledBuffer(buffer, allocation, capacity, mappedPtr));
            totalPooled++;
        }
    }

    /**
     * Rounds a size up to the nearest power-of-2, with minimum MIN_SIZE.
     */
    private static int bucketize(int size) {
        size = Math.max(size, MIN_SIZE);
        // Round up to next power of 2
        size--;
        size |= size >>> 1;
        size |= size >>> 2;
        size |= size >>> 4;
        size |= size >>> 8;
        size |= size >>> 16;
        size++;
        return size;
    }

    /**
     * Destroys all pooled buffers and pending deferred releases.
     */
    public void destroy() {
        // Free all pooled buffers (unmap persistently-mapped memory first)
        for (Deque<PooledBuffer> bucket : freeBuckets.values()) {
            synchronized (bucket) {
                for (PooledBuffer pb : bucket) {
                    if (pb.mappedPtr != 0) {
                        memory.unmap(pb.allocation);
                    }
                    memory.freeBufferImmediate(
                            new VulkaniumMemory.BufferAllocation(pb.buffer, pb.allocation, pb.capacity, 0));
                }
                bucket.clear();
            }
        }
        freeBuckets.clear();

        // Free all deferred releases
        for (DeferredRelease dr : deferredReleases) {
            if (dr.mappedPtr != 0) {
                memory.unmap(dr.allocation);
            }
            memory.freeBufferImmediate(
                    new VulkaniumMemory.BufferAllocation(dr.buffer, dr.allocation, dr.capacity, 0));
        }
        deferredReleases.clear();

        LOGGER.info("Buffer pool destroyed (hits={}, misses={}, pooled={}, freed={})",
                acquireHits, acquireMisses, totalPooled, totalFreed);
        initialized = false;
    }

    public long getAcquireHits() { return acquireHits; }
    public long getAcquireMisses() { return acquireMisses; }
    public long getTotalPooled() { return totalPooled; }
    public boolean isInitialized() { return initialized; }
}
