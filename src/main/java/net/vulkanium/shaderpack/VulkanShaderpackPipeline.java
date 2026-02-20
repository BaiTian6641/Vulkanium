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
import net.vulkanium.render.shader.ShaderCompiler;
import net.vulkanium.render.shader.ShaderModuleManager;
import net.vulkanium.render.shader.ShaderModuleManager.CompiledProgram;
import net.vulkanium.render.shader.VulkaniumGlslTransformer.PassType;
import net.vulkanium.render.texture.VulkanTexture;
import net.vulkanium.resource.RenderTarget;
import net.vulkanium.core.VulkaniumCommand;
import net.vulkanium.shaderpack.compute.ShaderpackComputeManager;
import net.vulkanium.shaderpack.compute.ShaderpackSSBOManager;
import net.vulkanium.shaderpack.compute.ShaderpackImageManager;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
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

            properties = new ShaderpackProperties();
            String propsContent = source.readProperties();
            if (propsContent != null) {
                properties.parse(propsContent, optionOverrides);
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
                for (Map.Entry<String, String> entry : properties.getImageDeclarations().entrySet()) {
                    ShaderpackImageManager.ImageInfo info =
                            ShaderpackImageManager.parseImageDeclaration(entry.getKey(), entry.getValue());
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

                    long module = shaderModuleManager.compileComputeProgram(
                            programName, source.computeSource(), DEFAULT_SAMPLER_BINDINGS);
                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

                    if (module != 0) {
                        // Register with compute manager to create a real VkPipeline
                        if (computeManager != null && computeManager.isInitialized()) {
                            ShaderpackComputeManager.ComputeProgramInfo info =
                                    computeManager.registerComputeProgram(programName, module);
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
            long geometryModule = 0;
            if (program.geometryModule != 0 && warnedGeometryCompatBypass.add(requestedProgram.getSourceName())) {
                LOGGER.info("[COMPAT] Ignoring geometry stage for {} in compatibility gbuffers path", requestedProgram.getSourceName());
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
        float[] modelView = new float[16];
        VRenderSystem.getModelViewMatrix().get(modelView);
        org.joml.Matrix4f vkProjection = new org.joml.Matrix4f(VRenderSystem.getProjectionMatrix());
        float[] projection = new float[16];
        vkProjection.get(projection);
        float[] modelViewInv = new float[16];
        new org.joml.Matrix4f(VRenderSystem.getModelViewMatrix()).invert().get(modelViewInv);
        float[] projectionInv = new float[16];
        new org.joml.Matrix4f(vkProjection).invert().get(projectionInv);
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
                        // Build sampler/image/SSBO arrays for compute descriptor update
                        int maxTex = ShaderpackComputeManager.MAX_SAMPLERS;
                        long[] compViews = new long[maxTex];
                        long[] compSamplers = new long[maxTex];
                        fillFullscreenSamplerBindings(compViews, compSamplers, placeholderView, placeholderSampler);

                        // Storage images (colorimgN from G-buffer targets + custom images)
                        long[] storageImageViews = new long[ShaderpackComputeManager.MAX_STORAGE_IMAGES];
                        Arrays.fill(storageImageViews, VK_NULL_HANDLE);
                        if (fsTargets != null) {
                            for (int i = 0; i < Math.min(storageImageViews.length,
                                    FullscreenRenderTargets.MAX_COLOR_TARGETS); i++) {
                                RenderTarget rt = fsTargets.getReadTarget(i);
                                if (rt != null && rt.getImageView() != VK_NULL_HANDLE) {
                                    storageImageViews[i] = rt.getImageView();
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

                        // Dispatch compute
                        computeManager.dispatch(cmd, programName, computeDescSet, computeUboOffset);

                        // Pipeline barrier: compute write → fragment read
                        computeManager.recordComputeToFragmentBarrier(cmd);
                        computeDispatched++;
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

            // ── Get/create MRT render pass and pipeline ──
            int attachCount = targets.length;
            long mrtRenderPass = fsTargets.getOrCreateMrtRenderPass(attachCount);

            BasicPipeline pipeline = getOrCreateMrtFullscreenPipeline(programId, program, mrtRenderPass, attachCount);
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

                // Y-flipped viewport to match OpenGL gl_FragCoord convention
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                        .x(0.0f)
                        .y((float) height)
                        .width((float) width)
                        .height((float) -height)
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
            fillFullscreenSamplerBindings(views, samplers, placeholderView, placeholderSampler);
            int setIdx = drawBatcher.updateDescriptorSet(frameIndex, views, samplers);

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
                fsTargets.blitColorTarget0ToSwapchain(cmd,
                        swapchainImages[imageIndex], VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
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
     * Gets or creates an MRT fullscreen pipeline for the given program and attachment count.
     */
    private BasicPipeline getOrCreateMrtFullscreenPipeline(ProgramId id, CompiledProgram program,
                                                           long mrtRenderPass, int attachmentCount) {
        String key = id.name() + "_mrt" + attachmentCount;
        BasicPipeline existing = mrtPipelines.get(key);
        if (existing != null) return existing;

        try {
            BasicPipeline pipeline = new BasicPipeline();
            pipeline.initializeWithModules(
                    Vulkanium.getVulkanDevice().getLogicalDevice(),
                    mrtRenderPass,
                    "shaderpack_mrt_" + id.getSourceName() + "_" + attachmentCount,
                    program.vertexModule,
                    program.fragmentModule,
                    VK_NULL_HANDLE,
                    DefaultVertexFormat.POSITION,
                    attachmentCount
            );
            mrtPipelines.put(key, pipeline);
            return pipeline;
        } catch (Exception e) {
            LOGGER.warn("[FULLSCREEN] Failed to create MRT pipeline for {} (attachments={}): {}",
                    id.getSourceName(), attachmentCount, e.getMessage());
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
                                               long placeholderView, long placeholderSampler) {
        Arrays.fill(views, placeholderView);
        Arrays.fill(samplers, placeholderSampler);

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

        // Bind depth targets
        for (int i = 0; i < FullscreenRenderTargets.MAX_DEPTH_TARGETS; i++) {
            RenderTarget depthTarget = fsTargets.getDepthTarget(i);
            if (depthTarget != null
                    && depthTarget.getImageView() != VK_NULL_HANDLE
                    && depthTarget.getSampler() != VK_NULL_HANDLE) {
                bindSamplerAlias(views, samplers, "depthtex" + i,
                        depthTarget.getImageView(), depthTarget.getSampler());
            }
        }
        // Legacy depth alias
        RenderTarget dt0 = fsTargets.getDepthTarget(0);
        if (dt0 != null && dt0.getImageView() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "gdepthtex", dt0.getImageView(), dt0.getSampler());
        }

        // Also pick up any MC-bound textures (block atlas, lightmap, etc.)
        int maxUnits = Math.min(views.length, BasicPipeline.getMaxTextureBindings());
        for (int unit = 0; unit < maxUnits; unit++) {
            int textureId = VRenderSystem.getBoundTextureId(unit);
            if (textureId <= 0) continue;

            VulkanTexture texture = GlStateInterceptor.getVulkanTexture(textureId);
            if (texture == null || !texture.isAllocated()) continue;
            if (texture.getImageView() == VK_NULL_HANDLE || texture.getSampler() == VK_NULL_HANDLE) continue;

            views[unit] = texture.getImageView();
            samplers[unit] = texture.getSampler();
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
}
