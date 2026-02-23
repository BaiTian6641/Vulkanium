package net.vulkanium.rt;

import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AMD FidelityFX Super Resolution 3.1 upscaler implementation.
 *
 * <p>FSR 3.1 is an open-source temporal upscaling solution that works on all
 * GPUs (AMD, NVIDIA, Intel). It uses a Lanczos-based spatial upscaler combined
 * with a robust temporal accumulation algorithm.</p>
 *
 * <h3>Implementation</h3>
 * <p>FSR 3.1 is implemented as a series of compute shader passes:</p>
 * <ol>
 *   <li><b>Prepare Inputs</b> — Linearize depth, compute luminance, generate locks</li>
 *   <li><b>Compute Luminance Pyramid</b> — Downsample luminance (auto-exposure)</li>
 *   <li><b>Classify Shadows/Reflections</b> — Detect reactive regions</li>
 *   <li><b>Reconstruct &amp; Dilate</b> — Dilate motion vectors, reconstruct prev UV</li>
 *   <li><b>Reproject</b> — Motion-compensated history feedback</li>
 *   <li><b>Accumulate</b> — Blend current + history with adaptive weights</li>
 *   <li><b>RCAS (Optional)</b> — Robust Contrast-Adaptive Sharpening</li>
 * </ol>
 *
 * <p>In practice, the FSR SDK provides precompiled SPIR-V shaders that we integrate
 * via compute dispatch. This implementation stubs the shader calls with the correct
 * resource bindings and dispatch dimensions.</p>
 */
public class FSR3Upscaler implements Upscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/FSR3");

    private final VulkaniumDevice device;
    private final VulkaniumMemory memory;

    private QualityPreset currentPreset = QualityPreset.QUALITY;

    // Resolution
    private int inputWidth, inputHeight;
    private int outputWidth, outputHeight;

    // Compute pipelines (one per FSR pass)
    private long preparePipeline;
    private long luminancePipeline;
    private long reconstructPipeline;
    private long reprojectPipeline;
    private long accumulatePipeline;
    private long rcasPipeline;

    // Internal resources
    private long luminancePyramid;  // Mip chain for auto-exposure
    private long lockTexture;       // Temporal accumulation locks
    private long prevUpscaledColor; // Feedback buffer (ping-pong)

    private boolean initialized = false;
    private boolean rcasEnabled = true;
    private float rcasSharpness = 0.2f; // 0.0 = no sharpening, 1.0 = max
    private boolean loggedMissingPipelines = false;

    public FSR3Upscaler(VulkaniumDevice device, VulkaniumMemory memory) {
        this.device = device;
        this.memory = memory;
    }

    @Override
    public Type getType() {
        return Type.FSR3;
    }

    @Override
    public boolean isAvailable() {
        // FSR 3.1 works on all Vulkan 1.2+ GPUs with compute shaders
        return true;
    }

    @Override
    public void init(int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;

        // Allocate internal resources
        // Luminance pyramid: log2(max(w,h)) mip levels
        int mipLevels = (int) Math.ceil(Math.log(Math.max(inputWidth, inputHeight)) / Math.log(2));
        luminancePyramid = memory.createImage2D(inputWidth, inputHeight,
            VK10.VK_FORMAT_R16_SFLOAT,
            VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        LOGGER.debug("FSR3 luminance pyramid image allocated (mips={})", mipLevels);

        // Lock texture: same res as output
        lockTexture = memory.createImage2D(outputWidth, outputHeight,
            VK10.VK_FORMAT_R8_UINT,
            VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
        LOGGER.debug("FSR3 lock texture allocated");

        // Previous upscaled color: output resolution
        prevUpscaledColor = memory.createImage2D(outputWidth, outputHeight,
            VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
            VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
        LOGGER.debug("FSR3 previous upscaled color image allocated");

        // Create compute pipelines from FSR SPIR-V shaders
        LOGGER.info("FSR3 compute shaders are not yet packaged; running in resource-allocated, dispatch-disabled mode");

        initialized = true;
        LOGGER.info("FSR 3.1 initialized: {}×{} → {}×{} (preset={})",
                inputWidth, inputHeight, outputWidth, outputHeight,
                currentPreset.getDisplayName());
    }

    @Override
    public void execute(long commandBuffer, long inputColor, long outputColor,
                        long depth, long motionVectors,
                        float jitterX, float jitterY, float deltaTime) {
        if (!initialized) return;

        int inputGroupsX = (inputWidth + 15) / 16;
        int inputGroupsY = (inputHeight + 15) / 16;
        int outputGroupsX = (outputWidth + 15) / 16;
        int outputGroupsY = (outputHeight + 15) / 16;

        // Pass 1: Prepare inputs
        dispatchIfAvailable(commandBuffer, preparePipeline, inputGroupsX, inputGroupsY, "prepare");

        // Pass 2: Compute luminance pyramid
        if (luminancePipeline != 0L) {
            dispatchIfAvailable(commandBuffer, luminancePipeline, inputGroupsX, inputGroupsY, "luminance_mip_0");
            insertComputeBarrier(commandBuffer, "luminance_mip_barrier");
        }

        // Pass 3: Reconstruct & dilate
        dispatchIfAvailable(commandBuffer, reconstructPipeline, outputGroupsX, outputGroupsY, "reconstruct");

        // Pass 4: Reproject
        dispatchIfAvailable(commandBuffer, reprojectPipeline, outputGroupsX, outputGroupsY, "reproject");

        // Pass 5: Accumulate
        dispatchIfAvailable(commandBuffer, accumulatePipeline, outputGroupsX, outputGroupsY, "accumulate");

        // Pass 6: RCAS sharpening (optional)
        if (rcasEnabled) {
            dispatchIfAvailable(commandBuffer, rcasPipeline, outputGroupsX, outputGroupsY, "rcas");
        }

        // Flip feedback buffer
        LOGGER.debug("FSR3 execute complete (jitter=({}, {}), dt={}, sharpness={})",
                jitterX, jitterY, deltaTime, rcasSharpness);
    }

    @Override
    public void setQualityPreset(QualityPreset preset) {
        this.currentPreset = preset;
    }

    @Override
    public QualityPreset getQualityPreset() {
        return currentPreset;
    }

    public void setRCASEnabled(boolean enabled) {
        this.rcasEnabled = enabled;
    }

    public void setRCASSharpness(float sharpness) {
        this.rcasSharpness = Math.min(Math.max(sharpness, 0.0f), 1.0f);
    }

    @Override
    public void destroy() {
        VkCommandBuffer unused = null;
        var vkDevice = device.getLogicalDevice();
        if (preparePipeline != 0L) VK10.vkDestroyPipeline(vkDevice, preparePipeline, null);
        if (luminancePipeline != 0L) VK10.vkDestroyPipeline(vkDevice, luminancePipeline, null);
        if (reconstructPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, reconstructPipeline, null);
        if (reprojectPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, reprojectPipeline, null);
        if (accumulatePipeline != 0L) VK10.vkDestroyPipeline(vkDevice, accumulatePipeline, null);
        if (rcasPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, rcasPipeline, null);
        preparePipeline = luminancePipeline = reconstructPipeline = reprojectPipeline = accumulatePipeline = rcasPipeline = 0L;

        if (luminancePyramid != 0L) memory.destroyImage(luminancePyramid);
        if (lockTexture != 0L) memory.destroyImage(lockTexture);
        if (prevUpscaledColor != 0L) memory.destroyImage(prevUpscaledColor);
        luminancePyramid = lockTexture = prevUpscaledColor = 0L;

        initialized = false;
        LOGGER.info("FSR 3.1 destroyed");
    }

    private void dispatchIfAvailable(long commandBuffer, long pipeline, int groupsX, int groupsY, String passName) {
        if (pipeline == 0L) {
            if (!loggedMissingPipelines) {
                LOGGER.warn("FSR3 dispatch skipped: compute pipelines are not compiled yet (first missing pass: {})", passName);
                loggedMissingPipelines = true;
            }
            return;
        }
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, device.getLogicalDevice());
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VK10.vkCmdDispatch(cmd, groupsX, groupsY, 1);
        LOGGER.debug("FSR3 pass '{}' dispatched ({}x{})", passName, groupsX, groupsY);
    }

    private void insertComputeBarrier(long commandBuffer, String tag) {
        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, device.getLogicalDevice());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    barrier,
                    null,
                    null);
        }
        LOGGER.debug("FSR3 compute barrier inserted ({})", tag);
    }
}
