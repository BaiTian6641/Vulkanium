package net.vulkanium.rt;

import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import net.vulkanium.resource.SPIRVCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Top-level manager for the ray-tracing pipeline.
 *
 * <p>Orchestrates BLAS/TLAS construction, SBT management, RT pipeline creation,
 * and per-frame RT dispatch. This is the main entry point for all RT operations.</p>
 *
 * <h3>Per-Frame RT Flow</h3>
 * <ol>
 *   <li>Build/update dirty BLASes (from chunk mesh changes)</li>
 *   <li>Rebuild TLAS with current visible section instances</li>
 *   <li>Update SBT if materials changed</li>
 *   <li>Bind RT pipeline + descriptor sets</li>
 *   <li>Dispatch rays ({@code vkCmdTraceRaysKHR})</li>
 *   <li>Denoise output (SVGF compute passes)</li>
 *   <li>Composite into main render targets</li>
 * </ol>
 *
 * <h3>Integration with Raster Pipeline</h3>
 * <p>RT can replace specific render phases:</p>
 * <ul>
 *   <li>Shadow maps → RT shadow rays (Tier 1+)</li>
 *   <li>Screen-space reflections → RT reflections (Tier 2+)</li>
 *   <li>All lighting → Path-traced GI (Tier 3)</li>
 * </ul>
 *
 * <p>The raster pipeline remains active for passes that RT doesn't replace
 * (GUI, particles, etc.).</p>
 */
public class RTModuleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTManager");

    private final VulkaniumDevice vulkaniumDevice;
    private final VulkaniumMemory memory;

    // Sub-managers
    private final BLASManager blasManager;
    private final TLASBuilder tlasBuilder;
    private final ShaderBindingTable sbt;
    private final VulkaniumRayTracingPipeline rtPipeline;
    private final RTCapabilities capabilities;

    // Optional modules
    private SVGFDenoiser denoiser;
    private MaterialTable materialTable;

    // State
    private boolean enabled = false;
    private RTCapabilities.Tier activeTier = RTCapabilities.Tier.NONE;
    private int maxBounces = 1;
    private boolean denoiserEnabled = false;

    // Per-frame stats
    private int blasBuildsThisFrame;
    private long lastTraceTimeNs;

    // ── RT Pipeline Resources ──
    /** Descriptor set layout for RT pipeline: binding 0=TLAS, 1=output image, 2=camera UBO */
    private long descriptorSetLayout = 0;
    /** Pipeline layout wrapping descriptorSetLayout + push constants */
    private long pipelineLayout = 0;
    /** Descriptor pool */
    private long descriptorPool = 0;
    /** Descriptor set (single, updated each frame) */
    private long descriptorSet = 0;
    /** RT output image (RGBA16F, full screen resolution) */
    private long rtOutputImage = 0;
    private VulkaniumMemory.ImageAllocation rtOutputImageAlloc = null;
    private long rtOutputImageView = 0;
    /** Camera uniform buffer */
    private long cameraUBO = 0;
    private long cameraUBOAllocation = 0;
    private ByteBuffer cameraUBOMapped = null;
    /** Compiled VkShaderModule handles */
    private long rayGenModule = 0;
    private long skyMissModule = 0;
    private long shadowMissModule = 0;
    private long opaqueHitModule = 0;
    private long shadowAnyHitModule = 0;
    /** RT output dimensions */
    private int rtWidth = 0;
    private int rtHeight = 0;
    /** Whether the full RT pipeline has been successfully created */
    private boolean rtPipelineReady = false;
    /** Whether RT was actually dispatched this frame (has geometry + pipeline ready) */
    private boolean rtDispatchedThisFrame = false;

    public RTModuleManager(VulkaniumDevice device, VulkaniumMemory memory,
                            VulkaniumQueues queues, RTCapabilities capabilities) {
        this.vulkaniumDevice = device;
        this.memory = memory;
        this.capabilities = capabilities;

        this.blasManager = new BLASManager(memory, queues);
        this.tlasBuilder = new TLASBuilder(memory, queues);
        this.sbt = new ShaderBindingTable(memory);
        this.rtPipeline = new VulkaniumRayTracingPipeline(device);

        this.activeTier = capabilities.getTier();
        this.maxBounces = capabilities.getRecommendedMaxBounces();
    }

    /**
     * Initializes the RT pipeline if capabilities allow.
     */
    public void initialize() {
        if (!capabilities.isRTAvailable()) {
            LOGGER.info("Ray tracing not available on this GPU");
            return;
        }

        materialTable = new MaterialTable();
        materialTable.buildDefaultTable();
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getResourceManager() != null) {
                materialTable.applyLabPbrOverrides(mc.getResourceManager());
            }
        } catch (Throwable t) {
            LOGGER.debug("LabPBR material override pass skipped: {}", t.getMessage());
        }

        // Create RT pipeline and SBT
        rtPipeline.initialize(capabilities.hasRayTracingPipeline(),
                capabilities.getMaxRayRecursionDepth(),
                capabilities.getShaderGroupHandleSize(),
                capabilities.getShaderGroupHandleAlignment(),
                capabilities.getShaderGroupBaseAlignment());
        sbt.configure(capabilities.getShaderGroupHandleSize(),
                capabilities.getShaderGroupHandleAlignment(),
                capabilities.getShaderGroupBaseAlignment());
        blasManager.initialize(capabilities.hasAccelerationStructure());
        tlasBuilder.initialize();

        if (capabilities.shouldEnableDenoiser()) {
            denoiser = new SVGFDenoiser(vulkaniumDevice, memory);
            denoiser.initialize();
            denoiserEnabled = true;
        }

        // ── Compile RT shaders and create pipeline ──
        try {
            buildRTPipeline();
        } catch (Exception e) {
            LOGGER.error("Failed to create RT pipeline — RT dispatch will be disabled: {}", e.getMessage(), e);
            rtPipelineReady = false;
        }

        enabled = true;
        LOGGER.info("RT pipeline initialized: tier={}, maxBounces={}, denoiser={}, rtPipeline={}",
                activeTier.getName(), maxBounces, denoiserEnabled, rtPipelineReady);
    }

    /**
     * Compiles RT shaders, creates descriptor set layout, pipeline layout,
     * the default RT pipeline, SBT, camera UBO, and descriptor pool/set.
     * RT output image is created lazily on first resize().
     */
    private void buildRTPipeline() {
        VkDevice device = vulkaniumDevice.getLogicalDevice();

        // 1. Compile RT shaders
        SPIRVCompiler compiler = new SPIRVCompiler();
        compiler.initialize();
        try {
            rayGenModule = compileAndCreateModule(compiler, device,
                    "/assets/vulkanium/shaders/rt/world.rgen");
            skyMissModule = compileAndCreateModule(compiler, device,
                    "/assets/vulkanium/shaders/rt/sky.rmiss");
            shadowMissModule = compileAndCreateModule(compiler, device,
                    "/assets/vulkanium/shaders/rt/shadow.rmiss");
            opaqueHitModule = compileAndCreateModule(compiler, device,
                    "/assets/vulkanium/shaders/rt/world_solid.rchit");
            shadowAnyHitModule = compileAndCreateModule(compiler, device,
                    "/assets/vulkanium/shaders/rt/world_shadow.rahit");
        } finally {
            compiler.destroy();
        }

        LOGGER.info("RT shaders compiled: rgen=0x{}, miss=0x{}/{}, hit=0x{}, ahit=0x{}",
                Long.toHexString(rayGenModule), Long.toHexString(skyMissModule),
                Long.toHexString(shadowMissModule), Long.toHexString(opaqueHitModule),
                Long.toHexString(shadowAnyHitModule));

        // 2. Descriptor set layout:
        //   binding 0 = TLAS (acceleration structure)
        //   binding 1 = output image (storage image, RGBA16F)
        //   binding 2 = camera UBO
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            // TLAS
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
            // Output image
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            // Camera UBO
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                            | VK_SHADER_STAGE_MISS_BIT_KHR);

            var layoutCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            checkVk(vkCreateDescriptorSetLayout(device, layoutCI, null, pLayout));
            descriptorSetLayout = pLayout.get(0);

            // 3. Pipeline layout (descriptor set + push constant for maxBounces)
            var pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR)
                    .offset(0)
                    .size(4); // int maxBounces
            var pipelineLayoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            checkVk(vkCreatePipelineLayout(device, pipelineLayoutCI, null, pPipelineLayout));
            pipelineLayout = pPipelineLayout.get(0);
        }

        // 4. Create default RT pipeline
        var compiled = rtPipeline.createDefaultPipeline(
                pipelineLayout,
                rayGenModule, skyMissModule, shadowMissModule,
                opaqueHitModule,
                opaqueHitModule,  // transparent hit reuses opaque for now
                shadowAnyHitModule);
        if (compiled == null || compiled.getPipeline() == 0) {
            throw new RuntimeException("createDefaultPipeline returned null/zero pipeline");
        }

        // 5. Build SBT
        sbt.build(compiled.getPipeline(),
                compiled.getMissGroupCount(),
                compiled.getHitGroupCount(),
                compiled.getCallableGroupCount());

        // 6. Camera UBO (size matches shader CameraData struct: 2x mat4 + vec3 + float + uint + uint + float + float = 160 bytes, round up to 256)
        long cameraUBOSize = 256;
        long[] cameraAlloc = memory.allocateBuffer(cameraUBOSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | 0x00020000, 0x00000003); // CPU_TO_GPU
        cameraUBO = cameraAlloc[0];
        cameraUBOAllocation = cameraAlloc[1];
        cameraUBOMapped = memory.mapBuffer(cameraUBOAllocation);

        // 7. Descriptor pool and set
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            var poolCI = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);
            LongBuffer pPool = stack.mallocLong(1);
            checkVk(vkCreateDescriptorPool(device, poolCI, null, pPool));
            descriptorPool = pPool.get(0);

            // Allocate descriptor set
            var allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            checkVk(vkAllocateDescriptorSets(device, allocInfo, pSet));
            descriptorSet = pSet.get(0);
        }

        // Write camera UBO descriptor (static binding)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(cameraUBO)
                    .offset(0)
                    .range(256);
            var write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(bufInfo);
            vkUpdateDescriptorSets(device, write, null);
        }

        rtPipelineReady = true;

        // Create initial RT output image at current render resolution
        try {
            var swapchain = Vulkanium.getVulkanSwapchain();
            if (swapchain != null && swapchain.getWidth() > 0 && swapchain.getHeight() > 0) {
                createRTOutputImage(swapchain.getWidth(), swapchain.getHeight());
            }
        } catch (Throwable t) {
            LOGGER.debug("Deferred RT output image creation (swapchain not ready yet): {}", t.getMessage());
        }

        LOGGER.info("RT pipeline fully wired: descriptorSet=0x{}, pipelineLayout=0x{}, sbtReady={}",
                Long.toHexString(descriptorSet), Long.toHexString(pipelineLayout), sbt.isReady());
    }

    /**
     * Compiles a GLSL shader from classpath resources and creates a VkShaderModule.
     */
    private long compileAndCreateModule(SPIRVCompiler compiler, VkDevice device, String resourcePath) {
        int stage = SPIRVCompiler.stageFromExtension(resourcePath);
        ByteBuffer spirv = compiler.compileFromResource(resourcePath, stage);
        if (spirv == null) {
            throw new RuntimeException("Failed to compile RT shader: " + resourcePath);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var moduleCI = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            checkVk(vkCreateShaderModule(device, moduleCI, null, pModule));
            LOGGER.debug("Created VkShaderModule for {}: 0x{}", resourcePath, Long.toHexString(pModule.get(0)));
            return pModule.get(0);
        } finally {
            SPIRVCompiler.freeSpirv(spirv);
        }
    }

    /**
     * Creates/recreates the RT output image at the given resolution.
     */
    private void createRTOutputImage(int width, int height) {
        VkDevice device = vulkaniumDevice.getLogicalDevice();
        // Destroy previous
        if (rtOutputImageView != 0) {
            vkDestroyImageView(device, rtOutputImageView, null);
            rtOutputImageView = 0;
        }
        if (rtOutputImageAlloc != null) {
            memory.freeImageImmediate(rtOutputImageAlloc);
            rtOutputImageAlloc = null;
            rtOutputImage = 0;
        }

        // VK_FORMAT_R16G16B16A16_SFLOAT = 97
        int format = 97;
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        rtOutputImageAlloc = memory.createImage(width, height, 1, format,
                VK_IMAGE_TILING_OPTIMAL, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        rtOutputImage = rtOutputImageAlloc.image();

        // Image view
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewCI = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(rtOutputImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewCI.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            checkVk(vkCreateImageView(device, viewCI, null, pView));
            rtOutputImageView = pView.get(0);
        }

        // Update descriptor set binding 1 (output image)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(rtOutputImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            var write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(imgInfo);
            vkUpdateDescriptorSets(device, write, null);
        }

        rtWidth = width;
        rtHeight = height;
        rtPipeline.resize(width, height);
        LOGGER.info("RT output image created: {}x{} RGBA16F", width, height);
    }

    /**
     * Updates the TLAS descriptor (binding 0) with the current frame's TLAS handle.
     */
    private void updateTLASDescriptor(VkDevice device) {
        AccelerationStructure tlas = tlasBuilder.getTLAS();
        if (tlas == null || tlas.getHandle() == 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pAS = stack.longs(tlas.getHandle());
            var asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                    .pAccelerationStructures(pAS);
            var write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)
                    .pNext(asWrite);
            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /**
     * Updates the camera UBO with current frame's camera data.
     */
    private void updateCameraUBO(int frameIndex) {
        if (cameraUBOMapped == null) return;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.gameRenderer == null || mc.level == null) return;

            net.minecraft.world.phys.Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

            // Build viewInverse (camera-to-world matrix):
            // Rotation part = transpose of MC's camera rotation matrix (MC uses view = camRot, no translation)
            // Translation column = camera world position
            // This ensures: viewInverse * (0,0,0,1) == camera world position (rays start from camera)
            org.joml.Matrix4f camRot = new org.joml.Matrix4f(
                    net.vulkanium.compat.VRenderSystem.getModelViewMatrix());
            // Transpose gives camera-to-world rotation
            org.joml.Matrix4f viewInv = camRot.transpose(new org.joml.Matrix4f());
            // Inject camera world position as the translation column
            viewInv.m03((float) cam.x).m13((float) cam.y).m23((float) cam.z).m33(1.0f);

            // Projection inverse from Vulkan/MC perspective matrix
            org.joml.Matrix4f proj = new org.joml.Matrix4f(net.vulkanium.compat.VRenderSystem.getProjectionMatrix());
            org.joml.Matrix4f projInv = proj.invert(new org.joml.Matrix4f());

            cameraUBOMapped.position(0);
            // viewInverse (64 bytes)
            viewInv.get(cameraUBOMapped);
            cameraUBOMapped.position(64);
            // projInverse (64 bytes)
            projInv.get(cameraUBOMapped);
            cameraUBOMapped.position(128);
            // cameraPosition (vec3) at std140 offset 128:
            //   vec3 base alignment = 16, data size = 12 bytes, followed by 4 bytes implicit padding.
            //   Next element (float time) must start at offset 144 per std140, NOT 140.
            cameraUBOMapped.putFloat((float) cam.x);   // offset 128
            cameraUBOMapped.putFloat((float) cam.y);   // offset 132
            cameraUBOMapped.putFloat((float) cam.z);   // offset 136
            cameraUBOMapped.putFloat(0.0f);             // offset 140: std140 vec3 implicit padding
            // time (float) at std140 offset 144
            cameraUBOMapped.putFloat((float) (mc.level.getGameTime() % 24000) / 24000.0f);
            // frameIndex (uint) at offset 148
            cameraUBOMapped.putInt(frameIndex);
            // maxBounces (uint) at offset 152
            cameraUBOMapped.putInt(maxBounces);
            // sunAngle (float) at offset 156
            // mc.level.getSunAngle(1.0f) returns the celestial angle in RADIANS [0, 2π].
            // The RT shaders multiply by 2π (treating sunAngle as a [0,1] fraction),
            // so we normalize it to [0,1] before uploading to avoid double-rotation.
            float sunAngle = mc.level.getSunAngle(1.0f) / ((float) Math.PI * 2.0f);
            cameraUBOMapped.putFloat(sunAngle);
            // padding (float) at offset 160
            cameraUBOMapped.putFloat(0.0f);
        } catch (Throwable t) {
            LOGGER.debug("Camera UBO update failed: {}", t.getMessage());
        }
    }

    private static void checkVk(int result) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Vulkan call failed: " + result);
        }
    }

    /**
     * Per-frame RT execution.
     *
     * @param commandBuffer Active command buffer
     * @param frameIndex Current frame index
     */
    public void executeFrame(VkCommandBuffer commandBuffer, int frameIndex) {
        if (!enabled) return;

        long startNs = System.nanoTime();
        blasBuildsThisFrame = 0;

        // 1. Build dirty BLASes
        int blasBuilds = blasManager.buildDirtyBLASes(commandBuffer);
        blasBuildsThisFrame = blasBuilds;

        // 2. Rebuild TLAS — use actual camera position for camera-relative instance transforms
        double camX = 0, camY = 0, camZ = 0;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
                net.minecraft.world.phys.Vec3 cp = mc.gameRenderer.getMainCamera().getPosition();
                camX = cp.x; camY = cp.y; camZ = cp.z;
            }
        } catch (Throwable ignored) {}
        tlasBuilder.collectInstances(blasManager.getBuiltBLASes(), camX, camY, camZ);
        tlasBuilder.buildTLAS(commandBuffer);

        // 3. Barrier: AS build → ray trace
        insertASBarrier(commandBuffer);

        // 4. Update descriptors and dispatch rays (only when TLAS has geometry)
        if (rtPipelineReady && sbt.isReady() && tlasBuilder.getInstanceCount() > 0) {
            VkDevice device = vulkaniumDevice.getLogicalDevice();
            updateTLASDescriptor(device);
            updateCameraUBO(frameIndex);
            dispatchRays(commandBuffer);
            rtDispatchedThisFrame = true;
        } else {
            rtDispatchedThisFrame = false;
        }

        // 5. Denoise (if enabled)
        if (denoiserEnabled && denoiser != null) {
            denoiser.execute(commandBuffer, frameIndex);
        }

        lastTraceTimeNs = System.nanoTime() - startNs;
    }

    private void dispatchRays(VkCommandBuffer commandBuffer) {
        if (!rtPipelineReady || rtWidth == 0 || rtHeight == 0) return;

        // Bind RT pipeline
        rtPipeline.bind(commandBuffer);

        // Bind descriptor set
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var active = rtPipeline.getPipeline("vulkanium_default_rt");
            if (active != null && active.getPipelineLayout() != 0) {
                vkCmdBindDescriptorSets(commandBuffer,
                        VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                        active.getPipelineLayout(),
                        0,
                        stack.longs(descriptorSet),
                        null);
            }
        }

        // Push constants: bounce count
        rtPipeline.pushConstants(commandBuffer, maxBounces);

        // Trace rays — full screen dispatch
        sbt.cmdTraceRays(commandBuffer, rtWidth, rtHeight);
    }

    private void insertASBarrier(VkCommandBuffer commandBuffer) {
        // Memory barrier: acceleration structure build → ray trace read
        // srcStage: VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
        // dstStage: VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        // srcAccess: VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
        // dstAccess: VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(
                    commandBuffer,
                    org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    0,
                    barrier,
                    null,
                    null
            );
        }
        LOGGER.debug("Inserted AS->RT pipeline barrier for frame dispatch");
    }

    // ── Configuration ──

    public void setMaxBounces(int bounces) {
        this.maxBounces = Math.min(bounces, capabilities.getMaxRayRecursionDepth());
    }

    public void setDenoiserEnabled(boolean enabled) {
        this.denoiserEnabled = enabled && denoiser != null;
    }

    public void setActiveTier(RTCapabilities.Tier tier) {
        if (tier.getLevel() <= capabilities.getTier().getLevel()) {
            this.activeTier = tier;
            this.maxBounces = switch (tier) {
                case NONE -> 0;
                case BASIC -> 1;
                case STANDARD -> 2;
                case FULL -> capabilities.getRecommendedMaxBounces();
            };
        }
    }

    /**
     * Called when a chunk's mesh changes — marks its BLAS dirty.
     */
    public void onChunkMeshChanged(int sectionX, int sectionY, int sectionZ,
                                     long vertexBuffer, long vertexOffset,
                                     long indexBuffer, long indexOffset,
                                     int vertexCount, int indexCount,
                                     int vertexStride, boolean translucent) {
        if (!enabled) return;
        long sectionKey = packSectionKey(sectionX, sectionY, sectionZ);
        blasManager.setGeometry(sectionKey,
            vertexBuffer, vertexOffset,
            vertexCount, Math.max(16, vertexStride),
            indexBuffer, indexOffset, indexCount,
            indexCount > 65535);
        blasManager.setInstanceMaterialId(sectionKey, 0); // default terrain material
        blasManager.setInstanceSbtOffset(sectionKey, translucent ? 1 : 0);
        blasManager.markDirty(sectionKey);
    }

    /**
     * Called when a chunk is unloaded.
     */
    public void onChunkUnloaded(int sectionX, int sectionY, int sectionZ) {
        if (!enabled) return;
        long sectionKey = packSectionKey(sectionX, sectionY, sectionZ);
        blasManager.removeBLAS(sectionKey);
    }

    /**
     * Registers or updates RT geometry for entities/items, including material routing.
     *
     * @param instanceId unique runtime key for entity or item instance
     * @param materialName material key used for LabPBR-capable material lookup
     */
    public void onDynamicMeshChanged(long instanceId,
                                     long vertexBuffer, long indexBuffer,
                                     int vertexCount, int indexCount,
                                     String materialName,
                                     boolean translucent) {
        if (!enabled) return;

        long dynamicKey = packDynamicKey(instanceId);
        int materialId = materialTable != null ? materialTable.getMaterialId(materialName) : 0;

        blasManager.setGeometry(dynamicKey,
                vertexBuffer, 0,
                vertexCount, 32,
                indexBuffer, 0, indexCount,
                indexCount > 65535);
        blasManager.setInstanceMaterialId(dynamicKey, materialId);
        blasManager.setInstanceSbtOffset(dynamicKey, translucent ? 1 : 0);
        blasManager.markDirty(dynamicKey);
    }

    /** Removes entity/item RT geometry instance. */
    public void onDynamicMeshRemoved(long instanceId) {
        if (!enabled) return;
        blasManager.removeBLAS(packDynamicKey(instanceId));
    }

    public void resize(int width, int height) {
        if (!enabled) return;
        if (rtPipelineReady && (width != rtWidth || height != rtHeight) && width > 0 && height > 0) {
            createRTOutputImage(width, height);
        }
        rtPipeline.resize(width, height);
        if (denoiser != null) denoiser.resize(width, height);
    }

    public void destroy() {
        VkDevice device = vulkaniumDevice.getLogicalDevice();
        if (denoiser != null) denoiser.destroy();
        sbt.destroy();
        rtPipeline.destroy();
        tlasBuilder.destroy();
        blasManager.destroy();

        // Clean up RT pipeline resources
        if (rtOutputImageView != 0) { vkDestroyImageView(device, rtOutputImageView, null); rtOutputImageView = 0; }
        if (rtOutputImageAlloc != null) { memory.freeImageImmediate(rtOutputImageAlloc); rtOutputImageAlloc = null; rtOutputImage = 0; }
        if (cameraUBOMapped != null) { memory.unmapBuffer(cameraUBOAllocation); cameraUBOMapped = null; }
        if (cameraUBO != 0) { memory.freeBuffer(cameraUBO, cameraUBOAllocation); cameraUBO = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0; }
        if (pipelineLayout != 0) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0; }
        if (descriptorSetLayout != 0) { vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null); descriptorSetLayout = 0; }

        long[] modules = {rayGenModule, skyMissModule, shadowMissModule, opaqueHitModule, shadowAnyHitModule};
        for (long m : modules) {
            if (m != 0) vkDestroyShaderModule(device, m, null);
        }
        rayGenModule = skyMissModule = shadowMissModule = opaqueHitModule = shadowAnyHitModule = 0;

        LOGGER.info("RT pipeline destroyed");
    }

    // ── Internal ──

    private static long packSectionKey(int x, int y, int z) {
        return ((long)(x & 0x3FFFFF)) | (((long)(y & 0xFFFFF)) << 22) | (((long)(z & 0x3FFFFF)) << 42);
    }

    private static long packDynamicKey(long instanceId) {
        return (1L << 63) | (instanceId & 0x7FFFFFFFFFFFFFFFL);
    }

    // ── Accessors ──

    public boolean isEnabled() { return enabled; }
    public boolean isRTPipelineReady() { return rtPipelineReady; }
    /** Returns true if RT dispatch actually ran this frame (pipeline ready + TLAS non-empty). */
    public boolean wasRTDispatchedThisFrame() { return rtDispatchedThisFrame; }
    public long getRTOutputImage() { return rtOutputImage; }
    public long getRTOutputImageView() { return rtOutputImageView; }
    public int getRTWidth() { return rtWidth; }
    public int getRTHeight() { return rtHeight; }
    public RTCapabilities getCapabilities() { return capabilities; }
    public RTCapabilities.Tier getActiveTier() { return activeTier; }
    public int getBLASBuildsThisFrame() { return blasBuildsThisFrame; }
    public long getLastTraceTimeNs() { return lastTraceTimeNs; }
}
