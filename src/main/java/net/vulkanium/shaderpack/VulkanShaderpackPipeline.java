package net.vulkanium.shaderpack;

import net.vulkanium.Vulkanium;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.vulkanium.render.gbuffer.GBufferManager;
import net.vulkanium.render.gbuffer.RenderTargetSettings;
import net.vulkanium.render.pipeline.BasicPipeline;
import net.vulkanium.render.pipeline.DrawBatcher;
import net.vulkanium.render.shadow.ShadowDirectives;
import net.vulkanium.render.shadow.ShadowMap;
import net.vulkanium.render.shadow.ShadowMatrices;
import net.vulkanium.render.shadow.ShadowRenderer;
import net.vulkanium.render.shader.ShaderCompiler;
import net.vulkanium.render.shader.ShaderModuleManager;
import net.vulkanium.render.shader.ShaderModuleManager.CompiledProgram;
import net.vulkanium.render.shader.VulkaniumGlslTransformer.PassType;
import net.vulkanium.render.texture.VulkanTexture;
import net.vulkanium.resource.RenderTarget;
import net.vulkanium.core.VulkaniumCommand;
import net.vulkanium.core.VulkaniumMemory;
import net.vulkanium.shaderpack.compute.ShaderpackComputeManager;
import net.vulkanium.shaderpack.compute.ShaderpackSSBOManager;
import net.vulkanium.shaderpack.compute.ShaderpackImageManager;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

/**
 * Concrete Vulkan implementation of the shaderpack pipeline.
 *
 * <p>Routes shaderpack GLSL programs through the full Vulkanium compilation pipeline:</p>
 * <pre>
 *   ProgramSet (GlslPreprocessor for #include resolution)
 *     → OptiFineGlslPreprocessor (text cleanup + render target extraction)
 *     → VulkaniumGlslTransformer (GLSL 450 + UBO/sampler injection)
 *     → ShaderCompiler (shaderc with disk cache → SPIR-V)
 *     → VkShaderModule
 * </pre>
 *
 * <h3>Pipeline-per-Program Architecture</h3>
 * <p>Each {@link ProgramId} maps to one VkPipeline (vertex + fragment).
 * When MC enters a render phase (e.g. terrain rendering), the shaderpack
 * manager activates the corresponding program's pipeline. All MC draw calls
 * for that phase use the shaderpack's pipeline instead of the default one.</p>
 *
 * <h3>Fullscreen Pass Execution</h3>
 * <p>Composite and deferred passes render a fullscreen triangle using their
 * respective programs. The output from one pass feeds into the next as
 * input textures via descriptor set binding.</p>
 */
public class VulkanShaderpackPipeline implements ShaderpackPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShaderpackPipe");

    // ═══════════════════════════════════════════════════════════════
    //  Default Sampler Bindings (OptiFine/Iris sampler name → binding index)
    // ═══════════════════════════════════════════════════════════════

    /** Standard OptiFine/Iris sampler name → descriptor binding index (set=1) */
    private static final Map<String, Integer> DEFAULT_SAMPLER_BINDINGS;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        // Primary textures
        m.put("gtexture", 0);       // Block atlas / main texture
        m.put("texture", 0);        // Alias for gtexture
        m.put("tex", 0);            // Another alias
        m.put("lightmap", 1);       // MC lightmap texture
        m.put("normals", 2);        // Normal map atlas
        m.put("specular", 3);       // Specular/PBR atlas

        // Depth textures
        m.put("depthtex0", 4);
        m.put("gdepthtex", 4);      // Alias
        m.put("depthtex1", 5);
        m.put("depthtex2", 6);

        // Color textures (composite pass inputs)
        m.put("colortex0", 7);
        m.put("gcolor", 7);         // Alias
        m.put("colortex1", 8);
        m.put("gdepth", 8);         // Legacy alias
        m.put("colortex2", 9);
        m.put("gnormal", 9);        // Legacy alias
        m.put("colortex3", 10);
        m.put("composite", 10);     // Legacy alias
        m.put("colortex4", 11);
        m.put("gaux1", 11);         // Legacy alias
        m.put("colortex5", 12);
        m.put("gaux2", 12);
        m.put("colortex6", 13);
        m.put("gaux3", 13);
        m.put("colortex7", 14);
        m.put("gaux4", 14);
        m.put("colortex8", 15);
        m.put("colortex9", 16);
        m.put("colortex10", 17);
        m.put("colortex11", 18);
        m.put("colortex12", 19);
        m.put("colortex13", 20);
        m.put("colortex14", 21);
        m.put("colortex15", 22);

        // Shadow textures
        m.put("shadowtex0", 23);
        m.put("shadow", 23);        // Alias
        m.put("waterShadow", 23);   // Alias
        m.put("shadowtex1", 24);
        m.put("shadowcolor", 25);
        m.put("shadowcolor0", 25);
        m.put("shadowcolor1", 26);

        // Noise texture
        m.put("noisetex", 27);

        DEFAULT_SAMPLER_BINDINGS = Collections.unmodifiableMap(m);
    }

    // ═══════════════════════════════════════════════════════════════
    //  ProgramId → PassType Mapping
    // ═══════════════════════════════════════════════════════════════

    /**
     * Maps a ProgramId to the correct VulkaniumGlslTransformer.PassType,
     * which determines vertex decode strategy and UBO bindings.
     */
    private static PassType getPassType(ProgramId id) {
        String name = id.getSourceName();

        // Shadow passes
        if (name.startsWith("shadow")) return PassType.SHADOW;

        // Composite, deferred, final, prepare — all fullscreen
        if (name.startsWith("composite") || name.startsWith("deferred")
                || name.equals("final") || name.startsWith("prepare")) {
            return PassType.COMPOSITE;
        }

        // Gbuffers passes by type
        return switch (id) {
            case GBUFFERS_TERRAIN, GBUFFERS_TERRAIN_SOLID, GBUFFERS_TERRAIN_CUTOUT,
                 GBUFFERS_TERRAIN_CUTOUT_MIPPED, GBUFFERS_WATER, GBUFFERS_DAMAGED_BLOCK -> PassType.TERRAIN;

            case GBUFFERS_ENTITIES, GBUFFERS_ENTITIES_TRANSLUCENT, GBUFFERS_ENTITIES_GLOWING,
                 GBUFFERS_BLOCK -> PassType.ENTITY;

            case GBUFFERS_HAND, GBUFFERS_HAND_WATER -> PassType.HAND;

            case GBUFFERS_SKYBASIC, GBUFFERS_SKYTEXTURED -> PassType.SKY;

            case GBUFFERS_WEATHER -> PassType.PARTICLE;

            case GBUFFERS_CLOUDS, GBUFFERS_TEXTURED, GBUFFERS_TEXTURED_LIT,
                 GBUFFERS_ARMOR_GLINT, GBUFFERS_SPIDEREYES, GBUFFERS_BEACON_BEAM,
                 GBUFFERS_BASIC, GBUFFERS_LINE -> PassType.ENTITY;

            default -> PassType.ENTITY; // Safe default
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════

    /** Compiled VkPipeline per ProgramId */
    private final Map<ProgramId, Long> pipelines = new EnumMap<>(ProgramId.class);

    /** VkPipelineLayout per ProgramId */
    private final Map<ProgramId, Long> pipelineLayouts = new EnumMap<>(ProgramId.class);

    /** Compiled programs from the ShaderModuleManager pipeline */
    private final Map<ProgramId, CompiledProgram> compiledPrograms = new EnumMap<>(ProgramId.class);

    /** The loaded ProgramSet */
    private ProgramSet programSet;

    /** Shaderpack properties */
    private ShaderpackProperties properties;

    /** Shaderpack uniforms provider */
    private ShaderpackUniformsImpl uniforms;

    /** ShaderCompiler (cached, per-pipeline lifecycle) */
    private ShaderCompiler shaderCompiler;

    /** ShaderModuleManager (orchestrates full pipeline) */
    private ShaderModuleManager shaderModuleManager;

    /** Lifecycle state */
    private boolean loaded = false;
    private String packName = "";

    /** Current phase (for tracking active pipeline) */
    private ShaderPhase currentPhase = null;

    /** Runtime compatibility pipelines (ProgramId + vertex format key -> BasicPipeline). */
    private final Map<String, BasicPipeline> compatibilityPipelines = new HashMap<>();
        private final Set<String> warnedGeometryCompatBypass = new HashSet<>();
    private final Map<ProgramId, BasicPipeline> fullscreenPipelines = new EnumMap<>(ProgramId.class);
    private final Set<ProgramId> warnedUnsafePrograms = EnumSet.noneOf(ProgramId.class);
    private Map<String, String> optionOverrides = Collections.emptyMap();
    private List<ShaderpackOption> discoveredOptions = List.of();
    private boolean compatibilityWorldRenderingSupported = true;
    private String compatibilityIssueMessage = "";
    private boolean loggedCompatibilityIssue = false;
    private boolean loggedFullscreenExecution = false;
    private boolean loggedFullscreenSkipped = false;
    private boolean loggedSafeModeBlocked = false;
    private Boolean safeFullscreenExecutionAllowed = null;
    /** MRT render targets for fullscreen composite/deferred/final passes (double-buffered ping-pong). */
    private FullscreenRenderTargets fsTargets;

    /** G-buffer manager: MRT render targets + render pass for gbuffers world rendering. */
    private GBufferManager gbufferManager;

    /** Per-target format/clear settings parsed from shaderpack source. */
    private RenderTargetSettings renderTargetSettings;

    /**
     * Compute shader dispatch manager. Creates VkPipelines for .csh programs
     * and dispatches them via vkCmdDispatch.
     * <p>Reference: Iris ComputeProgram/ComputeRenderer (Iris Shaders, LGPL-3.0)</p>
     */
    private ShaderpackComputeManager computeManager;

    /**
     * SSBO manager. Creates VMA-backed VkBuffers for shaderpack storage buffers.
     * <p>Reference: Iris ShaderStorageBufferHolder (Iris Shaders, LGPL-3.0)</p>
     */
    private ShaderpackSSBOManager ssboManager;

    /**
     * Custom image manager. Creates VMA-backed VkImages for shaderpack storage images.
     * <p>Reference: Iris IrisImages/GlImage (Iris Shaders, LGPL-3.0)</p>
     */
    private ShaderpackImageManager imageManager;

    // ── Shadow rendering infrastructure ──
    /** Shadow directives parsed from shader source (resolution, distance, etc.) */
    private ShadowDirectives shadowDirectives;

    /** Shadow map render targets (depth + color attachments) */
    private ShadowMap shadowMap;

    /** Shadow renderer orchestrating the shadow pass */
    private ShadowRenderer shadowRenderer;

    /** Shadow render pass handle — CLEAR variant (depth-only, loadOp=CLEAR) */
    private long shadowRenderPass = VK_NULL_HANDLE;

    /** Shadow render pass handle — LOAD variant (depth-only, loadOp=LOAD for translucent) */
    private long shadowRenderPassLoad = VK_NULL_HANDLE;

    /** Shadow framebuffer handle */
    private long shadowFramebuffer = VK_NULL_HANDLE;

    /** Shadow terrain pipeline (depth-only, for rendering shadow geometry) */
    private BasicPipeline shadowTerrainPipeline;

    /** Shadow entity pipeline (depth-only, entity vertex format, for entity/block-entity shadows) */
    private BasicPipeline shadowEntityPipeline;

    /** Whether shadow depth images have been initialized (transitioned to readable layout) */
    private boolean shadowImagesInitialized = false;

    // ── Noise texture ──
    /** 256x256 RGBA noise texture for shaderpack noisetex sampler */
    private long noiseImage = VK_NULL_HANDLE;
    private long noiseImageAllocation = 0L;
    private long noiseImageView = VK_NULL_HANDLE;
    private long noiseSampler = VK_NULL_HANDLE;
    private static final int NOISE_TEXTURE_SIZE = 256;

    /** Cached MRT pipelines: keyed by ProgramId + attachment count to match MRT render pass. */
    private final Map<String, BasicPipeline> mrtPipelines = new HashMap<>();

    private enum FullscreenCompatMode {
        OFF,
        SAFE,
        FULL
    }

    private static final FullscreenCompatMode FULLSCREEN_COMPAT_MODE = resolveFullscreenCompatMode();
    private static final List<ProgramId> FULLSCREEN_PASS_ORDER = List.of(
            ProgramId.DEFERRED,
            ProgramId.DEFERRED1,
            ProgramId.DEFERRED2,
            ProgramId.DEFERRED3,
            ProgramId.DEFERRED4,
            ProgramId.DEFERRED5,
            ProgramId.DEFERRED6,
            ProgramId.DEFERRED7,
            ProgramId.DEFERRED8,
            ProgramId.DEFERRED9,
            ProgramId.DEFERRED10,
            ProgramId.DEFERRED11,
            ProgramId.DEFERRED12,
            ProgramId.DEFERRED13,
            ProgramId.DEFERRED14,
            ProgramId.DEFERRED15,
            ProgramId.COMPOSITE,
            ProgramId.COMPOSITE1,
            ProgramId.COMPOSITE2,
            ProgramId.COMPOSITE3,
            ProgramId.COMPOSITE4,
            ProgramId.COMPOSITE5,
            ProgramId.COMPOSITE6,
            ProgramId.COMPOSITE7,
            ProgramId.COMPOSITE8,
            ProgramId.COMPOSITE9,
            ProgramId.COMPOSITE10,
            ProgramId.COMPOSITE11,
            ProgramId.COMPOSITE12,
            ProgramId.COMPOSITE13,
            ProgramId.COMPOSITE14,
            ProgramId.COMPOSITE15,
            ProgramId.FINAL
        );

    private static final ByteBuffer FULLSCREEN_TRIANGLE_TEMPLATE = createFullscreenTriangleTemplate();

    private static FullscreenCompatMode resolveFullscreenCompatMode() {
        String explicit = System.getProperty("vulkanium.shaderpack.fullscreenCompatMode");
        if (explicit != null && !explicit.isBlank()) {
            return switch (explicit.trim().toLowerCase(Locale.ROOT)) {
                case "off", "disabled", "none" -> FullscreenCompatMode.OFF;
                case "full", "experimental", "on" -> FullscreenCompatMode.FULL;
                default -> FullscreenCompatMode.SAFE;
            };
        }

        if (Boolean.getBoolean("vulkanium.shaderpack.experimentalFullscreenCompat")) {
            return FullscreenCompatMode.FULL;
        }

        // Default to FULL so composite/deferred/final passes execute.
        // Without full MRT ping-pong, multi-pass chains may have visual
        // differences, but passes must run for any shaderpack effects.
        return FullscreenCompatMode.FULL;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Progress Tracking
    // ═══════════════════════════════════════════════════════════════

    /** Progress listener for UI feedback */
    private Consumer<LoadProgress> progressListener;

    /**
     * Progress information for shaderpack loading.
     */
    public static class LoadProgress {
        public enum Phase {
            PARSING_PROPERTIES,
            DISCOVERING_PROGRAMS,
            PREPROCESSING,
            TRANSFORMING,
            COMPILING,
            CREATING_MODULES,
            DONE
        }

        public final Phase phase;
        public final String currentProgram;
        public final String detail;
        public final int completed;
        public final int total;
        public final List<ProgramResult> results;

        public LoadProgress(Phase phase, String currentProgram, String detail,
                            int completed, int total, List<ProgramResult> results) {
            this.phase = phase;
            this.currentProgram = currentProgram;
            this.detail = detail;
            this.completed = completed;
            this.total = total;
            this.results = results != null ? results : List.of();
        }
    }

    /**
     * Result of compiling a single program (for progress display).
     */
    public static class ProgramResult {
        public final String programName;
        public final boolean success;
        public final boolean isFallback;
        public final boolean fromCache;
        public final String errorMessage;
        public final long compileTimeMs;
        public final boolean stageEvent;

        public ProgramResult(String programName, boolean success, boolean isFallback,
                             boolean fromCache, String errorMessage, long compileTimeMs) {
            this(programName, success, isFallback, fromCache, errorMessage, compileTimeMs, false);
        }

        public ProgramResult(String programName, boolean success, boolean isFallback,
                             boolean fromCache, String errorMessage, long compileTimeMs,
                             boolean stageEvent) {
            this.programName = programName;
            this.success = success;
            this.isFallback = isFallback;
            this.fromCache = fromCache;
            this.errorMessage = errorMessage;
            this.compileTimeMs = compileTimeMs;
            this.stageEvent = stageEvent;
        }
    }

    /**
     * Sets a progress listener for loading feedback (used by loading screen).
     */
    public void setProgressListener(Consumer<LoadProgress> listener) {
        this.progressListener = listener;
    }

    private void reportProgress(LoadProgress progress) {
        if (progressListener != null) {
            try {
                progressListener.accept(progress);
            } catch (Exception e) {
                LOGGER.debug("Progress listener error: {}", e.getMessage());
            }
        }
    }

    private static List<ProgramResult> progressSnapshot(List<ProgramResult> results) {
        return results == null || results.isEmpty() ? List.of() : List.copyOf(results);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase Mapping
    // ═══════════════════════════════════════════════════════════════

    /** Phase → ProgramId mapping */
    private static final Map<ShaderPhase, ProgramId> PHASE_TO_PROGRAM = new EnumMap<>(ShaderPhase.class);
    static {
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_BASIC, ProgramId.GBUFFERS_BASIC);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_TERRAIN, ProgramId.GBUFFERS_TERRAIN);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_TERRAIN_CUTOUT, ProgramId.GBUFFERS_TERRAIN_CUTOUT);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_WATER, ProgramId.GBUFFERS_WATER);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_ENTITIES, ProgramId.GBUFFERS_ENTITIES);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_ENTITIES_TRANSLUCENT, ProgramId.GBUFFERS_ENTITIES_TRANSLUCENT);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_BLOCK, ProgramId.GBUFFERS_BLOCK);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_SKYBASIC, ProgramId.GBUFFERS_SKYBASIC);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_SKYTEXTURED, ProgramId.GBUFFERS_SKYTEXTURED);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_CLOUDS, ProgramId.GBUFFERS_CLOUDS);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_WEATHER, ProgramId.GBUFFERS_WEATHER);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_HAND, ProgramId.GBUFFERS_HAND);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_HAND_WATER, ProgramId.GBUFFERS_HAND_WATER);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_TEXTURED, ProgramId.GBUFFERS_TEXTURED);
        PHASE_TO_PROGRAM.put(ShaderPhase.GBUFFERS_ARMOR_GLINT, ProgramId.GBUFFERS_ARMOR_GLINT);
        PHASE_TO_PROGRAM.put(ShaderPhase.SHADOW, ProgramId.SHADOW);
        PHASE_TO_PROGRAM.put(ShaderPhase.SHADOW_SOLID, ProgramId.SHADOW_SOLID);
        PHASE_TO_PROGRAM.put(ShaderPhase.SHADOW_CUTOUT, ProgramId.SHADOW_CUTOUT);
        PHASE_TO_PROGRAM.put(ShaderPhase.DEFERRED, ProgramId.DEFERRED);
        PHASE_TO_PROGRAM.put(ShaderPhase.COMPOSITE, ProgramId.COMPOSITE);
        PHASE_TO_PROGRAM.put(ShaderPhase.FINAL, ProgramId.FINAL);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Load / Compile
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean load(ShaderpackSource source) {
        packName = source.getName();
        LOGGER.info("[LOAD] ══════════════════════════════════════════════════════════");
        LOGGER.info("[LOAD] ──── Phase 1: Parsing properties ────");

        List<ProgramResult> allResults = new ArrayList<>();

        try {
            // ── Phase 1: Parse properties ──
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.PARSING_PROPERTIES, null,
                    "Parsing shaders.properties...", 0, 0, null));

            // Load option overrides first so properties can evaluate #if conditionals
            VulkaniumGameOptions opts = VulkaniumGameOptions.loadFromDisk();
            optionOverrides = opts.shader.shaderpackOptionOverrides != null
                ? new HashMap<>(opts.shader.shaderpackOptionOverrides)
                : Collections.emptyMap();

            // Scan shader source for option defaults (e.g. #define SKYBOX_RESOLUTION 64 // [48 64 96 ...])
            // These defaults are needed so that shaders.properties #if conditionals can be
            // evaluated correctly even when the user hasn't explicitly overridden a value.
            Map<String, String> optionDefaults = source.scanOptionDefaults();
            Map<String, String> mergedDefines = new HashMap<>(optionDefaults);
            mergedDefines.putAll(optionOverrides); // user overrides take precedence

            properties = new ShaderpackProperties();
            String propsContent = source.readProperties();
            if (propsContent != null) {
                properties.parse(propsContent, mergedDefines);
                LOGGER.info("[LOAD]   shaders.properties: {} entries parsed", properties.getAll().size());
                LOGGER.info("[LOAD]   Shadow resolution: {}x{}", properties.getShadowResolution(), properties.getShadowResolution());
                LOGGER.info("[LOAD]   Cloud setting: {}", properties.getCloudSetting());
                // Log required/optional feature flags
                if (!properties.getRequiredFeatures().isEmpty()) {
                    LOGGER.warn("[LOAD]   Required features: {}", properties.getRequiredFeatures());
                }
                if (!properties.getOptionalFeatures().isEmpty()) {
                    LOGGER.info("[LOAD]   Optional features: {}", properties.getOptionalFeatures());
                }
            } else {
                LOGGER.info("[LOAD]   No shaders.properties found (using defaults)");
            }

            // ── Phase 1b: Parse block.properties for material IDs ──
            try {
                String blockPropsContent = source.readShaderFile("block.properties");
                if (blockPropsContent != null) {
                    net.vulkanium.shaderpack.materialmap.BlockPropertyIdMap.load(blockPropsContent);
                    LOGGER.info("[LOAD]   block.properties loaded — material IDs active");
                } else {
                    LOGGER.info("[LOAD]   No block.properties found (all blocks get mc_Entity=-1)");
                    net.vulkanium.shaderpack.materialmap.BlockPropertyIdMap.clear();
                }
            } catch (Exception e) {
                LOGGER.error("[LOAD]   Failed to parse block.properties", e);
                net.vulkanium.shaderpack.materialmap.BlockPropertyIdMap.clear();
            }

            // ── Phase 2: Discover and resolve programs ──
            LOGGER.info("[LOAD] ──── Phase 2: Discovering shader programs ────");
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.DISCOVERING_PROGRAMS, null,
                    "Discovering shader programs...", 0, 0, null));

                programSet = new ProgramSet(source, properties, optionOverrides,
                    (scanned, total, programName, status) -> reportProgress(new LoadProgress(
                        LoadProgress.Phase.DISCOVERING_PROGRAMS,
                        programName,
                        String.format("Discovering programs [%d/%d]: %s (%s)", scanned, total, programName, status),
                        scanned,
                        total,
                        progressSnapshot(allResults))));
                discoveredOptions = ShaderpackOptionParser.parse(programSet, optionOverrides);
                compatibilityWorldRenderingSupported = evaluateCompatibilitySupport();
                int totalPrograms = countDirectPrograms();

            // ── Phase 3: Initialize the proper compilation pipeline ──
            LOGGER.info("[LOAD] ──── Phase 3: Initializing compilation pipeline ────");
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.PREPROCESSING, null,
                    "Initializing compilation pipeline...", 0, totalPrograms, progressSnapshot(allResults)));
            initializeCompilationPipeline();

            // ── Phase 4: Create uniform provider ──
            uniforms = new ShaderpackUniformsImpl();
                reportProgress(new LoadProgress(
                    LoadProgress.Phase.TRANSFORMING, null,
                    "Preparing shader compilation...", 0, totalPrograms, progressSnapshot(allResults)));

            // ── Phase 4b: Initialize compute/SSBO/image managers ──
            // Must happen before compilation so registerComputeProgram() is available.
            // Reference: Iris ComputeRenderer initialization (Iris Shaders, LGPL-3.0)
            initializeComputeInfrastructure();

            // ── Phase 5: Compile all programs through the full pipeline ──
            LOGGER.info("[LOAD] ──── Phase 5: Compiling GLSL → Preprocess → Transform → SPIR-V ────");
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.COMPILING, null,
                    "Beginning shader compilation...", 0, totalPrograms, progressSnapshot(allResults)));
            int compiled = compileProgramsThroughPipeline(allResults);

            if (compiled == 0) {
                LOGGER.warn("[LOAD] ✗ No shader programs were compiled — shaderpack may be empty or incompatible");
                LOGGER.warn("[LOAD]   Programs discovered: {}", programSet.getAllPrograms().size());
                LOGGER.warn("[LOAD]   Directly provided: {}", programSet.getDirectlyProvidedPrograms());
                reportProgress(new LoadProgress(
                        LoadProgress.Phase.DONE, null,
                        "FAILED: No programs compiled", 0, 0, allResults));
                return false;
            }

            loaded = true;

            // ── Phase 6: Initialize G-buffer render targets for MRT gbuffers ──
            initializeGBuffer();

            // ── Phase 7: Initialize shadow rendering infrastructure ──
            initializeShadow();

            // ── Phase 8: Create noise texture ──
            initializeNoiseTexture();

            // Report module creation phase
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.CREATING_MODULES, null,
                    "Finalizing shader modules...", compiled,
                    totalPrograms, progressSnapshot(allResults)));

            // ── Summary ──
            LOGGER.info("[LOAD] ──── Load Complete ────");
            LOGGER.info("[LOAD]   Pack: {}", packName);
            LOGGER.info("[LOAD]   Programs compiled: {}/{}", compiled, programSet.getDirectlyProvidedPrograms().size());
            LOGGER.info("[LOAD]   Failed: {}", shaderModuleManager.getFailedProgramCount());
            LOGGER.info("[LOAD]   Shadow: {}x{}", properties.getShadowResolution(), properties.getShadowResolution());
            LOGGER.info("[LOAD]   Properties: {} entries", properties.getAll().size());
            LOGGER.info("[LOAD]   Shaderpack options detected: {}", discoveredOptions.size());
            if (!compatibilityWorldRenderingSupported) {
                LOGGER.warn("[LOAD]   Compatibility world rendering disabled: {}", compatibilityIssueMessage);
            }
            LOGGER.info("[LOAD]   Compiler stats: {}", shaderCompiler.getStats());

            if (!shaderModuleManager.getFailedPrograms().isEmpty()) {
                LOGGER.warn("[LOAD]   ┌── Failed Programs ──┐");
                for (Map.Entry<String, String> fail : shaderModuleManager.getFailedPrograms().entrySet()) {
                    LOGGER.warn("[LOAD]   │ {} → {}", fail.getKey(),
                            fail.getValue().length() > 80 ? fail.getValue().substring(0, 80) + "..." : fail.getValue());
                }
                LOGGER.warn("[LOAD]   └─────────────────────┘");
            }

            reportProgress(new LoadProgress(
                    LoadProgress.Phase.DONE, null,
                    String.format("Done! %d/%d compiled", compiled, programSet.getDirectlyProvidedPrograms().size()),
                    totalPrograms, totalPrograms, progressSnapshot(allResults)));

            LOGGER.info("[LOAD] ══════════════════════════════════════════════════════════");
            return true;

        } catch (Exception e) {
            LOGGER.error("[LOAD] ✗ EXCEPTION during shaderpack load: {}", packName);
            LOGGER.error("[LOAD]   Type: {}", e.getClass().getName());
            LOGGER.error("[LOAD]   Message: {}", e.getMessage());
            LOGGER.error("[LOAD]   Stack trace:", e);
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.DONE, null,
                    "EXCEPTION: " + e.getMessage(), 0, 0, progressSnapshot(allResults)));
            unload();
            return false;
        }
    }

    private int countDirectPrograms() {
        if (programSet == null) return 0;
        int total = 0;
        for (ProgramId id : programSet.getAllPrograms().keySet()) {
            if (programSet.isDirectlyProvided(id)) total++;
        }
        return total;
    }

    /**
     * Initializes the ShaderCompiler (with disk cache) and ShaderModuleManager.
     */
    private void initializeCompilationPipeline() {
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();
        Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();

        // Create and initialize the cached ShaderCompiler
        shaderCompiler = new ShaderCompiler();
        shaderCompiler.initialize(gameDir);

        // Apply configurable optimization level from user settings
        try {
            VulkaniumGameOptions opts = VulkaniumGameOptions.loadFromDisk();
            shaderCompiler.setOptimizationLevel(opts.shader.spirvOptimizationLevel);
            LOGGER.info("[LOAD]   ShaderCompiler initialized with disk cache (opt level: {})",
                    opts.shader.spirvOptimizationLevel);
        } catch (Exception e) {
            LOGGER.info("[LOAD]   ShaderCompiler initialized with disk cache (opt level: default)");
        }

        // Create the ShaderModuleManager (orchestrates the full pipeline)
        shaderModuleManager = new ShaderModuleManager(device, shaderCompiler);
        shaderModuleManager.setTranslatedCacheNamespace(packName);
        shaderModuleManager.createFallbacks();
        LOGGER.info("[LOAD]   ShaderModuleManager initialized with fallback shaders");
    }

    /**
     * Initializes compute shader infrastructure: ShaderpackComputeManager, SSBOManager, ImageManager.
     *
     * <p>Reference: Iris CompositeRenderer initialization (Iris Shaders, LGPL-3.0)
     * sets up ComputeProgram instances, ShaderStorageBufferHolder, and image bindings
     * during pipeline construction. This Vulkan implementation mirrors that with
     * VkPipeline-based compute dispatch, VMA storage buffers, and VMA storage images.</p>
     */
    private void initializeComputeInfrastructure() {
        try {
            // Initialize compute manager (pipeline layout + descriptor set layout)
            computeManager = new ShaderpackComputeManager();
            computeManager.initialize();
            LOGGER.info("[LOAD]   ShaderpackComputeManager initialized");

            // Initialize SSBO manager and register any declared SSBOs
            long vmaAllocator = Vulkanium.getVulkanMemory().getAllocator();
            ssboManager = new ShaderpackSSBOManager(vmaAllocator);
            if (properties != null && properties.hasSSBOs()) {
                for (Map.Entry<Integer, String> entry : properties.getSSBODeclarations().entrySet()) {
                    ShaderpackSSBOManager.SSBOInfo info =
                            ShaderpackSSBOManager.parseSSBODeclaration(
                                    "bufferObject." + entry.getKey(), entry.getValue());
                    if (info != null) {
                        ssboManager.registerSSBO(entry.getKey(), info);
                    }
                }
                // Create buffers at current screen dimensions (use swapchain size if
                // the window object is not yet available during early initialization).
                int w = 0, h = 0;
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.getWindow() != null) {
                    w = mc.getWindow().getWidth();
                    h = mc.getWindow().getHeight();
                }
                if (w <= 0 || h <= 0) {
                    var swapchain = Vulkanium.getVulkanSwapchain();
                    if (swapchain != null) {
                        w = swapchain.getWidth();
                        h = swapchain.getHeight();
                    }
                }
                if (w > 0 && h > 0) {
                    ssboManager.createBuffers(w, h);
                }
                LOGGER.info("[LOAD]   ShaderpackSSBOManager: {} SSBOs registered", properties.getSSBODeclarations().size());
            }

            // Initialize custom image manager and register any declared images
            imageManager = new ShaderpackImageManager(vmaAllocator);
            if (properties != null && properties.hasCustomImages()) {
                Map<String, String> defines = properties.getActiveDefines();
                for (Map.Entry<String, String> entry : properties.getImageDeclarations().entrySet()) {
                    ShaderpackImageManager.ImageInfo info =
                            ShaderpackImageManager.parseImageDeclaration(entry.getKey(), entry.getValue(), defines);
                    if (info != null) {
                        imageManager.registerImage(info);
                    }
                }
                // Create images at current screen dimensions (use swapchain size if
                // the window object is not yet available during early initialization).
                int w = 0, h = 0;
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.getWindow() != null) {
                    w = mc.getWindow().getWidth();
                    h = mc.getWindow().getHeight();
                }
                if (w <= 0 || h <= 0) {
                    var swapchain = Vulkanium.getVulkanSwapchain();
                    if (swapchain != null) {
                        w = swapchain.getWidth();
                        h = swapchain.getHeight();
                    }
                }
                if (w > 0 && h > 0) {
                    imageManager.createImages(w, h);
                }
                LOGGER.info("[LOAD]   ShaderpackImageManager: {} custom images registered",
                        properties.getImageDeclarations().size());
            }
        } catch (Exception e) {
            LOGGER.warn("[LOAD]   Compute infrastructure initialization failed (compute shaders will be disabled): {}",
                    e.getMessage());
            LOGGER.debug("[LOAD]   Stack trace:", e);
        }
    }

    /**
     * Compiles all resolved programs through the full Vulkanium pipeline:
     * OptiFineGlslPreprocessor → VulkaniumGlslTransformer → ShaderCompiler → VkShaderModule
     *
     * @param results Accumulator for per-program results (for progress UI)
     * @return Number of successfully compiled programs
     */
    private int compileProgramsThroughPipeline(List<ProgramResult> results) {
        int compiled = 0;
        int failed = 0;
        int skipped = 0;
        int total = 0;

        // Count total programs to compile
        for (ProgramId id : programSet.getAllPrograms().keySet()) {
            if (programSet.isDirectlyProvided(id)) total++;
        }

        int index = 0;
        int processed = 0;

        for (Map.Entry<ProgramId, ProgramSource> entry : programSet.getAllPrograms().entrySet()) {
            ProgramId id = entry.getKey();
            ProgramSource source = entry.getValue();

            // Only compile directly provided programs (fallback-resolved share modules)
            if (!programSet.isDirectlyProvided(id)) {
                skipped++;
                continue;
            }

            index++;
            String programName = id.getSourceName();
            PassType passType = getPassType(id);

            // Report progress
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.COMPILING, programName,
                    String.format("[%d/%d] Processing %s (pass: %s)", index, total, programName, passType),
                    processed, total, progressSnapshot(results)));

            if (source.isValidGraphics()) {
                long startTime = System.nanoTime();
                final int displayIndex = index;
                final int displayProcessed = processed;
                final int totalPrograms = total;
                try {
                    shaderModuleManager.setCompileStageListener((listenerProgramName, stage, detail) ->
                    {
                        results.add(new ProgramResult(
                                listenerProgramName + " [" + stage + "]",
                                true,
                                false,
                                false,
                                detail,
                                0,
                                true));
                        reportProgress(new LoadProgress(
                                LoadProgress.Phase.COMPILING,
                                listenerProgramName,
                                String.format("[%d/%d] %s — %s", displayIndex, totalPrograms, listenerProgramName, detail),
                                displayProcessed,
                                totalPrograms,
                                progressSnapshot(results)));
                    });

                    LOGGER.info("[COMPILE] [{}/{}] {} — pass: {} — compiling...",
                            index, total, programName, passType);

                    // ═══ THE FULL PIPELINE — ShaderModuleManager handles everything ═══
                    // ProgramSet already did: GlslPreprocessor (#include resolution)
                    // ShaderModuleManager will do:
                    //   1. OptiFineGlslPreprocessor (text cleanup, DRAWBUFFERS, gl_FragData→iris_FragData)
                    //   2. VulkaniumGlslTransformer (GLSL 450 + UBO + sampler layout)
                    //   3. ShaderCompiler (shaderc → SPIR-V + disk cache)
                    //   4. vkCreateShaderModule
                    CompiledProgram program = shaderModuleManager.compileProgram(
                            programName,
                            source.vertexSource(),
                            source.fragmentSource(),
                            source.hasGeometry() ? source.geometrySource() : null,
                            passType,
                            DEFAULT_SAMPLER_BINDINGS
                    );

                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

                    if (program != null) {
                        compiledPrograms.put(id, program);
                        pipelines.put(id, 0L); // Pipeline creation is separate (descriptor/vertex state)

                        if (program.isFallback) {
                            LOGGER.warn("[COMPILE] ⚠ {} — using FALLBACK ({}ms)", programName, elapsedMs);
                            results.add(new ProgramResult(programName, false, true, false,
                                    "Compilation failed, using fallback", elapsedMs));
                            failed++;
                        } else {
                            LOGGER.info("[COMPILE] ✓ {} — OK (vert=0x{}, frag=0x{}, targets={}) [{}ms]",
                                    programName,
                                    Long.toHexString(program.vertexModule),
                                    Long.toHexString(program.fragmentModule),
                                    Arrays.toString(program.renderTargets),
                                    elapsedMs);
                            results.add(new ProgramResult(programName, true, false, false,
                                    null, elapsedMs));
                            compiled++;
                        }
                    }

                    processed++;
                    reportProgress(new LoadProgress(
                            LoadProgress.Phase.COMPILING, programName,
                            String.format("[%d/%d] Finished %s", processed, total, programName),
                            processed, total, progressSnapshot(results)));

                } catch (Exception e) {
                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                    LOGGER.error("[COMPILE] ✗ {} — EXCEPTION: {} — {} [{}ms]",
                            programName, e.getClass().getSimpleName(), e.getMessage(), elapsedMs);
                    LOGGER.debug("[COMPILE]   Stack trace:", e);
                    logShaderSourceSnippet(source.vertexSource(), programName + ".vsh");
                    logShaderSourceSnippet(source.fragmentSource(), programName + ".fsh");
                    results.add(new ProgramResult(programName, false, false, false,
                            e.getClass().getSimpleName() + ": " + e.getMessage(), elapsedMs));
                    failed++;
                        processed++;
                        reportProgress(new LoadProgress(
                            LoadProgress.Phase.COMPILING, programName,
                            String.format("[%d/%d] Failed %s", processed, total, programName),
                            processed, total, progressSnapshot(results)));
                    } finally {
                        shaderModuleManager.setCompileStageListener(null);
                }

            } else if (source.isValidCompute()) {
                long startTime = System.nanoTime();
                final int displayIndex = index;
                final int displayProcessed = processed;
                final int totalPrograms = total;
                try {
                        shaderModuleManager.setCompileStageListener((listenerProgramName, stage, detail) ->
                    {
                        results.add(new ProgramResult(
                            listenerProgramName + " [" + stage + "]",
                            true,
                            false,
                            false,
                            detail,
                            0,
                            true));
                        reportProgress(new LoadProgress(
                            LoadProgress.Phase.COMPILING,
                            listenerProgramName,
                            String.format("[%d/%d] %s — %s", displayIndex, totalPrograms, listenerProgramName, detail),
                            displayProcessed,
                            totalPrograms,
                            progressSnapshot(results)));
                    });

                    LOGGER.info("[COMPILE] [{}/{}] {} — compute shader", index, total, programName);

                    String computeGlslSource = source.computeSource();
                    long module = shaderModuleManager.compileComputeProgram(
                            programName, computeGlslSource, DEFAULT_SAMPLER_BINDINGS);
                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

                    if (module != 0) {
                        // Register with compute manager to create a real VkPipeline
                        if (computeManager != null && computeManager.isInitialized()) {
                            ShaderpackComputeManager.ComputeProgramInfo info =
                                    computeManager.registerComputeProgram(programName, module, computeGlslSource);
                            if (info != null && info.pipeline != VK_NULL_HANDLE) {
                                // Compute pipelines are owned/destroyed exclusively by
                                // ShaderpackComputeManager. Keep the generic pipeline map
                                // at 0 for compute ProgramIds to avoid double-destroy
                                // during unload on drivers sensitive to repeated free.
                                pipelines.put(id, 0L);
                                LOGGER.info("[COMPILE] ✓ {} — compute OK (module=0x{}, pipeline=0x{}) [{}ms]",
                                        programName, Long.toHexString(module),
                                        Long.toHexString(info.pipeline), elapsedMs);
                            } else {
                                pipelines.put(id, 0L);
                                LOGGER.warn("[COMPILE] ⚠ {} — compute module OK but pipeline creation failed [{}ms]",
                                        programName, elapsedMs);
                            }
                        } else {
                            pipelines.put(id, 0L);
                            LOGGER.info("[COMPILE] ✓ {} — compute OK (module=0x{}, no compute manager) [{}ms]",
                                    programName, Long.toHexString(module), elapsedMs);
                        }
                        compiled++;
                        results.add(new ProgramResult(programName, true, false, false, null, elapsedMs));
                    } else {
                        LOGGER.error("[COMPILE] ✗ {} — compute compilation failed [{}ms]", programName, elapsedMs);
                        results.add(new ProgramResult(programName, false, false, false,
                                "Compute shader compilation failed", elapsedMs));
                        failed++;
                    }
                        processed++;
                        reportProgress(new LoadProgress(
                            LoadProgress.Phase.COMPILING, programName,
                            String.format("[%d/%d] Finished %s", processed, total, programName),
                            processed, total, progressSnapshot(results)));
                } catch (Exception e) {
                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                    LOGGER.error("[COMPILE] ✗ {} — compute EXCEPTION: {} [{}ms]",
                            programName, e.getMessage(), elapsedMs);
                    results.add(new ProgramResult(programName, false, false, false,
                            e.getMessage(), elapsedMs));
                    failed++;
                    processed++;
                    reportProgress(new LoadProgress(
                            LoadProgress.Phase.COMPILING, programName,
                            String.format("[%d/%d] Failed %s", processed, total, programName),
                            processed, total, progressSnapshot(results)));
                } finally {
                    shaderModuleManager.setCompileStageListener(null);
                }
            } else {
                LOGGER.warn("[COMPILE] ? {} — not a valid graphics or compute program (vert={}, frag={}, comp={})",
                        programName, source.hasVertex(), source.hasFragment(), source.hasCompute());
                skipped++;
                processed++;
                reportProgress(new LoadProgress(
                        LoadProgress.Phase.COMPILING, programName,
                        String.format("[%d/%d] Skipped %s", processed, total, programName),
                        processed, total, progressSnapshot(results)));
            }
        }

        // ── Summary ──
        LOGGER.info("[COMPILE] ─── Compilation Summary ───");
        LOGGER.info("[COMPILE]   Compiled: {} | Failed: {} | Skipped (fallback): {}", compiled, failed, skipped);
        LOGGER.info("[COMPILE]   Compiler stats: {}", shaderCompiler.getStats());

        return compiled;
    }

    /**
     * Logs the first and last few lines of a shader source for debugging compilation failures.
     */
    private void logShaderSourceSnippet(String source, String filename) {
        if (source == null || source.isEmpty()) {
            LOGGER.error("[COMPILE]    Source for {} is null/empty", filename);
            return;
        }
        String[] lines = source.split("\n");
        int totalLines = lines.length;
        LOGGER.error("[COMPILE]    Source '{}' ({} lines):", filename, totalLines);

        // Log first 10 lines
        int headCount = Math.min(10, totalLines);
        for (int i = 0; i < headCount; i++) {
            LOGGER.error("[COMPILE]      {:>4}: {}", i + 1, lines[i]);
        }

        // If source is long, show a gap and last 5 lines
        if (totalLines > 15) {
            LOGGER.error("[COMPILE]      ... ({} lines omitted) ...", totalLines - 15);
            for (int i = Math.max(headCount, totalLines - 5); i < totalLines; i++) {
                LOGGER.error("[COMPILE]      {:>4}: {}", i + 1, lines[i]);
            }
        } else if (totalLines > headCount) {
            for (int i = headCount; i < totalLines; i++) {
                LOGGER.error("[COMPILE]      {:>4}: {}", i + 1, lines[i]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void unload() {
        VkDevice device = Vulkanium.getVulkanDevice() != null
                ? Vulkanium.getVulkanDevice().getLogicalDevice() : null;

        for (BasicPipeline compatibilityPipeline : compatibilityPipelines.values()) {
            if (compatibilityPipeline != null) {
                compatibilityPipeline.destroy();
            }
        }
        compatibilityPipelines.clear();
        warnedGeometryCompatBypass.clear();

        for (BasicPipeline fullscreenPipeline : fullscreenPipelines.values()) {
            if (fullscreenPipeline != null) {
                fullscreenPipeline.destroy();
            }
        }
        fullscreenPipelines.clear();

        if (device != null) {
            // Destroy VkPipelines (graphics/fullscreen compatibility only).
            // Compute pipelines are owned by ShaderpackComputeManager and destroyed
            // there to avoid double-destroy across owners.
            for (Map.Entry<ProgramId, Long> entry : pipelines.entrySet()) {
                long pipeline = entry.getValue() != null ? entry.getValue() : 0L;
                if (pipeline == VK_NULL_HANDLE || pipeline == 0) continue;

                if (computeManager != null && computeManager.hasComputeProgram(entry.getKey().getSourceName())) {
                    continue;
                }

                vkDestroyPipeline(device, pipeline, null);
            }

            // Destroy VkPipelineLayouts
            for (long layout : pipelineLayouts.values()) {
                if (layout != VK_NULL_HANDLE) {
                    vkDestroyPipelineLayout(device, layout, null);
                }
            }
        }

        // ShaderModuleManager handles all VkShaderModule cleanup
        if (shaderModuleManager != null) {
            shaderModuleManager.destroyAll();
            shaderModuleManager = null;
        }

        // Destroy the cached ShaderCompiler
        if (shaderCompiler != null) {
            shaderCompiler.destroy();
            shaderCompiler = null;
        }

        pipelines.clear();
        pipelineLayouts.clear();
        compiledPrograms.clear();
        programSet = null;
        uniforms = null;
        loaded = false;
        progressListener = null;
        optionOverrides = Collections.emptyMap();
        discoveredOptions = List.of();
        compatibilityWorldRenderingSupported = true;
        compatibilityIssueMessage = "";
        loggedCompatibilityIssue = false;
        loggedFullscreenExecution = false;
        loggedFullscreenSkipped = false;
        loggedSafeModeBlocked = false;
        safeFullscreenExecutionAllowed = null;

        // Destroy MRT render targets and cached MRT pipelines
        if (fsTargets != null) {
            fsTargets.destroy();
            fsTargets = null;
        }

        // Destroy G-buffer manager
        if (gbufferManager != null) {
            gbufferManager.destroy();
            gbufferManager = null;
        }
        renderTargetSettings = null;

        // Destroy shadow infrastructure
        if (shadowRenderer != null) {
            shadowRenderer.destroy();
            shadowRenderer = null;
        }
        if (shadowMap != null) {
            long shadowAllocator = Vulkanium.getVulkanMemory() != null
                    ? Vulkanium.getVulkanMemory().getAllocator() : 0L;
            shadowMap.destroy(0L, shadowAllocator);
            shadowMap = null;
        }
        if (device != null) {
            if (shadowFramebuffer != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(device, shadowFramebuffer, null);
            }
            if (shadowRenderPass != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device, shadowRenderPass, null);
            }
            if (shadowRenderPassLoad != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device, shadowRenderPassLoad, null);
            }
        }
        shadowFramebuffer = VK_NULL_HANDLE;
        shadowRenderPass = VK_NULL_HANDLE;
        shadowRenderPassLoad = VK_NULL_HANDLE;
        shadowDirectives = null;
        shadowImagesInitialized = false;
        if (shadowTerrainPipeline != null) {
            shadowTerrainPipeline.destroy();
            shadowTerrainPipeline = null;
        }
        if (shadowEntityPipeline != null) {
            shadowEntityPipeline.destroy();
            shadowEntityPipeline = null;
        }

        // Destroy compute infrastructure
        // Reference: Iris CompositeRenderer cleanup (Iris Shaders, LGPL-3.0)
        if (computeManager != null) {
            computeManager.destroy();
            computeManager = null;
        }
        if (ssboManager != null) {
            ssboManager.destroy();
            ssboManager = null;
        }
        if (imageManager != null) {
            imageManager.destroy();
            imageManager = null;
        }

        // Destroy noise texture
        if (device != null) {
            if (noiseImageView != VK_NULL_HANDLE) {
                vkDestroyImageView(device, noiseImageView, null);
            }
            if (noiseSampler != VK_NULL_HANDLE) {
                vkDestroySampler(device, noiseSampler, null);
            }
        }
        if (noiseImage != VK_NULL_HANDLE && noiseImageAllocation != 0L && Vulkanium.getVulkanMemory() != null) {
            Vulkanium.getVulkanMemory().freeImageImmediate(
                    new VulkaniumMemory.ImageAllocation(noiseImage, noiseImageAllocation,
                            NOISE_TEXTURE_SIZE, NOISE_TEXTURE_SIZE, VK_FORMAT_R8G8B8A8_UNORM, 1));
        }
        noiseImage = VK_NULL_HANDLE;
        noiseImageAllocation = 0L;
        noiseImageView = VK_NULL_HANDLE;
        noiseSampler = VK_NULL_HANDLE;

        for (BasicPipeline mrtP : mrtPipelines.values()) {
            if (mrtP != null) mrtP.destroy();
        }
        mrtPipelines.clear();

        LOGGER.info("Shaderpack pipeline unloaded: {}", packName);
    }

    /**
     * Initializes G-buffer render targets for MRT gbuffers rendering.
     *
     * <p>Scans all compiled gbuffers programs to find the union of all color targets
     * they write to, then creates the MRT render pass and framebuffer accordingly.</p>
     */
    private void initializeGBuffer() {
        // Collect all color targets used by any gbuffers program
        Set<Integer> allGbufferTargets = new TreeSet<>();
        for (Map.Entry<ProgramId, CompiledProgram> entry : compiledPrograms.entrySet()) {
            ProgramId id = entry.getKey();
            if (!id.getSourceName().startsWith("gbuffers_")) continue;
            CompiledProgram prog = entry.getValue();
            if (prog.renderTargets != null) {
                for (int t : prog.renderTargets) {
                    allGbufferTargets.add(t);
                }
            }
        }

        if (allGbufferTargets.isEmpty()) {
            LOGGER.info("[MRT] No gbuffers programs with render targets — MRT disabled");
            return;
        }

        // Build RenderTargetSettings from shaderpack properties
        renderTargetSettings = new RenderTargetSettings();
        for (int idx : allGbufferTargets) {
            renderTargetSettings.markUsed(idx);
        }

        // Apply format overrides from shaders.properties
        if (properties != null) {
            for (int i = 0; i < RenderTargetSettings.MAX_COLOR_TARGETS; i++) {
                String format = properties.getColorTexFormat(i);
                if (format != null && !format.isEmpty()) {
                    int vkFormat = RenderTargetSettings.resolveFormat(format);
                    renderTargetSettings.getColorSettings(i).setVkFormat(vkFormat);
                }
            }
        }

        // Create G-buffer manager
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();
        int swapchainW = Vulkanium.getVulkanSwapchain().getWidth();
        int swapchainH = Vulkanium.getVulkanSwapchain().getHeight();
        int depthFormat = Vulkanium.getVulkanSwapchain().getDepthFormat();

        gbufferManager = new GBufferManager();
        gbufferManager.initialize(device, Vulkanium.getVulkanMemory(),
                swapchainW, swapchainH, renderTargetSettings, allGbufferTargets, depthFormat);

        LOGGER.info("[MRT] G-buffer initialized for {} color targets: {}",
                allGbufferTargets.size(), allGbufferTargets);
    }

    /**
     * Initializes the shadow rendering infrastructure.
     *
     * <p>Collects shadow directives from all compiled programs, creates the
     * shadow map render targets, builds a depth-only render pass, and
     * creates the shadow framebuffer.</p>
     *
     * <p>The depth images are left in UNDEFINED layout after creation. On first
     * use in {@link #onFrameBegin}, they are transitioned to
     * DEPTH_STENCIL_READ_ONLY_OPTIMAL with depth cleared to 1.0 so that
     * shaderpacks sampling shadowtex0/1 see "no shadow" rather than black.</p>
     */
    private void initializeShadow() {
        // ── Step 1: Collect shadow directives from all compiled programs ──
        Map<String, String> mergedDirectives = new HashMap<>();
        for (Map.Entry<ProgramId, CompiledProgram> entry : compiledPrograms.entrySet()) {
            CompiledProgram prog = entry.getValue();
            if (prog.packDirectives != null) {
                for (Map.Entry<String, Number> d : prog.packDirectives.entrySet()) {
                    mergedDirectives.putIfAbsent(d.getKey(), d.getValue().toString());
                }
            }
        }

        // Also apply shadow resolution from shaders.properties if set
        if (properties != null) {
            int propRes = properties.getShadowResolution();
            if (propRes > 0 && propRes != 1024) {
                mergedDirectives.put("shadowMapResolution", String.valueOf(propRes));
            }
            float propDist = properties.getShadowDistance();
            if (propDist > 0 && propDist != 128.0f) {
                mergedDirectives.put("shadowDistance", String.valueOf(propDist));
            }
        }

        shadowDirectives = new ShadowDirectives();
        shadowDirectives.acceptDirectives(mergedDirectives);

        // ── Step 2: Determine sunPathRotation from shaderpack ──
        float sunPathRotation = 0.0f;
        String sprValue = mergedDirectives.get("sunPathRotation");
        if (sprValue != null) {
            try { sunPathRotation = Float.parseFloat(sprValue); } catch (NumberFormatException ignored) {}
        }

        int resolution = shadowDirectives.getResolution();
        LOGGER.info("[SHADOW] Initializing shadow map: {}x{}, distance={}, interval={}",
                resolution, resolution, shadowDirectives.getDistance(), shadowDirectives.getIntervalSize());

        // ── Step 3: Create ShadowMap (allocates depth images) ──
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();
        long allocator = Vulkanium.getVulkanMemory().getAllocator();
        shadowMap = new ShadowMap(shadowDirectives);
        shadowMap.create(0L, allocator); // device param unused — ShadowMap uses global device

        // ── Step 4: Create depth-only shadow render passes ──
        // CLEAR variant: first opaque pass clears depth
        shadowRenderPass = createShadowRenderPass(device, shadowMap.getDepthFormat(),
                VK_ATTACHMENT_LOAD_OP_CLEAR,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        // LOAD variant: translucent pass preserves opaque depth
        shadowRenderPassLoad = createShadowRenderPass(device, shadowMap.getDepthFormat(),
                VK_ATTACHMENT_LOAD_OP_LOAD,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        if (shadowRenderPass == VK_NULL_HANDLE) {
            LOGGER.warn("[SHADOW] Failed to create shadow render pass — shadow rendering disabled");
            if (shadowRenderPassLoad != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device, shadowRenderPassLoad, null);
                shadowRenderPassLoad = VK_NULL_HANDLE;
            }
            shadowMap.destroy(0L, allocator);
            shadowMap = null;
            return;
        }

        // ── Step 5: Create shadow framebuffer (depth attachment only) ──
        shadowFramebuffer = createShadowFramebuffer(device, shadowRenderPass,
                shadowMap.getMainDepthView(), resolution, resolution);
        if (shadowFramebuffer == VK_NULL_HANDLE) {
            LOGGER.warn("[SHADOW] Failed to create shadow framebuffer — shadow rendering disabled");
            vkDestroyRenderPass(device, shadowRenderPass, null);
            shadowRenderPass = VK_NULL_HANDLE;
            shadowMap.destroy(0L, allocator);
            shadowMap = null;
            return;
        }

        // ── Step 6: Create ShadowRenderer ──
        shadowRenderer = new ShadowRenderer(shadowMap, shadowDirectives, null);
        shadowRenderer.setSunPathRotation(sunPathRotation);
        shadowRenderer.setShadowRenderTargets(shadowRenderPass, shadowRenderPassLoad,
                shadowFramebuffer, 0);

        // ── Step 6b: Create shadow terrain + entity pipelines ──
        createShadowTerrainPipeline(device);
        createShadowEntityPipeline(device);

        shadowImagesInitialized = false;

        // ── Step 7: Feed shadow parameters to DrawBatcher ──
        DrawBatcher batcher = Vulkanium.getDrawBatcher();
        if (batcher != null) {
            batcher.setShadowParams(
                    sunPathRotation,
                    shadowDirectives.getDistance(),
                    shadowDirectives.getIntervalSize(),
                    shadowDirectives.getNearPlane(),
                    shadowDirectives.getFarPlane(),
                    shadowDirectives.getDistanceRenderMul(),
                    shadowDirectives.getResolution()
            );
        }

        LOGGER.info("[SHADOW] Shadow infrastructure initialized: {}x{} depth-only render pass",
                resolution, resolution);
    }

    /**
     * Creates a 256x256 RGBA8 noise texture for the noisetex sampler.
     * Uses the same random-data approach as Iris (java.util.Random, deterministic seed per pixel).
     * Reference: Iris NoiseTexture (Iris Shaders, LGPL-3.0)
     */
    private void initializeNoiseTexture() {
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();
        VulkaniumMemory memory = Vulkanium.getVulkanMemory();
        VulkaniumCommand command = Vulkanium.getVulkanCommand();

        if (device == null || memory == null || command == null) {
            LOGGER.warn("[NOISE] Cannot create noise texture — device/memory/command not ready");
            return;
        }

        int w = NOISE_TEXTURE_SIZE;
        int h = NOISE_TEXTURE_SIZE;
        int dataSize = w * h * 4; // RGBA8

        // 1. Create device-local image
        VulkaniumMemory.ImageAllocation img = memory.createImage(
                w, h, 1,
                VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        // 2. Fill staging buffer with random noise
        VulkaniumMemory.BufferAllocation staging = memory.createStagingBuffer(dataSize);
        long ptr = memory.map(staging.allocation());
        java.util.Random rng = new java.util.Random(0L); // deterministic seed for reproducibility
        for (int i = 0; i < w * h; i++) {
            byte r = (byte) rng.nextInt(256);
            byte g = (byte) rng.nextInt(256);
            byte b = (byte) rng.nextInt(256);
            byte a = (byte) rng.nextInt(256);
            MemoryUtil.memPutByte(ptr + (long) i * 4, r);
            MemoryUtil.memPutByte(ptr + (long) i * 4 + 1, g);
            MemoryUtil.memPutByte(ptr + (long) i * 4 + 2, b);
            MemoryUtil.memPutByte(ptr + (long) i * 4 + 3, a);
        }
        memory.unmap(staging.allocation());

        // 3. Upload via one-shot command buffer
        VkCommandBuffer cmd = command.beginSingleTimeCommand();

        // Transition UNDEFINED → TRANSFER_DST
        VulkaniumCommand.transitionImageLayout(cmd, img.image(),
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        // Copy buffer → image
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(w, h, 1);
            vkCmdCopyBufferToImage(cmd, staging.buffer(), img.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }

        // Transition TRANSFER_DST → SHADER_READ
        VulkaniumCommand.transitionImageLayout(cmd, img.image(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);

        command.endSingleTimeCommand(cmd);
        memory.freeBufferImmediate(staging);

        // 4. Create image view
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
            int result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[NOISE] Failed to create noise image view: {}", result);
                return;
            }
            noiseImageView = pView.get(0);
        }

        // 5. Create sampler (REPEAT + NEAREST, matching Iris noisetex sampler)
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
            int result = vkCreateSampler(device, samplerInfo, null, pSampler);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[NOISE] Failed to create noise sampler: {}", result);
                return;
            }
            noiseSampler = pSampler.get(0);
        }

        noiseImage = img.image();
        noiseImageAllocation = img.allocation();
        LOGGER.info("[NOISE] Created {}x{} RGBA8 noise texture (noisetex binding=27)", w, h);
    }

    /**
     * Creates a VkRenderPass with a single depth-only attachment for shadow mapping.
     *
     * @param loadOp        VK_ATTACHMENT_LOAD_OP_CLEAR or VK_ATTACHMENT_LOAD_OP_LOAD
     * @param initialLayout initial depth layout (UNDEFINED for clear, ATTACHMENT for load)
     * @param finalLayout   final depth layout after the pass
     */
    private long createShadowRenderPass(VkDevice device, int depthFormat,
                                         int loadOp, int initialLayout, int finalLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(1, stack);
            attachments.get(0)
                    .format(depthFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(loadOp)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(initialLayout)
                    .finalLayout(finalLayout);

            VkAttachmentReference.Buffer depthRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpasses = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(0)
                    .pDepthStencilAttachment(depthRef.get(0));

            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.calloc(2, stack);
            // External → subpass
            dependencies.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);
            // Subpass → external
            dependencies.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);

            VkRenderPassCreateInfo renderPassCI = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpasses)
                    .pDependencies(dependencies);

            LongBuffer pRenderPass = stack.mallocLong(1);
            int result = vkCreateRenderPass(device, renderPassCI, null, pRenderPass);
            if (result != VK_SUCCESS) {
                LOGGER.error("[SHADOW] vkCreateRenderPass failed: {}", result);
                return VK_NULL_HANDLE;
            }
            return pRenderPass.get(0);
        }
    }

    /**
     * Creates a VkFramebuffer for the shadow render pass with a single depth attachment.
     */
    private long createShadowFramebuffer(VkDevice device, long renderPass,
                                          long depthView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer attachments = stack.mallocLong(1);
            attachments.put(0, depthView);

            VkFramebufferCreateInfo fbCI = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1);

            LongBuffer pFramebuffer = stack.mallocLong(1);
            int result = vkCreateFramebuffer(device, fbCI, null, pFramebuffer);
            if (result != VK_SUCCESS) {
                LOGGER.error("[SHADOW] vkCreateFramebuffer failed: {}", result);
                return VK_NULL_HANDLE;
            }
            return pFramebuffer.get(0);
        }
    }

    /**
     * Creates a depth-only shadow terrain pipeline from compiled shadow shader modules.
     *
     * <p>Uses the shadow/shadow_solid/shadow_cutout program modules if the shaderpack
     * compiled them. Falls back to gbuffers_terrain modules if no shadow programs exist.
     * The pipeline renders with {@code cullMode=NONE}, depth bias enabled, and zero
     * color attachments (depth-only render pass).</p>
     */
    private void createShadowTerrainPipeline(VkDevice device) {
        // Resolve shadow program (SHADOW → SHADOW_SOLID → gbuffers_terrain fallback)
        CompiledProgram shadowProg = compiledPrograms.get(ProgramId.SHADOW);
        if (shadowProg == null) shadowProg = compiledPrograms.get(ProgramId.SHADOW_SOLID);
        if (shadowProg == null) shadowProg = compiledPrograms.get(ProgramId.GBUFFERS_TERRAIN);
        if (shadowProg == null) shadowProg = compiledPrograms.get(ProgramId.GBUFFERS_TERRAIN_SOLID);

        if (shadowProg == null || shadowProg.vertexModule == 0 || shadowProg.fragmentModule == 0) {
            LOGGER.warn("[SHADOW] No shadow or terrain shader modules found — shadow terrain pipeline skipped");
            return;
        }

        try {
            shadowTerrainPipeline = new BasicPipeline();
            long sharedDescriptorSetLayout = VK_NULL_HANDLE;
            if (Vulkanium.getPipelineRegistry() != null) {
                sharedDescriptorSetLayout = Vulkanium.getPipelineRegistry().getDescriptorSetLayout();
            }

            // Use DefaultVertexFormat.BLOCK (terrain format) with 0 color attachments
            // for the depth-only shadow render pass
            shadowTerrainPipeline.initializeWithModules(
                    device,
                    shadowRenderPass,
                    "shadow_terrain",
                    shadowProg.vertexModule,
                    shadowProg.fragmentModule,
                    VK_NULL_HANDLE,
                    DefaultVertexFormat.BLOCK,
                        0,  // no color attachments — depth only
                        sharedDescriptorSetLayout
            );
            LOGGER.info("[SHADOW] Shadow terrain pipeline created using program: {}",
                    compiledPrograms.containsKey(ProgramId.SHADOW) ? "shadow" :
                    compiledPrograms.containsKey(ProgramId.SHADOW_SOLID) ? "shadow_solid" :
                    "gbuffers_terrain (fallback)");
            // CW front face: no shader Y-flip, positive viewport
            shadowTerrainPipeline.setFrontFace(org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE);
        } catch (Exception e) {
            LOGGER.error("[SHADOW] Failed to create shadow terrain pipeline: {}", e.getMessage());
            shadowTerrainPipeline = null;
        }
    }

    /**
     * Creates the shadow entity pipeline for rendering entities and block entities
     * in the shadow pass. Uses the entity vertex format (NEW_ENTITY) compiled
     * against the shadow render pass (depth-only, 0 color attachments).
     *
     * <p>The program used is {@code gbuffers_entities} (resolved with its fallback
     * chain). The fragment shader's color outputs are simply discarded since the
     * shadow render pass has no color attachments — only depth is written.</p>
     */
    private void createShadowEntityPipeline(VkDevice device) {
        if (shadowRenderPass == VK_NULL_HANDLE) {
            LOGGER.warn("[SHADOW] No shadow render pass — entity pipeline skipped");
            return;
        }

        // Resolve entity program: GBUFFERS_ENTITIES → fallback chain
        CompiledProgram entityProg = null;
        for (ProgramId candidate : new ProgramId[]{
                ProgramId.GBUFFERS_ENTITIES,
                ProgramId.GBUFFERS_TEXTURED_LIT,
                ProgramId.GBUFFERS_TEXTURED,
                ProgramId.GBUFFERS_BASIC}) {
            CompiledProgram p = compiledPrograms.get(candidate);
            if (p != null && p.vertexModule != 0 && p.fragmentModule != 0) {
                entityProg = p;
                break;
            }
        }

        if (entityProg == null) {
            LOGGER.warn("[SHADOW] No entity shader modules found — entity shadow pipeline skipped");
            return;
        }

        try {
            shadowEntityPipeline = new BasicPipeline();
            long sharedDescriptorSetLayout = VK_NULL_HANDLE;
            if (Vulkanium.getPipelineRegistry() != null) {
                sharedDescriptorSetLayout = Vulkanium.getPipelineRegistry().getDescriptorSetLayout();
            }

            // Compile the entity pipeline against the shadow render pass with 0 color attachments.
            // The entity vertex format (NEW_ENTITY) provides position at attribute location 0,
            // UV0 at 2, etc. Fragment color outputs are discarded (no attachments).
            shadowEntityPipeline.initializeWithModules(
                    device,
                    shadowRenderPass,
                    "shadow_entity",
                    entityProg.vertexModule,
                    entityProg.fragmentModule,
                    VK_NULL_HANDLE,
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
                    0,  // no color attachments — depth only
                    sharedDescriptorSetLayout
            );
            LOGGER.info("[SHADOW] Shadow entity pipeline created (entity vertex format, depth-only)");
            // CW front face: no shader Y-flip, positive viewport
            shadowEntityPipeline.setFrontFace(org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE);
        } catch (Exception e) {
            LOGGER.error("[SHADOW] Failed to create shadow entity pipeline: {}", e.getMessage());
            shadowEntityPipeline = null;
        }
    }

    /** Returns the shadow entity pipeline, or {@code null} if not available. */
    public BasicPipeline getShadowEntityPipeline() { return shadowEntityPipeline; }

    /**
     * Executes the shadow rendering pass for the current frame.
     *
     * <p>This must be called BEFORE the main MRT G-buffer pass begins, as
     * shadow rendering uses its own depth-only VkRenderPass. The sequence is:</p>
     * <ol>
     *   <li>Set {@link WorldRenderingPhase} to SHADOW</li>
     *   <li>Bind shadow terrain pipeline to ChunkRenderer</li>
     *   <li>Call {@link ShadowRenderer#renderShadows} (17-step sequence)</li>
     *   <li>Transition shadow depth images to DEPTH_STENCIL_READ_ONLY for sampling</li>
     *   <li>Reset phase to NONE</li>
     * </ol>
     */
    public void renderShadowPass(VkCommandBuffer cmd) {
        // Initialize shadow depth images on first call (must be outside any VkRenderPass).
        // The caller (onWorldRenderStart) ends the main render pass before calling us,
        // so vkCmdPipelineBarrier/vkCmdClearDepthStencilImage are legal here.
        if (!shadowImagesInitialized && shadowMap != null) {
            initializeShadowImageLayouts(cmd);
        }

        if (shadowRenderer == null || shadowMap == null) {
            LOGGER.debug("[SHADOW] renderShadowPass skipped: shadowRenderer={} shadowMap={}",
                    shadowRenderer != null ? "OK" : "null",
                    shadowMap != null ? "OK" : "null");
            return;
        }
        if (shadowDirectives == null || shadowDirectives.getDistance() <= 0) {
            LOGGER.debug("[SHADOW] renderShadowPass skipped: shadowDirectives={} distance={}",
                    shadowDirectives != null ? "OK" : "null",
                    shadowDirectives != null ? shadowDirectives.getDistance() : "N/A");
            return;
        }

        // Get camera position and sky angle from live game state
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            LOGGER.debug("[SHADOW] renderShadowPass skipped: mc={} level={} player={}",
                    mc != null ? "OK" : "null",
                    mc != null && mc.level != null ? "OK" : "null",
                    mc != null && mc.player != null ? "OK" : "null");
            return;
        }

        float partialTick = Vulkanium.getCurrentPartialTick();
        float skyAngle = mc.level.getTimeOfDay(partialTick);
        org.joml.Vector3d cameraPos = new org.joml.Vector3d(
                mc.player.getX(partialTick),
                mc.player.getEyeY(),
                mc.player.getZ(partialTick));

        // Get ChunkRenderer for terrain draws
        net.vulkanium.world.VulkaniumWorldRenderer worldRenderer =
                net.vulkanium.world.VulkaniumWorldRenderer.getInstance();
        net.vulkanium.render.terrain.ChunkRenderer chunkRenderer = worldRenderer.getChunkRenderer();
        if (chunkRenderer == null) {
            LOGGER.debug("[SHADOW] renderShadowPass skipped: chunkRenderer is null");
            return;
        }

        LOGGER.debug("[SHADOW] Executing shadow pass: distance={}, skyAngle={}",
                shadowDirectives.getDistance(), skyAngle);
        net.vulkanium.render.program.WorldRenderingPhase.setPhase(
                net.vulkanium.render.program.WorldRenderingPhase.Phase.SHADOW);

        // Bind shadow terrain pipeline for ChunkRenderer shadow draws
        if (shadowTerrainPipeline != null) {
            long pipeline = shadowTerrainPipeline.getOrCreatePipeline(
                    false,   // no blend
                    true,    // depth test
                    true,    // depth write
                    false,   // no cull (both sides for shadows)
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            chunkRenderer.setShadowTerrainPipeline(pipeline, shadowTerrainPipeline.getPipelineLayout());
        }

        try {
            // Mark shadow images as initialized (the render pass CLEAR handles init)
            shadowImagesInitialized = true;

            // Execute the full 17-step shadow rendering sequence
            shadowRenderer.renderShadows(
                    cmd.address(), cameraPos, skyAngle, partialTick, chunkRenderer);

            // Transition shadow depth images to DEPTH_STENCIL_READ_ONLY for sampling
            transitionShadowDepthToReadOnly(cmd);
        } catch (Exception e) {
            LOGGER.error("[SHADOW] Error during shadow pass: {}", e.getMessage(), e);
        } finally {
            // Clear shadow pipeline binding
            chunkRenderer.setShadowTerrainPipeline(0, 0);

            // Reset phase
            net.vulkanium.render.program.WorldRenderingPhase.setPhase(
                    net.vulkanium.render.program.WorldRenderingPhase.Phase.NONE);
        }
    }

    /**
     * Transitions shadow depth images from DEPTH_STENCIL_ATTACHMENT to
     * DEPTH_STENCIL_READ_ONLY for sampling in composite/deferred passes.
     */
    private void transitionShadowDepthToReadOnly(VkCommandBuffer cmd) {
        long mainDepth = shadowMap.getMainDepthImage();
        if (mainDepth == VK_NULL_HANDLE) return;

        // Only transition mainDepth here.  noTranslucentsDepthImage is already in
        // DEPTH_STENCIL_READ_ONLY_OPTIMAL after copyPreTranslucentDepth() ran earlier
        // in the shadow render sequence (Step 11).
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(1, stack);
            barriers.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(mainDepth)
                    .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            barriers.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barriers);
        }
    }

    /**
     * Initializes shadow depth images for first-time use.
     * Transitions both depth images to DEPTH_STENCIL_READ_ONLY_OPTIMAL so
     * they can be safely sampled before any shadow geometry is rendered.
     * The clear-on-load in the render pass ensures depth = 1.0 (no shadow).
     */
    private void initializeShadowImageLayouts(VkCommandBuffer cmd) {
        if (shadowImagesInitialized || shadowMap == null) return;
        shadowImagesInitialized = true;

        long mainDepth = shadowMap.getMainDepthImage();
        long noTransDepth = shadowMap.getNoTranslucentsDepthImage();
        if (mainDepth == VK_NULL_HANDLE) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Collect depth images to initialize
            boolean hasNoTrans = (noTransDepth != VK_NULL_HANDLE && noTransDepth != mainDepth);
            int imageCount = hasNoTrans ? 2 : 1;
            long[] depthImages = hasNoTrans
                    ? new long[]{mainDepth, noTransDepth}
                    : new long[]{mainDepth};

            // Transition all depth images UNDEFINED → TRANSFER_DST
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(imageCount, stack);
            for (int i = 0; i < imageCount; i++) {
                barriers.get(i)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(depthImages[i])
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barriers.get(i).subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                        .baseArrayLayer(0).layerCount(1);
            }
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, barriers);

            // Clear all depth images to 1.0 (no shadow)
            VkClearDepthStencilValue clearDS = VkClearDepthStencilValue.calloc(stack)
                    .depth(1.0f).stencil(0);
            VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(1);
            for (int i = 0; i < imageCount; i++) {
                vkCmdClearDepthStencilImage(cmd, depthImages[i],
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearDS, clearRange);
            }

            // Transition all depth images TRANSFER_DST → DEPTH_STENCIL_READ_ONLY
            for (int i = 0; i < imageCount; i++) {
                barriers.get(i)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            }
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barriers);
        }

        LOGGER.debug("[SHADOW] Shadow depth images initialized (cleared to depth=1.0)");
    }

    /**
     * Returns (or lazily creates) a compatibility graphics pipeline for this program and vertex format.
     *
     * <p>This bridges shaderpack modules into Vulkanium's current draw path while full phase render graph
     * integration is still in progress.</p>
     */
    public BasicPipeline getOrCreateCompatibilityPipeline(ProgramId requestedProgram, VertexFormat format) {
        if (!loaded || requestedProgram == null || format == null) return null;

        // Skip fullscreen passes — they need a separate execution path (composite/deferred/final)
        if (requestedProgram.isFullscreenPass()) {
            return null;
        }

        String cacheKey = requestedProgram.name() + "::" + formatKey(format);
        BasicPipeline existing = compatibilityPipelines.get(cacheKey);
        if (existing != null) return existing;

        CompiledProgram program = resolveCompiledProgramWithFallback(requestedProgram);
        if (program == null) return null;

        try {
            long geometryModule = program.geometryModule;
            if (geometryModule != 0 && warnedGeometryCompatBypass.add(requestedProgram.getSourceName())) {
                LOGGER.info("[COMPAT] Using geometry stage for {} in compatibility gbuffers path", requestedProgram.getSourceName());
            }

            // Use MRT render pass for gbuffers programs when G-buffer is active
            long renderPassHandle;
            int colorAttachmentCount;
            if (gbufferManager != null && !requestedProgram.isFullscreenPass()) {
                renderPassHandle = gbufferManager.getRenderPass();
                colorAttachmentCount = gbufferManager.getSubpassColorRefCount();
            } else {
                renderPassHandle = Vulkanium.getMainRenderPass().getRenderPass();
                colorAttachmentCount = 1;
            }

            BasicPipeline pipeline = new BasicPipeline();
            pipeline.initializeWithModules(
                    Vulkanium.getVulkanDevice().getLogicalDevice(),
                    renderPassHandle,
                    "shaderpack_" + requestedProgram.getSourceName(),
                    program.vertexModule,
                    program.fragmentModule,
                    geometryModule,
                    format,
                    colorAttachmentCount
            );
            compatibilityPipelines.put(cacheKey, pipeline);
            // Shaderpack compatibility pipelines use CCW front face to match
            // Minecraft/OpenGL winding expectations for terrain/entity meshes.
            // Using CW here causes inside-out culling in gbuffer terrain passes.
            pipeline.setFrontFace(org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE);
            return pipeline;
        } catch (Exception e) {
            LOGGER.warn("Failed to create compatibility pipeline for {}: {}", requestedProgram, e.getMessage());
            return null;
        }
    }

    private CompiledProgram resolveCompiledProgramWithFallback(ProgramId requestedProgram) {
        for (ProgramId candidate : requestedProgram.getFallbackChain()) {
            CompiledProgram program = compiledPrograms.get(candidate);
            if (program != null && program.compatibilitySafe) {
                return program;
            }
            if (program != null && !program.compatibilitySafe && warnedUnsafePrograms.add(candidate)) {
                LOGGER.warn("Skipping unsafe compatibility pipeline for {} (descriptor/sampler/geometry constraints)", candidate.getSourceName());
            }
        }
        return null;
    }

    /**
     * Evaluates which features the shaderpack requires beyond basic gbuffers.
     * Populates status flags used by the config screen and logging.
     */
    private boolean evaluateCompatibilitySupport() {
        if (programSet == null) return true;

        // ── Check iris.features.required ──
        // Validate required features using the FeatureFlags enum.
        // Reference: Iris FeatureFlags.isInvalid() / getInvalidStatus() from
        // net.irisshaders.iris.features.FeatureFlags (Iris Shaders, LGPL-3.0).
        if (properties != null && !properties.getRequiredFeatures().isEmpty()) {
            List<String> unsupported = FeatureFlags.getUnsupportedFeatures(properties.getRequiredFeatures());
            if (!unsupported.isEmpty()) {
                LOGGER.warn("[COMPAT] Pack requires unsupported features: {}", unsupported);
                compatibilityIssueMessage = FeatureFlags.getUnsupportedMessage(properties.getRequiredFeatures());
                // Don't return false — let the pack try to load anyway
            }
        }

        boolean hasFullscreenPasses = false;
        List<String> fullscreenPrograms = new ArrayList<>();
        for (ProgramId programId : programSet.getDirectlyProvidedPrograms()) {
            if (programId.isFullscreenPass()) {
                hasFullscreenPasses = true;
                fullscreenPrograms.add(programId.getSourceName());
            }
        }

        if (hasFullscreenPasses) {
            if (FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.FULL) {
            LOGGER.info("[COMPAT] Pack defines {} fullscreen passes: {} — gbuffers render path active; fullscreen passes enabled (full mode)",
                fullscreenPrograms.size(), fullscreenPrograms);
            compatibilityIssueMessage = "Composite/deferred/final passes (" + fullscreenPrograms.size()
                + " programs) enabled in full compatibility mode.";
            return true; // FULL mode: passes execute, world rendering stays active
            } else if (FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.SAFE) {
            LOGGER.info("[COMPAT] Pack defines {} fullscreen passes: {} — gbuffers render path active; fullscreen passes run in safe compatibility mode",
                fullscreenPrograms.size(), fullscreenPrograms);
            compatibilityIssueMessage = "Composite/deferred/final passes (" + fullscreenPrograms.size()
                + " programs) run in safe compatibility mode (single-target passes only). "
                + "Use -Dvulkanium.shaderpack.fullscreenCompatMode=full for experimental full chain.";
            return true; // safe mode is the supported default compatibility path
            } else {
            LOGGER.info("[COMPAT] Pack defines {} fullscreen passes: {} — gbuffers render path active; fullscreen passes are disabled in safe mode",
                fullscreenPrograms.size(), fullscreenPrograms);
            compatibilityIssueMessage = "Composite/deferred/final passes (" + fullscreenPrograms.size()
                + " programs) are disabled. Enable safe/full with -Dvulkanium.shaderpack.fullscreenCompatMode=safe|full.";
            return false;
            }
        }

        return true;
    }

    private static String formatKey(VertexFormat format) {
        StringBuilder sb = new StringBuilder();
        for (VertexFormatElement element : format.getElements()) {
            if (element.getUsage() != VertexFormatElement.Usage.PADDING) {
                sb.append(element.getUsage().name())
                        .append(element.getType().name())
                        .append(element.getCount())
                        .append(element.getIndex())
                        .append('_');
            }
        }
        return sb.toString();
    }

    @Override
    public boolean isLoaded() { return loaded; }

    /** Returns the G-buffer manager, or null if MRT is not initialized. */
    public GBufferManager getGBufferManager() { return gbufferManager; }

    /** Returns the shadow map, or null if shadow is not initialized. */
    public ShadowMap getShadowMap() { return shadowMap; }

    /** Returns the shadow renderer, or null if shadow is not initialized. */
    public ShadowRenderer getShadowRenderer() { return shadowRenderer; }

    /** Returns the shadow directives, or null if not parsed. */
    public ShadowDirectives getShadowDirectives() { return shadowDirectives; }

    @Override
    public void onFrameBegin(VkCommandBuffer cmd, int frameIndex) {
        if (!loaded) return;
        if (computeManager != null && computeManager.isInitialized()) {
            computeManager.resetDescriptorPoolForFrame(frameIndex);
        }
        // Update per-frame uniforms from game state
        if (uniforms != null) {
            uniforms.updateFromGameState();
        }

        // NOTE: Shadow depth image initialization moved to renderShadowPass()
        // where it runs OUTSIDE any VkRenderPass. vkCmdPipelineBarrier with
        // image memory barriers and vkCmdClearDepthStencilImage are illegal
        // inside a render pass (mainRenderPass is active here).
    }

    @Override
    public void onFrameEnd(VkCommandBuffer cmd, int frameIndex) {
        if (!loaded) return;
        runFullscreenPasses(cmd, frameIndex);
    }

    /**
     * Captures the current color + depth scene inputs for fullscreen shaderpack passes.
     * Must be called outside any active render pass.
     *
     * <p>When the G-buffer MRT manager is active, copies G-buffer color and depth
     * targets into the fullscreen ping-pong targets. Otherwise falls back to
     * capturing from the swapchain color/depth.</p>
     */
    public void prepareFullscreenInputs(VkCommandBuffer cmd, int frameIndex) {
        if (!loaded) return;
        if (Vulkanium.getVulkanSwapchain() == null || Vulkanium.getFrameOrchestrator() == null) return;

        int width = Vulkanium.getVulkanSwapchain().getWidth();
        int height = Vulkanium.getVulkanSwapchain().getHeight();
        if (width <= 0 || height <= 0) return;

        int colorFormat = Vulkanium.getVulkanSwapchain().getImageFormat();
        int depthFormat = Vulkanium.getVulkanSwapchain().getDepthFormat();
        VkDevice device = Vulkanium.getVulkanDevice().getLogicalDevice();

        // Use HDR-aware internal render format when HDR is active
        int renderFormat = net.vulkanium.render.hdr.HdrConfig.isHdrEnabled()
                ? net.vulkanium.render.hdr.HdrConfig.getInternalRenderFormat()
                : colorFormat;

        // Lazily create / resize MRT targets
        if (fsTargets == null) {
            fsTargets = new FullscreenRenderTargets();
            // Apply per-target format overrides from shaderpack properties
            if (renderTargetSettings != null) {
                for (int i = 0; i < RenderTargetSettings.MAX_COLOR_TARGETS; i++) {
                    RenderTargetSettings.BufferSettings bs = renderTargetSettings.getColorSettings(i);
                    if (bs != null && bs.getVkFormat() != 0) {
                        fsTargets.setTargetFormat(i, bs.getVkFormat());
                    }
                }
            }
        }
        fsTargets.ensureSize(device, Vulkanium.getVulkanMemory(), width, height, renderFormat, depthFormat);
        fsTargets.initializeImageLayouts(cmd); // transition UNDEFINED → SHADER_READ on first use
        fsTargets.resetFlips();

        // ── G-buffer MRT path: copy all G-buffer targets into fsTargets ──
        if (gbufferManager != null && gbufferManager.isInitialized()) {
            // Copy each used G-buffer color target into the corresponding fsTargets colortex
            for (int colortexIdx : gbufferManager.getUsedColorTargets()) {
                RenderTarget gbufTarget = gbufferManager.getColorTarget(colortexIdx);
                if (gbufTarget != null && gbufTarget.getImage() != VK_NULL_HANDLE) {
                    fsTargets.captureGBufferColorTarget(cmd, gbufTarget.getImage(), colortexIdx);
                }
            }

            // Copy G-buffer depth into depthtex0/1/2
            RenderTarget gbufDepth = gbufferManager.getDepthTarget();
            if (gbufDepth != null && gbufDepth.getImage() != VK_NULL_HANDLE) {
                fsTargets.captureGBufferDepthTarget(cmd, gbufDepth.getImage());
            }
        } else {
            // ── Fallback: capture from swapchain (no G-buffer) ──
            long[] swapchainImages = Vulkanium.getVulkanSwapchain().getImages();
            int imageIndex = Vulkanium.getFrameOrchestrator().getCurrentImageIndex();
            if (swapchainImages != null && imageIndex >= 0 && imageIndex < swapchainImages.length) {
                fsTargets.captureSceneToColorTarget0(cmd, swapchainImages[imageIndex],
                        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }

            // Capture depth → depthtex0/1/2
            long srcDepthImage = Vulkanium.getVulkanSwapchain().getDepthImage();
            if (srcDepthImage != VK_NULL_HANDLE) {
                fsTargets.captureDepthToTarget0(cmd, srcDepthImage);
            }
        }
    }

    private void runFullscreenPasses(VkCommandBuffer cmd, int frameIndex) {
        if (FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.OFF) {
            if (!loggedFullscreenSkipped) {
                LOGGER.info("[FULLSCREEN] Skipping deferred/composite/final execution (mode=off). "
                        + "Use -Dvulkanium.shaderpack.fullscreenCompatMode=safe or full.");
                loggedFullscreenSkipped = true;
            }
            return;
        }

        if (FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.SAFE && !isSafeModeExecutionAllowed()) {
            if (!loggedSafeModeBlocked) {
                LOGGER.info("[FULLSCREEN] Skipping fullscreen execution in mode=safe because pass chain is not safe-compatible "
                        + "(requires compute and/or non-colortex0 targets). Use mode=full for experimental full chain.");
                loggedSafeModeBlocked = true;
            }
            return;
        }

        if (fsTargets == null || !fsTargets.isInitialized()) return;

        DrawBatcher drawBatcher = Vulkanium.getDrawBatcher();
        if (drawBatcher == null) return;

        int width = fsTargets.getWidth();
        int height = fsTargets.getHeight();
        if (width <= 0 || height <= 0) return;

        long placeholderView = Vulkanium.getPlaceholderImageView();
        long placeholderSampler = Vulkanium.getPlaceholderSampler();
        if (placeholderView == VK_NULL_HANDLE || placeholderSampler == VK_NULL_HANDLE) return;

        // Pre-compute uniform data shared across all passes
        // Use world-render snapshots — the live matrices may have been
        // overwritten by GUI/HUD rendering by the time fullscreen passes run.
        //
        // IMPORTANT: For fullscreen passes (composite/deferred/final), the
        // per-draw matrices (iris_ModelViewMatrix, iris_ProjectionMatrix at
        // offsets 0 and 128) MUST be identity.  In Iris/GL, these passes draw
        // a fullscreen triangle without any GL matrix state, so ftransform()
        // → gl_ModelViewProjectionMatrix * gl_Vertex = identity * vertex = NDC.
        // The per-frame camera matrices are written separately to
        // gbufferModelView (offset 1312) and gbufferProjection (offset 1440)
        // inside uploadUniformsShaderpack().
        float[] modelView = new float[16];
        new org.joml.Matrix4f().get(modelView); // identity for per-draw matrix
        float[] projection = new float[16];
        new org.joml.Matrix4f().get(projection); // identity for per-draw matrix
        float[] modelViewInv = new float[16];
        new org.joml.Matrix4f().get(modelViewInv); // inv(identity) = identity
        float[] projectionInv = new float[16];
        new org.joml.Matrix4f().get(projectionInv); // inv(identity) = identity
        float[] colorMod = {
                VRenderSystem.getShaderColorR(),
                VRenderSystem.getShaderColorG(),
                VRenderSystem.getShaderColorB(),
                VRenderSystem.getShaderColorA()
        };
        float[] fogParams = {
                VRenderSystem.getFogColorR(),
                VRenderSystem.getFogColorG(),
                VRenderSystem.getFogColorB(),
                VRenderSystem.getFogColorA(),
                VRenderSystem.getFogStart(),
                VRenderSystem.getFogEnd()
        };
        float[] texMat = new float[16];
        VRenderSystem.getTextureMatrix().get(texMat);
        float[] chunkOffset = {
                VRenderSystem.getChunkOffsetX(),
                VRenderSystem.getChunkOffsetY(),
                VRenderSystem.getChunkOffsetZ()
        };

        int executed = 0;
        int skipped = 0;
        int computeDispatched = 0;

        for (ProgramId programId : FULLSCREEN_PASS_ORDER) {
            String programName = programId.getSourceName();

            // ── Compute dispatch: if this pass has a .csh, dispatch it before the fragment pass ──
            // Reference: Iris CompositeRenderer (Iris Shaders, LGPL-3.0) dispatches each
            // pass's compute programs, then glMemoryBarrier, then renders the fragment pass.
            boolean hasCompute = computeManager != null && computeManager.isInitialized()
                    && computeManager.hasComputeProgram(programName);
            if (hasCompute && FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.FULL) {
                try {
                    // Upload uniforms for compute dispatch
                    int computeUboOffset = drawBatcher.uploadUniformsShaderpack(
                            frameIndex, modelView, modelViewInv, projection, projectionInv,
                            colorMod, fogParams, texMat, chunkOffset
                    );

                    // Allocate and update compute descriptor set
                    long computeDescSet = computeManager.allocateComputeDescriptorSet(frameIndex);
                    if (computeDescSet != VK_NULL_HANDLE) {
                        // Transition storage image targets to GENERAL for compute imageStore access
                        // Reference: Vulkan spec requires VK_IMAGE_LAYOUT_GENERAL for storage images
                        int storageImageCount = Math.min(ShaderpackComputeManager.MAX_STORAGE_IMAGES,
                                FullscreenRenderTargets.MAX_COLOR_TARGETS);
                        if (fsTargets != null) {
                            fsTargets.transitionReadTargetsToGeneral(cmd, storageImageCount);
                        }

                        // Build sampler/image/SSBO arrays for compute descriptor update
                        int maxTex = ShaderpackComputeManager.MAX_SAMPLERS;
                        long[] compViews = new long[maxTex];
                        long[] compSamplers = new long[maxTex];
                        int[] compLayouts = new int[maxTex];
                        fillFullscreenSamplerBindings(compViews, compSamplers, compLayouts, placeholderView, placeholderSampler);

                        // Storage images (colorimgN from G-buffer targets + custom images)
                        long[] storageImageViews = new long[ShaderpackComputeManager.MAX_STORAGE_IMAGES];
                        Arrays.fill(storageImageViews, VK_NULL_HANDLE);
                        int storageImagesPopulated = 0;
                        if (fsTargets != null) {
                            for (int i = 0; i < Math.min(storageImageViews.length,
                                    FullscreenRenderTargets.MAX_COLOR_TARGETS); i++) {
                                RenderTarget rt = fsTargets.getReadTarget(i);
                                if (rt != null && rt.getImageView() != VK_NULL_HANDLE) {
                                    storageImageViews[i] = rt.getImageView();
                                    storageImagesPopulated++;
                                }
                            }
                        }

                        // SSBO buffers
                        long[] ssboBuffers = ssboManager != null ? ssboManager.getAllBuffers() : new long[0];
                        long[] ssboSizes = ssboManager != null ? ssboManager.getAllSizes() : new long[0];

                        long uboBuffer = drawBatcher.getUniformBuffer(frameIndex);
                        long uboRange = (long) drawBatcher.getUniformBufferRange();
                        computeManager.updateComputeDescriptorSet(computeDescSet,
                                uboBuffer, 0L, uboRange,
                                compViews, compSamplers,
                                storageImageViews,
                                ssboBuffers, ssboSizes);

                        // Set screen dimensions for workgroup calculation
                        computeManager.setScreenDimensions(width, height);

                        // Debug: count non-null sampler bindings
                        int samplerCount = 0;
                        for (long v : compViews) { if (v != VK_NULL_HANDLE && v != placeholderView) samplerCount++; }
                        LOGGER.debug("[FULLSCREEN] Compute '{}' — samplers={}, storageImages={}, " +
                                "SSBOs={}, uboOffset={}, screen={}x{}",
                                programName, samplerCount, storageImagesPopulated,
                                ssboBuffers.length, computeUboOffset, width, height);

                        // Dispatch compute
                        computeManager.dispatch(cmd, programName, computeDescSet, computeUboOffset);

                        // Pipeline barrier: compute write → fragment read
                        computeManager.recordComputeToFragmentBarrier(cmd);

                        // Transition storage image targets back to SHADER_READ_ONLY_OPTIMAL
                        if (fsTargets != null) {
                            fsTargets.transitionReadTargetsFromGeneral(cmd, storageImageCount);
                        }

                        computeDispatched++;
                    } else {
                        LOGGER.warn("[FULLSCREEN] Failed to allocate compute descriptor set for '{}'", programName);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[FULLSCREEN] Compute dispatch failed for '{}': {}", programName, e.getMessage());
                    LOGGER.debug("[FULLSCREEN]   Stack trace:", e);
                }
            }

            // ── Fragment pass: standard fullscreen triangle rendering ──
            CompiledProgram program = compiledPrograms.get(programId);
            if (program == null || program.vertexModule == 0 || program.fragmentModule == 0) continue;

            if (!shouldExecuteFullscreenProgram(programId, program)) {
                skipped++;
                continue;
            }

            int[] targets = program.renderTargets;
            if (targets == null || targets.length == 0) {
                targets = new int[]{0}; // Default: write to colortex0
            }

            // ── Get/create compact MRT render pass and pipeline ──
            // Compact mapping: attachmentCount == renderTargets.length.
            // Fragment output layout(location=i) maps to framebuffer attachment i,
            // which holds colortex[targets[i]].  Always ≤ maxColorAttachments.
            int subpassColorCount = FullscreenRenderTargets.getSubpassColorCount(targets);
            long mrtRenderPass = fsTargets.getOrCreateMrtRenderPass(targets);

            BasicPipeline pipeline = getOrCreateMrtFullscreenPipeline(
                    programId, program, mrtRenderPass, targets, subpassColorCount);
            if (pipeline == null) continue;

            long vkPipeline = pipeline.getOrCreatePipeline(
                    false, false, false, false,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                    VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                    VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO,
                    VK_COMPARE_OP_ALWAYS
            );
            if (vkPipeline == VK_NULL_HANDLE) continue;

            // ── Transition write targets → COLOR_ATTACHMENT_OPTIMAL ──
            fsTargets.transitionWriteTargetsToAttachment(cmd, targets);

            // ── Get/create framebuffer for this pass's targets ──
            long framebuffer = fsTargets.getOrCreateFramebuffer(targets, mrtRenderPass);

            // ── Begin MRT render pass ──
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(mrtRenderPass)
                        .framebuffer(framebuffer);
                rpBegin.renderArea().offset().set(0, 0);
                rpBegin.renderArea().extent().set(width, height);

                vkCmdBeginRenderPass(cmd, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

                // Standard positive-height viewport for OpenGL-matching gl_FragCoord
                // With no shader Y-flip, gl_FragCoord.y = 0 at scene bottom (matching OpenGL)
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                        .x(0.0f)
                        .y(0.0f)
                        .width((float) width)
                        .height((float) height)
                        .minDepth(0.0f)
                        .maxDepth(1.0f);
                vkCmdSetViewport(cmd, 0, viewport);

                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(0, 0);
                scissor.extent().set(width, height);
                vkCmdSetScissor(cmd, 0, scissor);
            }

            // ── Upload uniforms ──
            int uboOffset = drawBatcher.uploadUniformsShaderpack(
                    frameIndex, modelView, modelViewInv, projection, projectionInv,
                    colorMod, fogParams, texMat, chunkOffset
            );

            // ── Bind sampler descriptors (read from ping-pong targets) ──
            int maxTex = BasicPipeline.getMaxTextureBindings();
            long[] views = new long[maxTex];
            long[] samplers = new long[maxTex];
            int[] imageLayouts = new int[maxTex];
            fillFullscreenSamplerBindings(views, samplers, imageLayouts, placeholderView, placeholderSampler);
            int setIdx = drawBatcher.updateDescriptorSet(frameIndex, views, samplers, imageLayouts);

            // ── Bind pipeline + descriptors ──
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vkPipeline);
            drawBatcher.bindDescriptorSet(cmd, pipeline.getPipelineLayout(), setIdx, uboOffset);

            // ── Draw fullscreen triangle ──
            ByteBuffer fullscreenTri = FULLSCREEN_TRIANGLE_TEMPLATE.duplicate();
            fullscreenTri.position(0);
            drawBatcher.draw(cmd, frameIndex, vkPipeline, pipeline.getPipelineLayout(),
                    fullscreenTri, 3, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
                    DefaultVertexFormat.POSITION.getVertexSize());

            // ── End MRT render pass ──
            vkCmdEndRenderPass(cmd);

            // ── Transition written targets → SHADER_READ + flip for next pass ──
            fsTargets.transitionAndFlipAfterPass(cmd, targets);

            executed++;
        }

        // ── Blit colortex0 → swapchain ──
        // When MRT G-buffer is active, the fullscreen pass chain can produce meaningful
        // output because colortex0-N were populated by gbuffers world rendering.
        // Auto-enabled when gbufferManager is initialized, or via -Dvulkanium.mrt.enabled=true.
        boolean mrtGbuffersAvailable = (gbufferManager != null && gbufferManager.isInitialized())
                || Boolean.getBoolean("vulkanium.mrt.enabled");
        if (executed > 0 && mrtGbuffersAvailable) {
            long[] swapchainImages = Vulkanium.getVulkanSwapchain().getImages();
            int imageIndex = Vulkanium.getFrameOrchestrator().getCurrentImageIndex();
            if (swapchainImages != null && imageIndex >= 0 && imageIndex < swapchainImages.length) {
                int scWidth = Vulkanium.getVulkanSwapchain().getWidth();
                int scHeight = Vulkanium.getVulkanSwapchain().getHeight();
                fsTargets.blitColorTarget0ToSwapchain(cmd,
                        swapchainImages[imageIndex], VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                        scWidth, scHeight);
            }
        }

        if (executed > 0 && !loggedFullscreenExecution) {
            LOGGER.info("[FULLSCREEN] Executed {} deferred/composite/final passes (mode={}, skipped={}, compute={}, blit={})",
                    executed, FULLSCREEN_COMPAT_MODE.name().toLowerCase(Locale.ROOT), skipped,
                    computeDispatched,
                    mrtGbuffersAvailable ? "enabled (MRT G-buffer active)" : "disabled (no MRT gbuffers)");
            if (!mrtGbuffersAvailable) {
                LOGGER.info("[FULLSCREEN] Blit disabled: colortex1-7 are empty without MRT gbuffers. "
                        + "Swapchain retains basic rendered scene. "
                        + "Enable with -Dvulkanium.mrt.enabled=true to test blit.");
            }
            loggedFullscreenExecution = true;
        } else if (executed == 0 && !loggedFullscreenSkipped) {
            LOGGER.info("[FULLSCREEN] No compatible fullscreen pass executed in mode={} (skipped={})",
                    FULLSCREEN_COMPAT_MODE.name().toLowerCase(Locale.ROOT), skipped);
            loggedFullscreenSkipped = true;
        }
    }

    /**
     * Gets or creates an MRT fullscreen pipeline for the given program and target configuration.
     * The subpassColorCount must match the subpass's colorAttachmentCount (= max_target + 1)
     * so that the pipeline's blend state array has the correct size.
     */
    private BasicPipeline getOrCreateMrtFullscreenPipeline(ProgramId id, CompiledProgram program,
                                                           long mrtRenderPass, int[] targets,
                                                           int subpassColorCount) {
        String key = id.name() + "_mrt" + java.util.Arrays.toString(targets);
        BasicPipeline existing = mrtPipelines.get(key);
        if (existing != null) return existing;

        try {
            BasicPipeline pipeline = new BasicPipeline();
            pipeline.initializeWithModules(
                    Vulkanium.getVulkanDevice().getLogicalDevice(),
                    mrtRenderPass,
                    "shaderpack_mrt_" + id.getSourceName() + "_" + java.util.Arrays.toString(targets),
                    program.vertexModule,
                    program.fragmentModule,
                    VK_NULL_HANDLE,
                    DefaultVertexFormat.POSITION,
                    subpassColorCount
            );
            mrtPipelines.put(key, pipeline);
            // Keep CCW winding consistent with compatibility pipelines.
            pipeline.setFrontFace(org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE);
            return pipeline;
        } catch (Exception e) {
            LOGGER.warn("[FULLSCREEN] Failed to create MRT pipeline for {} (targets={}): {}",
                    id.getSourceName(), java.util.Arrays.toString(targets), e.getMessage());
            return null;
        }
    }

    /**
     * Determines whether a specific fullscreen program should be executed.
     *
     * <p>In FULL mode, all programs execute. In SAFE mode, programs are filtered:
     * compute-associated passes are skipped, and only colortex0-writing passes run.
     * This allows packs with mixed compute+graphics chains (like iterationRP) to
     * at least render their non-compute graphics passes.</p>
     */
    private boolean shouldExecuteFullscreenProgram(ProgramId programId, CompiledProgram program) {
        if (FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.FULL) {
            return true;
        }

        // Safe mode: skip compute-associated passes (compute dispatch not yet implemented)
        ProgramSource source = programSet != null ? programSet.getAllPrograms().get(programId) : null;
        if (source != null && source.isValidCompute()) {
            if (warnedUnsafePrograms.add(programId)) {
                LOGGER.info("[FULLSCREEN] Skipping compute-associated pass {} in safe mode (vkCmdDispatch not implemented)",
                        programId.getSourceName());
            }
            return false;
        }

        int[] renderTargets = program.renderTargets;
        if (renderTargets == null || renderTargets.length == 0) {
            return false;
        }

        boolean writesColor0 = false;
        for (int target : renderTargets) {
            if (target == 0) {
                writesColor0 = true;
                continue;
            }
            // Non-colortex0 target in safe mode — skip this pass
            if (warnedUnsafePrograms.add(programId)) {
                LOGGER.info("[FULLSCREEN] Skipping {} in safe mode (writes to non-colortex0 targets: {})",
                        programId.getSourceName(), java.util.Arrays.toString(renderTargets));
            }
            return false;
        }
        return writesColor0;
    }

    /**
     * Determines whether safe-mode fullscreen execution is allowed.
     *
     * <p>In safe mode, compute-only passes are individually skipped during execution
     * rather than blocking the entire chain. This allows packs like iterationRP
     * (which mix compute + graphics passes) to still render their graphics passes.</p>
     *
     * <p>Reference: Iris {@code CompositeRenderer} (Iris Shaders, LGPL-3.0) iterates
     * all passes and dispatches compute programs individually via
     * {@code ComputeProgram.dispatch()}, then runs the associated fragment pass.
     * Compute-only passes (no fragment shader) are supported as {@code ComputeOnlyPass}.
     * We skip compute dispatch but still execute graphics fragment passes.</p>
     */
    private boolean isSafeModeExecutionAllowed() {
        if (safeFullscreenExecutionAllowed != null) {
            return safeFullscreenExecutionAllowed;
        }

        boolean hasSafeGraphicsPass = false;
        for (ProgramId programId : FULLSCREEN_PASS_ORDER) {
            CompiledProgram program = compiledPrograms.get(programId);
            if (program == null || program.vertexModule == 0 || program.fragmentModule == 0) {
                continue;
            }

            // In safe mode, we skip compute-associated passes at execution time
            // rather than blocking everything. Check if we have at least one
            // pure graphics pass that writes to colortex0.
            ProgramSource source = programSet != null ? programSet.getAllPrograms().get(programId) : null;
            if (source != null && source.isValidCompute()) {
                // This pass has compute — will be skipped individually at execution time.
                // Don't block the entire chain.
                continue;
            }

            hasSafeGraphicsPass = true;
            if (!writesOnlyColor0(program.renderTargets)) {
                // In safe mode, non-colortex0 writes are still skipped individually
                continue;
            }
        }

        safeFullscreenExecutionAllowed = hasSafeGraphicsPass;
        return safeFullscreenExecutionAllowed;
    }

    private static boolean writesOnlyColor0(int[] renderTargets) {
        if (renderTargets == null || renderTargets.length == 0) {
            return false;
        }
        for (int target : renderTargets) {
            if (target != 0) {
                return false;
            }
        }
        return true;
    }

    private static ByteBuffer createFullscreenTriangleTemplate() {
        ByteBuffer vertices = java.nio.ByteBuffer.allocateDirect(3 * 3 * Float.BYTES)
                .order(java.nio.ByteOrder.nativeOrder());
        vertices.putFloat(-1.0f).putFloat(-1.0f).putFloat(0.0f);
        vertices.putFloat( 3.0f).putFloat(-1.0f).putFloat(0.0f);
        vertices.putFloat(-1.0f).putFloat( 3.0f).putFloat(0.0f);
        vertices.flip();
        return vertices;
    }

    private void fillFullscreenSamplerBindings(long[] views, long[] samplers,
                                               int[] imageLayouts,
                                               long placeholderView, long placeholderSampler) {
        Arrays.fill(views, placeholderView);
        Arrays.fill(samplers, placeholderSampler);
        if (imageLayouts != null) Arrays.fill(imageLayouts, 0); // 0 = default (SHADER_READ_ONLY)

        if (fsTargets == null || !fsTargets.isInitialized()) return;

        // Bind colortex0-15 from their current READ side (ping-pong)
        for (int i = 0; i < FullscreenRenderTargets.MAX_COLOR_TARGETS; i++) {
            RenderTarget readTarget = fsTargets.getReadTarget(i);
            if (readTarget != null
                    && readTarget.getImageView() != VK_NULL_HANDLE
                    && readTarget.getSampler() != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "colortex" + i,
                        readTarget.getImageView(), readTarget.getSampler());
            }
        }

        // Legacy aliases (map to the same underlying colortex targets)
        RenderTarget ct0 = fsTargets.getReadTarget(0);
        if (ct0 != null && ct0.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gcolor", ct0.getImageView(), ct0.getSampler());
            bindSamplerAlias(views, samplers, "composite", ct0.getImageView(), ct0.getSampler());
        }
        RenderTarget ct1 = fsTargets.getReadTarget(1);
        if (ct1 != null && ct1.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gdepth", ct1.getImageView(), ct1.getSampler());
        }
        RenderTarget ct2 = fsTargets.getReadTarget(2);
        if (ct2 != null && ct2.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gnormal", ct2.getImageView(), ct2.getSampler());
        }
        RenderTarget ct4 = fsTargets.getReadTarget(4);
        if (ct4 != null && ct4.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gaux1", ct4.getImageView(), ct4.getSampler());
        }
        RenderTarget ct5 = fsTargets.getReadTarget(5);
        if (ct5 != null && ct5.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gaux2", ct5.getImageView(), ct5.getSampler());
        }
        RenderTarget ct6 = fsTargets.getReadTarget(6);
        if (ct6 != null && ct6.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gaux3", ct6.getImageView(), ct6.getSampler());
        }
        RenderTarget ct7 = fsTargets.getReadTarget(7);
        if (ct7 != null && ct7.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gaux4", ct7.getImageView(), ct7.getSampler());
        }

        // Bind depth targets (these images are in DEPTH_STENCIL_READ_ONLY_OPTIMAL)
        for (int i = 0; i < FullscreenRenderTargets.MAX_DEPTH_TARGETS; i++) {
            RenderTarget depthTarget = fsTargets.getDepthTarget(i);
            if (depthTarget != null
                    && depthTarget.getImageView() != VK_NULL_HANDLE
                    && depthTarget.getSampler() != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "depthtex" + i,
                        depthTarget.getImageView(), depthTarget.getSampler());
                // Mark depth textures with correct layout for descriptor set
                Integer depthBinding = DEFAULT_SAMPLER_BINDINGS.get("depthtex" + i);
                if (imageLayouts != null && depthBinding != null && depthBinding >= 0 && depthBinding < imageLayouts.length) {
                    imageLayouts[depthBinding] = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
                }
            }
        }
        // Legacy depth alias
        RenderTarget dt0 = fsTargets.getDepthTarget(0);
        if (dt0 != null && dt0.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gdepthtex", dt0.getImageView(), dt0.getSampler());
            // gdepthtex uses same binding as depthtex0, layout already set above
        }

        // ── Shadow texture bindings ──
        // Bind shadow depth maps (shadowtex0 → binding 23, shadowtex1 → binding 24)
        // and shadow color attachments (shadowcolor0 → binding 25, shadowcolor1 → binding 26)
        if (shadowMap != null && shadowImagesInitialized) {
            // shadowtex0 — main shadow depth (DEPTH_STENCIL_READ_ONLY_OPTIMAL)
            long stView0 = shadowMap.getMainDepthView();
            long stSamp0 = shadowMap.getMainDepthSampler();
            if (stView0 != VK_NULL_HANDLE && stSamp0 != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "shadowtex0", stView0, stSamp0);
                bindSamplerAlias(views, samplers, "shadow", stView0, stSamp0);
                bindSamplerAlias(views, samplers, "waterShadow", stView0, stSamp0);
                Integer st0Binding = DEFAULT_SAMPLER_BINDINGS.get("shadowtex0");
                if (imageLayouts != null && st0Binding != null && st0Binding >= 0 && st0Binding < imageLayouts.length) {
                    imageLayouts[st0Binding] = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
                }
            }

            // shadowtex1 — pre-translucent depth (DEPTH_STENCIL_READ_ONLY_OPTIMAL)
            long stView1 = shadowMap.getNoTranslucentsDepthView();
            long stSamp1 = shadowMap.getNoTranslucentsDepthSampler();
            if (stView1 != VK_NULL_HANDLE && stSamp1 != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "shadowtex1", stView1, stSamp1);
                Integer st1Binding = DEFAULT_SAMPLER_BINDINGS.get("shadowtex1");
                if (imageLayouts != null && st1Binding != null && st1Binding >= 0 && st1Binding < imageLayouts.length) {
                    imageLayouts[st1Binding] = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
                }
            }

            // shadowcolor0..7 — shadow color attachments (if allocated)
            for (int i = 0; i < ShadowMap.MAX_COLOR_TARGETS; i++) {
                if (shadowMap.isColorAllocated(i)) {
                    long scView = shadowMap.getColorView(i);
                    long scSamp = shadowMap.getColorSampler(i);
                    if (scView != VK_NULL_HANDLE && scSamp != VK_NULL_HANDLE) {
                        bindSamplerAlias(views, samplers, "shadowcolor" + i, scView, scSamp);
                    }
                }
            }
        }

        // ── Noise texture binding (noisetex → binding 27) ──
        if (noiseImageView != VK_NULL_HANDLE && noiseSampler != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "noisetex", noiseImageView, noiseSampler);
        }

        // Also pick up any MC-bound textures (block atlas, lightmap, etc.)
        // But do NOT overwrite shaderpack-managed slots (depthtex, shadowtex, shadowcolor, colortex, noisetex)
        // since fullscreen/composite passes must use shaderpack targets, not MC GL state.
        // We only allow MC textures to fill gtexture (0), lightmap (1), normals (2), specular (3).
        int maxUnits = Math.min(4, Math.min(views.length, BasicPipeline.getMaxTextureBindings()));
        for (int unit = 0; unit < maxUnits; unit++) {
            int textureId = VRenderSystem.getBoundTextureId(unit);
            if (textureId <= 0) continue;

            VulkanTexture texture = GlStateInterceptor.getVulkanTexture(textureId);
            if (texture == null || !texture.isAllocated()) continue;
            if (texture.getImageView() == VK_NULL_HANDLE || texture.getSampler() == VK_NULL_HANDLE) continue;

            views[unit] = texture.getImageView();
            samplers[unit] = texture.getSampler();
        }

        // Remap MC lightmap: MC binds lightmap to GL unit 2, but shaders expect
        // it at binding 1 (see DEFAULT_SAMPLER_BINDINGS "lightmap" → 1).
        {
            int lmUnit = 2; // MC's lightmap GL texture unit
            int lmBinding = 1; // Shader binding for "lightmap"
            int lmId = VRenderSystem.getBoundTextureId(lmUnit);
            if (lmId > 0 && lmBinding < views.length) {
                VulkanTexture lmTex = GlStateInterceptor.getVulkanTexture(lmId);
                if (lmTex != null && lmTex.isAllocated()
                        && lmTex.getImageView() != VK_NULL_HANDLE
                        && lmTex.getSampler() != VK_NULL_HANDLE) {
                    views[lmBinding] = lmTex.getImageView();
                    samplers[lmBinding] = lmTex.getSampler();
                }
            }
        }
    }

    private void bindSamplerAlias(long[] views, long[] samplers, String samplerName,
                                  long imageView, long sampler) {
        Integer binding = DEFAULT_SAMPLER_BINDINGS.get(samplerName);
        if (binding == null || binding < 0 || binding >= views.length) {
            return;
        }
        views[binding] = imageView;
        samplers[binding] = sampler;
    }

    @Override
    public boolean beginPhase(ShaderPhase phase, VkCommandBuffer cmd) {
        if (!loaded) return false;
        ProgramId programId = PHASE_TO_PROGRAM.get(phase);
        if (programId == null) return false;

        Long pipeline = pipelines.get(programId);
        if (pipeline == null || pipeline == 0) return false;

        currentPhase = phase;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

        return true;
    }

    @Override
    public void endPhase(ShaderPhase phase, VkCommandBuffer cmd) {
        if (currentPhase == phase) {
            currentPhase = null;
        }
    }

    @Override
    public long getPipelineForPhase(ShaderPhase phase) {
        ProgramId programId = PHASE_TO_PROGRAM.get(phase);
        if (programId == null) return 0;
        Long pipeline = pipelines.get(programId);
        return pipeline != null ? pipeline : 0;
    }

    @Override
    public long getPipelineLayoutForPhase(ShaderPhase phase) {
        ProgramId programId = PHASE_TO_PROGRAM.get(phase);
        if (programId == null) return 0;
        Long layout = pipelineLayouts.get(programId);
        return layout != null ? layout : 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Accessors
    // ═══════════════════════════════════════════════════════════════

    @Override
    public ShaderpackUniforms getUniforms() { return uniforms; }

    @Override
    public String getName() { return packName; }

    @Override
    public ShaderpackProperties getProperties() { return properties; }

    /**
     * Returns the cloud rendering setting from the shaderpack's {@code shaders.properties}.
     *
     * <p>Used by {@code MixinOptions_CloudsOverride} to disable vanilla cloud
     * rendering when the shaderpack renders its own volumetric clouds.</p>
     */
    public CloudSetting getCloudSetting() {
        return properties != null ? properties.getCloudSetting() : CloudSetting.DEFAULT;
    }

    /**
     * Returns the compiled program for a given ProgramId (for pipeline creation).
     */
    public CompiledProgram getCompiledProgram(ProgramId id) {
        return compiledPrograms.get(id);
    }

    /**
     * Returns all compiled programs.
     */
    public Map<ProgramId, CompiledProgram> getCompiledPrograms() {
        return Collections.unmodifiableMap(compiledPrograms);
    }

    /**
     * Returns the ShaderModuleManager (for accessing module handles during VkPipeline creation).
     */
    public ShaderModuleManager getShaderModuleManager() {
        return shaderModuleManager;
    }

    /**
     * Returns the ShaderCompiler (for cache statistics, etc.).
     */
    public ShaderCompiler getShaderCompiler() {
        return shaderCompiler;
    }

    public List<ShaderpackOption> getDiscoveredOptions() {
        return discoveredOptions;
    }

    public boolean isCompatibilityWorldRenderingSupported() {
        return compatibilityWorldRenderingSupported;
    }

    public String getCompatibilityIssueMessage() {
        return compatibilityIssueMessage;
    }

    /**
     * Invalidates fullscreen render targets so they are recreated at the new
     * swapchain size on the next frame. Called after window resize.
     */
    public void invalidateFullscreenTargets() {
        // The fsTargets will be resized via ensureSize() on the next
        // prepareFullscreenInputs() call which checks dimensions.
        // No explicit invalidation needed — size check is done each frame.
    }

    /**
     * Populates sampler bindings for G-buffer draws with shaderpack-managed textures
     * (shadow maps, noise, etc.) so that gbuffers programs can access them during
     * terrain/sky/entity rendering, not just during fullscreen composite passes.
     *
     * @param views           Sampler image view array to populate
     * @param samplers        Sampler handle array to populate
     * @param imageLayouts    Image layout array for depth textures (may be null)
     * @param placeholderView Placeholder image view for unbound slots
     * @param placeholderSampler Placeholder sampler for unbound slots
     */
    public void populateShaderpackTexturesForGbuffers(long[] views, long[] samplers,
                                                       int[] imageLayouts,
                                                       long placeholderView,
                                                       long placeholderSampler) {
        // Bind shadow textures so gbuffers shaders can access them
        if (shadowMap != null && shadowImagesInitialized) {
            long stView0 = shadowMap.getMainDepthView();
            long stSamp0 = shadowMap.getMainDepthSampler();
            if (stView0 != VK_NULL_HANDLE && stSamp0 != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "shadowtex0", stView0, stSamp0);
                bindSamplerAlias(views, samplers, "shadow", stView0, stSamp0);
                Integer st0Binding = DEFAULT_SAMPLER_BINDINGS.get("shadowtex0");
                if (imageLayouts != null && st0Binding != null && st0Binding >= 0 && st0Binding < imageLayouts.length) {
                    imageLayouts[st0Binding] = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
                }
            }
            long stView1 = shadowMap.getNoTranslucentsDepthView();
            long stSamp1 = shadowMap.getNoTranslucentsDepthSampler();
            if (stView1 != VK_NULL_HANDLE && stSamp1 != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "shadowtex1", stView1, stSamp1);
                Integer st1Binding = DEFAULT_SAMPLER_BINDINGS.get("shadowtex1");
                if (imageLayouts != null && st1Binding != null && st1Binding >= 0 && st1Binding < imageLayouts.length) {
                    imageLayouts[st1Binding] = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
                }
            }
        }

        // Bind noise texture
        if (noiseImageView != VK_NULL_HANDLE && noiseSampler != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "noisetex", noiseImageView, noiseSampler);
        }
    }
}
