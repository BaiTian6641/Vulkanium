package net.vulkanium.render.hdr;

import net.vulkanium.core.VulkaniumDevice;
import net.vulkanium.render.shader.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Color space conversion via Vulkan compute shader — transforms the final composite output
 * between color gamuts (sRGB → Display P3, Rec.2020, DCI-P3, Adobe RGB, etc.).
 *
 * <h3>Reference: Iris ColorSpaceComputeConverter</h3>
 * <p>Modeled after {@code net.irisshaders.iris.pathways.colorspace.ColorSpaceComputeConverter}
 * from the <a href="https://github.com/IrisShaders/Iris">Iris Shaders</a> project (LGPL-3.0).
 * Iris uses a GLSL compute shader ({@code colorSpace.csh}) with color-space-specific
 * #defines to select the correct gamut transform matrices. The shader applies:</p>
 * <ol>
 *   <li>Inverse sRGB EOTF (gamma decode to linear)</li>
 *   <li>3×3 gamut matrix (sRGB → XYZ → target gamut)</li>
 *   <li>Target EOTF (gamma encode for display)</li>
 * </ol>
 *
 * <h3>Vulkan Implementation</h3>
 * <p>This converter processes a VkImage in-place using a compute shader dispatch.
 * The image must be in VK_IMAGE_LAYOUT_GENERAL (storage image access).
 * After dispatch, the image contains pixels in the target color space
 * ready for presentation to a color-space-aware swapchain.</p>
 */
public class ColorSpaceConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ColorSpaceConverter");

    // ── Vulkan objects ──
    private long computePipeline = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;
    private long descriptorPool = VK_NULL_HANDLE;
    private long shaderModule = VK_NULL_HANDLE;
    private boolean initialized = false;

    // ── Configuration ──
    private HdrConfig.ColorSpaceTarget targetColorSpace;
    private int workGroupSizeX = 8;
    private int workGroupSizeY = 8;

    /**
     * Initializes the color space converter for the given target color space.
     *
     * <p>Compiles the GLSL compute shader with the appropriate #defines
     * for the selected color space, creates the Vulkan compute pipeline
     * and descriptor set layout.</p>
     *
     * @param targetSpace The desired output color space
     * @param compiler    ShaderCompiler for GLSL → SPIR-V compilation
     */
    public void initialize(HdrConfig.ColorSpaceTarget targetSpace, ShaderCompiler compiler) {
        if (initialized) destroy();

        this.targetColorSpace = targetSpace;
        VkDevice device = VulkaniumDevice.getGlobalDevice();

        if (targetSpace == HdrConfig.ColorSpaceTarget.SRGB) {
            LOGGER.info("[HDR/CS] Target=sRGB — no color space conversion needed");
            initialized = true;
            return;
        }

        try {
            // 1. Generate GLSL source with color space defines
            String glslSource = generateComputeSource(targetSpace);

            // 2. Compile to SPIR-V
            ShaderCompiler.CompilationResult result = compiler.compileCompute(
                    glslSource, "colorspace_convert.comp");

            if (!result.isSuccess()) {
                LOGGER.error("[HDR/CS] Color space compute shader compilation failed: {}",
                        result.errorMessage);
                return;
            }

            ByteBuffer spirv = result.spirvBinary;

            // 3. Create VkShaderModule
            shaderModule = createShaderModule(device, spirv);

            // 4. Create descriptor set layout (1 storage image binding)
            createDescriptorSetLayout(device);

            // 5. Create pipeline layout
            createPipelineLayout(device);

            // 6. Create compute pipeline
            createComputePipeline(device);

            // 7. Create descriptor pool
            createDescriptorPool(device);

            initialized = true;
            LOGGER.info("[HDR/CS] Color space converter initialized — target={}", targetSpace);

        } catch (Exception e) {
            LOGGER.error("[HDR/CS] Failed to initialize color space converter: {}", e.getMessage());
            LOGGER.debug("[HDR/CS]   Stack trace:", e);
            destroy();
        }
    }

    /**
     * Dispatches the color space conversion compute shader on the given image.
     *
     * <p>The image must be in VK_IMAGE_LAYOUT_GENERAL before dispatch.
     * After dispatch, the image contains pixels in the target color space.</p>
     *
     * @param cmd       Active command buffer (outside any render pass)
     * @param imageView Image view to convert (storage image binding)
     * @param width     Image width
     * @param height    Image height
     */
    public void convert(VkCommandBuffer cmd, long imageView, int width, int height) {
        if (!initialized || targetColorSpace == HdrConfig.ColorSpaceTarget.SRGB) return;
        if (computePipeline == VK_NULL_HANDLE) return;

        VkDevice device = VulkaniumDevice.getGlobalDevice();

        try (MemoryStack stack = stackPush()) {
            // Allocate and update descriptor set
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pSet = stack.longs(VK_NULL_HANDLE);
            int r = vkAllocateDescriptorSets(device, allocInfo, pSet);
            if (r != VK_SUCCESS) {
                LOGGER.warn("[HDR/CS] Failed to allocate descriptor set: {}", r);
                return;
            }
            long descriptorSet = pSet.get(0);

            // Update with the storage image
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(imageView)
                    .sampler(VK_NULL_HANDLE);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(device, write, null);

            // Bind pipeline and descriptor set
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);

            // Dispatch
            int groupsX = (width + workGroupSizeX - 1) / workGroupSizeX;
            int groupsY = (height + workGroupSizeY - 1) / workGroupSizeY;
            vkCmdDispatch(cmd, groupsX, groupsY, 1);

            // Barrier: compute write → fragment/transfer read
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, barrier, null, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GLSL Source Generation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generates the GLSL compute shader source for color space conversion.
     *
     * <p>Reference: Iris colorSpace.csh (Iris Shaders, LGPL-3.0)
     * — gamut transform matrices from sRGB→XYZ→target using
     * Bradford chromatic adaptation. EOTF curves per standard.</p>
     */
    private String generateComputeSource(HdrConfig.ColorSpaceTarget target) {
        return """
                #version 450
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(rgba16f, set = 0, binding = 0) uniform image2D colorImage;

                // ── Transfer Functions (Reference: Iris colorSpace.csh, LGPL-3.0) ──

                // sRGB EOTF — https://en.wikipedia.org/wiki/SRGB#Transfer_function
                vec3 EOTF_Curve(vec3 LinearCV, float LinearFactor, float Exponent, float Alpha, float Beta) {
                    return mix(LinearCV * LinearFactor, clamp(Alpha * pow(LinearCV, vec3(Exponent)) - (Alpha - 1.0), 0.0, 1.0), step(Beta, LinearCV));
                }
                vec3 EOTF_IEC61966(vec3 LinearCV) {
                    return EOTF_Curve(LinearCV, 12.92, 1.0 / 2.4, 1.055, 0.0031308);
                }
                vec3 InverseEOTF_IEC61966(vec3 DisplayCV) {
                    return max(mix(DisplayCV / 12.92, pow(0.947867 * DisplayCV + 0.0521327, vec3(2.4)), step(0.04045, DisplayCV)), 0.0);
                }
                vec3 EOTF_BT709(vec3 LinearCV) {
                    return EOTF_Curve(LinearCV, 4.5, 0.45, 1.099, 0.018);
                }
                vec3 EOTF_P3DCI(vec3 LinearCV) {
                    return pow(max(LinearCV, 0.0), vec3(1.0 / 2.6));
                }
                vec3 EOTF_Adobe(vec3 LinearCV) {
                    return pow(max(LinearCV, 0.0), vec3(1.0 / 2.2));
                }

                // PQ (Perceptual Quantizer) for HDR10 — SMPTE ST 2084
                vec3 EOTF_PQ(vec3 Lin) {
                    const float m1 = 0.1593017578125;
                    const float m2 = 78.84375;
                    const float c1 = 0.8359375;
                    const float c2 = 18.8515625;
                    const float c3 = 18.6875;
                    vec3 Ym1 = pow(max(Lin / 10000.0, 0.0), vec3(m1));
                    return pow((c1 + c2 * Ym1) / (1.0 + c3 * Ym1), vec3(m2));
                }

                // ── Gamut Matrices (Reference: Iris colorSpace.csh, LGPL-3.0) ──

                const mat3 sRGB_XYZ = mat3(
                    0.4124564, 0.3575761, 0.1804375,
                    0.2126729, 0.7151522, 0.0721750,
                    0.0193339, 0.1191920, 0.9503041
                );
                const mat3 XYZ_P3D65 = mat3(
                    2.4933963, -0.9313459, -0.4026945,
                    -0.8294868,  1.7626597,  0.0236246,
                    0.0358507, -0.0761827,  0.9570140
                );
                const mat3 XYZ_REC2020 = mat3(
                    1.7166511880, -0.3556707838, -0.2533662814,
                    -0.6666843518,  1.6164812366,  0.0157685458,
                    0.0176398574, -0.0427706133,  0.9421031212
                );
                const mat3 XYZ_AdobeRGB = mat3(
                    2.04158790381075,  -0.56500697427886,  -0.34473135077833,
                    -0.96924363628088,   1.87596750150772, 0.0415550574071756,
                    0.0134442806320311, -0.118362392231018,   1.01517499439121
                );
                const mat3 D65_DCI = mat3(
                    1.02449672775258,     0.0151635410224164, 0.0196885223342068,
                    0.0256121933371582,   0.972586305624413,  0.00471635229242733,
                    0.00638423065008769, -0.0122680827367302, 1.14794244517368
                );

                #define SRGB_TARGET 0
                #define DCI_P3_TARGET 1
                #define DISPLAY_P3_TARGET 2
                #define REC2020_TARGET 3
                #define ADOBE_RGB_TARGET 4

                #define CURRENT_COLOR_SPACE """ + target.index + """

                void main() {
                    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                    ivec2 imgSize = imageSize(colorImage);
                    if (pixel.x >= imgSize.x || pixel.y >= imgSize.y) return;

                    vec4 color = imageLoad(colorImage, pixel);

                    #if CURRENT_COLOR_SPACE != SRGB_TARGET
                    // Decode sRGB gamma to linear
                    vec3 linear = InverseEOTF_IEC61966(color.rgb);

                    #if CURRENT_COLOR_SPACE == DCI_P3_TARGET
                        vec3 target = linear * ((sRGB_XYZ) * XYZ_P3D65) * D65_DCI;
                        target = EOTF_P3DCI(target);
                    #elif CURRENT_COLOR_SPACE == DISPLAY_P3_TARGET
                        vec3 target = linear * sRGB_XYZ * XYZ_P3D65;
                        target = EOTF_IEC61966(target);
                    #elif CURRENT_COLOR_SPACE == REC2020_TARGET
                        vec3 target = linear * sRGB_XYZ * XYZ_REC2020;
                        target = EOTF_BT709(target);
                    #elif CURRENT_COLOR_SPACE == ADOBE_RGB_TARGET
                        vec3 target = linear * sRGB_XYZ * XYZ_AdobeRGB;
                        target = EOTF_Adobe(target);
                    #endif

                    imageStore(colorImage, pixel, vec4(target, color.a));
                    #endif
                }
                """;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Vulkan Object Creation
    // ═══════════════════════════════════════════════════════════════

    private long createShaderModule(VkDevice device, ByteBuffer spirv) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);

            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            int r = vkCreateShaderModule(device, createInfo, null, pModule);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateShaderModule failed: " + r);
            return pModule.get(0);
        }
    }

    private void createDescriptorSetLayout(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            int r = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateDescriptorSetLayout failed: " + r);
            descriptorSetLayout = pLayout.get(0);
        }
    }

    private void createPipelineLayout(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            int r = vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreatePipelineLayout failed: " + r);
            pipelineLayout = pLayout.get(0);
        }
    }

    private void createComputePipeline(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stage)
                    .layout(pipelineLayout);

            LongBuffer pPipeline = stack.longs(VK_NULL_HANDLE);
            int r = vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateComputePipelines failed: " + r);
            computePipeline = pPipeline.get(0);
        }
    }

    private void createDescriptorPool(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(16); // enough for multiple frames

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(16)
                    .pPoolSizes(poolSize);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            int r = vkCreateDescriptorPool(device, poolInfo, null, pPool);
            if (r != VK_SUCCESS) throw new RuntimeException("vkCreateDescriptorPool failed: " + r);
            descriptorPool = pPool.get(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════

    public void destroy() {
        VkDevice device = VulkaniumDevice.getGlobalDevice();
        if (device == null) return;

        if (computePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, computePipeline, null);
            computePipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayout = VK_NULL_HANDLE;
        }
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = VK_NULL_HANDLE;
        }
        if (shaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = VK_NULL_HANDLE;
        }
        initialized = false;
    }

    public boolean isInitialized() { return initialized; }
    public HdrConfig.ColorSpaceTarget getTargetColorSpace() { return targetColorSpace; }
}
