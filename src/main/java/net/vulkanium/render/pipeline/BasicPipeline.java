package net.vulkanium.render.pipeline;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages Vulkan graphics pipelines for Minecraft shader programs.
 *
 * <p>Following VulkanMod's pattern: pipelines are lazily created based on
 * the combination of shader + render state (blend, depth, cull, topology).
 * Each unique state combination gets its own VkPipeline.</p>
 */
public class BasicPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Pipeline");
    private static final int MAX_TEXTURE_BINDINGS = 64;

    private VkDevice device;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;
    private long vertShaderModule = VK_NULL_HANDLE;
    private long fragShaderModule = VK_NULL_HANDLE;
    private long geomShaderModule = VK_NULL_HANDLE;
    private boolean ownsShaderModules = true;
    private boolean ownsDescriptorSetLayout = true;

    // Pipeline cache keyed by state hash
    private final Map<Long, Long> pipelineCache = new ConcurrentHashMap<>();
    private long renderPass;

    private VertexFormat vertexFormat;
    private String name;
    private int colorAttachmentCount = 1;

    /**
     * Creates a pipeline for the given shader sources and vertex format.
     */
    public void initialize(VkDevice device, long renderPass, String name,
                           ByteBuffer vertSpirv, ByteBuffer fragSpirv,
                           VertexFormat vertexFormat) {
        this.device = device;
        this.renderPass = renderPass;
        this.name = name;
        this.vertexFormat = vertexFormat;
        this.ownsShaderModules = true;

        // Create shader modules
        this.vertShaderModule = createShaderModule(vertSpirv);
        this.fragShaderModule = createShaderModule(fragSpirv);

        // Free the SPIRV buffers
        memFree(vertSpirv);
        memFree(fragSpirv);

        // Create descriptor set layout: binding 0 = UBO (vertex), binding 1 = UBO (fragment), binding 2 = sampler
        createDescriptorSetLayout();

        // Create pipeline layout
        createPipelineLayout();

        LOGGER.info("Pipeline '{}' initialized (vert={}, frag={})", name,
                vertShaderModule != VK_NULL_HANDLE, fragShaderModule != VK_NULL_HANDLE);
    }

    /**
     * Initializes a pipeline using pre-created shader modules.
     *
     * <p>The modules are owned by the caller and will not be destroyed by this pipeline.</p>
     */
    public void initializeWithModules(VkDevice device, long renderPass, String name,
                                      long vertShaderModule, long fragShaderModule,
                                      VertexFormat vertexFormat) {
        initializeWithModules(device, renderPass, name, vertShaderModule, fragShaderModule, VK_NULL_HANDLE, vertexFormat);
    }

    /**
     * Initializes a pipeline using pre-created shader modules (optionally with geometry stage).
     */
    public void initializeWithModules(VkDevice device, long renderPass, String name,
                                      long vertShaderModule, long fragShaderModule,
                                      long geomShaderModule,
                                      VertexFormat vertexFormat) {
        initializeWithModules(device, renderPass, name, vertShaderModule, fragShaderModule,
            geomShaderModule, vertexFormat, 1, VK_NULL_HANDLE);
    }

    /**
     * Initializes a pipeline using pre-created shader modules with explicit color attachment count.
     * Use {@code colorAttachmentCount > 1} for MRT (Multiple Render Targets) render passes.
     */
    public void initializeWithModules(VkDevice device, long renderPass, String name,
                                      long vertShaderModule, long fragShaderModule,
                                      long geomShaderModule,
                                      VertexFormat vertexFormat, int colorAttachmentCount) {
        initializeWithModules(device, renderPass, name, vertShaderModule, fragShaderModule,
            geomShaderModule, vertexFormat, colorAttachmentCount, VK_NULL_HANDLE);
        }

        /**
         * Initializes a pipeline using pre-created shader modules, with optional external descriptor set layout reuse.
         *
         * <p>If {@code sharedDescriptorSetLayout != VK_NULL_HANDLE}, the pipeline layout will be created
         * using that descriptor set layout and this pipeline will not destroy it.</p>
         */
        public void initializeWithModules(VkDevice device, long renderPass, String name,
                          long vertShaderModule, long fragShaderModule,
                          long geomShaderModule,
                          VertexFormat vertexFormat, int colorAttachmentCount,
                          long sharedDescriptorSetLayout) {
        this.device = device;
        this.renderPass = renderPass;
        this.name = name;
        this.vertexFormat = vertexFormat;
        this.ownsShaderModules = false;

        this.vertShaderModule = vertShaderModule;
        this.fragShaderModule = fragShaderModule;
        this.geomShaderModule = geomShaderModule;
        this.colorAttachmentCount = colorAttachmentCount;

        if (sharedDescriptorSetLayout != VK_NULL_HANDLE) {
            this.descriptorSetLayout = sharedDescriptorSetLayout;
            this.ownsDescriptorSetLayout = false;
        } else {
            this.ownsDescriptorSetLayout = true;
            createDescriptorSetLayout();
        }
        createPipelineLayout();

        LOGGER.info("Pipeline '{}' initialized from external modules (vert=0x{}, frag=0x{}, geom=0x{})",
                name,
                Long.toHexString(vertShaderModule),
            Long.toHexString(fragShaderModule),
            Long.toHexString(geomShaderModule));
    }

    /**
     * Front face winding order.
     *
     * <p>Vulkanium uses two viewport conventions:</p>
     * <ul>
     *   <li><b>GUI rendering</b>: negative-height viewport ({@code height = -H, y = H}).
     *       VK_KHR_maintenance1 (core in Vulkan 1.1) automatically inverts the winding
     *       determination for negative viewport height, so CCW is correct.</li>
     *   <li><b>World rendering</b> (G-buffer, shadow, composite): positive-height viewport.
     *       The viewport transform preserves the sign of the triangle's signed area
     *       (both X and Y scale factors are positive), so CCW content from OpenGL
     *       remains CCW in framebuffer coordinates. CCW is correct here too.</li>
     * </ul>
     *
     * <p>All pipelines use CCW, which is correct for both viewport conventions.</p>
     */
    private int frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    public void setFrontFace(int face) {
        this.frontFace = face;
        // Invalidate cached pipelines — they embed the old front face
        for (long pipeline : pipelineCache.values()) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
        }
        pipelineCache.clear();
    }

    private void createDescriptorSetLayout() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1 + MAX_TEXTURE_BINDINGS, stack);

            // Include geometry stage in descriptor visibility when geometry shader is present
            int uboStages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
            int samplerStages = VK_SHADER_STAGE_FRAGMENT_BIT;
            if (geomShaderModule != VK_NULL_HANDLE) {
                uboStages |= VK_SHADER_STAGE_GEOMETRY_BIT;
                samplerStages |= VK_SHADER_STAGE_GEOMETRY_BIT;
            }

            // Binding 0: Combined UBO (MVP + ColorModulator) — DYNAMIC for per-draw offset
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(1)
                    .stageFlags(uboStages);

                // Bindings 1..N: Texture samplers
                for (int binding = 1; binding <= MAX_TEXTURE_BINDINGS; binding++) {
                bindings.get(binding)
                    .binding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(samplerStages);
                }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            checkResult(result, "Failed to create descriptor set layout");
            this.descriptorSetLayout = pLayout.get(0);
        }
    }

    private void createPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pLayout = stack.longs(VK_NULL_HANDLE);
            int result = vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            checkResult(result, "Failed to create pipeline layout");
            this.pipelineLayout = pLayout.get(0);
        }
    }

    /**
     * Gets or creates a pipeline for the given render state.
     */
    public long getOrCreatePipeline(boolean blendEnabled, boolean depthTestEnabled,
                                     boolean depthWriteEnabled, boolean cullEnabled, int topology,
                                     int srcColorBlend, int dstColorBlend, int srcAlphaBlend, int dstAlphaBlend,
                                     int depthCompareOp) {
        long stateKey = encodeState(blendEnabled, depthTestEnabled, depthWriteEnabled, cullEnabled, topology,
                srcColorBlend, dstColorBlend, srcAlphaBlend, dstAlphaBlend, depthCompareOp);
        return pipelineCache.computeIfAbsent(stateKey, k ->
                createGraphicsPipeline(blendEnabled, depthTestEnabled, depthWriteEnabled, cullEnabled, topology,
                        srcColorBlend, dstColorBlend, srcAlphaBlend, dstAlphaBlend, depthCompareOp));
    }

    /** Convenience: legacy 5-arg overload for callers that don't need per-draw blend/depth. */
    public long getOrCreatePipeline(boolean blendEnabled, boolean depthTestEnabled,
                                     boolean depthWriteEnabled, boolean cullEnabled, int topology) {
        return getOrCreatePipeline(blendEnabled, depthTestEnabled, depthWriteEnabled, cullEnabled, topology,
                VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VK_COMPARE_OP_LESS_OR_EQUAL);
    }

    private long createGraphicsPipeline(boolean blendEnabled, boolean depthTestEnabled,
                                         boolean depthWriteEnabled, boolean cullEnabled, int topology,
                                         int srcColorBlend, int dstColorBlend,
                                         int srcAlphaBlend, int dstAlphaBlend,
                                         int depthCompareOp) {
        try (MemoryStack stack = stackPush()) {
            // Shader stages
            int stageCount = geomShaderModule != VK_NULL_HANDLE ? 3 : 2;
            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            shaderStages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertShaderModule)
                    .pName(stack.UTF8("main"));
            shaderStages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragShaderModule)
                    .pName(stack.UTF8("main"));
            if (geomShaderModule != VK_NULL_HANDLE) {
            shaderStages.get(2)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_GEOMETRY_BIT)
                .module(geomShaderModule)
                .pName(stack.UTF8("main"));
            }

            // Vertex input
            VkVertexInputBindingDescription.Buffer bindingDesc = VkVertexInputBindingDescription.calloc(1, stack);
            bindingDesc.get(0)
                    .binding(0)
                    .stride(vertexFormat.getVertexSize())
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attrDescs = createAttributeDescriptions(vertexFormat, stack);

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindingDesc)
                    .pVertexAttributeDescriptions(attrDescs);

            // Input assembly
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(topology)
                    .primitiveRestartEnable(false);

            // Dynamic viewport + scissor + depth bias
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_BIAS));

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1)
                    .scissorCount(1);

            // Rasterization
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(cullEnabled ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                    .frontFace(frontFace)
                    .depthBiasEnable(true);

            // Multisampling
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // Depth-stencil
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(depthTestEnabled)
                    .depthWriteEnable(depthWriteEnabled)
                    .depthCompareOp(depthCompareOp)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            // Color blend: one attachment state per color attachment in the render pass.
            // For MRT G-buffer pipelines (colorAttachmentCount > 1), only the first
            // attachment (colortex0 / albedo) uses the requested blend state. All other
            // G-buffer targets (normals, specular, etc.) write through without blending,
            // so translucent geometry (water, ice) doesn't zero-out encoded G-buffer data.
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(colorAttachmentCount, stack);
            for (int att = 0; att < colorAttachmentCount; att++) {
                colorBlendAttachment.get(att)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

                // Only blend attachment 0 (colortex0); disable for other MRT targets
                boolean attachBlend = blendEnabled && (att == 0 || colorAttachmentCount == 1);
                colorBlendAttachment.get(att).blendEnable(attachBlend);
                if (attachBlend) {
                    colorBlendAttachment.get(att)
                            .srcColorBlendFactor(srcColorBlend)
                            .dstColorBlendFactor(dstColorBlend)
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(srcAlphaBlend)
                            .dstAlphaBlendFactor(dstAlphaBlend)
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
            }

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            // Create graphics pipeline
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);

            LongBuffer pPipeline = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            checkResult(result, "Failed to create graphics pipeline");

            long pipeline = pPipeline.get(0);
            LOGGER.debug("Created pipeline variant for '{}' (blend={}, depth={}, depthWrite={}, cull={}, topo={})",
                    name, blendEnabled, depthTestEnabled, depthWriteEnabled, cullEnabled, topology);
            return pipeline;
        }
    }

    /**
     * Converts Minecraft's VertexFormat to Vulkan vertex attribute descriptions.
     *
     * <p>CRITICAL: Locations must match the shader layout, NOT the memory order.
     * MC formats can vary element order (e.g. POSITION_COLOR_TEX_LIGHTMAP has
     * Color before UV, but our shader expects Position=0, UV=1, Color=2).
     * We map locations based on semantic usage to match the shader.</p>
     *
     * <p>Shader location assignments:
     * <ul>
     *   <li>position_only: 0=Position</li>
     *   <li>position_color: 0=Position, 1=Color</li>
     *   <li>position_tex: 0=Position, 1=UV</li>
     *   <li>position_tex_color: 0=Position, 1=UV, 2=Color</li>
     * </ul></p>
     */
    private VkVertexInputAttributeDescription.Buffer createAttributeDescriptions(
            VertexFormat format, MemoryStack stack) {
        List<VertexFormatElement> elements = format.getElements();

        // Determine which elements exist and their byte offsets.
        // Use element.getIndex() to correctly identify UV sub-types:
        //   UV index 0 = texture coordinates (UV0)
        //   UV index 1 = overlay (hurt/flash, entity-only — skipped)
        //   UV index 2 = lightmap (UV2)
        boolean hasUV = false, hasColor = false, hasUV2 = false, hasNormal = false;
        boolean hasGeneric = false;
        for (VertexFormatElement e : elements) {
            if (e.getUsage() == VertexFormatElement.Usage.COLOR) hasColor = true;
            if (e.getUsage() == VertexFormatElement.Usage.NORMAL) hasNormal = true;
            if (e.getUsage() == VertexFormatElement.Usage.GENERIC) hasGeneric = true;
            if (e.getUsage() == VertexFormatElement.Usage.UV) {
                if (e.getIndex() == 0) hasUV = true;
                else if (e.getIndex() == 2) hasUV2 = true;
            }
        }

        // Count attributes we'll emit
        int attrCount = 1; // always have Position
        if (hasUV) attrCount++;
        if (hasColor) attrCount++;
        if (hasUV2) attrCount++;
        if (hasNormal) attrCount++;
        if (hasGeneric) attrCount++;

        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(attrCount, stack);
        int attrIdx = 0;

        // Walk format elements: compute each element's byte offset, and assign
        // the shader location based on semantic, not order.
        // Location scheme:
        //   0 = Position
        //   1 = UV0 (when present)
        //   2 = Color (when hasUV, else 1)
        //   3 = UV2 (lightmap, when present)
        //   4 = Normal (when present)
        int offset = 0;
        boolean positionDone = false, uvDone = false, colorDone = false;
        boolean uv2Done = false, normalDone = false;
        for (VertexFormatElement element : elements) {
            if (element.getUsage() == VertexFormatElement.Usage.PADDING) {
                offset += element.getByteSize();
                continue;
            }

            int loc = -1;
            switch (element.getUsage()) {
                case POSITION -> {
                    if (!positionDone) { loc = 0; positionDone = true; }
                }
                case UV -> {
                    // Use element index to distinguish UV sub-types:
                    //   index 0 = texture coords (UV0) → location 1
                    //   index 1 = overlay (entity hurt/flash) → skip (not used in shader)
                    //   index 2 = lightmap (UV2) → location 3
                    int uvIndex = element.getIndex();
                    if (uvIndex == 0 && !uvDone && hasUV) {
                        loc = 1;
                        uvDone = true;
                    } else if (uvIndex == 2 && !uv2Done && hasUV2) {
                        loc = 3;
                        uv2Done = true;
                    }
                    // UV index 1 (overlay) is intentionally skipped — its bytes are
                    // still in the vertex buffer at the correct offset, just not bound
                    // to any shader input.
                }
                case COLOR -> {
                    if (!colorDone && hasColor) {
                        // Shaderpack pipelines always use the entity vertex layout where
                        // Color is at location 2 (vkm_Entity_Color).  For non-shaderpack
                        // pipelines without UV0, Color can go to location 1.
                        // Without this, sky (POSITION_COLOR) feeds Color into location 1
                        // where the shader expects UV0, leaving vkm_Entity_Color = (0,0,0,0).
                        boolean isShaderpack = name != null && name.startsWith("shaderpack_");
                        loc = (isShaderpack || hasUV) ? 2 : 1;
                        colorDone = true;
                    }
                }
                case NORMAL -> {
                    if (!normalDone && hasNormal) {
                        // Normal is location 4
                        loc = 4;
                        normalDone = true;
                    }
                }
                default -> {} // Unhandled usage types
            }

            // GENERIC elements (mc_Entity etc.) — assign after standard elements
            if (element.getUsage() == VertexFormatElement.Usage.GENERIC && loc < 0) {
                loc = 5; // mc_Entity at location 5 (ivec2)
            }

            if (loc >= 0 && attrIdx < attrCount) {
                int vkFormat = mapVertexFormat(element);
                attrs.get(attrIdx)
                        .binding(0)
                        .location(loc)
                        .format(vkFormat)
                        .offset(offset);
                attrIdx++;
            }

            offset += element.getByteSize();
        }
        return attrs;
    }

    /**
     * Maps Minecraft VertexFormatElement to VkFormat.
     */
    private int mapVertexFormat(VertexFormatElement element) {
        VertexFormatElement.Usage usage = element.getUsage();
        VertexFormatElement.Type type = element.getType();
        int count = element.getCount();

        return switch (usage) {
            case POSITION -> switch (type) {
                case FLOAT -> count == 3 ? VK_FORMAT_R32G32B32_SFLOAT : VK_FORMAT_R32G32B32A32_SFLOAT;
                case SHORT -> VK_FORMAT_R16G16B16A16_SINT;
                default -> VK_FORMAT_R32G32B32_SFLOAT;
            };
            case COLOR -> VK_FORMAT_R8G8B8A8_UNORM;
            case UV -> switch (type) {
                case FLOAT -> count == 2 ? VK_FORMAT_R32G32_SFLOAT : VK_FORMAT_R32G32B32_SFLOAT;
                case SHORT -> VK_FORMAT_R16G16_SINT;
                case USHORT -> VK_FORMAT_R16G16_UINT;
                default -> VK_FORMAT_R32G32_SFLOAT;
            };
            case NORMAL -> VK_FORMAT_R8G8B8A8_SNORM;
            case GENERIC -> switch (type) {
                case SHORT -> count == 2 ? VK_FORMAT_R16G16_SINT : VK_FORMAT_R16_SINT;
                case INT -> VK_FORMAT_R32_SINT;
                default -> VK_FORMAT_R32_SINT;
            };
            default -> VK_FORMAT_R32G32B32A32_SFLOAT;
        };
    }

    private long createShaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);

            LongBuffer pModule = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateShaderModule(device, createInfo, null, pModule);
            checkResult(result, "Failed to create shader module");
            return pModule.get(0);
        }
    }

    private long encodeState(boolean blend, boolean depth, boolean depthWrite, boolean cull, int topology,
                             int srcColor, int dstColor, int srcAlpha, int dstAlpha, int depthOp) {
        return (blend ? 1L : 0L) | (depth ? 2L : 0L) | (cull ? 4L : 0L) | (depthWrite ? 8L : 0L)
                | ((long) topology << 8) | ((long) srcColor << 16) | ((long) dstColor << 20)
                | ((long) srcAlpha << 24) | ((long) dstAlpha << 28) | ((long) depthOp << 32);
    }

    public long getPipelineLayout() { return pipelineLayout; }
    public long getDescriptorSetLayout() { return descriptorSetLayout; }
    public String getName() { return name; }
    public int getFrontFace() { return frontFace; }
    public static int getMaxTextureBindings() { return MAX_TEXTURE_BINDINGS; }

    public void destroy() {
        for (long pipeline : pipelineCache.values()) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
        }
        pipelineCache.clear();
        if (ownsShaderModules) {
            if (vertShaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, vertShaderModule, null);
            if (fragShaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, fragShaderModule, null);
            if (geomShaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, geomShaderModule, null);
        }
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (ownsDescriptorSetLayout && descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        }
    }
}
