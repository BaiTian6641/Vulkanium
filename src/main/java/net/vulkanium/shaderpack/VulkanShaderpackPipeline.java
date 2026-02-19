package net.vulkanium.shaderpack;

import net.vulkanium.Vulkanium;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.vulkanium.render.pipeline.BasicPipeline;
import net.vulkanium.render.pipeline.DrawBatcher;
import net.vulkanium.render.shader.ShaderCompiler;
import net.vulkanium.render.shader.ShaderModuleManager;
import net.vulkanium.render.shader.ShaderModuleManager.CompiledProgram;
import net.vulkanium.render.shader.VulkaniumGlslTransformer.PassType;
import net.vulkanium.render.texture.VulkanTexture;
import net.vulkanium.resource.RenderTarget;
import net.vulkanium.core.VulkaniumCommand;
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
    private RenderTarget fullscreenSceneCapture;
    private RenderTarget fullscreenDepthCapture;

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

        // Default to OFF for stability.
        // Complex deferred/composite/final chains can black-screen without full
        // MRT/compute parity in the compatibility bridge.
        return FullscreenCompatMode.OFF;
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

            properties = new ShaderpackProperties();
            String propsContent = source.readProperties();
            if (propsContent != null) {
                properties.parse(propsContent);
                LOGGER.info("[LOAD]   shaders.properties: {} entries parsed", properties.getAll().size());
                LOGGER.info("[LOAD]   Shadow resolution: {}x{}", properties.getShadowResolution(), properties.getShadowResolution());
            } else {
                LOGGER.info("[LOAD]   No shaders.properties found (using defaults)");
            }

            // ── Phase 2: Discover and resolve programs ──
            LOGGER.info("[LOAD] ──── Phase 2: Discovering shader programs ────");
            reportProgress(new LoadProgress(
                    LoadProgress.Phase.DISCOVERING_PROGRAMS, null,
                    "Discovering shader programs...", 0, 0, null));

                VulkaniumGameOptions opts = VulkaniumGameOptions.loadFromDisk();
                optionOverrides = opts.shader.shaderpackOptionOverrides != null
                    ? new HashMap<>(opts.shader.shaderpackOptionOverrides)
                    : Collections.emptyMap();
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

                    long module = shaderModuleManager.compileComputeProgram(programName, source.computeSource());
                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

                    if (module != 0) {
                        pipelines.put(id, 0L);
                        compiled++;
                        LOGGER.info("[COMPILE] ✓ {} — compute OK (module=0x{}) [{}ms]",
                                programName, Long.toHexString(module), elapsedMs);
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
            // Destroy VkPipelines
            for (long pipeline : pipelines.values()) {
                if (pipeline != VK_NULL_HANDLE && pipeline != 0) {
                    vkDestroyPipeline(device, pipeline, null);
                }
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

        if (fullscreenSceneCapture != null) {
            fullscreenSceneCapture.destroy();
            fullscreenSceneCapture = null;
        }

        if (fullscreenDepthCapture != null) {
            fullscreenDepthCapture.destroy();
            fullscreenDepthCapture = null;
        }

        LOGGER.info("Shaderpack pipeline unloaded: {}", packName);
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

            BasicPipeline pipeline = new BasicPipeline();
            pipeline.initializeWithModules(
                    Vulkanium.getVulkanDevice().getLogicalDevice(),
                    Vulkanium.getMainRenderPass().getRenderPass(),
                    "shaderpack_" + requestedProgram.getSourceName(),
                    program.vertexModule,
                    program.fragmentModule,
                    geometryModule,
                    format
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
            LOGGER.info("[COMPAT] Pack defines {} fullscreen passes: {} — gbuffers render path active; fullscreen passes use experimental compatibility execution",
                fullscreenPrograms.size(), fullscreenPrograms);
            compatibilityIssueMessage = "Composite/deferred/final passes (" + fullscreenPrograms.size()
                + " programs) run via experimental compatibility execution. Visual differences are expected.";
            return false; // experimental path still considered limited support
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

    @Override
    public void onFrameBegin(VkCommandBuffer cmd, int frameIndex) {
        if (!loaded) return;
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
     */
    public void prepareFullscreenInputs(VkCommandBuffer cmd, int frameIndex) {
        if (!loaded) return;
        captureSceneColorForFullscreen(cmd, frameIndex);
        captureSceneDepthForFullscreen(cmd);
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

        DrawBatcher drawBatcher = Vulkanium.getDrawBatcher();
        if (drawBatcher == null) return;

        int width = Vulkanium.getVulkanSwapchain() != null ? Vulkanium.getVulkanSwapchain().getWidth() : 0;
        int height = Vulkanium.getVulkanSwapchain() != null ? Vulkanium.getVulkanSwapchain().getHeight() : 0;
        if (width <= 0 || height <= 0) return;

        long placeholderView = Vulkanium.getPlaceholderImageView();
        long placeholderSampler = Vulkanium.getPlaceholderSampler();
        if (placeholderView == VK_NULL_HANDLE || placeholderSampler == VK_NULL_HANDLE) return;

        int executed = 0;
        int skipped = 0;

        for (ProgramId programId : FULLSCREEN_PASS_ORDER) {
            CompiledProgram program = compiledPrograms.get(programId);
            if (program == null || program.vertexModule == 0 || program.fragmentModule == 0) continue;

            if (!shouldExecuteFullscreenProgram(program)) {
                skipped++;
                continue;
            }

            BasicPipeline pipeline = getOrCreateFullscreenPipeline(programId, program);
            if (pipeline == null) continue;

            long vkPipeline = pipeline.getOrCreatePipeline(
                    false,
                    false,
                    false,
                    false,
                    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                    VK_BLEND_FACTOR_ONE,
                    VK_BLEND_FACTOR_ZERO,
                    VK_BLEND_FACTOR_ONE,
                    VK_BLEND_FACTOR_ZERO,
                    VK_COMPARE_OP_ALWAYS
            );
            if (vkPipeline == VK_NULL_HANDLE) continue;

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

            int uboOffset = drawBatcher.uploadUniformsShaderpack(
                    frameIndex,
                    modelView,
                    modelViewInv,
                    projection,
                    projectionInv,
                    colorMod,
                    fogParams,
                    texMat,
                    chunkOffset
            );

            int maxTex = BasicPipeline.getMaxTextureBindings();
            long[] views = new long[maxTex];
            long[] samplers = new long[maxTex];
            fillFullscreenSamplerBindings(views, samplers, placeholderView, placeholderSampler);
            int setIdx = drawBatcher.updateDescriptorSet(frameIndex, views, samplers);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vkPipeline);
            drawBatcher.bindDescriptorSet(cmd, pipeline.getPipelineLayout(), setIdx, uboOffset);

            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                        .x(0.0f)
                        .y(0.0f)
                        .width(width)
                        .height(height)
                        .minDepth(0.0f)
                        .maxDepth(1.0f);
                vkCmdSetViewport(cmd, 0, viewport);

                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(0, 0);
                scissor.extent().set(width, height);
                vkCmdSetScissor(cmd, 0, scissor);
            }

                ByteBuffer fullscreenTri = FULLSCREEN_TRIANGLE_TEMPLATE.duplicate();
                fullscreenTri.position(0);
            drawBatcher.draw(cmd, frameIndex, vkPipeline, pipeline.getPipelineLayout(),
                    fullscreenTri, 3, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
                    DefaultVertexFormat.POSITION.getVertexSize());
            executed++;
        }

        if (executed > 0 && !loggedFullscreenExecution) {
            LOGGER.info("[FULLSCREEN] Executed {} deferred/composite/final passes (mode={}, skipped={})",
                    executed, FULLSCREEN_COMPAT_MODE.name().toLowerCase(Locale.ROOT), skipped);
            loggedFullscreenExecution = true;
        } else if (executed == 0 && !loggedFullscreenSkipped) {
            LOGGER.info("[FULLSCREEN] No compatible fullscreen pass executed in mode={} (skipped={})",
                    FULLSCREEN_COMPAT_MODE.name().toLowerCase(Locale.ROOT), skipped);
            loggedFullscreenSkipped = true;
        }
    }

    private boolean shouldExecuteFullscreenProgram(CompiledProgram program) {
        if (FULLSCREEN_COMPAT_MODE == FullscreenCompatMode.FULL) {
            return true;
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
            return false;
        }
        return writesColor0;
    }

    private boolean isSafeModeExecutionAllowed() {
        if (safeFullscreenExecutionAllowed != null) {
            return safeFullscreenExecutionAllowed;
        }

        boolean hasSafeGraphicsPass = false;
        for (ProgramId programId : FULLSCREEN_PASS_ORDER) {
            ProgramSource source = programSet != null ? programSet.getAllPrograms().get(programId) : null;
            if (source != null && source.isValidCompute()) {
                safeFullscreenExecutionAllowed = false;
                return false;
            }

            CompiledProgram program = compiledPrograms.get(programId);
            if (program == null || program.vertexModule == 0 || program.fragmentModule == 0) {
                continue;
            }
            hasSafeGraphicsPass = true;
            if (!writesOnlyColor0(program.renderTargets)) {
                safeFullscreenExecutionAllowed = false;
                return false;
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

        if (fullscreenSceneCapture != null
            && fullscreenSceneCapture.getImageView() != VK_NULL_HANDLE
            && fullscreenSceneCapture.getSampler() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "colortex0",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "gcolor",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "composite",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());

            // Compatibility fallback: many packs sample multiple colortex/gaux inputs
            // during deferred/composite/final passes. Until full MRT wiring is complete,
            // provide scene capture for these aliases instead of placeholder textures.
            for (int i = 1; i <= 15; i++) {
            bindSamplerAlias(views, samplers, "colortex" + i,
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            }
            bindSamplerAlias(views, samplers, "gdepth",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "gnormal",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "gaux1",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "gaux2",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "gaux3",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
            bindSamplerAlias(views, samplers, "gaux4",
                fullscreenSceneCapture.getImageView(), fullscreenSceneCapture.getSampler());
        }

        if (fullscreenDepthCapture != null
            && fullscreenDepthCapture.getImageView() != VK_NULL_HANDLE
            && fullscreenDepthCapture.getSampler() != VK_NULL_HANDLE) {
            bindSamplerAlias(views, samplers, "depthtex0",
                fullscreenDepthCapture.getImageView(), fullscreenDepthCapture.getSampler());
            bindSamplerAlias(views, samplers, "gdepthtex",
                fullscreenDepthCapture.getImageView(), fullscreenDepthCapture.getSampler());
            bindSamplerAlias(views, samplers, "depthtex1",
                fullscreenDepthCapture.getImageView(), fullscreenDepthCapture.getSampler());
            bindSamplerAlias(views, samplers, "depthtex2",
                fullscreenDepthCapture.getImageView(), fullscreenDepthCapture.getSampler());
        }

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

    private void ensureFullscreenSceneCapture(int width, int height) {
        if (width <= 0 || height <= 0 || Vulkanium.getVulkanSwapchain() == null) {
            return;
        }

        int swapchainFormat = Vulkanium.getVulkanSwapchain().getImageFormat();
        if (fullscreenSceneCapture != null
                && fullscreenSceneCapture.getWidth() == width
                && fullscreenSceneCapture.getHeight() == height
                && fullscreenSceneCapture.getFormat() == swapchainFormat) {
            return;
        }

        if (fullscreenSceneCapture != null) {
            fullscreenSceneCapture.destroy();
            fullscreenSceneCapture = null;
        }

        if (Vulkanium.getVulkanDevice() == null || Vulkanium.getVulkanMemory() == null) {
            return;
        }

        fullscreenSceneCapture = new RenderTarget();
        fullscreenSceneCapture.initialize(
                Vulkanium.getVulkanDevice().getLogicalDevice(),
                Vulkanium.getVulkanMemory(),
                "shaderpack_scene_capture",
                width,
                height,
                swapchainFormat,
                false
        );
    }

    private void ensureFullscreenDepthCapture(int width, int height) {
        if (width <= 0 || height <= 0 || Vulkanium.getVulkanSwapchain() == null) {
            return;
        }

        int swapchainDepthFormat = Vulkanium.getVulkanSwapchain().getDepthFormat();
        if (fullscreenDepthCapture != null
                && fullscreenDepthCapture.getWidth() == width
                && fullscreenDepthCapture.getHeight() == height
                && fullscreenDepthCapture.getFormat() == swapchainDepthFormat) {
            return;
        }

        if (fullscreenDepthCapture != null) {
            fullscreenDepthCapture.destroy();
            fullscreenDepthCapture = null;
        }

        if (Vulkanium.getVulkanDevice() == null || Vulkanium.getVulkanMemory() == null) {
            return;
        }

        fullscreenDepthCapture = new RenderTarget();
        fullscreenDepthCapture.initialize(
                Vulkanium.getVulkanDevice().getLogicalDevice(),
                Vulkanium.getVulkanMemory(),
                "shaderpack_depth_capture",
                width,
                height,
                swapchainDepthFormat,
                true
        );
    }

    private void captureSceneColorForFullscreen(VkCommandBuffer cmd, int frameIndex) {
        if (Vulkanium.getVulkanSwapchain() == null || Vulkanium.getFrameOrchestrator() == null) {
            return;
        }

        int width = Vulkanium.getVulkanSwapchain().getWidth();
        int height = Vulkanium.getVulkanSwapchain().getHeight();
        ensureFullscreenSceneCapture(width, height);
        if (fullscreenSceneCapture == null || fullscreenSceneCapture.getImage() == VK_NULL_HANDLE) {
            return;
        }

        long[] swapchainImages = Vulkanium.getVulkanSwapchain().getImages();
        int imageIndex = Vulkanium.getFrameOrchestrator().getCurrentImageIndex();
        if (swapchainImages == null || imageIndex < 0 || imageIndex >= swapchainImages.length) {
            return;
        }

        long srcImage = swapchainImages[imageIndex];
        long dstImage = fullscreenSceneCapture.getImage();

        VulkaniumCommand.transitionImageLayout(
                cmd,
                srcImage,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_ACCESS_TRANSFER_READ_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT
        );
        VulkaniumCommand.transitionImageLayout(
                cmd,
                dstImage,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT
        );

        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
            copyRegion.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copyRegion.srcOffset().set(0, 0, 0);

            copyRegion.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copyRegion.dstOffset().set(0, 0, 0);

            copyRegion.extent().set(width, height, 1);

            vkCmdCopyImage(
                    cmd,
                    srcImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copyRegion
            );
        }

        VulkaniumCommand.transitionImageLayout(
                cmd,
                dstImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT
        );
        VulkaniumCommand.transitionImageLayout(
                cmd,
                srcImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_ACCESS_TRANSFER_READ_BIT,
            0,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT
        );
    }

            private void captureSceneDepthForFullscreen(VkCommandBuffer cmd) {
            if (Vulkanium.getVulkanSwapchain() == null) {
                return;
            }

            int width = Vulkanium.getVulkanSwapchain().getWidth();
            int height = Vulkanium.getVulkanSwapchain().getHeight();
            ensureFullscreenDepthCapture(width, height);
            if (fullscreenDepthCapture == null || fullscreenDepthCapture.getImage() == VK_NULL_HANDLE) {
                return;
            }

            long srcDepthImage = Vulkanium.getVulkanSwapchain().getDepthImage();
            long dstDepthImage = fullscreenDepthCapture.getImage();
            if (srcDepthImage == VK_NULL_HANDLE || dstDepthImage == VK_NULL_HANDLE) {
                return;
            }

            VulkaniumCommand.transitionImageLayout(
                cmd,
                srcDepthImage,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT
            );
            VulkaniumCommand.transitionImageLayout(
                cmd,
                dstDepthImage,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT
            );

            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
                copyRegion.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
                copyRegion.srcOffset().set(0, 0, 0);

                copyRegion.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
                copyRegion.dstOffset().set(0, 0, 0);

                copyRegion.extent().set(width, height, 1);

                vkCmdCopyImage(
                    cmd,
                    srcDepthImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstDepthImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copyRegion
                );
            }

            VulkaniumCommand.transitionImageLayout(
                cmd,
                dstDepthImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT
            );
            VulkaniumCommand.transitionImageLayout(
                cmd,
                srcDepthImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT
            );
            }

    private BasicPipeline getOrCreateFullscreenPipeline(ProgramId id, CompiledProgram program) {
        BasicPipeline existing = fullscreenPipelines.get(id);
        if (existing != null) return existing;

        try {
            BasicPipeline pipeline = new BasicPipeline();
            pipeline.initializeWithModules(
                    Vulkanium.getVulkanDevice().getLogicalDevice(),
                    Vulkanium.getMainRenderPass().getRenderPass(),
                    "shaderpack_fullscreen_" + id.getSourceName(),
                    program.vertexModule,
                    program.fragmentModule,
                    VK_NULL_HANDLE,
                    DefaultVertexFormat.POSITION
            );
            fullscreenPipelines.put(id, pipeline);
            return pipeline;
        } catch (Exception e) {
            LOGGER.warn("[FULLSCREEN] Failed to create pipeline for {}: {}", id.getSourceName(), e.getMessage());
            return null;
        }
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
