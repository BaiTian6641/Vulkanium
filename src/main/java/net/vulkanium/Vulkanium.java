package net.vulkanium;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanium.core.*;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import net.vulkanium.render.pipeline.BasicPipeline;
import net.vulkanium.render.pipeline.BasicRenderPass;
import net.vulkanium.render.pipeline.DrawBatcher;
import net.vulkanium.render.pipeline.PipelineRegistry;
import net.vulkanium.render.texture.VulkanTexture;
import net.vulkanium.resource.DescriptorSetManager;
import net.vulkanium.resource.SPIRVCompiler;
import net.vulkanium.resource.StagingRing;
import net.vulkanium.rt.RTCapabilities;
import net.vulkanium.rt.RayTracingRenderer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkanium — Next-generation Vulkan rendering engine for Minecraft.
 *
 * <p>Entry point for the Fabric mod. Initializes the Vulkan subsystem and replaces
 * Minecraft's OpenGL rendering pipeline with a high-performance Vulkan backend.</p>
 */
public class Vulkanium implements ClientModInitializer {
    public static final String MOD_ID = "vulkanium";
    public static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium");

    private static String version;
    private static VulkaniumConfig config;
    private static boolean initialized = false;
    private static boolean vulkanReady = false;
    private static boolean vulkanWasUsed = false; // stays true forever once Vulkan was init'd

    // Core Vulkan subsystem
    private static VulkaniumInstance vulkanInstance;
    private static VulkaniumDevice vulkanDevice;
    private static VulkaniumQueues vulkanQueues;
    private static VulkaniumMemory vulkanMemory;
    private static VulkaniumSwapchain vulkanSwapchain;
    private static VulkaniumCommand vulkanCommand;
    private static VulkaniumSync vulkanSync;
    private static FrameOrchestrator frameOrchestrator;

    // Resource subsystem
    private static DescriptorSetManager descriptorSetManager;
    private static SPIRVCompiler spirvCompiler;
    private static StagingRing stagingRing;

    // Render pipeline
    private static BasicRenderPass mainRenderPass;
    private static PipelineRegistry pipelineRegistry;
    private static DrawBatcher drawBatcher;

    // Chunk buffer pool (eliminates VMA allocation stalls during movement)
    private static net.vulkanium.core.ChunkBufferPool chunkBufferPool;

    // Ray tracing
    private static RayTracingRenderer rtRenderer;
    private static RTCapabilities rtCapabilities;

    // Shaderpack
    private static net.vulkanium.shaderpack.ShaderpackManager shaderpackManager;

    // ─── RT Chunk Mesh Tracking ────────────────────────────────────────
    // When terrain chunk VertexBuffers are uploaded, track them so the RT pipeline
    // can build BLASes for ray-traced shadows and reflections.
    private static final java.util.List<ChunkMeshUpload> pendingChunkMeshes =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Records a newly uploaded terrain chunk mesh for RT processing.
     * Called from MixinVertexBuffer.onUpload() when a terrain-format buffer is uploaded.
     *
     * @param vkBuffer     The VkBuffer handle of the uploaded mesh
     * @param vertexCount  Number of vertices
     * @param vertexSize   Stride in bytes
     * @param bufferSize   Total buffer size in bytes
     */
    public static void notifyChunkMeshUploaded(long vkBuffer, int vertexCount,
                                                int vertexSize, int bufferSize) {
        if (rtRenderer == null || !rtRenderer.isEnabled()) return;
        pendingChunkMeshes.add(new ChunkMeshUpload(vkBuffer, vertexCount, vertexSize, bufferSize, frameCounter));
    }

    private record ChunkMeshUpload(long vkBuffer, int vertexCount, int vertexSize,
                                    int bufferSize, long uploadedAtFrame) {}

    // Placeholder 1x1 white texture
    private static long placeholderImageView = VK_NULL_HANDLE;
    private static long placeholderSampler = VK_NULL_HANDLE;

    // Frame state
    private static boolean frameStarted = false;
    // Track last viewport/scissor to avoid redundant vkCmdSet calls
    private static int lastVpX = -1, lastVpY = -1, lastVpW = -1, lastVpH = -1;
    private static boolean lastScissorEnabled = false;
    private static int lastScX = -1, lastScY = -1, lastScW = -1, lastScH = -1;

    // ─── Deferred Buffer Destruction ───────────────────────────────────
    // Buffers freed during upload() may still be in use by in-flight command buffers.
    // Queue them for deferred deletion after MAX_FRAMES_IN_FLIGHT frames.
    private static final int MAX_FRAMES_IN_FLIGHT = 3;
    private static final java.util.List<DeferredBufferFree> deferredBufferFrees =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private record DeferredBufferFree(long buffer, long allocation, int size, long queuedAtFrame) {}

    /**
     * Queue a buffer for deferred destruction (safe for frame-in-flight).
     * Called from MixinVertexBuffer when a persistent VkBuffer needs to be freed
     * but may still be referenced by an in-flight command buffer.
     * Buffers are returned to the pool after the delay, not destroyed.
     *
     * @param buffer    VkBuffer handle
     * @param allocation VMA allocation handle
     * @param size      Buffer capacity in bytes
     * @param mappedPtr Persistently mapped pointer (0 for non-pool buffers)
     */
    public static void deferBufferFree(long buffer, long allocation, int size, long mappedPtr) {
        if (buffer == VK_NULL_HANDLE) return;
        if (chunkBufferPool != null && chunkBufferPool.isInitialized()) {
            // Route through pool — buffer will be reused after in-flight frames complete
            chunkBufferPool.deferRelease(buffer, allocation, size, mappedPtr);
        } else {
            // Fallback: direct deferred free (unmap if needed)
            if (mappedPtr != 0 && vulkanMemory != null) {
                vulkanMemory.unmap(allocation);
            }
            deferredBufferFrees.add(new DeferredBufferFree(buffer, allocation, size, frameCounter));
        }
    }

    /** Flushes deferred buffer frees that are old enough to be safe. */
    private static void flushDeferredBufferFrees() {
        // Flush pool-based deferred releases
        if (chunkBufferPool != null && chunkBufferPool.isInitialized()) {
            chunkBufferPool.flushDeferredReleases(frameCounter);
        }

        // Flush legacy deferred frees (buffers queued before pool was initialized)
        long safeFrame = frameCounter - MAX_FRAMES_IN_FLIGHT;
        deferredBufferFrees.removeIf(entry -> {
            if (entry.queuedAtFrame <= safeFrame) {
                vulkanMemory.freeBufferImmediate(
                        new VulkaniumMemory.BufferAllocation(entry.buffer, entry.allocation, entry.size, 0));
                return true;
            }
            return false;
        });
    }

    @Override
    public void onInitializeClient() {
        version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        LOGGER.info("╔══════════════════════════════════════╗");
        LOGGER.info("║     Vulkanium {} Initializing     ║", version);
        LOGGER.info("║  Next-Gen Vulkan Rendering Engine    ║");
        LOGGER.info("╚══════════════════════════════════════╝");

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = VulkaniumConfig.load(configDir.resolve("vulkanium.json"));

        initialized = true;
        LOGGER.info("Vulkanium client initialization complete. Vulkan backend will activate on window creation.");
    }

    /**
     * Called from MixinWindow after GLFW window creation.
     */
    public static void onWindowCreated(long windowHandle) {
        if (!initialized || vulkanReady) return;

        VulkaniumCapabilities.CompatibilityResult compat = VulkaniumCapabilities.checkSystemCompatibility();
        if (!compat.compatible()) {
            LOGGER.error("Vulkan is NOT supported on this system: {}", compat.reason());
            initialized = false;
            return;
        }

        LOGGER.info("Window created — initializing Vulkan subsystem...");

        try {
            int framesInFlight = config.getFramesInFlight();
            VkDevice device;

            // 1-7: Core Vulkan subsystems
            vulkanInstance = new VulkaniumInstance(config.enableValidationLayers);
            vulkanInstance.initialize(windowHandle);

            vulkanDevice = new VulkaniumDevice();
            vulkanDevice.initialize(vulkanInstance);
            device = vulkanDevice.getLogicalDevice();

            vulkanQueues = new VulkaniumQueues();
            vulkanQueues.initialize(vulkanDevice);

            vulkanMemory = new VulkaniumMemory();
            vulkanMemory.initialize(vulkanInstance, vulkanDevice, framesInFlight);

            vulkanSwapchain = new VulkaniumSwapchain();
            int presentMode = config.getPresentModeVk();
            vulkanSwapchain.initialize(vulkanDevice, vulkanInstance, vulkanMemory, windowHandle, presentMode);

            vulkanCommand = new VulkaniumCommand();
            vulkanCommand.initialize(vulkanDevice, vulkanQueues, framesInFlight);

            vulkanSync = new VulkaniumSync();
            vulkanSync.initialize(vulkanDevice, framesInFlight);

            // 8. Frame orchestrator (split begin/end model)
            frameOrchestrator = new FrameOrchestrator();
            frameOrchestrator.initialize(vulkanDevice, vulkanQueues, vulkanSwapchain,
                    vulkanCommand, vulkanSync, vulkanMemory, framesInFlight);

            // 9. SPIR-V compiler
            spirvCompiler = new SPIRVCompiler();
            spirvCompiler.initialize();

            // 10. Descriptor set manager
            descriptorSetManager = new DescriptorSetManager();
            descriptorSetManager.initialize(device, framesInFlight, 64);

            // 11. Staging ring
            stagingRing = new StagingRing();
            stagingRing.initialize(vulkanMemory, 0);

            // === Render Pipeline Setup ===
            initRenderPipeline(device, framesInFlight);

            // Register swapchain recreation callback to rebuild framebuffers
            frameOrchestrator.setSwapchainRecreationCallback(() -> {
                LOGGER.info("Rebuilding framebuffers after swapchain recreation...");
                mainRenderPass.createFramebuffers(
                        vulkanSwapchain.getImageViews(),
                        vulkanSwapchain.getDepthImageView(),
                        vulkanSwapchain.getWidth(),
                        vulkanSwapchain.getHeight());

                // Rebuild RT compositor framebuffers and resize RT output
                if (rtRenderer != null && rtRenderer.isInitialized()) {
                    rtRenderer.resize(vulkanSwapchain.getWidth(), vulkanSwapchain.getHeight());
                    var compositor = rtRenderer.getSSAOCompositor();
                    if (compositor != null) {
                        compositor.onSwapchainRecreated(
                                vulkanSwapchain.getImageViews(),
                                vulkanSwapchain.getWidth(),
                                vulkanSwapchain.getHeight(),
                                rtRenderer.getRTOutputImageView(),
                                rtRenderer.getRTOutputSampler());
                    }
                }
            });

            vulkanReady = true;
            vulkanWasUsed = true;
            LOGGER.info("Vulkan subsystem fully initialized!");

            // Initialize shaderpack manager and scan for available packs
            shaderpackManager = new net.vulkanium.shaderpack.ShaderpackManager();
            Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
            int clearedFailedDumps = net.vulkanium.render.shader.ShaderCompiler.clearFailedSourceDumps(gameDir);
            if (clearedFailedDumps > 0) {
                LOGGER.info("Cleared {} failed shader source dumps at startup", clearedFailedDumps);
            }
            shaderpackManager.scanForPacks(gameDir.resolve("shaderpacks"));
            LOGGER.info("Render mode: {}", config.getRenderMode().getDisplayName());

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vulkan subsystem!", e);
            destroy();
        }
    }

    /**
     * Initializes the render pass, pipeline registry, draw batcher, and placeholder texture.
     */
    private static void initRenderPipeline(VkDevice device, int framesInFlight) {
        // 1. Render pass
        mainRenderPass = new BasicRenderPass();
        mainRenderPass.initialize(device, vulkanSwapchain.getImageFormat(), vulkanSwapchain.getDepthFormat());
        mainRenderPass.createFramebuffers(
                vulkanSwapchain.getImageViews(),
                vulkanSwapchain.getDepthImageView(),
                vulkanSwapchain.getWidth(),
                vulkanSwapchain.getHeight());

        // 2. Pipeline registry (lazily creates pipelines per vertex format)
        pipelineRegistry = new PipelineRegistry();
        pipelineRegistry.initialize(device, mainRenderPass.getRenderPass(), spirvCompiler);

        // 3. Pre-warm pipelines for common formats to get descriptor set layout
        BasicPipeline warmup = pipelineRegistry.getPipeline(DefaultVertexFormat.POSITION_COLOR);

        // 4. Draw batcher
        drawBatcher = new DrawBatcher();
        drawBatcher.initialize(device, vulkanMemory, framesInFlight,
                warmup.getDescriptorSetLayout());

        // 5. Placeholder 1x1 white texture
        createPlaceholderTexture(device);

        // 6. Ray tracing renderer (compute SSAO, optional hardware RT)
        initRayTracing(device);

        // 6b. SSAO Compositor (composites RT output onto swapchain)
        if (rtRenderer != null && rtRenderer.isInitialized()) {
            rtRenderer.initCompositor(
                    vulkanSwapchain.getImageFormat(),
                    vulkanSwapchain.getImageViews());
        }

        // 7. Chunk buffer pool (reduces VMA allocation stalls during movement)
        chunkBufferPool = new net.vulkanium.core.ChunkBufferPool();
        chunkBufferPool.initialize(vulkanMemory);

        LOGGER.info("Render pipeline initialized (render pass, pipeline registry, draw batcher, RT, buffer pool)");
    }

    /**
     * Creates a 1x1 white texture + sampler for untextured draws.
     */
    private static void createPlaceholderTexture(VkDevice device) {
        // Create 1x1 RGBA8 image
        VulkaniumMemory.ImageAllocation img = vulkanMemory.createImage(
                1, 1, 1,
                VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        // Create staging buffer with white pixel
        VulkaniumMemory.BufferAllocation staging = vulkanMemory.createStagingBuffer(4);
        long ptr = vulkanMemory.map(staging.allocation());
        MemoryUtil.memPutInt(ptr, 0xFFFFFFFF); // RGBA white
        vulkanMemory.unmap(staging.allocation());

        // Upload via one-shot command buffer
        VkCommandBuffer cmd = vulkanCommand.beginSingleTimeCommand();

        // Transition UNDEFINED → TRANSFER_DST
        VulkaniumCommand.transitionImageLayout(cmd, img.image(),
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Copy buffer → image
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(1, 1, 1);
            vkCmdCopyBufferToImage(cmd, staging.buffer(), img.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }

        // Transition TRANSFER_DST → SHADER_READ
        VulkaniumCommand.transitionImageLayout(cmd, img.image(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        vulkanCommand.endSingleTimeCommand(cmd);
        vulkanMemory.freeBufferImmediate(staging);

        // Create image view
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(img.image())
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateImageView(device, viewInfo, null, pView), "Failed to create placeholder image view");
            placeholderImageView = pView.get(0);
        }

        // Create sampler
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);

            LongBuffer pSampler = stack.longs(VK_NULL_HANDLE);
            checkResult(vkCreateSampler(device, samplerInfo, null, pSampler), "Failed to create placeholder sampler");
            placeholderSampler = pSampler.get(0);
        }

        LOGGER.info("Placeholder 1x1 white texture created");
    }

    /**
     * Initializes the RT subsystem: probes capabilities from VulkaniumDevice,
     * creates compute SSAO pipeline, optionally enables hardware RT if extensions
     * were enabled at device creation.
     */
    private static void initRayTracing(VkDevice device) {
        try {
            // Build RT capabilities from VulkaniumDevice's already-probed extension data
            // (extensions were enabled during createLogicalDevice if available)
            rtCapabilities = RTCapabilities.probe(vulkanDevice);

            // Always create the RT renderer — compute SSAO works on all GPUs
            rtRenderer = new RayTracingRenderer();
            rtRenderer.initialize(device, vulkanMemory, spirvCompiler, rtCapabilities,
                    vulkanSwapchain.getWidth(), vulkanSwapchain.getHeight());

            // Respect config: if RT is disabled, keep renderer initialized but disabled
            if (!config.rayTracingEnabled) {
                rtRenderer.setEnabled(false);
                if (vulkanDevice.isRTExtensionsEnabled()) {
                    LOGGER.info("RT renderer initialized with hardware RT AVAILABLE but DISABLED by config");
                    LOGGER.info("  Set rayTracingEnabled=true in vulkanium-options.json to enable");
                } else {
                    LOGGER.info("RT renderer initialized (compute-only, no hardware RT extensions)");
                }
            } else {
                // Apply SSAO config
                rtRenderer.setSSAOEnabled(config.ssaoEnabled);
                rtRenderer.setAOSamples(config.ssaoSamples);
                rtRenderer.setAORadius(config.ssaoRadius);
                LOGGER.info("RT renderer ENABLED: tier={}, hwRT={}, SSAO={}, samples={}, radius={}",
                        rtCapabilities.getTier().getName(),
                        vulkanDevice.isRTExtensionsEnabled(),
                        rtRenderer.isSSAOEnabled(),
                        rtRenderer.getAOSamples(), rtRenderer.getAORadius());
            }

        } catch (Exception e) {
            LOGGER.warn("RT initialization failed — continuing without RT", e);
            rtRenderer = null;
            rtCapabilities = null;
        }
    }

    // ─── Frame Lifecycle ───────────────────────────────────────────────

    /**
     * Called from MixinGameRenderer at the start of each frame.
     * Acquires swapchain image, begins command buffer, begins render pass.
     */
    public static void onFrameBegin(float partialTick) {
        if (!vulkanReady) return;

        stagingRing.resetForFrame(frameOrchestrator.getCurrentFrame());

        // Begin frame: acquire → fence wait → cmd begin
        if (!frameOrchestrator.beginFrame()) {
            // Swapchain out of date — will retry next frame
            return;
        }

        frameStarted = true;

        // Reset draw batcher for this frame
        drawBatcher.resetFrame(frameOrchestrator.getCurrentFrame());

        // Begin the main render pass on the swapchain framebuffer
        VkCommandBuffer cmd = frameOrchestrator.getCommandBuffer();
        int imageIndex = frameOrchestrator.getCurrentImageIndex();
        mainRenderPass.begin(cmd, imageIndex,
                vulkanSwapchain.getWidth(), vulkanSwapchain.getHeight(),
                VRenderSystem.getClearR(), VRenderSystem.getClearG(),
                VRenderSystem.getClearB(), VRenderSystem.getClearA());

        // Reset viewport tracking so the first draw will issue viewport commands
        lastVpX = -1; lastVpY = -1; lastVpW = -1; lastVpH = -1;
        lastScissorEnabled = false;
        lastScX = -1; lastScY = -1; lastScW = -1; lastScH = -1;
        diagFrameDrawCount = 0;
        frameHadWorldRender = false;

        // Log frame-start state for diagnostic frames
        if (isDebugLogging() && (frameCounter < 15 || (frameCounter >= 295 && frameCounter <= 305))) {
            LOGGER.info("[DIAG] FrameBegin #{} clear=({},{},{},{}) swapchain={}x{}",
                    frameCounter,
                    String.format("%.2f", VRenderSystem.getClearR()),
                    String.format("%.2f", VRenderSystem.getClearG()),
                    String.format("%.2f", VRenderSystem.getClearB()),
                    String.format("%.2f", VRenderSystem.getClearA()),
                    vulkanSwapchain.getWidth(), vulkanSwapchain.getHeight());
        }

        // Descriptor sets are now updated per-draw in recordDraw()

        if (frameHadWorldRender
            && getRenderMode() == net.vulkanium.render.RenderMode.SHADERPACK
                && shaderpackManager != null
                && shaderpackManager.getActivePipeline() != null
                && shaderpackManager.getActivePipeline().isLoaded()) {
            shaderpackManager.getActivePipeline().onFrameBegin(cmd, frameOrchestrator.getCurrentFrame());
        }
    }

    /**
     * Called from MixinGameRenderer at the end of each frame.
     * Ends render pass, ends command buffer, submits, presents.
     */
    private static long frameCounter = 0;
    public static void onFrameEnd() {
        if (!vulkanReady || !frameStarted) return;

        VkCommandBuffer cmd = frameOrchestrator.getCommandBuffer();

        // End render pass
        mainRenderPass.end(cmd);

        if (getRenderMode() == net.vulkanium.render.RenderMode.SHADERPACK
                && shaderpackManager != null
                && shaderpackManager.getActivePipeline() != null
                && shaderpackManager.getActivePipeline().isLoaded()) {
            int imageIndex = frameOrchestrator.getCurrentImageIndex();
            int width = vulkanSwapchain.getWidth();
            int height = vulkanSwapchain.getHeight();

            mainRenderPass.beginPreserve(cmd, imageIndex, width, height);
            shaderpackManager.getActivePipeline().onFrameEnd(cmd, frameOrchestrator.getCurrentFrame());
            mainRenderPass.end(cmd);
        }

        // ── RT pass: feed chunk meshes to RT pipeline, then dispatch ──
        // Run RT whenever it's enabled in config (SSAO works in any render mode)
        if (config.rayTracingEnabled && rtRenderer != null && rtRenderer.isEnabled()) {
            try {
                // Feed any newly uploaded terrain meshes to the RT module manager
                // so it can build BLASes for ray-traced shadow computation
                if (!pendingChunkMeshes.isEmpty()) {
                    var rtModule = rtRenderer.getRTModuleManager();
                    if (rtModule != null && rtModule.isEnabled()) {
                        for (ChunkMeshUpload upload : pendingChunkMeshes) {
                            // Derive approximate section coordinates from the buffer
                            // (the section key is used for BLAS deduplication)
                            int sectionKey = (int) (upload.vkBuffer % 0x3FFFFF);
                            rtModule.onChunkMeshChanged(
                                    sectionKey & 0x3FF, (sectionKey >> 10) & 0xFF,
                                    (sectionKey >> 18) & 0x3FF,
                                    upload.vkBuffer, 0 /* no index buffer */,
                                    upload.vertexCount, 0);
                        }
                        if (isDebugLogging() && frameCounter % 300 == 0) {
                            LOGGER.info("[RT] Fed {} chunk meshes to RT pipeline", pendingChunkMeshes.size());
                        }
                    }
                    pendingChunkMeshes.clear();
                }

                long depthImage = vulkanSwapchain.getDepthImage();
                long depthView = vulkanSwapchain.getDepthImageView();
                int frameIdx = frameOrchestrator.getCurrentFrame();
                int swapImageIdx = frameOrchestrator.getCurrentImageIndex();
                rtRenderer.executeFrame(cmd, depthImage, depthView, frameIdx, swapImageIdx);
            } catch (Exception e) {
                if (isDebugLogging() && (frameCounter < 5 || frameCounter % 300 == 0)) {
                    LOGGER.warn("[RT] Frame dispatch error: {}", e.getMessage());
                }
            }
        }

        // End frame: end cmd → submit → present
        boolean rendered = frameOrchestrator.endFrame();
        frameStarted = false;
        frameCounter++;

        // Flush deferred buffer frees (safe now that in-flight frames have advanced)
        flushDeferredBufferFrees();

        if (isDebugLogging() && (frameCounter == 1 || frameCounter == 10 || frameCounter % 300 == 0)) {
            LOGGER.info("[Frame] #{} rendered={} draws={} vtxBytes={}",
                    frameCounter, rendered,
                    drawBatcher.getDrawCallsThisFrame(),
                    drawBatcher.getVertexBytesThisFrame());
        }

        // Dump texture registry at key moments
        if (isDebugLogging() && (frameCounter == 15 || frameCounter == 300 || frameCounter == 500)) {
            GlStateInterceptor.dumpTextureRegistry();
        }
    }

    // ─── Draw Dispatch (called from VRenderSystem) ─────────────────────

    /**
     * Records a draw command into the open command buffer during MC's render phase.
     */
    private static long totalDraws = 0;
    private static int diagFrameDrawCount = 0; // draws within diagnostic frame
    private static final int MAX_TEXTURE_BINDINGS = BasicPipeline.getMaxTextureBindings();
    private static final long[] drawTextureViews = new long[MAX_TEXTURE_BINDINGS];
    private static final long[] drawTextureSamplers = new long[MAX_TEXTURE_BINDINGS];
    private static final Set<String> loggedShaderpackSelections = new HashSet<>();
    private static final Set<String> loggedShaderpackMappingMisses = new HashSet<>();
    private static volatile boolean worldRenderActive = false;
    private static volatile boolean frameHadWorldRender = false;

    private static org.joml.Matrix4f toVulkanClipProjection(org.joml.Matrix4f glProjection) {
        return new org.joml.Matrix4f(glProjection);
    }

    public static void onWorldRenderStart() {
        worldRenderActive = true;
        frameHadWorldRender = true;
    }

    public static void onWorldRenderEnd() {
        worldRenderActive = false;
    }

        private static BasicPipeline resolvePipelineForDraw(
            com.mojang.blaze3d.vertex.VertexFormat format,
            int vertexCount,
            com.mojang.blaze3d.vertex.VertexFormat.Mode mode
        ) {
        BasicPipeline fallback = pipelineRegistry.getPipeline(format);
        if (fallback == null) return null;

        if (getRenderMode() != net.vulkanium.render.RenderMode.SHADERPACK) {
            return fallback;
        }

        if (shaderpackManager == null || !(shaderpackManager.getActivePipeline() instanceof net.vulkanium.shaderpack.VulkanShaderpackPipeline shaderpackPipeline)) {
            return fallback;
        }

        net.vulkanium.shaderpack.ProgramId requested = mapShaderNameToProgramId(
            VRenderSystem.getCurrentShaderName(), format, vertexCount, mode);
        if (requested == null) {
            String shaderName = VRenderSystem.getCurrentShaderName();
            String key = (shaderName != null ? shaderName : "") + "::" + format;
            if (loggedShaderpackMappingMisses.add(key) && shouldLogUnmappedShader(shaderName)) {
                LOGGER.info("Shaderpack mapping miss: shader='{}' format={} (using fallback pipeline)",
                        shaderName, format);
            }
            return fallback;
        }

        BasicPipeline compatibility = shaderpackPipeline.getOrCreateCompatibilityPipeline(requested, format);
        if (compatibility != null) {
            String key = requested.getSourceName() + "::" + compatibility.getName();
            if (loggedShaderpackSelections.add(key)) {
                LOGGER.info("Using shaderpack pipeline for world draw: requested={} selected={} shader='{}'",
                        requested.getSourceName(),
                        compatibility.getName(),
                        VRenderSystem.getCurrentShaderName());
            }
            return compatibility;
        }
        return fallback;
    }

        private static net.vulkanium.shaderpack.ProgramId mapShaderNameToProgramId(
            String shaderName,
            com.mojang.blaze3d.vertex.VertexFormat format,
            int vertexCount,
            com.mojang.blaze3d.vertex.VertexFormat.Mode mode
        ) {
        String name = shaderName != null ? shaderName.toLowerCase(Locale.ROOT) : "";

            boolean activeWorldContext = worldRenderActive
                    || (frameHadWorldRender && (isExplicitWorldShaderName(name)
                    || (name.isEmpty() && isTerrainLikeFormat(format))));

            // Never route GUI/HUD/menu draws through shaderpack compatibility pipelines.
            // Shaderpack phase mapping is only valid during active world rendering.
                if (!activeWorldContext) {
                return null;
            }

        if (name.contains("cloud")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_CLOUDS;
        }
        if (name.contains("weather") || name.contains("rain") || name.contains("snow")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_WEATHER;
        }
        if (name.contains("sky") || name.contains("sun") || name.contains("moon") || name.contains("star")) {
            return hasUV0(format)
                    ? net.vulkanium.shaderpack.ProgramId.GBUFFERS_SKYTEXTURED
                    : net.vulkanium.shaderpack.ProgramId.GBUFFERS_SKYBASIC;
        }

        if (activeWorldContext && !hasUV2(format) && hasUV0(format)
                && ("position_tex".equals(name) || "position_tex_color".equals(name))) {
            // In vanilla 1.20.x, both sky-textured and cloud draws frequently use position_tex.
            // Cloud passes are typically much larger than sun/moon quad draws.
            if (mode == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS && vertexCount > 64) {
                return net.vulkanium.shaderpack.ProgramId.GBUFFERS_CLOUDS;
            }
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_SKYTEXTURED;
        }

        if (worldRenderActive && isGenericPositionShader(name) && isLikelySkyFormat(format)) {
            return hasUV0(format)
                    ? net.vulkanium.shaderpack.ProgramId.GBUFFERS_SKYTEXTURED
                    : net.vulkanium.shaderpack.ProgramId.GBUFFERS_SKYBASIC;
        }

        if (name.startsWith("rendertype_entity_translucent")
                || name.startsWith("rendertype_entity_cutout")
                || name.startsWith("rendertype_entity_cutout_no_cull")
                || name.startsWith("rendertype_entity_solid")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_ENTITIES;
        }

        if (name.startsWith("rendertype_glint") || name.contains("armor_glint")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_ARMOR_GLINT;
        }

        if (!isTerrainLikeFormat(format)) {
            return null;
        }

        if (name.isEmpty()) {
            return net.vulkanium.compat.VRenderSystem.isBlendEnabled()
                    ? net.vulkanium.shaderpack.ProgramId.GBUFFERS_WATER
                    : net.vulkanium.shaderpack.ProgramId.GBUFFERS_TERRAIN_SOLID;
        }

        // Only remap actual terrain/world chunk layer shader names.
        // Avoid remapping entity/gui/item draws that may share vertex formats.
        boolean isWorldTerrainShader = name.startsWith("rendertype_solid")
                || name.startsWith("rendertype_cutout")
                || name.startsWith("rendertype_cutout_mipped")
                || name.startsWith("rendertype_translucent")
                || name.startsWith("rendertype_tripwire")
                || name.contains("terrain");
        if (!isWorldTerrainShader) {
            return null;
        }

        if (name.contains("cutout_mipped")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_TERRAIN_CUTOUT_MIPPED;
        }
        if (name.contains("cutout")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_TERRAIN_CUTOUT;
        }
        if (name.contains("translucent") || name.contains("tripwire") || name.contains("water")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_WATER;
        }
        if (name.contains("solid")) {
            return net.vulkanium.shaderpack.ProgramId.GBUFFERS_TERRAIN_SOLID;
        }
        return net.vulkanium.shaderpack.ProgramId.GBUFFERS_TERRAIN;
    }

    private static boolean isExplicitWorldShaderName(String shaderName) {
        if (shaderName == null || shaderName.isEmpty()) {
            return false;
        }
        return shaderName.contains("sky")
                || shaderName.contains("cloud")
                || shaderName.contains("weather")
                || shaderName.contains("rain")
                || shaderName.contains("snow")
                || shaderName.startsWith("rendertype_solid")
                || shaderName.startsWith("rendertype_cutout")
                || shaderName.startsWith("rendertype_cutout_mipped")
                || shaderName.startsWith("rendertype_translucent")
                || shaderName.startsWith("rendertype_tripwire")
                || shaderName.contains("terrain");
    }

    private static boolean shouldLogUnmappedShader(String shaderName) {
        if (shaderName == null || shaderName.isEmpty()) {
            return false;
        }
        String name = shaderName.toLowerCase(Locale.ROOT);
        return name.contains("sky")
                || name.contains("cloud")
                || name.contains("weather")
                || name.contains("rain")
                || name.contains("snow")
                || name.startsWith("rendertype_");
    }

    private static boolean hasUV0(com.mojang.blaze3d.vertex.VertexFormat format) {
        for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
            if (element.getUsage() == com.mojang.blaze3d.vertex.VertexFormatElement.Usage.UV
                    && element.getIndex() == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUV2(com.mojang.blaze3d.vertex.VertexFormat format) {
        for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
            if (element.getUsage() == com.mojang.blaze3d.vertex.VertexFormatElement.Usage.UV
                    && element.getIndex() == 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelySkyFormat(com.mojang.blaze3d.vertex.VertexFormat format) {
        return !hasUV2(format);
    }

    private static boolean isGenericPositionShader(String shaderName) {
        return "position".equals(shaderName)
                || "position_color".equals(shaderName)
                || "position_tex".equals(shaderName)
                || "position_tex_color".equals(shaderName);
    }

    private static boolean isTerrainLikeFormat(com.mojang.blaze3d.vertex.VertexFormat format) {
        boolean hasUV0 = false;
        boolean hasUV1 = false;
        boolean hasUV2 = false;
        boolean hasColor = false;
        boolean hasNormal = false;

        for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
            switch (element.getUsage()) {
                case UV -> {
                    if (element.getIndex() == 0) hasUV0 = true;
                    if (element.getIndex() == 1) hasUV1 = true;
                    if (element.getIndex() == 2) hasUV2 = true;
                }
                case COLOR -> hasColor = true;
                case NORMAL -> hasNormal = true;
                default -> {
                }
            }
        }

        return hasUV0 && !hasUV1 && hasUV2 && hasColor && hasNormal;
    }

    private static void populateBoundTexturesForDraw() {
        for (int i = 0; i < MAX_TEXTURE_BINDINGS; i++) {
            drawTextureViews[i] = placeholderImageView;
            drawTextureSamplers[i] = placeholderSampler;

            int boundTexId = VRenderSystem.getBoundTextureId(i);
            if (boundTexId <= 0) continue;

            VulkanTexture vt = GlStateInterceptor.getVulkanTexture(boundTexId);
            if (vt != null && vt.isAllocated()) {
                drawTextureViews[i] = vt.getImageView();
                drawTextureSamplers[i] = vt.getSampler();
            }
        }
    }

    public static void recordDraw(ByteBuffer vertexData, int vertexCount,
                                   com.mojang.blaze3d.vertex.VertexFormat.Mode mode, int vertexSize,
                                   com.mojang.blaze3d.vertex.VertexFormat format) {
        if (!frameStarted || !frameOrchestrator.isRecording()) return;

        // Resolve default or shaderpack compatibility pipeline for this draw
        BasicPipeline pipeline = resolvePipelineForDraw(format, vertexCount, mode);
        if (pipeline == null) return;

        VkCommandBuffer cmd = frameOrchestrator.getCommandBuffer();
        int frameIndex = frameOrchestrator.getCurrentFrame();

        // Upload uniforms
        float[] mvp = new float[16];
        net.vulkanium.compat.VRenderSystem.getMVPMatrix().get(mvp);
        float[] colorMod = {
            net.vulkanium.compat.VRenderSystem.getShaderColorR(),
            net.vulkanium.compat.VRenderSystem.getShaderColorG(),
            net.vulkanium.compat.VRenderSystem.getShaderColorB(),
            net.vulkanium.compat.VRenderSystem.getShaderColorA()
        };

        totalDraws++;
        diagFrameDrawCount++;

        // ─── DIAGNOSTIC: Log detailed per-draw state for early frames ───
        boolean diagLog = isDebugLogging() && ((frameCounter < 3) ||
                          (frameCounter >= 295 && frameCounter <= 297));
        if (diagLog) {
            boolean blend = net.vulkanium.compat.VRenderSystem.isBlendEnabled();
            boolean depthD = net.vulkanium.compat.VRenderSystem.isDepthTestEnabled();
            boolean cullD = net.vulkanium.compat.VRenderSystem.isCullEnabled();
            int boundTexIdD = VRenderSystem.getBoundTextureId(0);
            String shader = VRenderSystem.getCurrentShaderName();
            int vpW = VRenderSystem.getViewportWidth();
            int vpH = VRenderSystem.getViewportHeight();

            // First few bytes of vertex data for debugging
            String vtxSample = "";
            if (vertexData != null && vertexData.remaining() >= 12) {
                int pos = vertexData.position();
                float x = vertexData.getFloat(pos);
                float y = vertexData.getFloat(pos + 4);
                float z = vertexData.getFloat(pos + 8);
                vtxSample = String.format("v0=(%.1f,%.1f,%.1f)", x, y, z);
            }

            LOGGER.info("[DIAG] F#{} D#{} pipe={} shader='{}' verts={} mode={} vtxSize={} " +
                        "blend={} depth={} cull={} " +
                        "color=({},{},{},{}) " +
                        "tex={} vp={}x{} " +
                        "mvp[0,5,10,12,13,14,15]=({},{},{},{},{},{},{}) {}",
                    frameCounter, diagFrameDrawCount, pipeline.getName(), shader,
                    vertexCount, mode, vertexSize,
                    blend, depthD, cullD,
                    String.format("%.2f", colorMod[0]), String.format("%.2f", colorMod[1]),
                    String.format("%.2f", colorMod[2]), String.format("%.2f", colorMod[3]),
                    boundTexIdD, vpW, vpH,
                    String.format("%.3f", mvp[0]), String.format("%.3f", mvp[5]),
                    String.format("%.3f", mvp[10]), String.format("%.3f", mvp[12]),
                    String.format("%.3f", mvp[13]), String.format("%.3f", mvp[14]),
                    String.format("%.3f", mvp[15]),
                    vtxSample);
        }
        // ─── END DIAGNOSTIC ───

        // Build fog params for shader UBO
        float[] fogParams = {
            net.vulkanium.compat.VRenderSystem.getFogColorR(),
            net.vulkanium.compat.VRenderSystem.getFogColorG(),
            net.vulkanium.compat.VRenderSystem.getFogColorB(),
            net.vulkanium.compat.VRenderSystem.getFogColorA(),
            net.vulkanium.compat.VRenderSystem.getFogStart(),
            net.vulkanium.compat.VRenderSystem.getFogEnd()
        };

        // Get texture matrix for glint/scroll UV animation
        float[] texMat = new float[16];
        net.vulkanium.compat.VRenderSystem.getTextureMatrix().get(texMat);

        boolean shaderpackCompat = getRenderMode() == net.vulkanium.render.RenderMode.SHADERPACK
                && pipeline.getName().startsWith("shaderpack_");

        int uboOffset;
        if (shaderpackCompat) {
            boolean terrainLike = isTerrainLikeFormat(format);
            float chunkOffsetX = net.vulkanium.compat.VRenderSystem.getChunkOffsetX();
            float chunkOffsetY = net.vulkanium.compat.VRenderSystem.getChunkOffsetY();
            float chunkOffsetZ = net.vulkanium.compat.VRenderSystem.getChunkOffsetZ();
            boolean hasChunkOffset = chunkOffsetX != 0.0f || chunkOffsetY != 0.0f || chunkOffsetZ != 0.0f;

            org.joml.Matrix4f modelViewMat = new org.joml.Matrix4f(net.vulkanium.compat.VRenderSystem.getModelViewMatrix());
            if (terrainLike && hasChunkOffset) {
            // Stabilize terrain compatibility path: apply section translation in CPU model-view
            // and zero the explicit chunk offset uniform to avoid double application.
            modelViewMat.translate(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
            chunkOffsetX = 0.0f;
            chunkOffsetY = 0.0f;
            chunkOffsetZ = 0.0f;
            }

            float[] modelView = new float[16];
            modelViewMat.get(modelView);
            float[] projection = new float[16];
            org.joml.Matrix4f glProjection = new org.joml.Matrix4f(net.vulkanium.compat.VRenderSystem.getProjectionMatrix());
            glProjection.get(projection);
            float[] modelViewInv = new float[16];
            new org.joml.Matrix4f(modelViewMat).invert().get(modelViewInv);
            float[] projectionInv = new float[16];
            new org.joml.Matrix4f(glProjection).invert().get(projectionInv);
            float[] chunkOffset = {
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ
            };
            uboOffset = drawBatcher.uploadUniformsShaderpack(frameIndex,
                    modelView, modelViewInv,
                    projection, projectionInv,
                    colorMod, fogParams, texMat, chunkOffset);
        } else {
            uboOffset = drawBatcher.uploadUniformsLegacy(frameIndex, mvp, colorMod, fogParams, texMat);
        }

        populateBoundTexturesForDraw();

        // Update descriptor set for this draw with all bound shader texture slots
        int setIdx = drawBatcher.updateDescriptorSet(frameIndex, drawTextureViews, drawTextureSamplers);

        // Bind pipeline with per-draw blend/depth state (GL→VK conversion)
        boolean blend = net.vulkanium.compat.VRenderSystem.isBlendEnabled();
        boolean depth = net.vulkanium.compat.VRenderSystem.isDepthTestEnabled();
        boolean depthWrite = net.vulkanium.compat.VRenderSystem.isDepthWriteEnabled();
        boolean cull = net.vulkanium.compat.VRenderSystem.isCullEnabled();
        int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; // Quads use indexed triangles

        int srcColorVk = net.vulkanium.compat.VRenderSystem.glToVkBlendFactor(
                net.vulkanium.compat.VRenderSystem.getBlendSrcRGB());
        int dstColorVk = net.vulkanium.compat.VRenderSystem.glToVkBlendFactor(
                net.vulkanium.compat.VRenderSystem.getBlendDstRGB());
        int srcAlphaVk = net.vulkanium.compat.VRenderSystem.glToVkBlendFactor(
                net.vulkanium.compat.VRenderSystem.getBlendSrcAlpha());
        int dstAlphaVk = net.vulkanium.compat.VRenderSystem.glToVkBlendFactor(
                net.vulkanium.compat.VRenderSystem.getBlendDstAlpha());
        int depthOpVk = net.vulkanium.compat.VRenderSystem.glToVkDepthFunc(
                net.vulkanium.compat.VRenderSystem.getDepthFunc());

        long vkPipeline = pipeline.getOrCreatePipeline(blend, depth, depthWrite, cull, topology,
                srcColorVk, dstColorVk, srcAlphaVk, dstAlphaVk, depthOpVk);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vkPipeline);

        // Set dynamic depth bias for polygon offset (entity shadows, decals)
        // GL: glPolygonOffset(slopeFactor, constantFactor)
        // VK: vkCmdSetDepthBias(cmd, constantFactor, clamp, slopeFactor)
        if (net.vulkanium.compat.VRenderSystem.isPolygonOffsetEnabled()) {
            vkCmdSetDepthBias(cmd,
                    net.vulkanium.compat.VRenderSystem.getPolygonOffsetUnits(),
                    0.0f,
                    net.vulkanium.compat.VRenderSystem.getPolygonOffsetFactor());
        } else {
            vkCmdSetDepthBias(cmd, 0.0f, 0.0f, 0.0f);
        }

        // Update dynamic viewport/scissor if MC changed them since last draw
        updateViewportScissor(cmd);

        // Bind the per-draw descriptor set with dynamic UBO offset
        drawBatcher.bindDescriptorSet(cmd, pipeline.getPipelineLayout(), setIdx, uboOffset);

        // Issue draw
        drawBatcher.draw(cmd, frameIndex, vkPipeline, pipeline.getPipelineLayout(),
                vertexData, vertexCount, mode, vertexSize);
    }

    // ─── Persistent VBO Draw (called from MixinVertexBuffer) ──────────

    /**
     * Records a draw command using an externally-owned persistent VkBuffer.
     *
     * <p>Unlike {@link #recordDraw}, this does NOT copy vertex data into the
     * streaming DrawBatcher vertex buffer. The buffer was already uploaded
     * in {@code VertexBuffer.upload()} and remains GPU-resident until the
     * chunk section is rebuilt or freed.</p>
     */
    public static void recordDrawPersistent(long vkBuffer, int vertexCount,
                                             com.mojang.blaze3d.vertex.VertexFormat.Mode mode,
                                             int vertexSize,
                                             com.mojang.blaze3d.vertex.VertexFormat format) {
        if (!frameStarted || !frameOrchestrator.isRecording()) return;
        if (vkBuffer == 0 || vertexCount <= 0) return;

        BasicPipeline pipeline = resolvePipelineForDraw(format, vertexCount, mode);
        if (pipeline == null) return;

        VkCommandBuffer cmd = frameOrchestrator.getCommandBuffer();
        int frameIndex = frameOrchestrator.getCurrentFrame();

        // Upload uniforms (same selection logic as recordDraw)
        float[] mvp = new float[16];
        VRenderSystem.getMVPMatrix().get(mvp);
        float[] colorMod = {
            VRenderSystem.getShaderColorR(),
            VRenderSystem.getShaderColorG(),
            VRenderSystem.getShaderColorB(),
            VRenderSystem.getShaderColorA()
        };

        totalDraws++;
        diagFrameDrawCount++;

        // ─── DIAGNOSTIC: Log first few persistent draws per frame at specific frame numbers
        boolean diagPersist = isDebugLogging() && (frameCounter >= 500 && frameCounter <= 502 && diagFrameDrawCount <= 5);

        // Build fog params for shader UBO
        float[] fogParams = {
            VRenderSystem.getFogColorR(), VRenderSystem.getFogColorG(),
            VRenderSystem.getFogColorB(), VRenderSystem.getFogColorA(),
            VRenderSystem.getFogStart(),  VRenderSystem.getFogEnd()
        };

        // Get texture matrix for glint/scroll UV animation
        float[] texMat = new float[16];
        VRenderSystem.getTextureMatrix().get(texMat);

        boolean shaderpackCompat = getRenderMode() == net.vulkanium.render.RenderMode.SHADERPACK
            && pipeline.getName().startsWith("shaderpack_");

        int uboOffset;
        if (shaderpackCompat) {
            boolean terrainLike = isTerrainLikeFormat(format);
            float chunkOffsetX = VRenderSystem.getChunkOffsetX();
            float chunkOffsetY = VRenderSystem.getChunkOffsetY();
            float chunkOffsetZ = VRenderSystem.getChunkOffsetZ();
            boolean hasChunkOffset = chunkOffsetX != 0.0f || chunkOffsetY != 0.0f || chunkOffsetZ != 0.0f;

            org.joml.Matrix4f modelViewMat = new org.joml.Matrix4f(VRenderSystem.getModelViewMatrix());
            if (terrainLike && hasChunkOffset) {
                // Stabilize terrain compatibility path: apply section translation in CPU model-view
                // and zero the explicit chunk offset uniform to avoid double application.
                modelViewMat.translate(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
                chunkOffsetX = 0.0f;
                chunkOffsetY = 0.0f;
                chunkOffsetZ = 0.0f;
            }

            float[] modelView = new float[16];
            modelViewMat.get(modelView);
            float[] projection = new float[16];
            org.joml.Matrix4f glProjection = new org.joml.Matrix4f(VRenderSystem.getProjectionMatrix());
            glProjection.get(projection);
            float[] modelViewInv = new float[16];
            new org.joml.Matrix4f(modelViewMat).invert().get(modelViewInv);
            float[] projectionInv = new float[16];
            new org.joml.Matrix4f(glProjection).invert().get(projectionInv);
            float[] chunkOffset = {
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ
            };
            uboOffset = drawBatcher.uploadUniformsShaderpack(frameIndex,
                modelView, modelViewInv,
                projection, projectionInv,
                colorMod, fogParams, texMat, chunkOffset);
        } else {
            uboOffset = drawBatcher.uploadUniformsLegacy(frameIndex, mvp, colorMod, fogParams, texMat);
        }

        populateBoundTexturesForDraw();

        if (diagPersist) {
            LOGGER.info("[PERSIST-DIAG] F#{} D#{} pipe={} verts={} stride={} colorMod=({},{},{},{}) " +
                        "blend={} depth={} depthWrite={} cull={} " +
                        "mvp[0,5,10,15]=({},{},{},{})",
                    frameCounter, diagFrameDrawCount, pipeline.getName(),
                    vertexCount, vertexSize,
                    String.format("%.2f", colorMod[0]), String.format("%.2f", colorMod[1]),
                    String.format("%.2f", colorMod[2]), String.format("%.2f", colorMod[3]),
                    VRenderSystem.isBlendEnabled(), VRenderSystem.isDepthTestEnabled(),
                    VRenderSystem.isDepthWriteEnabled(), VRenderSystem.isCullEnabled(),
                    String.format("%.3f", mvp[0]), String.format("%.3f", mvp[5]),
                    String.format("%.3f", mvp[10]), String.format("%.3f", mvp[15]));
        }

        // Update descriptor set
        int setIdx = drawBatcher.updateDescriptorSet(frameIndex, drawTextureViews, drawTextureSamplers);

        // Bind pipeline with per-draw blend/depth state (GL→VK conversion)
        boolean blend = VRenderSystem.isBlendEnabled();
        boolean depth = VRenderSystem.isDepthTestEnabled();
        boolean depthWrite = VRenderSystem.isDepthWriteEnabled();
        boolean cull = VRenderSystem.isCullEnabled();

        int srcColorVk = VRenderSystem.glToVkBlendFactor(VRenderSystem.getBlendSrcRGB());
        int dstColorVk = VRenderSystem.glToVkBlendFactor(VRenderSystem.getBlendDstRGB());
        int srcAlphaVk = VRenderSystem.glToVkBlendFactor(VRenderSystem.getBlendSrcAlpha());
        int dstAlphaVk = VRenderSystem.glToVkBlendFactor(VRenderSystem.getBlendDstAlpha());
        int depthOpVk  = VRenderSystem.glToVkDepthFunc(VRenderSystem.getDepthFunc());

        long vkPipeline = pipeline.getOrCreatePipeline(blend, depth, depthWrite, cull,
                org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                srcColorVk, dstColorVk, srcAlphaVk, dstAlphaVk, depthOpVk);
        org.lwjgl.vulkan.VK10.vkCmdBindPipeline(cmd,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, vkPipeline);

        // Set dynamic depth bias for polygon offset (entity shadows, decals)
        if (VRenderSystem.isPolygonOffsetEnabled()) {
            org.lwjgl.vulkan.VK10.vkCmdSetDepthBias(cmd,
                    VRenderSystem.getPolygonOffsetUnits(),
                    0.0f,
                    VRenderSystem.getPolygonOffsetFactor());
        } else {
            org.lwjgl.vulkan.VK10.vkCmdSetDepthBias(cmd, 0.0f, 0.0f, 0.0f);
        }

        // Update viewport/scissor
        updateViewportScissor(cmd);

        // Bind descriptor set
        drawBatcher.bindDescriptorSet(cmd, pipeline.getPipelineLayout(), setIdx, uboOffset);

        // Bind the persistent vertex buffer directly (not from DrawBatcher's streaming buffer)
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vkBuffer), stack.longs(0));
        }

        // Issue draw command based on primitive mode.
        // QUADS, TRIANGLE_FAN, and TRIANGLE_STRIP use index buffers
        // to convert to TRIANGLE_LIST draws.
        switch (mode) {
            case QUADS -> {
                int quadCount = vertexCount / 4;
                int indexCount = quadCount * 6;
                org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd,
                        drawBatcher.getQuadIndexBuffer(), 0, org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32);
                org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
            }
            case TRIANGLE_FAN -> {
                int indexCount = (vertexCount - 2) * 3;
                org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd,
                        drawBatcher.getFanIndexBuffer(), 0, org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16);
                org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
            }
            case TRIANGLE_STRIP -> {
                int indexCount = (vertexCount - 2) * 3;
                org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd,
                        drawBatcher.getStripIndexBuffer(), 0, org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16);
                org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
            }
            default -> {
                // TRIANGLES, LINE_STRIP, lines, etc. — direct draw
                org.lwjgl.vulkan.VK10.vkCmdDraw(cmd, vertexCount, 1, 0, 0);
            }
        }

        drawBatcher.incrementDrawStats(vertexCount * vertexSize);
    }

    /**
     * Updates the dynamic viewport and scissor commands if MC changed them since last draw.
     * MC changes viewport for GUI vs 3D rendering within the same frame.
     * Respects VRenderSystem.isScissorEnabled() for scroll area clipping.
     */
    private static void updateViewportScissor(VkCommandBuffer cmd) {
        // Read current viewport from VRenderSystem (set by MixinGlStateManager._viewport)
        int vpX = VRenderSystem.getViewportX();
        int vpY = VRenderSystem.getViewportY();
        int vpW = VRenderSystem.getViewportWidth();
        int vpH = VRenderSystem.getViewportHeight();

        // Read scissor state
        boolean scissorEnabled = VRenderSystem.isScissorEnabled();
        int scX, scY, scW, scH;
        if (scissorEnabled) {
            scX = VRenderSystem.getScissorX();
            scY = VRenderSystem.getScissorY();
            scW = VRenderSystem.getScissorWidth();
            scH = VRenderSystem.getScissorHeight();
        } else {
            // When scissor is disabled, use full viewport as scissor rect
            scX = vpX;
            scY = vpY;
            scW = vpW;
            scH = vpH;
        }

        boolean vpChanged = vpW > 0 && vpH > 0 && (vpX != lastVpX || vpY != lastVpY || vpW != lastVpW || vpH != lastVpH);
        boolean scChanged = (scissorEnabled != lastScissorEnabled || scX != lastScX || scY != lastScY || scW != lastScW || scH != lastScH);

        if (vpChanged || scChanged) {
            int fbHeight = vulkanSwapchain.getHeight();
            int fbWidth = vulkanSwapchain.getWidth();

            try (MemoryStack stack = stackPush()) {
                if (vpChanged) {
                    // Negative height + y = vpY + vpH for OpenGL Y-up convention
                    // (VulkanMod: viewport.y(height + y), viewport.height(-height))
                    VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                            .x(vpX).y(vpY + vpH)
                            .width(vpW).height(-vpH)
                            .minDepth(0.0f).maxDepth(1.0f);
                    vkCmdSetViewport(cmd, 0, viewport);
                    lastVpX = vpX; lastVpY = vpY; lastVpW = vpW; lastVpH = vpH;
                }

                if (vpChanged || scChanged) {
                    // Transform scissor Y for Vulkan's top-left origin.
                    // GL scissor Y=0 is at the bottom; Vulkan scissor Y=0 is at the top.
                    // VulkanMod: scissor.offset().set(x, framebufferHeight - (y + height))
                    int clampedX = Math.max(0, scX);
                    int clampedW = Math.max(0, Math.min(scW, fbWidth - clampedX));
                    int clampedH = Math.max(0, scH);
                    int clampedY = Math.max(0, fbHeight - (scY + clampedH));
                    clampedH = Math.min(clampedH, fbHeight - clampedY);

                    VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                    scissor.offset().set(clampedX, clampedY);
                    scissor.extent().set(Math.max(0, clampedW), Math.max(0, clampedH));
                    vkCmdSetScissor(cmd, 0, scissor);

                    lastScissorEnabled = scissorEnabled;
                    lastScX = scX; lastScY = scY; lastScW = scW; lastScH = scH;
                }
            }
        }
    }

    private static int glModeToVkTopology(int glMode) {
        return switch (glMode) {
            case 4 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;      // GL_TRIANGLES
            case 5 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;     // GL_TRIANGLE_STRIP
            case 6 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;       // GL_TRIANGLE_FAN
            case 1 -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;          // GL_LINES
            case 3 -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;         // GL_LINE_STRIP
            case 0 -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;         // GL_POINTS
            default -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        };
    }

    // ─── Shutdown ──────────────────────────────────────────────────────

    public static void destroy() {
        LOGGER.info("Shutting down Vulkanium...");

        if (frameOrchestrator != null) frameOrchestrator.waitIdle();

        if (drawBatcher != null) drawBatcher.destroy();
        if (rtRenderer != null) rtRenderer.destroy();
        if (chunkBufferPool != null) chunkBufferPool.destroy();
        if (pipelineRegistry != null) pipelineRegistry.destroy();
        if (mainRenderPass != null) mainRenderPass.destroy();

        VkDevice device = vulkanDevice != null ? vulkanDevice.getLogicalDevice() : null;
        if (device != null) {
            if (placeholderImageView != VK_NULL_HANDLE) vkDestroyImageView(device, placeholderImageView, null);
            if (placeholderSampler != VK_NULL_HANDLE) vkDestroySampler(device, placeholderSampler, null);
        }

        if (stagingRing != null) stagingRing.destroy();
        if (descriptorSetManager != null) descriptorSetManager.destroy();
        if (spirvCompiler != null) spirvCompiler.destroy();
        if (vulkanSync != null) vulkanSync.destroy();
        if (vulkanCommand != null) vulkanCommand.destroy();
        if (vulkanSwapchain != null) vulkanSwapchain.destroy();
        if (vulkanMemory != null) vulkanMemory.destroy();
        if (vulkanQueues != null) vulkanQueues.destroy();
        if (vulkanDevice != null) vulkanDevice.destroy();
        if (vulkanInstance != null) vulkanInstance.destroy();

        vulkanReady = false;
        LOGGER.info("Vulkanium shutdown complete.");
    }

    // ─── VSync / Resize ────────────────────────────────────────────────

    /**
     * Clears color and/or depth attachments mid-render-pass using vkCmdClearAttachments.
     * 
     * <p>MC calls {@code RenderSystem.clear(GL_DEPTH_BUFFER_BIT)} between world
     * and GUI rendering. Without this, the depth buffer retains world geometry
     * and GUI draws using LEQUAL depth test (RenderType.gui()) fail against
     * close objects (their depth 0.5 fails ≤ test vs world depth ~0.0–0.4).</p>
     *
     * @param mask    GL clear mask bits (0x4000 = COLOR, 0x100 = DEPTH)
     * @param r,g,b,a clear color (used when COLOR bit is set)
     * @param depth   clear depth value (used when DEPTH bit is set)
     */
    public static void clearAttachments(int mask, float r, float g, float b, float a, float depth) {
        if (!frameStarted || !frameOrchestrator.isRecording()) return;

        boolean clearColor = (mask & 0x4000) != 0;  // GL_COLOR_BUFFER_BIT
        boolean clearDepth = (mask & 0x0100) != 0;   // GL_DEPTH_BUFFER_BIT

        if (!clearColor && !clearDepth) return;

        VkCommandBuffer cmd = frameOrchestrator.getCommandBuffer();

        try (MemoryStack stack = stackPush()) {
            int attachmentCount = (clearColor ? 1 : 0) + (clearDepth ? 1 : 0);
            VkClearAttachment.Buffer clearAttachments = VkClearAttachment.calloc(attachmentCount, stack);

            int idx = 0;
            if (clearColor) {
                clearAttachments.get(idx)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .colorAttachment(0)
                        .clearValue(cv -> cv.color(c -> c.float32(0, r).float32(1, g).float32(2, b).float32(3, a)));
                idx++;
            }
            if (clearDepth) {
                clearAttachments.get(idx)
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .clearValue(cv -> cv.depthStencil(ds -> ds.depth(depth).stencil(0)));
            }

            // Clear the full framebuffer area
            VkClearRect.Buffer clearRect = VkClearRect.calloc(1, stack);
            clearRect.get(0)
                    .rect(rect -> rect.offset(o -> o.set(0, 0))
                            .extent(e -> e.set(vulkanSwapchain.getWidth(), vulkanSwapchain.getHeight())))
                    .baseArrayLayer(0)
                    .layerCount(1);

            vkCmdClearAttachments(cmd, clearAttachments, clearRect);
        }
    }

    public static void setVsync(boolean vsync) {
        if (!vulkanReady || vulkanSwapchain == null) return;
        int pm = vsync ? 2 : 1; // FIFO vs MAILBOX
        LOGGER.info("Vsync {} — present mode {}", vsync ? "enabled" : "disabled", pm);
        vulkanSwapchain.setPresentMode(pm);
    }

    public static void scheduleSwapchainRecreation(int width, int height) {
        if (!vulkanReady || vulkanSwapchain == null) return;
        LOGGER.info("Scheduling swapchain recreation: {}x{}", width, height);
        vulkanSwapchain.setNeedsRecreation(true);
        if (rtRenderer != null && rtRenderer.isInitialized()) {
            rtRenderer.resize(width, height);
        }
    }

    // ─── Getters ───────────────────────────────────────────────────────

    public static boolean isInitialized() { return initialized; }
    public static boolean isVulkanReady() { return vulkanReady; }
    public static boolean wasVulkanUsed() { return vulkanWasUsed; }
    public static boolean isFrameStarted() { return frameStarted; }
    public static String getVersion() { return version; }
    public static VulkaniumConfig getConfig() { return config; }

    /** Returns true if verbose debug logging is enabled in config. */
    public static boolean isDebugLogging() {
        return config != null && config.debugLogging;
    }

    /** Get the current rendering mode from config. */
    public static net.vulkanium.render.RenderMode getRenderMode() {
        return config != null ? config.getRenderMode() : net.vulkanium.render.RenderMode.VANILLA;
    }

    /** Set the current rendering mode and save config. */
    public static void setRenderMode(net.vulkanium.render.RenderMode mode) {
        if (config != null) {
            config.setRenderMode(mode);
            config.save();
            LOGGER.info("Render mode changed to: {}", mode.getDisplayName());
        }
    }

    public static VulkaniumInstance getVulkanInstance() { return vulkanInstance; }
    public static VulkaniumDevice getVulkanDevice() { return vulkanDevice; }
    public static VulkaniumQueues getVulkanQueues() { return vulkanQueues; }
    public static VulkaniumMemory getVulkanMemory() { return vulkanMemory; }
    public static VulkaniumSwapchain getVulkanSwapchain() { return vulkanSwapchain; }
    public static VulkaniumCommand getVulkanCommand() { return vulkanCommand; }
    public static VulkaniumSync getVulkanSync() { return vulkanSync; }
    public static FrameOrchestrator getFrameOrchestrator() { return frameOrchestrator; }
    public static DescriptorSetManager getDescriptorSetManager() { return descriptorSetManager; }

    public static long getPlaceholderImageView() { return placeholderImageView; }

    public static long getPlaceholderSampler() { return placeholderSampler; }
    public static SPIRVCompiler getSpirvCompiler() { return spirvCompiler; }
    public static StagingRing getStagingRing() { return stagingRing; }
    public static BasicRenderPass getMainRenderPass() { return mainRenderPass; }
    public static PipelineRegistry getPipelineRegistry() { return pipelineRegistry; }
    public static DrawBatcher getDrawBatcher() { return drawBatcher; }
    public static RayTracingRenderer getRTRenderer() { return rtRenderer; }
    public static RTCapabilities getRTCapabilities() { return rtCapabilities; }
    public static net.vulkanium.shaderpack.ShaderpackManager getShaderpackManager() { return shaderpackManager; }
    public static net.vulkanium.core.ChunkBufferPool getChunkBufferPool() { return chunkBufferPool; }
    public static long getFrameCounter() { return frameCounter; }
    public static boolean didFrameRenderWorld() { return frameHadWorldRender; }

}
