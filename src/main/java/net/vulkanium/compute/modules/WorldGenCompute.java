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
 * GPU-accelerated world generation noise computation.
 *
 * <p>Offloads Perlin/Simplex noise generation and terrain density field computation
 * to the GPU. Inspired by C2ME's parallel chunk generation, but runs noise passes
 * on the massively parallel compute pipeline instead of CPU thread pools.</p>
 *
 * <h3>Supported Noise Functions</h3>
 * <ul>
 *   <li><b>Perlin noise</b> — Classic gradient noise with configurable octaves</li>
 *   <li><b>Simplex noise</b> — Improved gradient noise (fewer directional artifacts)</li>
 *   <li><b>Voronoi/Worley</b> — Cell noise for biome boundary variation</li>
 *   <li><b>Domain warping</b> — Composable noise distortion</li>
 * </ul>
 *
 * <h3>Usage Patterns</h3>
 * <ol>
 *   <li><b>2D Surface Noise</b> — Height map generation (16×16 per chunk column)</li>
 *   <li><b>3D Density Field</b> — Cave/ore density volumes (16×16×16 per section)</li>
 *   <li><b>Biome Blending</b> — Multi-octave noise for biome weight maps</li>
 * </ol>
 *
 * <h3>C2ME Integration</h3>
 * <p>When C2ME is present, Vulkanium can intercept noise generation requests
 * from C2ME's parallelized {@code NoiseChunkGenerator} and redirect them to
 * the GPU. The results are read back and injected into C2ME's pipeline.</p>
 *
 * <h3>Data Layout</h3>
 * <pre>
 *   struct NoiseParams {
 *       uint seed;
 *       uint octaves;
 *       float frequency;
 *       float amplitude;
 *       float lacunarity;     // frequency multiplier per octave
 *       float persistence;    // amplitude multiplier per octave
 *       ivec2 chunkPos;       // chunk X, Z
 *       uint noiseType;       // 0=perlin, 1=simplex, 2=voronoi
 *       uint dimensions;      // 2 or 3
 *   }; // 40 bytes
 * </pre>
 */
public class WorldGenCompute {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GPUWorldGen");

    private static final String NOISE_SHADER = "worldgen_noise";

    /** Maximum chunks to generate noise for per dispatch */
    private static final int MAX_CHUNKS_PER_DISPATCH = 64;

    /** 2D noise: 16×16 = 256 samples per chunk */
    private static final int SAMPLES_2D = 256;

    /** 3D noise: 16×16×16 = 4096 samples per section */
    private static final int SAMPLES_3D = 4096;

    /** Noise parameter struct size */
    private static final int NOISE_PARAMS_BYTES = 40;

    private static final int[] WORKGROUP_SIZE_2D = {16, 16, 1}; // 256 threads = 1 chunk column
    private static final int[] WORKGROUP_SIZE_3D = {8, 8, 4};   // 256 threads = 1/16 of section

    public enum NoiseType {
        PERLIN(0), SIMPLEX(1), VORONOI(2);

        final int id;
        NoiseType(int id) { this.id = id; }
    }

    private final ComputeAllocator allocator;

    private ComputeAllocator.BufferRegion paramsBuffer;
    private ComputeAllocator.BufferRegion outputBuffer;

    public WorldGenCompute(ComputeAllocator allocator) {
        this.allocator = allocator;
    }

    /**
     * Creates a 2D surface noise generation task (height maps).
     *
     * @param requests Noise generation requests per chunk column
     * @return A compute task for the scheduler
     */
    public ComputeTask createSurfaceNoiseTask(List<NoiseRequest> requests) {
        int count = Math.min(requests.size(), MAX_CHUNKS_PER_DISPATCH);
        uploadParams(requests, count);

        // Output: count × 256 floats (16×16 height values per chunk)
        outputBuffer = allocator.allocateReadback((long) count * SAMPLES_2D * 4);

        return new ComputeTask() {
            @Override public String getShaderSource() { return NOISE_SHADER; }
            @Override public int[] getWorkGroupSize() { return WORKGROUP_SIZE_2D; }
            @Override public int[] getDispatchSize() { return new int[]{count, 1, 1}; }
            @Override public Priority getPriority() { return Priority.LOW; }
            @Override public List<ComputeTask> getDependencies() { return Collections.emptyList(); }
        };
    }

    /**
     * Creates a 3D density field noise task (caves, ores).
     *
     * @param requests Noise requests per chunk section
     * @return A compute task for the scheduler
     */
    public ComputeTask createDensityFieldTask(List<NoiseRequest> requests) {
        int count = Math.min(requests.size(), MAX_CHUNKS_PER_DISPATCH);
        uploadParams(requests, count);

        // Output: count × 4096 floats per section
        outputBuffer = allocator.allocateReadback((long) count * SAMPLES_3D * 4);

        return new ComputeTask() {
            @Override public String getShaderSource() { return NOISE_SHADER; }
            @Override public int[] getWorkGroupSize() { return WORKGROUP_SIZE_3D; }
            @Override public int[] getDispatchSize() { return new int[]{count, 1, 16}; } // 16 vertical slices
            @Override public Priority getPriority() { return Priority.LOW; }
            @Override public List<ComputeTask> getDependencies() { return Collections.emptyList(); }
        };
    }

    /**
     * Reads back computed noise values.
     */
    public float[] readSurfaceNoise(int chunkIndex) {
        if (outputBuffer == null) return null;

        ByteBuffer data = ByteBuffer.allocate(SAMPLES_2D * 4).order(ByteOrder.LITTLE_ENDIAN);
        // Read from offset for this chunk
        ComputeAllocator.BufferRegion subRegion = new ComputeAllocator.BufferRegion(
                outputBuffer.buffer(),
                outputBuffer.offset() + (long) chunkIndex * SAMPLES_2D * 4,
                SAMPLES_2D * 4
        );
        allocator.readback(subRegion, data);
        data.flip();

        float[] result = new float[SAMPLES_2D];
        data.asFloatBuffer().get(result);
        return result;
    }

    private void uploadParams(List<NoiseRequest> requests, int count) {
        ByteBuffer params = ByteBuffer.allocate(count * NOISE_PARAMS_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < count; i++) {
            NoiseRequest req = requests.get(i);
            params.putInt((int) req.seed);
            params.putInt(req.octaves);
            params.putFloat(req.frequency);
            params.putFloat(req.amplitude);
            params.putFloat(req.lacunarity);
            params.putFloat(req.persistence);
            params.putInt(req.chunkX);
            params.putInt(req.chunkZ);
            params.putInt(req.noiseType.id);
            params.putInt(req.is3D ? 3 : 2);
        }
        params.flip();
        paramsBuffer = allocator.upload(params);
    }

    // ── Data types ──

    public static class NoiseRequest {
        public final long seed;
        public final int chunkX, chunkZ;
        public final NoiseType noiseType;
        public final boolean is3D;

        // Noise parameters
        public int octaves = 8;
        public float frequency = 0.005f;
        public float amplitude = 1.0f;
        public float lacunarity = 2.0f;
        public float persistence = 0.5f;

        public NoiseRequest(long seed, int chunkX, int chunkZ,
                            NoiseType type, boolean is3D) {
            this.seed = seed;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.noiseType = type;
            this.is3D = is3D;
        }
    }
}
