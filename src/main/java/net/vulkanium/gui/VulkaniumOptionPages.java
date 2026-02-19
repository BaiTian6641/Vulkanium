package net.vulkanium.gui;

import net.minecraft.client.Minecraft;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.VulkaniumGameOptions.*;
import net.vulkanium.gui.options.*;
import net.vulkanium.gui.options.control.*;
import net.vulkanium.gui.options.storage.VulkaniumOptionsStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines all option pages for the Vulkanium settings GUI.
 *
 * <p>Combines Sodium-style video/quality/performance options with
 * Vulkan-specific and shader-engine settings. The page structure is:</p>
 * <ol>
 *   <li><b>General</b> — render distance, brightness, GUI scale, VSync, FPS limit</li>
 *   <li><b>Quality</b> — graphics mode, shadows, weather, leaves, particles, AO</li>
 *   <li><b>Performance</b> — chunk threads, culling, deferred updates, GPU culling</li>
 *   <li><b>Vulkan</b> — present mode, frames in flight, transfer queue, staging buffer, device</li>
 *   <li><b>Shaders</b> — render targets, SPIR-V optimization, geometry shaders, debug</li>
 *   <li><b>Ray Tracing</b> — enable RT, tier, denoiser, upscaler selection</li>
 * </ol>
 */
public class VulkaniumOptionPages {

    private final VulkaniumOptionsStorage storage;

    public VulkaniumOptionPages(VulkaniumGameOptions options) {
        this.storage = new VulkaniumOptionsStorage(options);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 1: General
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        // Render/simulation distance — bind to vanilla MC options
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Render Distance")
                        .setTooltip("Controls how far chunks are visible. Higher values require more GPU memory and bandwidth.")
                        .setControl(opt -> new SliderControl(opt, 2, 32, 1, ControlValueFormatter.quantity("chunk", "chunks")))
                        .setBinding((opts, v) -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.options != null) mc.options.renderDistance().set(v);
                                },
                                opts -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    return (mc.options != null) ? mc.options.renderDistance().get() : 12;
                                })
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Simulation Distance")
                        .setTooltip("Controls how far game ticks are simulated around the player.")
                        .setControl(opt -> new SliderControl(opt, 5, 32, 1, ControlValueFormatter.quantity("chunk", "chunks")))
                        .setBinding((opts, v) -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.options != null) mc.options.simulationDistance().set(v);
                                },
                                opts -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    return (mc.options != null) ? mc.options.simulationDistance().get() : 12;
                                })
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Brightness")
                        .setTooltip("Controls the brightness (gamma) of the game.")
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, v) -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.options != null) mc.options.gamma().set(v / 100.0);
                                },
                                opts -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    return (mc.options != null) ? (int) (mc.options.gamma().get() * 100.0) : 50;
                                })
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("GUI Scale")
                        .setTooltip("Controls the size of the GUI elements.")
                        .setControl(opt -> new SliderControl(opt, 0, 4, 1, ControlValueFormatter.guiScale()))
                        .setBinding((opts, v) -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.options != null) mc.options.guiScale().set(v);
                                },
                                opts -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    return (mc.options != null) ? mc.options.guiScale().get() : 0;
                                })
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("FPS Limit")
                        .setTooltip("Limits the maximum number of frames rendered per second. Lower values save power on laptops.")
                        .setControl(opt -> new SliderControl(opt, 10, 260, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, v) -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.options != null) mc.options.framerateLimit().set(v);
                                },
                                opts -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    return (mc.options != null) ? mc.options.framerateLimit().get() : 260;
                                })
                        .build())
                .build());

        return new OptionPage("General", groups);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 2: Quality
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(QualitySettings.GraphicsQuality.class, storage)
                        .setName("Weather Quality")
                        .setTooltip("Controls the visual quality of rain and snow particles.")
                        .setControl(opt -> new CyclingControl<>(opt, QualitySettings.GraphicsQuality.class))
                        .setBinding((opts, v) -> opts.quality.weatherQuality = v, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(QualitySettings.GraphicsQuality.class, storage)
                        .setName("Leaves Quality")
                        .setTooltip("Controls whether leaves are opaque (fast) or transparent (fancy).")
                        .setControl(opt -> new CyclingControl<>(opt, QualitySettings.GraphicsQuality.class))
                        .setBinding((opts, v) -> opts.quality.leavesQuality = v, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Vignette")
                        .setTooltip("Controls the dark border effect around screen edges.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.quality.enableVignette = v, opts -> opts.quality.enableVignette)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Shadow Resolution")
                        .setTooltip("Override shadow map resolution. 0 uses the shader pack's default. Higher values produce sharper shadows at the cost of performance.")
                        .setControl(opt -> new SliderControl(opt, 0, 8192, 512, ControlValueFormatter.custom(
                                v -> v == 0 ? "Pack Default" : v + "x" + v)))
                        .setBinding((opts, v) -> opts.quality.shadowResolution = v, opts -> opts.quality.shadowResolution)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_SHADER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Shadow Distance")
                        .setTooltip("Maximum distance in blocks for shadow rendering. Lower values improve performance significantly.")
                        .setControl(opt -> new SliderControl(opt, 32, 256, 16, ControlValueFormatter.blocks()))
                        .setBinding((opts, v) -> opts.quality.maxShadowDistance = v,
                                    opts -> (int) opts.quality.maxShadowDistance)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        return new OptionPage("Quality", groups);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 3: Performance
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage performance() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Chunk Builder Threads")
                        .setTooltip("Number of threads used for building chunk meshes. 0 = auto (half of available cores). More threads speed up chunk loading but may impact gameplay smoothness.")
                        .setControl(opt -> new SliderControl(opt, 0,
                                Runtime.getRuntime().availableProcessors(), 1,
                                ControlValueFormatter.quantityOrDisabled("threads", "Auto")))
                        .setBinding((opts, v) -> opts.performance.chunkBuilderThreads = v,
                                    opts -> opts.performance.chunkBuilderThreads)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Defer Chunk Updates")
                        .setTooltip("Always defer chunk rebuilds to background threads. Prevents lag spikes from block changes but may cause brief visual artifacts.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.alwaysDeferChunkUpdates = v,
                                    opts -> opts.performance.alwaysDeferChunkUpdates)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Block Face Culling")
                        .setTooltip("Removes invisible block faces during mesh building. Reduces triangle count significantly with no visual impact.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.useBlockFaceCulling = v,
                                    opts -> opts.performance.useBlockFaceCulling)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Fog Occlusion")
                        .setTooltip("Skips rendering chunks hidden behind fog. Most effective at lower render distances.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.useFogOcclusion = v,
                                    opts -> opts.performance.useFogOcclusion)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Entity Culling")
                        .setTooltip("Skips rendering entities outside the camera frustum.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.useEntityCulling = v,
                                    opts -> opts.performance.useEntityCulling)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Animate Only Visible Textures")
                        .setTooltip("Only animates textures (e.g. flowing water) that are currently visible. Saves CPU time.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.animateOnlyVisibleTextures = v,
                                    opts -> opts.performance.animateOnlyVisibleTextures)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("GPU Frustum Culling")
                        .setTooltip("Uses a compute shader to cull sections on the GPU before drawing. Faster than CPU culling for large render distances.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.gpuFrustumCulling = v,
                                    opts -> opts.performance.gpuFrustumCulling)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("GPU Translucent Sort")
                        .setTooltip("Sorts translucent geometry on the GPU via compute shader. Eliminates CPU-side sort overhead.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.gpuTranslucentSort = v,
                                    opts -> opts.performance.gpuTranslucentSort)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Indirect Draw")
                        .setTooltip("Uses multi-draw-indirect for terrain rendering. Reduces draw call overhead on the CPU.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.performance.useIndirectDraw = v,
                                    opts -> opts.performance.useIndirectDraw)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        return new OptionPage("Performance", groups);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 4: Vulkan
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage vulkan() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(VideoSettings.PresentMode.class, storage)
                        .setName("Present Mode")
                        .setTooltip("Controls how frames are delivered to the display.\n" +
                                "• VSync — Synchronizes to display refresh, no tearing\n" +
                                "• Mailbox — Triple-buffered, low latency, no tearing\n" +
                                "• Immediate — Uncapped FPS, may cause tearing")
                        .setControl(opt -> new CyclingControl<>(opt, VideoSettings.PresentMode.class))
                        .setBinding((opts, v) -> opts.video.presentMode = v, opts -> opts.video.presentMode)
                        .setImpact(OptionImpact.VARIES)
                        .setFlags(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Frames in Flight")
                        .setTooltip("Number of frames the GPU can work on simultaneously. Higher values increase throughput but add latency.\n" +
                                "• 2 — Lower latency, may reduce FPS\n" +
                                "• 3 — Best balance (recommended)\n" +
                                "• 4 — Maximum throughput, highest latency")
                        .setControl(opt -> new SliderControl(opt, 2, 4, 1,
                                ControlValueFormatter.custom(v -> v + " frames")))
                        .setBinding((opts, v) -> opts.video.framesInFlight = v, opts -> opts.video.framesInFlight)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("HDR Output")
                        .setTooltip("Enable HDR10 output if your display supports it. Requires a compatible monitor and driver.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.video.hdrOutput = v, opts -> opts.video.hdrOutput)
                        .setFlags(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Multi-Threaded Recording")
                        .setTooltip("Record Vulkan command buffers on multiple threads. Uses secondary command buffers for parallel work dispatch.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.vulkan.multiThreadedRecording = v,
                                    opts -> opts.vulkan.multiThreadedRecording)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Async Transfers")
                        .setTooltip("Upload textures and buffers via a dedicated transfer queue. Prevents upload stalls during rendering.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.vulkan.asyncTransfers = v,
                                    opts -> opts.vulkan.asyncTransfers)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Staging Buffer Size")
                        .setTooltip("Size of the ring buffer for CPU→GPU uploads in megabytes. Increase for high render distances or many chunk updates.")
                        .setControl(opt -> new SliderControl(opt, 8, 128, 8, ControlValueFormatter.megabytes()))
                        .setBinding((opts, v) -> opts.vulkan.stagingBufferSizeMB = v,
                                    opts -> opts.vulkan.stagingBufferSizeMB)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Pipeline Cache")
                        .setTooltip("Persist compiled Vulkan pipelines to disk. Dramatically reduces load times on subsequent launches.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.vulkan.persistPipelineCache = v,
                                    opts -> opts.vulkan.persistPipelineCache)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("SPIR-V Cache")
                        .setTooltip("Cache compiled SPIR-V shaders to disk. Avoids recompilation when shader packs haven't changed.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.vulkan.persistShaderCache = v,
                                    opts -> opts.vulkan.persistShaderCache)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Advanced Culling")
                        .setTooltip("GPU-accelerated advanced culling aggressiveness.\n" +
                                "• Aggressive — Maximum culling, may clip at edges\n" +
                                "• Normal — Good balance (recommended)\n" +
                                "• Conservative — Minimal culling artifacts\n" +
                                "• Off — No advanced culling")
                        .setControl(opt -> new CyclingControl<>(opt,
                                new Integer[]{1, 2, 3, 10},
                                new String[]{"Aggressive", "Normal", "Conservative", "Off"}))
                        .setBinding((opts, v) -> opts.vulkan.advancedCulling = v,
                                    opts -> opts.vulkan.advancedCulling)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("GPU Device")
                        .setTooltip("Select which GPU to use for Vulkan rendering. -1 = auto-select best device. Requires game restart to take effect.")
                        .setControl(opt -> new SliderControl(opt, -1, 3, 1,
                                ControlValueFormatter.custom(v -> v == -1 ? "Auto" : "Device " + v)))
                        .setBinding((opts, v) -> opts.video.deviceIndex = v, opts -> opts.video.deviceIndex)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .build());

        return new OptionPage("Vulkan", groups);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 5: Shaders
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage shaders() {
        List<OptionGroup> groups = new ArrayList<>();

        // ── Shader Compilation Settings ──
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Max Render Targets")
                        .setTooltip("Maximum number of render targets (color attachments) for MRT. Lower values save VRAM. Most packs need 4-8.")
                        .setControl(opt -> new SliderControl(opt, 1, 16, 1,
                                ControlValueFormatter.custom(v -> v + " targets")))
                        .setBinding((opts, v) -> opts.shader.maxRenderTargets = v,
                                    opts -> opts.shader.maxRenderTargets)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_SHADER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Force RGBA8")
                        .setTooltip("Force all render targets to RGBA8 format. Saves VRAM on low-end GPUs but may reduce quality with HDR packs.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.shader.forceRGBA8 = v, opts -> opts.shader.forceRGBA8)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_SHADER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Geometry Shaders")
                        .setTooltip("Enable geometry shader emulation. Disabling this improves performance but breaks packs that use geometry shaders.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.shader.disableGeometryShaders = v,
                                    opts -> opts.shader.disableGeometryShaders)
                        .setFlags(OptionFlag.REQUIRES_SHADER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("SPIR-V Optimization")
                        .setTooltip("Optimization level for GLSL→SPIR-V compilation.\n" +
                                "• None — Fastest compilation, debug-friendly\n" +
                                "• Size — Optimize for smaller shader size\n" +
                                "• Performance — Maximum runtime performance (recommended)")
                        .setControl(opt -> new CyclingControl<>(opt,
                                new Integer[]{0, 1, 2},
                                new String[]{"None", "Size", "Performance"}))
                        .setBinding((opts, v) -> opts.shader.spirvOptimizationLevel = v,
                                    opts -> opts.shader.spirvOptimizationLevel)
                        .setImpact(OptionImpact.VARIES)
                        .setFlags(OptionFlag.REQUIRES_SHADER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Dump Shaders on Error")
                        .setTooltip("Save transformed GLSL and SPIR-V to disk when shader compilation fails. Useful for debugging shader pack issues.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.shader.dumpShadersOnError = v,
                                    opts -> opts.shader.dumpShadersOnError)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Auto Debug Report")
                        .setTooltip("Automatically generate a comprehensive debug report when shader compilation fails.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.shader.autoDebugReport = v,
                                    opts -> opts.shader.autoDebugReport)
                        .build())
                .build());

        return new OptionPage("Shaders", groups);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 6: Ray Tracing
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage rayTracing() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Enable Ray Tracing")
                        .setTooltip("Enable hardware ray tracing. Requires a GPU with VK_KHR_ray_tracing_pipeline support (NVIDIA RTX, AMD RX 6000+, Intel Arc).")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.rayTracing.enabled = v, opts -> opts.rayTracing.enabled)
                        .setImpact(OptionImpact.EXTREME)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Quality Tier")
                        .setTooltip("Ray tracing quality level.\n" +
                                "• Off — No ray tracing\n" +
                                "• RT Shadows — Ray-traced shadows only (4+ GB VRAM)\n" +
                                "• RT Reflections — + ray-traced reflections (8+ GB VRAM)\n" +
                                "• Full Path Trace — Complete path-traced GI (12+ GB VRAM)")
                        .setControl(opt -> new CyclingControl<>(opt,
                                new Integer[]{0, 1, 2, 3},
                                new String[]{"Off", "RT Shadows", "RT Reflections", "Full Path Trace"}))
                        .setBinding((opts, v) -> opts.rayTracing.qualityTier = v,
                                    opts -> opts.rayTracing.qualityTier)
                        .setImpact(OptionImpact.EXTREME)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        // ── SSAO (works on all GPUs, no RT hardware needed) ──
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("SSAO")
                        .setTooltip("Enable Screen-Space Ambient Occlusion. Adds soft contact shadows in corners and creases. Works on ALL GPUs via compute shader — no RT hardware required.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.rayTracing.ssaoEnabled = v,
                                    opts -> opts.rayTracing.ssaoEnabled)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("SSAO Samples")
                        .setTooltip("Number of AO samples per pixel. Higher values produce smoother AO at the cost of GPU time.")
                        .setControl(opt -> new SliderControl(opt, 4, 64, 4,
                                ControlValueFormatter.custom(v -> v + " samples")))
                        .setBinding((opts, v) -> opts.rayTracing.ssaoSamples = v,
                                    opts -> opts.rayTracing.ssaoSamples)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build());

        // ── Denoiser ──
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Denoiser Iterations")
                        .setTooltip("Number of SVGF à-trous wavelet filter iterations. More iterations produce smoother results but cost more GPU time.")
                        .setControl(opt -> new SliderControl(opt, 1, 5, 1,
                                ControlValueFormatter.custom(v -> v + " passes")))
                        .setBinding((opts, v) -> opts.rayTracing.denoiserIterations = v,
                                    opts -> opts.rayTracing.denoiserIterations)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Temporal Upscaling")
                        .setTooltip("Enable temporal upscaling to render at a lower internal resolution. Dramatically improves RT performance.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.rayTracing.enableUpscaler = v,
                                    opts -> opts.rayTracing.enableUpscaler)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Upscaler")
                        .setTooltip("Temporal upscaling algorithm.\n" +
                                "• FSR 3 — AMD FidelityFX, works on all GPUs\n" +
                                "• DLSS — NVIDIA only, requires RTX GPU\n" +
                                "• XeSS — Intel, works on all GPUs with DP4a")
                        .setControl(opt -> new CyclingControl<>(opt,
                                new Integer[]{0, 1, 2},
                                new String[]{"FSR 3", "DLSS", "XeSS"}))
                        .setBinding((opts, v) -> opts.rayTracing.upscalerType = v,
                                    opts -> opts.rayTracing.upscalerType)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName("Upscaler Quality")
                        .setTooltip("Upscaling quality preset. Higher quality renders at a higher internal resolution.\n" +
                                "• Ultra Quality — 77% render scale\n" +
                                "• Quality — 67% render scale\n" +
                                "• Balanced — 59% render scale\n" +
                                "• Performance — 50% render scale\n" +
                                "• Ultra Performance — 33% render scale")
                        .setControl(opt -> new CyclingControl<>(opt,
                                new Integer[]{0, 1, 2, 3, 4},
                                new String[]{"Ultra Quality", "Quality", "Balanced", "Performance", "Ultra Performance"}))
                        .setBinding((opts, v) -> opts.rayTracing.upscalerQuality = v,
                                    opts -> opts.rayTracing.upscalerQuality)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        return new OptionPage("Ray Tracing", groups);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page 7: Debug
    // ═══════════════════════════════════════════════════════════════════

    public OptionPage debug() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Validation Layers")
                        .setTooltip("Enable Vulkan validation layers. Extremely useful for debugging but severely impacts performance. Requires the LunarG Vulkan SDK.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.debug.enableValidationLayers = v,
                                    opts -> opts.debug.enableValidationLayers)
                        .setImpact(OptionImpact.EXTREME)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Debug Markers")
                        .setTooltip("Insert Vulkan debug markers for GPU profiling tools (RenderDoc, NVIDIA Nsight, AMD RGP).")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.debug.enableDebugMarkers = v,
                                    opts -> opts.debug.enableDebugMarkers)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Performance Overlay")
                        .setTooltip("Show an F3-style overlay with GPU timing, draw call counts, memory usage, and cache statistics.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.debug.showPerformanceOverlay = v,
                                    opts -> opts.debug.showPerformanceOverlay)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Memory Overlay")
                        .setTooltip("Show GPU memory allocation details in the performance overlay.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.debug.showMemoryOverlay = v,
                                    opts -> opts.debug.showMemoryOverlay)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Memory Tracing")
                        .setTooltip("Track all Vulkan memory allocations with callsite information. High overhead, for debugging only.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.debug.enableMemoryTracing = v,
                                    opts -> opts.debug.enableMemoryTracing)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName("Debug Logging")
                        .setTooltip("Enable verbose per-draw diagnostics, texture parameter logging, and frame stats. Very high overhead — causes FPS drop from LOGGER calls.")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, v) -> opts.debug.debugLogging = v,
                                    opts -> opts.debug.debugLogging)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        return new OptionPage("Debug", groups);
    }

    // ─── Utility ───────────────────────────────────────────────────────

    /** Get all pages in order for the options GUI */
    public List<OptionPage> getAllPages() {
        return List.of(
                general(),
                quality(),
                performance(),
                vulkan(),
                shaders(),
                rayTracing(),
                debug()
        );
    }
}
