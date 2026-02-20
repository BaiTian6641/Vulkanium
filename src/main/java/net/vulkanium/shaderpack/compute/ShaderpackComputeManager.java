package net.vulkanium.shaderpack.compute;

import net.vulkanium.compute.VulkaniumComputePipeline;
import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.render.shader.ShaderModuleManager;
import net.vulkanium.render.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages Vulkan compute pipelines for shaderpack compute shader dispatch.
 *
 * <p>Shaderpacks define compute shaders (e.g., {@code composite3.csh}, {@code deferred0.csh})
 * that execute before or independently of their associated fragment passes. This manager
 * creates the Vulkan compute pipelines, descriptor set layouts, and pipeline layouts
 * needed to dispatch these shaders via {@code vkCmdDispatch}.</p>
 *
 * <h3>Reference: Iris ComputeProgram</h3>
 * <p>Modeled after {@code net.irisshaders.iris.gl.program.ComputeProgram} from the
 * <a href="https://github.com/IrisShaders/Iris">Iris Shaders</a> project (LGPL-3.0).
 * Iris's ComputeProgram wraps an OpenGL compute program, stores local_size from
 * {@code GL_COMPUTE_WORK_GROUP_SIZE}, supports absolute/relative/indirect dispatch,
 * and manages per-program uniforms/samplers/images. This Vulkan implementation
 * mirrors that architecture with VkPipeline + descriptor sets.</p>
 *
 * <h3>Dispatch Model</h3>
 * <pre>
 *   For each fullscreen pass with associated .csh:
 *     1. Bind compute pipeline
 *     2. Bind descriptor sets (UBO + samplers + images + SSBOs)
 *     3. vkCmdDispatch(groupCountX, groupCountY, 1)
 *     4. Pipeline barrier (compute write → fragment read)
 *     5. Execute fragment pass (existing fullscreen triangle draw)
 * </pre>
 *
 * <h3>Work Group Calculation</h3>
 * <p>Reference: Iris ComputeProgram.getWorkGroups() (Iris Shaders, LGPL-3.0)</p>
 * <ul>
 *   <li><b>Absolute:</b> {@code const ivec3 workGroups = ivec3(x, y, z)} — fixed dispatch size</li>
 *   <li><b>Relative:</b> {@code const vec2 workGroupsRender = vec2(sx, sy)} —
 *       scaled by screen size: {@code ceil(ceil(width * sx) / localSizeX)}</li>
 *   <li><b>Default:</b> {@code ceil(width / localSizeX), ceil(height / localSizeY), 1}</li>
 * </ul>
 */
public class ShaderpackComputeManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShaderpackCompute");

    /** Maximum image bindings for compute shaders */
    public static final int MAX_STORAGE_IMAGES = 8;

    /** Maximum SSBO bindings for compute shaders */
    public static final int MAX_SSBOS = 16;

    /** Maximum sampler bindings for compute shaders.
     *  Must be >= the highest binding value in DEFAULT_SAMPLER_BINDINGS + 4 extra
     *  for auto-assigned unbound samplers.  The +1 offset applied by the GLSL
     *  transformer means SPIR-V sampler bindings span [1 .. MAX_SAMPLERS]. */
    public static final int MAX_SAMPLERS = 32;

    // ── Vulkan Objects ──

    /** Descriptor set layout for shaderpack compute shaders */
    private long computeDescriptorSetLayout = VK_NULL_HANDLE;

    /** Pipeline layout for shaderpack compute shaders */
    private long computePipelineLayout = VK_NULL_HANDLE;

    /** Per-frame descriptor pools for compute descriptor sets */
    private long[] computeDescriptorPools = new long[0];

    /** Number of frames-in-flight used to size per-frame pools */
    private int framesInFlight = 3;

    /** Created compute pipelines: program name → VkPipeline */
    private final Map<String, Long> computePipelines = new LinkedHashMap<>();

    /** Compute program info: program name → ComputeInfo */
    private final Map<String, ComputeProgramInfo> computePrograms = new LinkedHashMap<>();

    /** Allocated descriptor sets for compute */
    private long[] computeDescriptorSets;

    // ── State ──
    private boolean initialized = false;
    private int screenWidth;
    private int screenHeight;
    /** Tracks first dispatch per program for one-time INFO-level logging */
    private final Set<String> firstDispatchLogged = new HashSet<>();

    /**
     * Information about a compiled compute program.
     *
     * <p>Reference: Iris ComputeProgram stores localSize[3], absoluteWorkGroups,
     * relativeWorkGroups, and indirectPointer. We mirror this structure for
     * Vulkan dispatch.</p>
     */
    public static class ComputeProgramInfo {
        public final String name;
        public final long shaderModule;
        public long pipeline;

        /** Local work group size from shader's layout(local_size_x=...) */
        public int localSizeX = 8;
        public int localSizeY = 8;
        public int localSizeZ = 1;

        /** Absolute work group counts (from const ivec3 workGroups) */
        public int absoluteGroupsX = -1;
        public int absoluteGroupsY = -1;
        public int absoluteGroupsZ = 1;

        /** Relative work group scale (from const vec2 workGroupsRender) */
        public float relativeScaleX = -1.0f;
        public float relativeScaleY = -1.0f;

        /** Whether this is a compute-only pass (no associated fragment shader) */
        public boolean computeOnly = false;

        public ComputeProgramInfo(String name, long shaderModule) {
            this.name = name;
            this.shaderModule = shaderModule;
        }

        /**
         * Calculates the dispatch work group counts for the given screen dimensions.
         *
         * <p>Reference: Iris ComputeProgram.getWorkGroups() (Iris Shaders, LGPL-3.0)
         * — supports absolute, relative (scaled by screen size), and default
         * (screen size / local_size) dispatch modes.</p>
         *
         * @param screenWidth  Current render width
         * @param screenHeight Current render height
         * @return {groupCountX, groupCountY, groupCountZ}
         */
        public int[] getWorkGroups(int screenWidth, int screenHeight) {
            if (absoluteGroupsX > 0) {
                // Absolute: fixed dispatch size from shader const directive
                return new int[]{absoluteGroupsX, absoluteGroupsY, absoluteGroupsZ};
            }

            if (relativeScaleX > 0) {
                // Relative: scale by screen dimensions, then divide by local_size
                // Reference: Iris ComputeProgram — ceil(ceil(width * scale) / localSize)
                int gx = (int) Math.ceil(Math.ceil(screenWidth * relativeScaleX) / localSizeX);
                int gy = (int) Math.ceil(Math.ceil(screenHeight * relativeScaleY) / localSizeY);
                return new int[]{Math.max(1, gx), Math.max(1, gy), 1};
            }

            // Default: screen divided by local_size
            int gx = (int) Math.ceil((double) screenWidth / localSizeX);
            int gy = (int) Math.ceil((double) screenHeight / localSizeY);
            return new int[]{Math.max(1, gx), Math.max(1, gy), 1};
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initializes the compute manager — creates descriptor set layout, pipeline
     * layout, and descriptor pool for shaderpack compute shaders.
     *
     * <p>The descriptor set layout mirrors the graphics pipeline's set 0 (UBO)
     * and set 1 (samplers), plus additional bindings for storage images
     * ({@code colorimgN}) and SSBOs ({@code bufferObject.N}).</p>
     *
     * <p>Layout bindings for shaderpack compute:</p>
     * <pre>
     *   Binding 0:      Uniform Buffer (shaderpack UBO — same as graphics set 0)
     *   Binding 1-32:   Combined Image Samplers (colortex, depthtex, shadowtex, etc.)
     *   Binding 33-40:  Storage Images (colorimg0 through colorimg7)
     *   Binding 41-56:  Storage Buffers (SSBO indices 0-15)
     * </pre>
     */
    public void initialize() {
        if (initialized) return;

        VkDevice device = VulkaniumDevice.getGlobalDevice();

        createComputeDescriptorSetLayout(device);
        createComputePipelineLayout(device);
        int configuredFrames = 3;
        try {
            if (net.vulkanium.Vulkanium.getConfig() != null) {
                configuredFrames = Math.max(1, net.vulkanium.Vulkanium.getConfig().getFramesInFlight());
            }
        } catch (Exception ignored) {
        }
        this.framesInFlight = configuredFrames;
        createComputeDescriptorPools(device, configuredFrames);

        initialized = true;
        LOGGER.info("Shaderpack compute manager initialized");
    }

    private void createComputeDescriptorSetLayout(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            int totalBindings = 1 + MAX_SAMPLERS + MAX_STORAGE_IMAGES + MAX_SSBOS;
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(totalBindings, stack);

            int b = 0;

            // Binding 0: Uniform buffer (shaderpack uniforms)
            bindings.get(b++)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Bindings 1-16: Combined image samplers (colortexN, depthtexN, etc.)
            for (int i = 0; i < MAX_SAMPLERS; i++) {
                bindings.get(b++)
                        .binding(1 + i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            // Bindings 17-24: Storage images (colorimgN for image load/store)
            for (int i = 0; i < MAX_STORAGE_IMAGES; i++) {
                bindings.get(b++)
                        .binding(1 + MAX_SAMPLERS + i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            // Bindings 25-40: Storage buffers (SSBOs)
            for (int i = 0; i < MAX_SSBOS; i++) {
                bindings.get(b++)
                        .binding(1 + MAX_SAMPLERS + MAX_STORAGE_IMAGES + i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(device, ci, null, pLayout);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute descriptor set layout: " + result);
            }
            computeDescriptorSetLayout = pLayout.get(0);
        }
    }

    private void createComputePipelineLayout(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(computeDescriptorSetLayout));

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(device, ci, null, pLayout);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute pipeline layout: " + result);
            }
            computePipelineLayout = pLayout.get(0);
        }
    }

        private void createComputeDescriptorPools(VkDevice device, int framesInFlight) {
        try (MemoryStack stack = stackPush()) {
            final int maxSetsPerFrame = 512;
            this.computeDescriptorPools = new long[Math.max(1, framesInFlight)];

            for (int frame = 0; frame < this.computeDescriptorPools.length; frame++) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
            poolSizes.get(0)
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                .descriptorCount(maxSetsPerFrame);
            poolSizes.get(1)
                .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(maxSetsPerFrame * MAX_SAMPLERS);
            poolSizes.get(2)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(maxSetsPerFrame * MAX_STORAGE_IMAGES);
            poolSizes.get(3)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(maxSetsPerFrame * MAX_SSBOS);

            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .flags(0)
                .maxSets(maxSetsPerFrame)
                .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(device, ci, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute descriptor pool (frame " + frame + "): " + result);
            }
            this.computeDescriptorPools[frame] = pPool.get(0);
            }

            LOGGER.info("Shaderpack compute descriptor pools created: {} pools × {} sets/frame",
                this.computeDescriptorPools.length, maxSetsPerFrame);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Compute Pipeline Creation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Registers a compiled compute shader module and creates its VkPipeline.
     *
     * @param programName  Program name (e.g. "composite3", "deferred0")
     * @param shaderModule VkShaderModule handle from ShaderModuleManager
     * @param glslSource   Raw GLSL compute source — used to parse local_size and
     *                     workGroups/workGroupsRender directives
     * @return The ComputeProgramInfo, or null on failure
     */
    public ComputeProgramInfo registerComputeProgram(String programName, long shaderModule,
                                                      String glslSource) {
        if (!initialized) {
            LOGGER.warn("Cannot register compute program '{}' — manager not initialized", programName);
            return null;
        }
        if (shaderModule == VK_NULL_HANDLE) return null;

        VkDevice device = VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .layout(computePipelineLayout);
            ci.stage()
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateComputePipelines(device, VK_NULL_HANDLE, ci, null, pPipeline);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to create compute pipeline for '{}': {}", programName, result);
                return null;
            }

            long pipeline = pPipeline.get(0);
            ComputeProgramInfo info = new ComputeProgramInfo(programName, shaderModule);
            info.pipeline = pipeline;

            // Parse local_size and work group directives from GLSL source
            // Reference: Iris ComputeProgram + ComputeDirectiveParser (LGPL-3.0)
            if (glslSource != null) {
                parseLocalSize(info, glslSource);
                parseWorkGroupDirectives(info, glslSource);
            }

            computePipelines.put(programName, pipeline);
            computePrograms.put(programName, info);

            LOGGER.info("[COMPUTE] Pipeline created for '{}' (0x{}) — localSize=({},{},{}), " +
                    "workGroups={}, relativeScale=({},{})",
                    programName, Long.toHexString(pipeline),
                    info.localSizeX, info.localSizeY, info.localSizeZ,
                    info.absoluteGroupsX > 0
                            ? "absolute(" + info.absoluteGroupsX + "," + info.absoluteGroupsY + "," + info.absoluteGroupsZ + ")"
                            : (info.relativeScaleX > 0
                                    ? "relative(" + info.relativeScaleX + "," + info.relativeScaleY + ")"
                                    : "default(screen/localSize)"),
                    info.relativeScaleX, info.relativeScaleY);
            return info;
        }
    }

    /**
     * Backward-compatible overload without GLSL source (uses default local_size=8).
     */
    public ComputeProgramInfo registerComputeProgram(String programName, long shaderModule) {
        return registerComputeProgram(programName, shaderModule, null);
    }

    /**
     * Allocates a descriptor set for a compute pass.
     */
    public long allocateComputeDescriptorSet(int frameIndex) {
        if (computeDescriptorPools == null || computeDescriptorPools.length == 0) return VK_NULL_HANDLE;

        int idx = Math.floorMod(frameIndex, computeDescriptorPools.length);
        long descriptorPool = computeDescriptorPools[idx];
        if (descriptorPool == VK_NULL_HANDLE) return VK_NULL_HANDLE;

        VkDevice device = VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(computeDescriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, allocInfo, pSet);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to allocate compute descriptor set (frame={} pool=0x{}): {}",
                        idx, Long.toHexString(descriptorPool), result);
                return VK_NULL_HANDLE;
            }
            return pSet.get(0);
        }
    }

    /**
     * Resets the descriptor pool for the current frame index.
     *
     * <p>This is safe when called at frame begin after waiting for the frame fence.
     * It reclaims all descriptor sets allocated from that frame's pool in prior uses
     * of the same frame slot.</p>
     */
    public void resetDescriptorPoolForFrame(int frameIndex) {
        if (!initialized || computeDescriptorPools == null || computeDescriptorPools.length == 0) return;

        int idx = Math.floorMod(frameIndex, computeDescriptorPools.length);
        long pool = computeDescriptorPools[idx];
        if (pool == VK_NULL_HANDLE) return;

        VkDevice device = VulkaniumDevice.getGlobalDevice();
        int result = vkResetDescriptorPool(device, pool, 0);
        if (result != VK_SUCCESS) {
            LOGGER.warn("Failed to reset compute descriptor pool for frame {}: {}", idx, result);
        }
    }

    /**
     * Updates a compute descriptor set with UBO, sampler, storage image, and SSBO bindings.
     *
     * @param descriptorSet The descriptor set to update
     * @param uniformBuffer UBO handle (binding 0)
     * @param uniformOffset UBO offset
     * @param uniformSize   UBO size
     * @param samplerViews  Image views for sampler bindings (may be null)
     * @param samplerSamplers Sampler handles for sampler bindings (may be null)
     * @param storageImageViews Image views for storage image bindings (may be null) — must be GENERAL layout
     * @param ssboBuffers   Buffer handles for SSBO bindings (may be null)
     * @param ssboSizes     Sizes for SSBO bindings (may be null)
     */
    public void updateComputeDescriptorSet(long descriptorSet,
                                            long uniformBuffer, long uniformOffset, long uniformSize,
                                            long[] samplerViews, long[] samplerSamplers,
                                            long[] storageImageViews,
                                            long[] ssboBuffers, long[] ssboSizes) {
        VkDevice device = VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            List<VkWriteDescriptorSet> writes = new ArrayList<>();

            // UBO (binding 0)
            if (uniformBuffer != VK_NULL_HANDLE) {
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(uniformBuffer)
                        .offset(uniformOffset)
                        .range(uniformSize);

                VkWriteDescriptorSet write = VkWriteDescriptorSet.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                        .pBufferInfo(bufferInfo);
                writes.add(write);
            }

            // Samplers (bindings 1-16)
            if (samplerViews != null && samplerSamplers != null) {
                for (int i = 0; i < Math.min(samplerViews.length, MAX_SAMPLERS); i++) {
                    if (samplerViews[i] == VK_NULL_HANDLE) continue;

                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                            .imageView(samplerViews[i])
                            .sampler(samplerSamplers[i]);

                    VkWriteDescriptorSet write = VkWriteDescriptorSet.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                            .dstSet(descriptorSet)
                            .dstBinding(1 + i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(imageInfo);
                    writes.add(write);
                }
            }

            // Storage images (bindings 17-24)
            if (storageImageViews != null) {
                for (int i = 0; i < Math.min(storageImageViews.length, MAX_STORAGE_IMAGES); i++) {
                    if (storageImageViews[i] == VK_NULL_HANDLE) continue;

                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .imageView(storageImageViews[i])
                            .sampler(VK_NULL_HANDLE);

                    VkWriteDescriptorSet write = VkWriteDescriptorSet.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                            .dstSet(descriptorSet)
                            .dstBinding(1 + MAX_SAMPLERS + i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(imageInfo);
                    writes.add(write);
                }
            }

            // SSBOs (bindings 25-40)
            if (ssboBuffers != null && ssboSizes != null) {
                for (int i = 0; i < Math.min(ssboBuffers.length, MAX_SSBOS); i++) {
                    if (ssboBuffers[i] == VK_NULL_HANDLE) continue;

                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(ssboBuffers[i])
                            .offset(0)
                            .range(ssboSizes[i]);

                    VkWriteDescriptorSet write = VkWriteDescriptorSet.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                            .dstSet(descriptorSet)
                            .dstBinding(1 + MAX_SAMPLERS + MAX_STORAGE_IMAGES + i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(bufferInfo);
                    writes.add(write);
                }
            }

            if (!writes.isEmpty()) {
                VkWriteDescriptorSet.Buffer writeArray =
                        VkWriteDescriptorSet.calloc(writes.size(), stack);
                for (int i = 0; i < writes.size(); i++) {
                    writeArray.get(i).set(writes.get(i));
                }
                vkUpdateDescriptorSets(device, writeArray, null);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Dispatch
    // ═══════════════════════════════════════════════════════════════

    /**
     * Dispatches a compute shader for the given program.
     *
     * <p>Binds the compute pipeline, binds descriptor sets, and calls
     * {@code vkCmdDispatch} with work group counts calculated from the
     * screen dimensions and shader local_size.</p>
     *
     * <p>Reference: Iris CompositeRenderer.renderAll() (Iris Shaders, LGPL-3.0)
     * — iterates passes, calls computeProgram.use() + computeProgram.dispatch(w,h),
     * then issues memory barriers before the fragment pass.</p>
     *
     * @param cmd           Active VkCommandBuffer
     * @param programName   Program name (e.g. "composite3")
     * @param descriptorSet Bound descriptor set for this dispatch
     * @param uboOffset     Dynamic UBO offset
     */
    public void dispatch(VkCommandBuffer cmd, String programName,
                         long descriptorSet, int uboOffset) {
        ComputeProgramInfo info = computePrograms.get(programName);
        if (info == null || info.pipeline == VK_NULL_HANDLE) {
            LOGGER.warn("[COMPUTE] Cannot dispatch '{}' — no pipeline", programName);
            return;
        }

        int[] groups = info.getWorkGroups(screenWidth, screenHeight);

        // Validate dispatch parameters
        if (groups[0] <= 0 || groups[1] <= 0 || groups[2] <= 0) {
            LOGGER.error("[COMPUTE] Invalid work groups for '{}': ({},{},{}) — screen={}x{}, " +
                    "localSize=({},{},{}), absolute=({},{},{}), relativeScale=({},{})",
                    programName, groups[0], groups[1], groups[2],
                    screenWidth, screenHeight,
                    info.localSizeX, info.localSizeY, info.localSizeZ,
                    info.absoluteGroupsX, info.absoluteGroupsY, info.absoluteGroupsZ,
                    info.relativeScaleX, info.relativeScaleY);
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // Bind compute pipeline
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, info.pipeline);

            // Bind descriptor set with dynamic UBO offset
            if (descriptorSet != VK_NULL_HANDLE) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                        computePipelineLayout, 0,
                        stack.longs(descriptorSet),
                        stack.ints(uboOffset));
            }

            // Dispatch compute
            vkCmdDispatch(cmd, groups[0], groups[1], groups[2]);

            // Log first dispatch per program at INFO level for debugging
            if (firstDispatchLogged.add(programName)) {
                LOGGER.info("[COMPUTE] First dispatch '{}' — groups=({},{},{}), screen={}x{}, " +
                        "localSize=({},{},{}), mode={}, uboOffset={}",
                        programName, groups[0], groups[1], groups[2],
                        screenWidth, screenHeight,
                        info.localSizeX, info.localSizeY, info.localSizeZ,
                        info.absoluteGroupsX > 0 ? "absolute" :
                                (info.relativeScaleX > 0 ? "relative" : "default"),
                        uboOffset);
            }

            LOGGER.debug("[COMPUTE] Dispatched '{}' — groups=({},{},{}), screen={}x{}, " +
                    "localSize=({},{},{}), uboOffset={}, descSet=0x{}",
                    programName, groups[0], groups[1], groups[2],
                    screenWidth, screenHeight,
                    info.localSizeX, info.localSizeY, info.localSizeZ,
                    uboOffset, Long.toHexString(descriptorSet));
        }
    }

    /**
     * Records a pipeline barrier after compute dispatch.
     *
     * <p>Reference: Iris CompositeRenderer (Iris Shaders, LGPL-3.0) uses
     * {@code glMemoryBarrier(IMAGE_ACCESS | TEXTURE_FETCH | SHADER_STORAGE)}
     * after each compute dispatch. The Vulkan equivalent must make writes
     * visible to both subsequent compute passes and fragment sampling/reads.</p>
     *
     * @param cmd Active VkCommandBuffer
     */
    public void recordComputeToFragmentBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Accessors
    // ═══════════════════════════════════════════════════════════════

    public boolean isInitialized() { return initialized; }

    public long getComputePipelineLayout() { return computePipelineLayout; }

    public long getComputeDescriptorSetLayout() { return computeDescriptorSetLayout; }

    public ComputeProgramInfo getComputeProgram(String name) {
        return computePrograms.get(name);
    }

    public boolean hasComputeProgram(String name) {
        return computePrograms.containsKey(name);
    }

    public int getComputeProgramCount() {
        return computePrograms.size();
    }

    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    // ═══════════════════════════════════════════════════════════════
    //  GLSL Source Parsing
    // ═══════════════════════════════════════════════════════════════

    /** Pattern to match layout(local_size_x=N, local_size_y=M, local_size_z=K) in */
    private static final Pattern LOCAL_SIZE_PATTERN = Pattern.compile(
            "layout\\s*\\(\\s*local_size_x\\s*=\\s*(\\d+)" +
            "(?:\\s*,\\s*local_size_y\\s*=\\s*(\\d+))?" +
            "(?:\\s*,\\s*local_size_z\\s*=\\s*(\\d+))?\\s*\\)\\s*in\\s*;",
            Pattern.MULTILINE);

    /** Pattern to match const ivec3 workGroups = ivec3(x, y, z); */
    private static final Pattern WORK_GROUPS_PATTERN = Pattern.compile(
            "const\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*;",
            Pattern.MULTILINE);

    /** Pattern to match const vec2 workGroupsRender = vec2(sx, sy); */
    private static final Pattern WORK_GROUPS_RENDER_PATTERN = Pattern.compile(
            "const\\s+vec2\\s+workGroupsRender\\s*=\\s*vec2\\s*\\(\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*\\)\\s*;",
            Pattern.MULTILINE);

    /**
     * Parses {@code layout(local_size_x=N, local_size_y=M, local_size_z=K) in;}
     * from the GLSL compute source and applies to the info object.
     *
     * <p>Reference: Iris ComputeProgram queries {@code GL_COMPUTE_WORK_GROUP_SIZE}
     * from the driver after compilation. In Vulkan we must parse from source since
     * SPIR-V reflection is not available via LWJGL.</p>
     */
    private void parseLocalSize(ComputeProgramInfo info, String glslSource) {
        Matcher m = LOCAL_SIZE_PATTERN.matcher(glslSource);
        if (m.find()) {
            try {
                info.localSizeX = Integer.parseInt(m.group(1));
                if (m.group(2) != null) {
                    info.localSizeY = Integer.parseInt(m.group(2));
                } else {
                    info.localSizeY = 1;
                }
                if (m.group(3) != null) {
                    info.localSizeZ = Integer.parseInt(m.group(3));
                } else {
                    info.localSizeZ = 1;
                }
                LOGGER.debug("[COMPUTE] '{}' local_size parsed: ({}, {}, {})",
                        info.name, info.localSizeX, info.localSizeY, info.localSizeZ);
            } catch (NumberFormatException e) {
                LOGGER.warn("[COMPUTE] '{}' failed to parse local_size, using defaults (8,8,1): {}",
                        info.name, e.getMessage());
            }
        } else {
            LOGGER.warn("[COMPUTE] '{}' — no layout(local_size_x=...) found, using defaults (8,8,1)",
                    info.name);
        }
    }

    /**
     * Parses {@code const ivec3 workGroups} and {@code const vec2 workGroupsRender}
     * directives from GLSL compute source.
     *
     * <p>Reference: Iris ComputeDirectiveParser (Iris Shaders, LGPL-3.0) — parses
     * these same directives from ComputeSource to set absolute or relative dispatch
     * sizes.</p>
     */
    private void parseWorkGroupDirectives(ComputeProgramInfo info, String glslSource) {
        // Check absolute work groups first — takes priority over relative
        Matcher absM = WORK_GROUPS_PATTERN.matcher(glslSource);
        if (absM.find()) {
            try {
                info.absoluteGroupsX = Integer.parseInt(absM.group(1));
                info.absoluteGroupsY = Integer.parseInt(absM.group(2));
                info.absoluteGroupsZ = Integer.parseInt(absM.group(3));
                LOGGER.debug("[COMPUTE] '{}' absolute workGroups: ({}, {}, {})",
                        info.name, info.absoluteGroupsX, info.absoluteGroupsY, info.absoluteGroupsZ);
                return;
            } catch (NumberFormatException e) {
                LOGGER.warn("[COMPUTE] '{}' failed to parse workGroups directive: {}",
                        info.name, e.getMessage());
            }
        }

        // Check relative work groups
        Matcher relM = WORK_GROUPS_RENDER_PATTERN.matcher(glslSource);
        if (relM.find()) {
            try {
                info.relativeScaleX = Float.parseFloat(relM.group(1));
                info.relativeScaleY = Float.parseFloat(relM.group(2));
                LOGGER.debug("[COMPUTE] '{}' relative workGroupsRender: ({}, {})",
                        info.name, info.relativeScaleX, info.relativeScaleY);
            } catch (NumberFormatException e) {
                LOGGER.warn("[COMPUTE] '{}' failed to parse workGroupsRender directive: {}",
                        info.name, e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    public void destroy() {
        VkDevice device = VulkaniumDevice.getGlobalDevice();

        if (device == null) {
            computePipelines.clear();
            computePrograms.clear();
            firstDispatchLogged.clear();
            computeDescriptorPools = new long[0];
            computePipelineLayout = VK_NULL_HANDLE;
            computeDescriptorSetLayout = VK_NULL_HANDLE;
            initialized = false;
            return;
        }

        // Ensure GPU is idle before tearing down compute resources to avoid
        // driver-side crashes when pipelines are still referenced in flight.
        vkDeviceWaitIdle(device);

        // Destroy compute pipelines (deduplicated by handle).
        Set<Long> destroyed = new HashSet<>();
        for (long pipeline : computePipelines.values()) {
            if (pipeline != VK_NULL_HANDLE && destroyed.add(pipeline)) {
                vkDestroyPipeline(device, pipeline, null);
            }
        }
        computePipelines.clear();
        computePrograms.clear();
        firstDispatchLogged.clear();

        // Destroy descriptor pools (each frees all allocated descriptor sets)
        if (computeDescriptorPools != null) {
            for (int i = 0; i < computeDescriptorPools.length; i++) {
                long pool = computeDescriptorPools[i];
                if (pool != VK_NULL_HANDLE) {
                    vkDestroyDescriptorPool(device, pool, null);
                }
            }
            computeDescriptorPools = new long[0];
        }

        // Destroy pipeline layout
        if (computePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, computePipelineLayout, null);
            computePipelineLayout = VK_NULL_HANDLE;
        }

        // Destroy descriptor set layout
        if (computeDescriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, computeDescriptorSetLayout, null);
            computeDescriptorSetLayout = VK_NULL_HANDLE;
        }

        initialized = false;
        LOGGER.info("Shaderpack compute manager destroyed");
    }
}
