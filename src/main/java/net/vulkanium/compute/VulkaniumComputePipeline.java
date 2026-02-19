package net.vulkanium.compute;

import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import net.vulkanium.render.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vulkan compute pipeline manager — creates and caches compute pipelines.
 *
 * <p>A compute pipeline in Vulkan is simpler than a graphics pipeline: it consists
 * of a single compute shader stage, a pipeline layout (descriptor set layouts +
 * push constant ranges), and any specialization constants.</p>
 *
 * <h3>Pipeline Caching</h3>
 * <p>Pipelines are cached by a composite key of (shader module, layout, specialization).
 * The Vulkan pipeline cache object is used to accelerate pipeline creation on subsequent
 * launches.</p>
 *
 * <h3>Usage Pattern</h3>
 * <pre>
 *   // 1. Compile compute shader
 *   long module = shaderModuleManager.compileComputeProgram("frustum_cull", source);
 *
 *   // 2. Get or create pipeline
 *   long pipeline = computePipelinePool.getOrCreate(module, layout);
 *
 *   // 3. Bind and dispatch
 *   vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
 *   vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, ...);
 *   vkCmdDispatch(cmd, groupCountX, groupCountY, groupCountZ);
 * </pre>
 *
 * <h3>Built-in Compute Shaders (Phase 7)</h3>
 * <ul>
 *   <li>{@code frustum_cull.comp}: GPU-side frustum culling of chunk sections</li>
 *   <li>{@code translucent_sort.comp}: Per-frame translucent triangle sort by camera distance</li>
 *   <li>{@code indirect_build.comp}: Build indirect draw commands from visibility buffer</li>
 * </ul>
 *
 * <h3>Compute Platform (Phase 9)</h3>
 * <p>The compute pipeline infrastructure also serves as the foundation for the
 * GP-Computing platform that external mods (like C2ME) can use to offload
 * parallel workloads to the GPU. See {@link ComputeScheduler} for the task
 * submission API.</p>
 */
public class VulkaniumComputePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ComputePipeline");

    /**
     * Key for pipeline cache lookup.
     */
    private record PipelineKey(long shaderModule, long pipelineLayout, int specializationHash) {}

    private final VkDevice device;
    private final VulkaniumMemory memory;
    private final ShaderCompiler shaderCompiler;

    /** Pipeline cache — Vulkan object for disk-persistent pipeline caching */
    private long vkPipelineCache = 0;

    /** Created pipelines indexed by key */
    private final Map<PipelineKey, Long> pipelines = new ConcurrentHashMap<>();

    /** Default compute descriptor set layout (for built-in shaders) */
    private long defaultDescriptorSetLayout = 0;

    /** Default pipeline layout */
    private long defaultPipelineLayout = 0;

    public VulkaniumComputePipeline(VkDevice device, VulkaniumMemory memory,
                                    ShaderCompiler shaderCompiler) {
        this.device = device;
        this.memory = memory;
        this.shaderCompiler = shaderCompiler;
    }

    /**
     * Initializes the compute pipeline system.
     *
     * @param cacheData Serialized pipeline cache from previous session (null for first run)
     */
    public void initialize(byte[] cacheData) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create Vulkan pipeline cache
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (cacheData != null) {
                java.nio.ByteBuffer cacheBuffer = stack.malloc(cacheData.length);
                cacheBuffer.put(cacheData).flip();
                cacheInfo.pInitialData(cacheBuffer);
            }

            LongBuffer pCache = stack.mallocLong(1);
            int result = VK12.vkCreatePipelineCache(device, cacheInfo, null, pCache);
            if (result == VK12.VK_SUCCESS) {
                vkPipelineCache = pCache.get(0);
            }

            // Create default descriptor set layout for built-in compute shaders
            createDefaultDescriptorSetLayout(stack);
            createDefaultPipelineLayout(stack);
        }

        LOGGER.info("Compute pipeline system initialized");
    }

    /**
     * Gets an existing compute pipeline or creates a new one.
     *
     * @param shaderModule   VkShaderModule for the compute shader
     * @param pipelineLayout VkPipelineLayout to use
     * @return VkPipeline handle
     */
    public long getOrCreate(long shaderModule, long pipelineLayout) {
        return getOrCreate(shaderModule, pipelineLayout, 0);
    }

    /**
     * Gets or creates a compute pipeline with specialization constants.
     *
     * @param shaderModule       VkShaderModule for the compute shader
     * @param pipelineLayout     VkPipelineLayout to use
     * @param specializationHash Hash of specialization constant values
     * @return VkPipeline handle
     */
    public long getOrCreate(long shaderModule, long pipelineLayout, int specializationHash) {
        PipelineKey key = new PipelineKey(shaderModule, pipelineLayout, specializationHash);
        return pipelines.computeIfAbsent(key, k -> createPipeline(shaderModule, pipelineLayout));
    }

    /**
     * Gets the default pipeline layout for built-in compute shaders.
     */
    public long getDefaultPipelineLayout() {
        return defaultPipelineLayout;
    }

    /**
     * Gets the default descriptor set layout for built-in compute shaders.
     */
    public long getDefaultDescriptorSetLayout() {
        return defaultDescriptorSetLayout;
    }

    /**
     * Creates a pipeline layout with custom descriptor set layouts.
     *
     * @param descriptorSetLayouts Array of VkDescriptorSetLayout handles
     * @param pushConstantSize     Size of push constants in bytes (0 if none)
     * @return VkPipelineLayout handle
     */
    public long createPipelineLayout(long[] descriptorSetLayouts, int pushConstantSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSetLayouts = stack.mallocLong(descriptorSetLayouts.length);
            for (long layout : descriptorSetLayouts) {
                pSetLayouts.put(layout);
            }
            pSetLayouts.flip();

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayouts);

            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(pushConstantSize);
                layoutInfo.pPushConstantRanges(pushConstants);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            int result = VK12.vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            if (result != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout failed: " + result);
            }

            return pLayout.get(0);
        }
    }

    /**
     * Creates a descriptor set layout from binding descriptions.
     *
     * @param bindings Array of (binding, descriptorType, descriptorCount, stageFlags)
     * @return VkDescriptorSetLayout handle
     */
    public long createDescriptorSetLayout(int[][] bindings) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer layoutBindings =
                    VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);

            for (int i = 0; i < bindings.length; i++) {
                layoutBindings.get(i)
                        .binding(bindings[i][0])
                        .descriptorType(bindings[i][1])
                        .descriptorCount(bindings[i][2])
                        .stageFlags(bindings[i][3]);
            }

            VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(layoutBindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = VK12.vkCreateDescriptorSetLayout(device, createInfo, null, pLayout);
            if (result != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout failed: " + result);
            }

            return pLayout.get(0);
        }
    }

    /**
     * Serializes the pipeline cache for disk storage.
     */
    public byte[] serializePipelineCache() {
        if (vkPipelineCache == 0) return null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Query cache size
            org.lwjgl.PointerBuffer pDataSize = stack.mallocPointer(1);
            VK12.vkGetPipelineCacheData(device, vkPipelineCache, pDataSize, (java.nio.ByteBuffer) null);

            long cacheSize = pDataSize.get(0);
            if (cacheSize == 0) return null;

            java.nio.ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc((int) cacheSize);
            try {
                VK12.vkGetPipelineCacheData(device, vkPipelineCache, pDataSize, buffer);
                byte[] data = new byte[(int) cacheSize];
                buffer.get(data);
                return data;
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(buffer);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal
    // ═══════════════════════════════════════════════════════════════

    private long createPipeline(long shaderModule, long pipelineLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer shaderStage =
                    VkPipelineShaderStageCreateInfo.calloc(1, stack)
                            .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                            .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shaderModule)
                            .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer createInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK12.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(shaderStage.get(0))
                            .layout(pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = VK12.vkCreateComputePipelines(device, vkPipelineCache, createInfo, null, pPipeline);
            if (result != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateComputePipelines failed: " + result);
            }

            LOGGER.debug("Compute pipeline created");
            return pPipeline.get(0);
        }
    }

    private void createDefaultDescriptorSetLayout(MemoryStack stack) {
        // Default layout for built-in compute shaders:
        // binding 0: UBO (VulkaniumUniforms)
        // binding 1: SSBO (input data — section AABBs, visibility, etc.)
        // binding 2: SSBO (output data — indirect draw commands, visibility results)
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
        bindings.get(0)
                .binding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
        bindings.get(1)
                .binding(1)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
        bindings.get(2)
                .binding(2)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);

        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);

        LongBuffer pLayout = stack.mallocLong(1);
        int result = VK12.vkCreateDescriptorSetLayout(device, createInfo, null, pLayout);
        if (result == VK12.VK_SUCCESS) {
            defaultDescriptorSetLayout = pLayout.get(0);
        }
    }

    private void createDefaultPipelineLayout(MemoryStack stack) {
        if (defaultDescriptorSetLayout == 0) return;

        // Push constant for dispatch parameters (workgroup count, etc.)
        VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                .offset(0)
                .size(64); // 16 floats for general purpose

        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(defaultDescriptorSetLayout))
                .pPushConstantRanges(pushConstants);

        LongBuffer pLayout = stack.mallocLong(1);
        int result = VK12.vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
        if (result == VK12.VK_SUCCESS) {
            defaultPipelineLayout = pLayout.get(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    public void destroy() {
        // Destroy all pipelines
        for (long pipeline : pipelines.values()) {
            if (pipeline != 0) {
                VK12.vkDestroyPipeline(device, pipeline, null);
            }
        }
        pipelines.clear();

        // Destroy layouts
        if (defaultPipelineLayout != 0) {
            VK12.vkDestroyPipelineLayout(device, defaultPipelineLayout, null);
            defaultPipelineLayout = 0;
        }
        if (defaultDescriptorSetLayout != 0) {
            VK12.vkDestroyDescriptorSetLayout(device, defaultDescriptorSetLayout, null);
            defaultDescriptorSetLayout = 0;
        }

        // Destroy pipeline cache
        if (vkPipelineCache != 0) {
            VK12.vkDestroyPipelineCache(device, vkPipelineCache, null);
            vkPipelineCache = 0;
        }

        LOGGER.info("Compute pipeline system destroyed");
    }
}
