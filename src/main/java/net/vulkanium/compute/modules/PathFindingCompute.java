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
 * GPU-accelerated A* pathfinding for entity AI.
 *
 * <p>Computes multiple A* paths in parallel on the GPU. Instead of running
 * pathfinding sequentially per entity on the CPU, this batches requests and
 * dispatches them as a single compute workload.</p>
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Voxel grid stored as a 3D SSBO (passability flags per block)</li>
 *   <li>Each workgroup computes one A* path (start → goal)</li>
 *   <li>Open set stored in shared memory (bounded priority queue)</li>
 *   <li>64 paths per dispatch (1 workgroup × 64 threads per path)</li>
 * </ul>
 *
 * <h3>Data Layout</h3>
 * <pre>
 *   struct PathRequest {
 *       ivec3 start;      // Block coordinates
 *       ivec3 goal;       // Block coordinates
 *       uint entityId;    // For result matching
 *       uint maxNodes;    // Search limit (default 200)
 *   };
 *
 *   struct PathResult {
 *       uint entityId;
 *       uint pathLength;
 *       ivec3 waypoints[MAX_PATH_LENGTH]; // Packed coords
 *       uint status;      // 0=found, 1=not_found, 2=partial
 *   };
 * </pre>
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Grid must be pre-uploaded each frame (changes invalidate paths)</li>
 *   <li>Shared memory limits max open set to ~512 nodes</li>
 *   <li>Works best for simple mob pathfinding (zombie, skeleton, etc.)</li>
 *   <li>Complex pathfinding (villager schedules) stays on CPU</li>
 * </ul>
 */
public class PathFindingCompute {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GPUPathfind");

    private static final String SHADER_NAME = "pathfind_astar";

    /** Maximum paths per dispatch */
    private static final int MAX_PATHS_PER_DISPATCH = 64;

    /** Maximum waypoints per path result */
    private static final int MAX_PATH_LENGTH = 64;

    /** Maximum nodes expanded per path search */
    private static final int DEFAULT_MAX_NODES = 200;

    /** Workgroup: 64 threads cooperating on 1 path */
    private static final int[] WORKGROUP_SIZE = {64, 1, 1};

    /** Voxel grid radius around the camera (in blocks) */
    private static final int GRID_RADIUS = 128;

    // Byte sizes
    private static final int PATH_REQUEST_BYTES = 32; // 3×4 + 3×4 + 4 + 4
    private static final int PATH_RESULT_BYTES = 8 + MAX_PATH_LENGTH * 12; // id+len + waypoints

    private final ComputeAllocator allocator;

    // GPU buffers
    private ComputeAllocator.BufferRegion voxelGridBuffer;
    private ComputeAllocator.BufferRegion requestBuffer;
    private ComputeAllocator.BufferRegion resultBuffer;

    public PathFindingCompute(ComputeAllocator allocator) {
        this.allocator = allocator;
    }

    /**
     * Creates a batch pathfinding compute task.
     *
     * @param requests Pathfinding requests for this frame
     * @return A compute task to submit to the scheduler
     */
    public ComputeTask createPathfindTask(List<PathRequest> requests) {
        int count = Math.min(requests.size(), MAX_PATHS_PER_DISPATCH);
        if (count == 0) return null;

        // Upload requests
        ByteBuffer requestData = ByteBuffer.allocate(count * PATH_REQUEST_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            PathRequest req = requests.get(i);
            requestData.putInt(req.startX).putInt(req.startY).putInt(req.startZ);
            requestData.putInt(req.goalX).putInt(req.goalY).putInt(req.goalZ);
            requestData.putInt(req.entityId);
            requestData.putInt(req.maxNodes > 0 ? req.maxNodes : DEFAULT_MAX_NODES);
        }
        requestData.flip();
        requestBuffer = allocator.upload(requestData);

        // Allocate result buffer
        resultBuffer = allocator.allocateReadback(count * PATH_RESULT_BYTES);

        final int pathCount = count;
        return new ComputeTask() {
            @Override
            public String getShaderSource() { return SHADER_NAME; }

            @Override
            public int[] getWorkGroupSize() { return WORKGROUP_SIZE; }

            @Override
            public int[] getDispatchSize() {
                return new int[]{pathCount, 1, 1};
            }

            @Override
            public ComputeTask.Priority getPriority() {
                return ComputeTask.Priority.NORMAL;
            }

            @Override
            public List<ComputeTask> getDependencies() {
                return Collections.emptyList();
            }
        };
    }

    /**
     * Uploads the voxel passability grid around the camera.
     *
     * @param grid Block passability flags: 0=passable, 1=solid, 2=water, 3=lava
     * @param centerX Camera block X
     * @param centerY Camera block Y
     * @param centerZ Camera block Z
     */
    public void uploadVoxelGrid(byte[] grid, int centerX, int centerY, int centerZ) {
        ByteBuffer data = ByteBuffer.allocate(grid.length + 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        // Header: grid center + radius
        data.putInt(centerX).putInt(centerY).putInt(centerZ).putInt(GRID_RADIUS);
        data.put(grid);
        data.flip();
        voxelGridBuffer = allocator.upload(data);
    }

    /**
     * Reads back computed paths.
     */
    public List<PathResult> readResults(int count) {
        if (resultBuffer == null) return Collections.emptyList();

        ByteBuffer data = ByteBuffer.allocate(count * PATH_RESULT_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        allocator.readback(resultBuffer, data);
        data.flip();

        List<PathResult> results = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int entityId = data.getInt();
            int pathLength = data.getInt();
            int status = 0;

            int[][] waypoints = new int[Math.min(pathLength, MAX_PATH_LENGTH)][3];
            for (int w = 0; w < MAX_PATH_LENGTH; w++) {
                int x = data.getInt(), y = data.getInt(), z = data.getInt();
                if (w < waypoints.length) {
                    waypoints[w] = new int[]{x, y, z};
                }
            }
            results.add(new PathResult(entityId, waypoints, pathLength, status));
        }
        return results;
    }

    // ── Data records ──

    public static class PathRequest {
        public final int entityId;
        public final int startX, startY, startZ;
        public final int goalX, goalY, goalZ;
        public int maxNodes = DEFAULT_MAX_NODES;

        public PathRequest(int entityId, int sx, int sy, int sz, int gx, int gy, int gz) {
            this.entityId = entityId;
            this.startX = sx; this.startY = sy; this.startZ = sz;
            this.goalX = gx; this.goalY = gy; this.goalZ = gz;
        }
    }

    public record PathResult(int entityId, int[][] waypoints, int pathLength, int status) {
        public boolean isFound() { return status == 0; }
        public boolean isPartial() { return status == 2; }
    }
}
