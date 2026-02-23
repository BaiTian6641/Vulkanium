package net.vulkanium.rt;

import net.vulkanium.Vulkanium;
import net.vulkanium.core.VulkaniumCommand;
import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.core.VulkaniumQueues;
import net.vulkanium.resource.SPIRVCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Ray Tracing Renderer — integrates RT capabilities into the Vulkanium frame lifecycle.
 *
 * <p>Provides a layered RT system with two modes:</p>
 * <ul>
 *   <li><b>Compute RT (Tier 0+)</b>: Screen-space ray marching using the depth buffer.
 *       Works on ALL Vulkan GPUs. Provides SSAO and contact shadows.</li>
 *   <li><b>Hardware RT (Tier 1+)</b>: Uses VK_KHR_ray_tracing_pipeline and
 *       acceleration structures. Provides true shadows, reflections, and GI.</li>
 * </ul>
 *
 * <h3>Frame Lifecycle Integration</h3>
 * <pre>
 *   Rasterization Pass (main render pass)
 *       ↓ end render pass
 *   Depth transition: DEPTH_STENCIL_ATTACHMENT → SHADER_READ_ONLY
 *   RT Compute Dispatch (SSAO / shadows)
 *       ↓ compute → fragment barrier
 *   Depth transition: SHADER_READ_ONLY → DEPTH_STENCIL_ATTACHMENT (restore)
 *       ↓ present
 * </pre>
 *
 * <h3>Compute RT Pipeline</h3>
 * <p>The compute shader reads from the depth buffer and outputs an AO/shadow mask
 * to a storage image at half resolution. This can be sampled during subsequent
 * raster passes (e.g. translucent layer) via a dedicated descriptor binding.</p>
 */
public class RayTracingRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/RTRenderer");

    /** Compute workgroup size (16×16 = 256 threads) */
    private static final int WORKGROUP_SIZE = 16;

    // ── Dependencies ──
    private VkDevice device;
    private VulkaniumMemory memory;
    private SPIRVCompiler compiler;

    // ── RT Output Storage Image ──
    private VulkaniumMemory.ImageAllocation rtOutputImageAlloc;
    private long rtOutputImageView = VK_NULL_HANDLE;
    private long rtOutputSampler = VK_NULL_HANDLE;
    /** Whether the RT output image has been transitioned to GENERAL at least once */
    private boolean rtOutputLayoutInitialized = false;

    // ── Depth sampler (for reading depth in compute) ──
    private long depthSampler = VK_NULL_HANDLE;
    /** Tracks the depthImageView currently bound in the descriptor set */
    private long boundDepthImageView = VK_NULL_HANDLE;

    // ── Compute Pipeline ──
    private long computePipeline = VK_NULL_HANDLE;
    private long computePipelineLayout = VK_NULL_HANDLE;
    private long computeDescriptorSetLayout = VK_NULL_HANDLE;
    private long computeDescriptorPool = VK_NULL_HANDLE;
    private long computeDescriptorSet = VK_NULL_HANDLE;
    private long computeShaderModule = VK_NULL_HANDLE;

    // ── State ──
    private boolean initialized = false;
    private boolean enabled = false;
    private boolean ssaoEnabled = true;
    private int width, height;
    private RTCapabilities.Tier activeTier = RTCapabilities.Tier.NONE;

    // ── RT Module Manager (for hardware RT) ──
    private RTModuleManager rtModuleManager;

    // ── SSAO Compositor (applies AO to swapchain via fullscreen pass) ──
    private SSAOCompositor ssaoCompositor;

    // ── Per-frame push constants ──
    private float cameraX, cameraY, cameraZ;
    private float sunDirX = 0.3f, sunDirY = 0.8f, sunDirZ = 0.5f;
    private float aoRadius = 1.5f;
    private int aoSamples = 16;
    private int frameIndex = 0;

    // ── Diagnostics ──
    private long totalSSAODispatches = 0;

    /**
     * Initializes the RT renderer.
     *
     * @param device       Vulkan logical device
     * @param memory       Memory manager
     * @param compiler     SPIR-V compiler
     * @param capabilities RT hardware capabilities
     * @param width        Initial framebuffer width
     * @param height       Initial framebuffer height
     */
    public void initialize(VkDevice device, VulkaniumMemory memory,
                           SPIRVCompiler compiler, RTCapabilities capabilities,
                           int width, int height) {
        this.device = device;
        this.memory = memory;
        this.compiler = compiler;
        this.width = width;
        this.height = height;
        this.activeTier = capabilities.getTier();

        try {
            // Create depth sampler for reading depth buffer in compute shader
            createDepthSampler();

            // Create RT output storage image
            createRTOutputImage();

            // Create compute pipeline for SSAO
            createComputePipeline();

            // Transition RT output image to GENERAL layout for compute writes
            transitionRTOutputToGeneral();

            // If hardware RT is available, initialize the full RT module
            if (capabilities.isRTAvailable() && capabilities.getTier().getLevel() >= 1) {
                try {
                    VulkaniumDevice vulkaniumDevice = Vulkanium.getVulkanDevice();
                    VulkaniumQueues queues = Vulkanium.getVulkanQueues();
                    rtModuleManager = new RTModuleManager(vulkaniumDevice, memory, queues, capabilities);
                    rtModuleManager.initialize();
                    LOGGER.info("Hardware RT module initialized (tier={})", capabilities.getTier().getName());
                } catch (Exception e) {
                    LOGGER.warn("Hardware RT initialization failed, continuing with compute-only RT", e);
                    rtModuleManager = null;
                }
            }

            initialized = true;
            enabled = true;
            LOGGER.info("RT renderer initialized: {}x{}, tier={}, computeSSAO={}",
                    width, height, activeTier.getName(), ssaoEnabled);

        } catch (Exception e) {
            LOGGER.error("Failed to initialize RT renderer", e);
            cleanup();
        }
    }

    /**
     * Initializes the SSAO compositor that applies the AO result to the swapchain.
     * Must be called AFTER {@link #initialize} and after the swapchain is fully set up.
     *
     * @param colorFormat  Swapchain image format
     * @param imageViews   Swapchain image views
     */
    public void initCompositor(int colorFormat, long[] imageViews) {
        if (!initialized || rtOutputImageView == VK_NULL_HANDLE) {
            LOGGER.warn("Cannot init compositor: RT renderer not initialized");
            return;
        }

        try {
            ssaoCompositor = new SSAOCompositor();
            ssaoCompositor.initialize(device, compiler, colorFormat, imageViews,
                    width, height, rtOutputImageView, rtOutputSampler);
        } catch (Exception e) {
            LOGGER.warn("SSAO compositor initialization failed — AO will not be visible", e);
            ssaoCompositor = null;
        }
    }

    /**
     * Per-frame RT execution. Called after the main raster pass ends.
     *
     * <p>The depth buffer must already be written by the raster pass. This method
     * transitions it to SHADER_READ_ONLY for compute sampling, dispatches SSAO,
     * composites the result onto the swapchain, then transitions depth back.</p>
     *
     * @param commandBuffer Active command buffer (outside render pass)
     * @param depthImage    The VkImage handle of the depth buffer
     * @param depthImageView Depth buffer image view
     * @param frameIdx      Current frame index
     * @param swapchainImageIndex Current swapchain image index (for compositing)
     */
    public void executeFrame(VkCommandBuffer commandBuffer, long depthImage,
                             long depthImageView, int frameIdx, int swapchainImageIndex,
                             boolean worldRenderedThisFrame) {
        if (!initialized || !enabled) return;
        if (!worldRenderedThisFrame) return;

        // Don't run RT/SSAO when we're not in a world (title/menu screens).
        // Running SSAO over empty/cleared depth on the main menu can darken the
        // entire framebuffer (AO -> 0) and produce a black screen; skip RT there.
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
        } catch (Throwable t) {
            // Defensive: if MC isn't available for any reason, skip RT
            return;
        }

        this.frameIndex = frameIdx;

        // 1. Hardware RT pass (if available)
        if (rtModuleManager != null && rtModuleManager.isEnabled()) {
            try {
                rtModuleManager.executeFrame(commandBuffer, frameIdx);
            } catch (Exception e) {
                if (totalSSAODispatches < 5) {
                    LOGGER.warn("Hardware RT dispatch failed: {}", e.getMessage());
                }
            }
        }

        // 2. Compute SSAO pass (always available on all Vulkan GPUs)
        if (ssaoEnabled) {
            dispatchSSAO(commandBuffer, depthImage, depthImageView);

            // 3. Composite SSAO onto the swapchain image
            if (ssaoCompositor != null && ssaoCompositor.isInitialized()) {
                ssaoCompositor.composite(commandBuffer, swapchainImageIndex);
            }
        }
    }

    /**
     * Dispatches the SSAO compute shader with proper image transitions.
     */
    private void dispatchSSAO(VkCommandBuffer commandBuffer, long depthImage,
                               long depthImageView) {
        if (computePipeline == VK_NULL_HANDLE) return;

        // ── Step 1: Transition depth buffer for compute shader reading ──
        // Depth attachment → Shader read (compute stage reads it)
        VulkaniumCommand.transitionImageLayout(commandBuffer, depthImage,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);

        // ── Step 2: Update descriptor set if depth image view changed ──
        if (depthImageView != boundDepthImageView) {
            updateDescriptorSet(depthImageView);
            boundDepthImageView = depthImageView;
        }

        // ── Step 3: Bind compute pipeline and descriptor set ──
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);

        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    computePipelineLayout, 0,
                    stack.longs(computeDescriptorSet), null);

            // ── Step 4: Push constants ──
            // Layout: 12 floats × 4 bytes = 48 bytes
            // Pass HALF-resolution dimensions to match the half-res output space
            int aoWidth = Math.max(1, width / 2);
            int aoHeight = Math.max(1, height / 2);
            ByteBuffer pushData = stack.calloc(48);
            pushData.putFloat(0, cameraX);
            pushData.putFloat(4, cameraY);
            pushData.putFloat(8, cameraZ);
            pushData.putFloat(12, sunDirX);
            pushData.putFloat(16, sunDirY);
            pushData.putFloat(20, sunDirZ);
            pushData.putFloat(24, aoRadius);
            pushData.putFloat(28, (float) aoSamples);
            pushData.putFloat(32, (float) aoWidth);   // half-res width
            pushData.putFloat(36, (float) aoHeight);   // half-res height
            pushData.putFloat(40, (float) frameIndex);
            pushData.putFloat(44, 0.0f); // padding

            vkCmdPushConstants(commandBuffer, computePipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
        }

        // ── Step 5: Dispatch compute workgroups ──
        // SSAO at half resolution
        int aoWidth = Math.max(1, width / 2);
        int aoHeight = Math.max(1, height / 2);
        int groupsX = (aoWidth + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        int groupsY = (aoHeight + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1);

        // ── Step 6: Image barrier — compute writes → fragment reads (for compositing) ──
        // Use an image memory barrier (not global) for explicit cache flushing on AMD RADV
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer imgBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(rtOutputImageAlloc.image());
            imgBarrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, null, null, imgBarrier);
        }

        // ── Step 7: Transition depth back to writable attachment ──
        VulkaniumCommand.transitionImageLayout(commandBuffer, depthImage,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT);

        totalSSAODispatches++;
        if (totalSSAODispatches == 1 || totalSSAODispatches == 10 || totalSSAODispatches % 300 == 0) {
            LOGGER.info("[RT] SSAO dispatch #{}: {}×{} → {}×{} workgroups, {} samples, radius={:.2f}",
                    totalSSAODispatches, aoWidth, aoHeight, groupsX, groupsY, aoSamples, aoRadius);
        }
    }

    // ── Camera / Sun Updates ──

    public void setCameraPosition(float x, float y, float z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
    }

    public void setSunDirection(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 0.001f) {
            this.sunDirX = x / len;
            this.sunDirY = y / len;
            this.sunDirZ = z / len;
        }
    }

    // ── Resize ──

    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        if (!initialized) return;

        LOGGER.info("RT renderer resizing: {}×{} → {}×{}", width, height, newWidth, newHeight);

        this.width = newWidth;
        this.height = newHeight;

        // Recreate output image at new size
        destroyRTOutputImage();
        createRTOutputImage();
        transitionRTOutputToGeneral();
        boundDepthImageView = VK_NULL_HANDLE; // force descriptor re-bind
        // updateDescriptorSet will happen next frame when depth view is available

        if (rtModuleManager != null) {
            rtModuleManager.resize(newWidth, newHeight);
        }

        // Note: compositor framebuffers are rebuilt from Vulkanium's swapchain recreation callback
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Resource Creation
    // ═══════════════════════════════════════════════════════════════════

    private void createDepthSampler() {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST) // depth should use nearest
                    .minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .anisotropyEnable(false)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);

            LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateSampler(device, info, null, pSampler),
                    "Failed to create depth sampler");
            depthSampler = pSampler.get(0);
        }
    }

    private void createRTOutputImage() {
        // Half-resolution SSAO output (R8_UNORM is sufficient for AO mask)
        int aoWidth = Math.max(1, width / 2);
        int aoHeight = Math.max(1, height / 2);

        rtOutputImageAlloc = memory.createImage(
                aoWidth, aoHeight, 1,
                VK_FORMAT_R8_UNORM,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        // Create image view
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(rtOutputImageAlloc.image())
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateImageView(device, viewInfo, null, pView),
                    "Failed to create RT output image view");
            rtOutputImageView = pView.get(0);
        }

        // Create sampler for the RT output (used when compositing in fragment shaders)
        if (rtOutputSampler == VK_NULL_HANDLE) {
            try (MemoryStack stack = stackPush()) {
                VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                        .magFilter(VK_FILTER_LINEAR)  // bilinear for upscaling half→full
                        .minFilter(VK_FILTER_LINEAR)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .anisotropyEnable(false)
                        .unnormalizedCoordinates(false)
                        .compareEnable(false)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);

                LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
                checkResult(vkCreateSampler(device, samplerInfo, null, pSampler),
                        "Failed to create RT output sampler");
                rtOutputSampler = pSampler.get(0);
            }
        }

        rtOutputLayoutInitialized = false;
        LOGGER.debug("RT output image created: {}×{} R8_UNORM", aoWidth, aoHeight);
    }

    /**
     * Transitions the RT output image from UNDEFINED → GENERAL and clears it to 1.0
     * (no occlusion) using a one-shot command. This prevents black-screen artifacts
     * from reading uninitialized image data before the first SSAO compute dispatch.
     */
    private void transitionRTOutputToGeneral() {
        if (rtOutputImageAlloc == null) return;

        VulkaniumCommand vulkanCommand = Vulkanium.getVulkanCommand();
        VkCommandBuffer cmd = vulkanCommand.beginSingleTimeCommand();

        // Transition from UNDEFINED to TRANSFER_DST for clearing
        VulkaniumCommand.transitionImageLayout(cmd, rtOutputImageAlloc.image(),
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Clear to 1.0 (white = no occlusion) so the compositor starts with neutral AO
        try (MemoryStack stack = stackPush()) {
            VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
            clearColor.float32(0, 1.0f); // R = 1.0 (no occlusion)
            clearColor.float32(1, 1.0f);
            clearColor.float32(2, 1.0f);
            clearColor.float32(3, 1.0f);

            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdClearColorImage(cmd, rtOutputImageAlloc.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, range);
        }

        // Transition from TRANSFER_DST to GENERAL for compute shader writes
        VulkaniumCommand.transitionImageLayout(cmd, rtOutputImageAlloc.image(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        vulkanCommand.endSingleTimeCommand(cmd);
        rtOutputLayoutInitialized = true;
    }

    private void createComputePipeline() {
        // Compile SSAO compute shader
        ByteBuffer spirv = compiler.compileCompute(SSAO_COMPUTE_SHADER, "ssao.comp");
        if (spirv == null) {
            LOGGER.error("Failed to compile SSAO compute shader");
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // Create shader module
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateShaderModule(device, moduleInfo, null, pModule),
                    "Failed to create SSAO shader module");
            computeShaderModule = pModule.get(0);

            // Descriptor set layout:
            //   binding 0: storage image (SSAO output, r8 writeonly)
            //   binding 1: combined image sampler (depth buffer, read)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout),
                    "Failed to create RT descriptor set layout");
            computeDescriptorSetLayout = pLayout.get(0);

            // Pipeline layout with push constants (48 bytes)
            VkPushConstantRange.Buffer pushConstant = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(48);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(computeDescriptorSetLayout))
                    .pPushConstantRanges(pushConstant);
            LongBuffer pPipeLayout = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipeLayout),
                    "Failed to create RT pipeline layout");
            computePipelineLayout = pPipeLayout.get(0);

            // Compute pipeline
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .layout(computePipelineLayout);
            pipelineInfo.get(0).stage()
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(computeShaderModule)
                    .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "Failed to create SSAO compute pipeline");
            computePipeline = pPipeline.get(0);

            // Descriptor pool: 1 storage image + 1 combined sampler
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);
            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateDescriptorPool(device, poolInfo, null, pPool),
                    "Failed to create RT descriptor pool");
            computeDescriptorPool = pPool.get(0);

            // Allocate descriptor set
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(computeDescriptorPool)
                    .pSetLayouts(stack.longs(computeDescriptorSetLayout));
            LongBuffer pSet = stack.longs(VK_NULL_HANDLE);
            checkResult(vkAllocateDescriptorSets(device, allocInfo, pSet),
                    "Failed to allocate RT descriptor set");
            computeDescriptorSet = pSet.get(0);

            // Write binding 0 (storage image) — binding 1 (depth) written per-frame
            updateStorageImageDescriptor();
        }

        memFree(spirv);
        LOGGER.info("SSAO compute pipeline created successfully");
    }

    /**
     * Updates binding 0 (storage image output) in the descriptor set.
     */
    private void updateStorageImageDescriptor() {
        if (computeDescriptorSet == VK_NULL_HANDLE || rtOutputImageView == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer storageImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(rtOutputImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(computeDescriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(storageImageInfo);

            vkUpdateDescriptorSets(device, write, null);
        }
    }

    /**
     * Updates binding 1 (depth sampler) in the descriptor set.
     * Called when the depth image view changes (resize, swapchain recreation).
     */
    private void updateDescriptorSet(long depthImageView) {
        if (computeDescriptorSet == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            // Also re-write binding 0 in case the RT output image was recreated
            VkDescriptorImageInfo.Buffer storageImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(rtOutputImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkDescriptorImageInfo.Buffer depthSamplerInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(depthImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                    .sampler(depthSampler);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(computeDescriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(storageImageInfo);
            writes.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(computeDescriptorSet)
                    .dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(depthSamplerInfo);

            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private void destroyRTOutputImage() {
        if (rtOutputImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, rtOutputImageView, null);
            rtOutputImageView = VK_NULL_HANDLE;
        }
        if (rtOutputImageAlloc != null) {
            memory.freeImageImmediate(rtOutputImageAlloc);
            rtOutputImageAlloc = null;
        }
        rtOutputLayoutInitialized = false;
    }

    /**
     * Cleans up all resources on initialization failure.
     */
    private void cleanup() {
        destroy();
        initialized = false;
        enabled = false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Getters / Setters
    // ═══════════════════════════════════════════════════════════════════

    public boolean isInitialized() { return initialized; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSSAOEnabled() { return ssaoEnabled; }
    public void setSSAOEnabled(boolean ssaoEnabled) { this.ssaoEnabled = ssaoEnabled; }
    public long getRTOutputImageView() { return rtOutputImageView; }
    public long getRTOutputSampler() { return rtOutputSampler; }
    public RTCapabilities.Tier getActiveTier() { return activeTier; }
    public RTModuleManager getRTModuleManager() { return rtModuleManager; }
    public SSAOCompositor getSSAOCompositor() { return ssaoCompositor; }

    public void setAORadius(float radius) { this.aoRadius = Math.max(0.1f, Math.min(radius, 10.0f)); }
    public void setAOSamples(int samples) { this.aoSamples = Math.max(4, Math.min(samples, 64)); }
    public float getAORadius() { return aoRadius; }
    public int getAOSamples() { return aoSamples; }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    public void destroy() {
        if (ssaoCompositor != null) {
            ssaoCompositor.destroy();
            ssaoCompositor = null;
        }

        if (computePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, computePipeline, null);
            computePipeline = VK_NULL_HANDLE;
        }
        if (computeShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, computeShaderModule, null);
            computeShaderModule = VK_NULL_HANDLE;
        }
        if (computePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, computePipelineLayout, null);
            computePipelineLayout = VK_NULL_HANDLE;
        }
        if (computeDescriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, computeDescriptorPool, null);
            computeDescriptorPool = VK_NULL_HANDLE;
            computeDescriptorSet = VK_NULL_HANDLE; // freed with pool
        }
        if (computeDescriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, computeDescriptorSetLayout, null);
            computeDescriptorSetLayout = VK_NULL_HANDLE;
        }

        destroyRTOutputImage();

        if (rtOutputSampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, rtOutputSampler, null);
            rtOutputSampler = VK_NULL_HANDLE;
        }
        if (depthSampler != VK_NULL_HANDLE) {
            vkDestroySampler(device, depthSampler, null);
            depthSampler = VK_NULL_HANDLE;
        }

        if (rtModuleManager != null) {
            rtModuleManager.destroy();
            rtModuleManager = null;
        }

        initialized = false;
        LOGGER.info("RT renderer destroyed (total SSAO dispatches: {})", totalSSAODispatches);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SSAO Compute Shader (GLSL 450)
    //
    // Screen-space ambient occlusion using hemisphere sampling on the
    // depth buffer. Outputs a single-channel AO mask to a half-res
    // storage image.
    //
    // Push constant struct is 48 bytes (std430):
    //   offset  0: vec4 cameraPos_sunDirX  (x,y,z, sunX)
    //   offset 16: vec4 sunDirYZ_aoRadSamp (sunY, sunZ, aoRadius, numSamples)
    //   offset 32: vec4 screenWH_frame_pad (screenW, screenH, frame, 0)
    //
    // But for simplicity we use a flat float[12] layout.
    // ═══════════════════════════════════════════════════════════════════

    private static final String SSAO_COMPUTE_SHADER = """
            #version 450
            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

            // Output: AO + contact shadow mask (R8_UNORM at half resolution)
            layout(binding = 0, r8) uniform writeonly image2D aoOutput;

            // Input: depth buffer from the raster pass
            layout(binding = 1) uniform sampler2D depthBuffer;

            // Push constants: 12 floats = 48 bytes
            // screenWidth/screenHeight are in HALF-RES pixels (matching output)
            layout(push_constant) uniform PushConstants {
                float camX, camY, camZ;
                float sunX, sunY, sunZ;
                float aoRadius;
                float numSamples;
                float screenWidth;   // half-res output width
                float screenHeight;  // half-res output height
                float frameIndex;
                float pad;
            } pc;

            // ── Pseudo-random hash for per-pixel noise ──
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            // Two-component hash for better 2D distribution
            vec2 hash2(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
                p3 += dot(p3, p3.yzx + 33.33);
                return fract(vec2((p3.x + p3.y) * p3.z, (p3.x + p3.z) * p3.y));
            }

            // Linearize depth value to view-space distance.
            // MC 1.20.1 uses standard perspective with near=0.05.
            // Far plane = renderDistance * 16 blocks. We use 256 as reasonable default,
            // but the formula is robust — far plane only affects extreme-distance AO.
            float linearizeDepth(float d) {
                float near = 0.05;
                float far = 256.0;
                return near * far / (far - d * (far - near));
            }

            // ── Screen-space contact shadow from sun direction ──
            // Marches rays in screen space toward the sun, checking for occluders.
            // Uses the HALF-RES output dimensions for correct screen-space math.
            float computeContactShadow(vec2 uv, float linDepth) {
                // Project sun direction to approximate screen-space direction
                vec2 sunScreenDir = normalize(vec2(pc.sunX, -pc.sunY) + vec2(0.001));

                float shadow = 1.0;
                const int SHADOW_STEPS = 10;
                // Scale march length based on depth (shorter for close objects)
                float marchLength = min(32.0, 20.0 / max(linDepth * 0.1, 0.1));

                for (int s = 1; s <= SHADOW_STEPS; s++) {
                    float t = float(s) / float(SHADOW_STEPS);
                    // Accelerating step size for better near-field precision
                    float stepT = t * t;
                    vec2 samplePos = uv + sunScreenDir * stepT * marchLength
                                    / vec2(pc.screenWidth, pc.screenHeight);

                    // Bounds check
                    if (samplePos.x < 0.001 || samplePos.x > 0.999 ||
                        samplePos.y < 0.001 || samplePos.y > 0.999) break;

                    float sampleDepth = texture(depthBuffer, samplePos).r;

                    // Skip sky samples
                    if (sampleDepth >= 0.9999) continue;

                    float sampleLin = linearizeDepth(sampleDepth);
                    float heightDiff = linDepth - sampleLin;

                    // If sample is closer to camera by a meaningful amount,
                    // it's between us and the sun -> shadow.
                    // Use tight range to avoid self-shadowing and distant artifacts
                    if (heightDiff > 0.05 && heightDiff < 1.5) {
                        // Gradual falloff based on distance along ray and depth difference
                        float depthFalloff = smoothstep(0.05, 1.5, heightDiff);
                        float distFalloff = 1.0 - t;
                        shadow *= mix(1.0, 0.7, (1.0 - depthFalloff) * distFalloff);
                    }
                }

                return clamp(shadow, 0.0, 1.0);
            }

            void main() {
                ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
                ivec2 outSize = imageSize(aoOutput);

                if (pos.x >= outSize.x || pos.y >= outSize.y) return;

                // UV coordinates in [0, 1]
                vec2 uv = (vec2(pos) + 0.5) / vec2(outSize);

                // Sample center depth
                float depth = texture(depthBuffer, uv).r;

                // Skip sky/invalid pixels.
                // Some transient depth states can produce 0.0 samples; treat these as non-occluding
                // to avoid full-screen black AO artifacts.
                if (depth >= 0.9999 || depth <= 0.000001) {
                    imageStore(aoOutput, pos, vec4(1.0));
                    return;
                }

                float linDepth = linearizeDepth(depth);

                // ======================================================
                //  1. SSAO: hemisphere sampling around the center pixel
                // ======================================================
                float ao = 0.0;
                int samples = int(pc.numSamples);

                // Per-pixel random rotation angle via hash (prevents banding)
                vec2 noise = hash2(vec2(pos) + pc.frameIndex * 1.7);

                // Scale sample radius inversely with depth for perspective-correct AO.
                // Use half-res screen dimensions for correct pixel-space scaling.
                float radius = pc.aoRadius / max(linDepth, 0.1);

                // Pixel-space radius (clamped to prevent excessive range)
                float pixRadius = min(radius * 30.0, 40.0);

                for (int i = 0; i < samples; i++) {
                    // Per-sample angle using golden angle spiral + per-pixel noise
                    float fi = float(i) + noise.x;
                    float angle = fi * 2.39996323 + noise.y * 6.2831853;
                    float r = sqrt(fi / float(samples)) * pixRadius;

                    // Sample offset in half-res pixel space -> UV space
                    vec2 offset = vec2(cos(angle), sin(angle)) * r;
                    vec2 sampleUV = uv + offset / vec2(pc.screenWidth, pc.screenHeight);
                    sampleUV = clamp(sampleUV, vec2(0.001), vec2(0.999));

                    float sampleDepth = texture(depthBuffer, sampleUV).r;

                    // Skip sky samples entirely — they should not contribute occlusion
                    if (sampleDepth >= 0.9999) continue;

                    float sampleLinDepth = linearizeDepth(sampleDepth);

                    // Depth difference: positive = sample is closer (occluder)
                    float depthDiff = linDepth - sampleLinDepth;

                    // Range check: large depth discontinuities indicate different
                    // surfaces (e.g., player hand vs distant terrain). These MUST
                    // be rejected to prevent halo artifacts around edges.
                    float absDiff = abs(depthDiff);
                    float maxRange = pc.aoRadius * 0.5;
                    float rangeCheck = 1.0 - smoothstep(maxRange * 0.3, maxRange, absDiff);

                    // Only count as occluder if sample is meaningfully closer
                    ao += step(0.005, depthDiff) * rangeCheck;
                }

                ao = 1.0 - (ao / float(samples));
                ao = clamp(ao, 0.0, 1.0);

                // Gentle power curve for natural AO falloff
                ao = pow(ao, 1.3);

                // ======================================================
                //  2. Contact shadows from sun direction
                // ======================================================
                float shadow = computeContactShadow(uv, linDepth);

                // ======================================================
                //  3. Combine AO and contact shadow
                // ======================================================
                float final_ao = ao * shadow;

                imageStore(aoOutput, pos, vec4(final_ao));
            }
            """;
}
