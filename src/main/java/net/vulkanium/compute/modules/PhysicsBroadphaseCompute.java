package net.vulkanium.compute.modules;

import net.vulkanium.api.ComputeTask;
import net.vulkanium.compute.ComputeAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

/**
 * GPU-accelerated broad-phase collision detection.
 *
 * <p>Performs sort-and-sweep AABB broad-phase collision detection on the GPU.
 * Outputs overlapping AABB pairs for the CPU narrow-phase. Useful for
 * entity-dense scenes (mob farms, PvP servers).</p>
 *
 * <h3>Algorithm: Sort and Sweep</h3>
 * <ol>
 *   <li>Upload all entity AABBs to an SSBO</li>
 *   <li>GPU radix sort AABBs by X-axis minimum (parallel bitonic sort)</li>
 *   <li>Sweep along sorted axis: for each AABB, check overlap with subsequent
 *       AABBs until the start of the next exceeds the end of the current</li>
 *   <li>For overlapping X ranges, check Y and Z overlap</li>
 *   <li>Output overlapping pairs via atomic append to result SSBO</li>
 * </ol>
 *
 * <h3>Data Layout</h3>
 * <pre>
 *   struct AABB {
 *       vec3 min;    // 12 bytes
 *       uint id;     // 4 bytes (entity ID)
 *       vec3 max;    // 12 bytes
 *       uint flags;  // 4 bytes (collision layer mask)
 *   }; // 32 bytes
 *
 *   struct CollisionPair {
 *       uint idA;
 *       uint idB;
 *   }; // 8 bytes
 * </pre>
 *
 * <h3>Performance</h3>
 * <p>CPU broad-phase typically scales O(n²) or O(n log n) with spatial hashing.
 * GPU sort-and-sweep achieves O(n) sweep after O(n log n) parallel sort,
 * with massive parallelism on modern hardware. Significant benefit above ~200 entities.</p>
 */
public class PhysicsBroadphaseCompute {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GPUPhysics");

    private static final String SORT_SHADER = "physics_sort";
    private static final String SWEEP_SHADER = "physics_broadphase";

    /** Maximum entities per dispatch */
    private static final int MAX_ENTITIES = 8192;

    /** Maximum collision pairs output */
    private static final int MAX_PAIRS = 16384;

    /** AABB struct size in bytes */
    private static final int AABB_BYTES = 32;

    /** CollisionPair struct size in bytes */
    private static final int PAIR_BYTES = 8;

    private static final int[] SORT_WORKGROUP = {256, 1, 1};
    private static final int[] SWEEP_WORKGROUP = {256, 1, 1};

    private final ComputeAllocator allocator;

    private ComputeAllocator.BufferRegion aabbBuffer;
    private ComputeAllocator.BufferRegion pairBuffer;
    private ComputeAllocator.BufferRegion counterBuffer;

    public PhysicsBroadphaseCompute(ComputeAllocator allocator) {
        this.allocator = allocator;
    }

    /**
     * Uploads entity AABBs for broad-phase detection.
     *
     * @param aabbs Entity bounding boxes
     * @return Number of entities uploaded
     */
    public int uploadAABBs(List<EntityAABB> aabbs) {
        int count = Math.min(aabbs.size(), MAX_ENTITIES);

        ByteBuffer data = ByteBuffer.allocate(count * AABB_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            EntityAABB aabb = aabbs.get(i);
            data.putFloat(aabb.minX).putFloat(aabb.minY).putFloat(aabb.minZ);
            data.putInt(aabb.entityId);
            data.putFloat(aabb.maxX).putFloat(aabb.maxY).putFloat(aabb.maxZ);
            data.putInt(aabb.collisionMask);
        }
        data.flip();
        aabbBuffer = allocator.upload(data);

        // Allocate output pair buffer + atomic counter
        pairBuffer = allocator.allocateReadback((long) MAX_PAIRS * PAIR_BYTES);
        counterBuffer = allocator.allocateDeviceLocal(4); // single uint32 atomic counter

        return count;
    }

    /**
     * Creates the sort compute task (pass 1).
     */
    public ComputeTask createSortTask(int entityCount) {
        return new ComputeTask() {
            @Override
            public String getShaderSource() { return SORT_SHADER; }

            @Override
            public int[] getWorkGroupSize() { return SORT_WORKGROUP; }

            @Override
            public int[] getDispatchSize() {
                return new int[]{(entityCount + 255) / 256, 1, 1};
            }

            @Override
            public Priority getPriority() { return Priority.NORMAL; }

            @Override
            public List<ComputeTask> getDependencies() {
                return Collections.emptyList();
            }
        };
    }

    /**
     * Creates the sweep compute task (pass 2, depends on sort).
     */
    public ComputeTask createSweepTask(int entityCount, ComputeTask sortTask) {
        return new ComputeTask() {
            @Override
            public String getShaderSource() { return SWEEP_SHADER; }

            @Override
            public int[] getWorkGroupSize() { return SWEEP_WORKGROUP; }

            @Override
            public int[] getDispatchSize() {
                return new int[]{(entityCount + 255) / 256, 1, 1};
            }

            @Override
            public Priority getPriority() { return Priority.NORMAL; }

            @Override
            public List<ComputeTask> getDependencies() {
                return List.of(sortTask);
            }
        };
    }

    /**
     * Reads back collision pairs after compute completes.
     */
    public List<CollisionPair> readResults() {
        if (pairBuffer == null) return Collections.emptyList();

        // Read counter first
        ByteBuffer counterData = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        allocator.readback(counterBuffer, counterData);
        counterData.flip();
        int pairCount = Math.min(counterData.getInt(), MAX_PAIRS);

        if (pairCount == 0) return Collections.emptyList();

        // Read pairs
        ByteBuffer pairData = ByteBuffer.allocate(pairCount * PAIR_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        allocator.readback(pairBuffer, pairData);
        pairData.flip();

        List<CollisionPair> pairs = new java.util.ArrayList<>(pairCount);
        for (int i = 0; i < pairCount; i++) {
            pairs.add(new CollisionPair(pairData.getInt(), pairData.getInt()));
        }

        LOGGER.debug("Broad-phase: {} collision pairs from sweep", pairCount);
        return pairs;
    }

    // ── Data types ──

    public static class EntityAABB {
        public final int entityId;
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;
        public int collisionMask = 0xFFFFFFFF;

        public EntityAABB(int entityId, float minX, float minY, float minZ,
                          float maxX, float maxY, float maxZ) {
            this.entityId = entityId;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    public record CollisionPair(int entityIdA, int entityIdB) {}
}
