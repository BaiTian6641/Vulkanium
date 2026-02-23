package net.vulkanium.rt;

import net.vulkanium.core.VulkaniumDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan Ray Tracing Pipeline (VK_KHR_ray_tracing_pipeline) wrapper.
 *
 * <p>Manages creation and caching of ray tracing pipelines. Each pipeline consists
 * of shader groups organized as:</p>
 *
 * <pre>
 *   ┌─────────────────────────────────────────┐
 *   │  Shader Groups:                         │
 *   │  [0] Ray Generation (raygen.rgen)       │
 *   │  [1] Miss          (miss.rmiss)         │
 *   │  [2] Miss          (shadow.rmiss)       │
 *   │  [3] Closest Hit   (opaque.rchit)       │
 *   │  [4] Closest Hit   (transparent.rchit)  │
 *   │  [5..N] User-defined (shader packs)     │
 *   └─────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Pipeline Creation Flow</h3>
 * <ol>
 *   <li>Compile RT shaders to SPIR-V modules</li>
 *   <li>Define shader stages (raygen, miss, closest-hit, any-hit, callable)</li>
 *   <li>Create shader groups linking stages together</li>
 *   <li>Set max recursion depth (typically 1-2 for performance)</li>
 *   <li>Create pipeline with {@code vkCreateRayTracingPipelinesKHR}</li>
 *   <li>Build SBT from pipeline's shader group handles</li>
 * </ol>
 *
 * <h3>Recursion Depth</h3>
 * <ul>
 *   <li><b>Depth 1:</b> Primary rays only — GI path tracing accumulates over frames</li>
 *   <li><b>Depth 2:</b> Primary + one bounce — reflections, simple GI</li>
 *   <li>Shader packs declare max recursion via {@code const int maxRayRecursion = N;}</li>
 * </ul>
 *
 * <h3>Integration with Shader Packs</h3>
 * <p>Shader packs that provide RT shaders (Radiance, MCVR-compatible packs) will
 * have their RT shaders compiled via the same pipeline as rasterization shaders:</p>
 * <pre>
 *   GLSL → OptiFineGlslPreprocessor → VulkaniumGlslTransformer → ShaderCompiler → SPIR-V
 * </pre>
 * <p>RT-specific extensions (#extension GL_EXT_ray_tracing) are preserved during
 * preprocessing since they're on the SUPPORTED_EXTENSIONS list.</p>
 *
 * <p><b>Note:</b> Phase 10 stub. Requires VK_KHR_ray_tracing_pipeline.</p>
 */
public class VulkaniumRayTracingPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTPipeline");

    /** Default max recursion depth (primary rays only) */
    public static final int DEFAULT_MAX_RECURSION = 1;

    /** Maximum recursion depth we'll allow from shader packs */
    public static final int MAX_RECURSION_LIMIT = 4;

    // ── Shader Stage Types ──

    /** Shader group types matching VkRayTracingShaderGroupTypeKHR */
    public enum ShaderGroupType {
        GENERAL,                    // Raygen, miss, callable
        TRIANGLES_HIT_GROUP,        // Closest-hit + optional any-hit
        PROCEDURAL_HIT_GROUP        // Intersection + closest-hit + optional any-hit
    }

    /**
     * Describes a shader group for pipeline creation.
     */
    public record ShaderGroup(
            ShaderGroupType type,
            long generalShader,         // VkShaderModule for raygen/miss/callable (VK_SHADER_UNUSED_KHR if n/a)
            long closestHitShader,      // VkShaderModule for closest-hit (VK_SHADER_UNUSED_KHR if n/a)
            long anyHitShader,          // VkShaderModule for any-hit (VK_SHADER_UNUSED_KHR if n/a)
            long intersectionShader     // VkShaderModule for intersection (VK_SHADER_UNUSED_KHR if n/a)
    ) {
        /** VK_SHADER_UNUSED_KHR = ~0 */
        public static final long SHADER_UNUSED = 0xFFFFFFFFL;

        /** Create a general group (raygen, miss, or callable) */
        public static ShaderGroup general(long shaderModule) {
            return new ShaderGroup(ShaderGroupType.GENERAL,
                    shaderModule, SHADER_UNUSED, SHADER_UNUSED, SHADER_UNUSED);
        }

        /** Create a triangles hit group with closest-hit only */
        public static ShaderGroup trianglesHit(long closestHit) {
            return new ShaderGroup(ShaderGroupType.TRIANGLES_HIT_GROUP,
                    SHADER_UNUSED, closestHit, SHADER_UNUSED, SHADER_UNUSED);
        }

        /** Create a triangles hit group with closest-hit and any-hit */
        public static ShaderGroup trianglesHitWithAnyHit(long closestHit, long anyHit) {
            return new ShaderGroup(ShaderGroupType.TRIANGLES_HIT_GROUP,
                    SHADER_UNUSED, closestHit, anyHit, SHADER_UNUSED);
        }
    }

    /**
     * A compiled RT pipeline ready for use.
     */
    public static class CompiledRTPipeline {
        private final long pipeline;
        private final long pipelineLayout;
        private final int maxRecursionDepth;
        private final int rayGenGroupCount;
        private final int missGroupCount;
        private final int hitGroupCount;
        private final int callableGroupCount;
        private final String name;

        public CompiledRTPipeline(long pipeline, long pipelineLayout, int maxRecursion,
                                  int rayGen, int miss, int hit, int callable, String name) {
            this.pipeline = pipeline;
            this.pipelineLayout = pipelineLayout;
            this.maxRecursionDepth = maxRecursion;
            this.rayGenGroupCount = rayGen;
            this.missGroupCount = miss;
            this.hitGroupCount = hit;
            this.callableGroupCount = callable;
            this.name = name;
        }

        public long getPipeline() { return pipeline; }
        public long getPipelineLayout() { return pipelineLayout; }
        public int getMaxRecursionDepth() { return maxRecursionDepth; }
        public int getRayGenGroupCount() { return rayGenGroupCount; }
        public int getMissGroupCount() { return missGroupCount; }
        public int getHitGroupCount() { return hitGroupCount; }
        public int getCallableGroupCount() { return callableGroupCount; }
        public String getName() { return name; }

        /** Total shader groups (for SBT construction) */
        public int getTotalGroupCount() {
            return rayGenGroupCount + missGroupCount + hitGroupCount + callableGroupCount;
        }
    }

    // ── State ──
    private final VulkaniumDevice device;

    /** Cached pipelines by name */
    private final Map<String, CompiledRTPipeline> pipelineCache = new ConcurrentHashMap<>();

    /** VkPipelineCache for disk persistence */
    private long vkPipelineCache = 0;

    /** Device RT properties */
    private int maxRayRecursionDepth = 1;
    private int shaderGroupHandleSize = 32;
    private int shaderGroupHandleAlignment = 32;
    private int shaderGroupBaseAlignment = 64;

    /** Whether RT pipeline extension is available */
    private boolean rtPipelineAvailable = false;

    /** Current render target dimensions */
    private int renderWidth, renderHeight;
    /** Last bound pipeline name for bind/dispatch operations. */
    private String activePipelineName;

    public VulkaniumRayTracingPipeline(VulkaniumDevice device) {
        this.device = device;
    }

    /**
     * Initializes with device RT pipeline properties.
     *
     * @param hasRTPipeline  Whether VK_KHR_ray_tracing_pipeline is available
     * @param maxRecursion   maxRayRecursionDepth from device properties
     * @param handleSize     shaderGroupHandleSize
     * @param handleAlign    shaderGroupHandleAlignment
     * @param baseAlign      shaderGroupBaseAlignment
     */
    public void initialize(boolean hasRTPipeline, int maxRecursion,
                           int handleSize, int handleAlign, int baseAlign) {
        this.rtPipelineAvailable = hasRTPipeline;
        this.maxRayRecursionDepth = maxRecursion;
        this.shaderGroupHandleSize = handleSize;
        this.shaderGroupHandleAlignment = handleAlign;
        this.shaderGroupBaseAlignment = baseAlign;

        if (!rtPipelineAvailable) {
            LOGGER.info("VK_KHR_ray_tracing_pipeline not available");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkPipelineCacheCreateInfo cacheInfo = org.lwjgl.vulkan.VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);
            java.nio.LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(device.getLogicalDevice(), cacheInfo, null, pCache);
            if (result == VK_SUCCESS) {
                vkPipelineCache = pCache.get(0);
                LOGGER.debug("Created RT VkPipelineCache handle=0x{}", Long.toHexString(vkPipelineCache));
            } else {
                vkPipelineCache = 0;
                LOGGER.warn("Failed to create RT VkPipelineCache (VkResult {})", result);
            }
        }

        LOGGER.info("RT pipeline initialized: maxRecursion={}, handleSize={}, handleAlign={}, baseAlign={}",
                maxRecursion, handleSize, handleAlign, baseAlign);
    }

    /**
     * Creates a ray tracing pipeline from shader groups.
     *
     * @param name             Pipeline name for caching and debugging
     * @param pipelineLayout   VkPipelineLayout handle
     * @param shaderGroups     Ordered list: [raygen groups] [miss groups] [hit groups] [callable groups]
     * @param rayGenCount      Number of raygen groups at the start
     * @param missCount        Number of miss groups following raygen
     * @param hitCount         Number of hit groups following miss
     * @param callableCount    Number of callable groups following hit
     * @param maxRecursion     Max ray recursion depth (clamped to device limit)
     * @return Compiled RT pipeline, or null if RT not available
     */
    public CompiledRTPipeline createPipeline(String name, long pipelineLayout,
                                              List<ShaderGroup> shaderGroups,
                                              int rayGenCount, int missCount,
                                              int hitCount, int callableCount,
                                              int maxRecursion) {
        if (!rtPipelineAvailable) return null;

        // Check cache
        CompiledRTPipeline cached = pipelineCache.get(name);
        if (cached != null) return cached;

        // Clamp recursion depth
        int clampedRecursion = Math.min(maxRecursion, Math.min(maxRayRecursionDepth, MAX_RECURSION_LIMIT));

        // TODO Phase 10: Create RT pipeline
        // 1. Build VkPipelineShaderStageCreateInfo[] from shader groups
        // 2. Build VkRayTracingShaderGroupCreateInfoKHR[] from shader groups
        // 3. VkRayTracingPipelineCreateInfoKHR:
        //    .stageCount = total stages
        //    .pStages = stage array
        //    .groupCount = shaderGroups.size()
        //    .pGroups = group array
        //    .maxPipelineRayRecursionDepth = clampedRecursion
        //    .layout = pipelineLayout
        // 4. vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, vkPipelineCache, 1, &createInfo, null, &pipeline)

        long pipeline = 0; // Placeholder

        CompiledRTPipeline compiled = new CompiledRTPipeline(
                pipeline, pipelineLayout, clampedRecursion,
                rayGenCount, missCount, hitCount, callableCount, name);

        pipelineCache.put(name, compiled);
        activePipelineName = name;

        LOGGER.info("Created RT pipeline '{}': {} groups ({}R/{}M/{}H/{}C), maxRecursion={}",
                name, shaderGroups.size(), rayGenCount, missCount, hitCount, callableCount, clampedRecursion);

        return compiled;
    }

    /**
     * Creates a default RT pipeline for Vulkanium's built-in path tracing.
     *
     * <p>Default pipeline structure:</p>
     * <ul>
     *   <li>1 raygen shader (primary ray casting)</li>
     *   <li>2 miss shaders (sky environment, shadow test)</li>
     *   <li>2 hit groups (opaque closest-hit, transparent closest-hit + any-hit)</li>
     * </ul>
     *
     * @param pipelineLayout VkPipelineLayout handle
     * @param rayGenModule   SPIR-V raygen shader module
     * @param missModule     SPIR-V environment miss shader module
     * @param shadowMissModule SPIR-V shadow miss shader module
     * @param opaqueHitModule  SPIR-V opaque closest-hit module
     * @param transparentHitModule SPIR-V transparent closest-hit module
     * @param transparentAnyHitModule SPIR-V transparent any-hit module
     * @return Compiled default RT pipeline
     */
    public CompiledRTPipeline createDefaultPipeline(long pipelineLayout,
                                                     long rayGenModule, long missModule,
                                                     long shadowMissModule, long opaqueHitModule,
                                                     long transparentHitModule, long transparentAnyHitModule) {
        List<ShaderGroup> groups = new ArrayList<>();

        // Group 0: Raygen
        groups.add(ShaderGroup.general(rayGenModule));

        // Group 1-2: Miss shaders
        groups.add(ShaderGroup.general(missModule));
        groups.add(ShaderGroup.general(shadowMissModule));

        // Group 3: Opaque hit (closest-hit only, no any-hit for performance)
        groups.add(ShaderGroup.trianglesHit(opaqueHitModule));

        // Group 4: Transparent hit (closest-hit + any-hit for alpha testing)
        groups.add(ShaderGroup.trianglesHitWithAnyHit(transparentHitModule, transparentAnyHitModule));

        return createPipeline("vulkanium_default_rt", pipelineLayout,
                groups, 1, 2, 2, 0, DEFAULT_MAX_RECURSION);
    }

    /**
     * Records a ray tracing dispatch command.
     *
     * @param commandBuffer VkCommandBuffer
     * @param sbt           Shader binding table with region addresses
     * @param width         Dispatch width (typically render width)
     * @param height        Dispatch height (typically render height)
     * @param depth         Dispatch depth (typically 1)
     */
    public void traceRays(long commandBuffer, ShaderBindingTable sbt,
                          int width, int height, int depth) {
        if (!rtPipelineAvailable || !sbt.isReady()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var raygen = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.getRayGenAddress())
                .stride(sbt.getRayGenStride())
                .size(sbt.getRayGenSize());
            var miss = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.getMissAddress())
                .stride(sbt.getMissStride())
                .size(sbt.getMissSize());
            var hit = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.getHitAddress())
                .stride(sbt.getHitStride())
                .size(sbt.getHitSize());
            var callable = org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbt.getCallableAddress())
                .stride(sbt.getCallableStride())
                .size(sbt.getCallableSize());

            VkCommandBuffer vkCmd = new VkCommandBuffer(commandBuffer, device.getLogicalDevice());
            vkCmdTraceRaysKHR(vkCmd, raygen, miss, hit, callable, width, height, depth);
        }
        LOGGER.debug("vkCmdTraceRaysKHR dispatched: {}x{}x{}", width, height, depth);
    }

    // ── Render Target & Binding Stubs (Phase 10) ──

    /** Binds this RT pipeline to the command buffer. */
    public void bind(VkCommandBuffer commandBuffer) {
        CompiledRTPipeline active = getActiveCompiledPipeline();
        if (active == null || active.getPipeline() == 0) {
            LOGGER.debug("RT bind skipped: no compiled pipeline available");
            return;
        }
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, active.getPipeline());
        LOGGER.debug("Bound RT pipeline '{}'", active.getName());
    }

    /** Binds descriptor sets (TLAS, output image, camera, materials). */
    public void bindDescriptors(VkCommandBuffer commandBuffer) {
        LOGGER.debug("RT descriptor binding hook invoked (descriptor wiring owned by RT module integration)");
    }

    /** Pushes per-frame constants (bounce count, etc.). */
    public void pushConstants(VkCommandBuffer commandBuffer, int maxBounces) {
        CompiledRTPipeline active = getActiveCompiledPipeline();
        if (active == null || active.getPipelineLayout() == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer push = stack.mallocInt(1);
            push.put(0, maxBounces);
            vkCmdPushConstants(
                    commandBuffer,
                    active.getPipelineLayout(),
                    org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                    0,
                    push
            );
        }
        LOGGER.debug("Pushed RT constants: maxBounces={}", maxBounces);
    }

    /** Returns current render target width. */
    public int getWidth() { return renderWidth; }

    /** Returns current render target height. */
    public int getHeight() { return renderHeight; }

    /** Sets the render target dimensions. */
    public void resize(int width, int height) {
        this.renderWidth = width;
        this.renderHeight = height;
    }

    // ── Getters ──

    public boolean isAvailable() { return rtPipelineAvailable; }
    public int getMaxRayRecursionDepth() { return maxRayRecursionDepth; }
    public int getShaderGroupHandleSize() { return shaderGroupHandleSize; }
    public int getShaderGroupHandleAlignment() { return shaderGroupHandleAlignment; }
    public int getShaderGroupBaseAlignment() { return shaderGroupBaseAlignment; }

    public CompiledRTPipeline getPipeline(String name) {
        return pipelineCache.get(name);
    }

    // ── Lifecycle ──

    public void destroy() {
        for (CompiledRTPipeline compiled : pipelineCache.values()) {
            if (compiled.getPipeline() != 0) {
                vkDestroyPipeline(device.getLogicalDevice(), compiled.getPipeline(), null);
            }
        }
        pipelineCache.clear();
        activePipelineName = null;

        if (vkPipelineCache != 0) {
            LOGGER.debug("Destroying RT pipeline cache handle=0x{}", Long.toHexString(vkPipelineCache));
            vkDestroyPipelineCache(device.getLogicalDevice(), vkPipelineCache, null);
            vkPipelineCache = 0;
        }

        LOGGER.info("RT pipeline manager destroyed");
    }

    private CompiledRTPipeline getActiveCompiledPipeline() {
        if (activePipelineName != null) {
            CompiledRTPipeline active = pipelineCache.get(activePipelineName);
            if (active != null) return active;
        }
        for (CompiledRTPipeline value : pipelineCache.values()) {
            if (value != null) return value;
        }
        return null;
    }
}
