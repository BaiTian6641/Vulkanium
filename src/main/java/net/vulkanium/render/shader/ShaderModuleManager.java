package net.vulkanium.render.shader;

import net.vulkanium.render.shader.VulkaniumGlslTransformer.PassType;
import net.vulkanium.render.shader.VulkaniumGlslTransformer.TransformParams;
import net.vulkanium.render.pipeline.BasicPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages VkShaderModule creation and lifecycle for all shader pack programs.
 *
 * <p>This is the top-level orchestrator of the shader compilation pipeline:</p>
 * <pre>
 *   Pack GLSL source (from ShaderPack/ProgramSource)
 *     → {@link OptiFineGlslPreprocessor} (text-level cleanup + metadata extraction)
 *     → {@link VulkaniumGlslTransformer} (GLSL 450 transform + UBO/sampler injection)
 *     → {@link ShaderCompiler} (shaderc → SPIR-V binary + disk cache)
 *     → {@link ShaderModuleManager} (SPIR-V → VkShaderModule)
 * </pre>
 *
 * <h3>Module Lifecycle</h3>
 * <ul>
 *   <li><b>Creation:</b> On shader pack load, all programs are compiled and modules created</li>
 *   <li><b>Usage:</b> Modules are referenced by graphics/compute pipelines</li>
 *   <li><b>Destruction:</b> On shader pack switch or game shutdown, all modules are destroyed</li>
 * </ul>
 *
 * <h3>Program Key</h3>
 * <p>Each shader module is identified by a {@link ProgramKey} combining the program name
 * (e.g., "gbuffers_terrain") and the shader stage (VERTEX, FRAGMENT, etc.). This key
 * is used for lookup when creating graphics pipelines.</p>
 *
 * <h3>Error Recovery</h3>
 * <p>If a shader program fails to compile, the manager substitutes a fallback shader
 * (simple pass-through for vertex, solid color for fragment) so the game remains playable.
 * A diagnostic overlay shows which programs failed and why.</p>
 */
public class ShaderModuleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShaderModuleManager");

    /**
     * Identifies a specific shader module.
     */
    public record ProgramKey(String programName, ShaderCompiler.ShaderStage stage) {
        @Override
        public String toString() {
            return programName + "." + stage.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A compiled shader program with vertex + fragment (+ optional geometry) modules.
     */
    public static class CompiledProgram {
        public final String name;
        public final long vertexModule;
        public final long fragmentModule;
        public final long geometryModule; // 0 if no geometry shader
        public final int[] renderTargets;
        public final Map<String, Number> packDirectives;
        public final boolean isFallback;
        public final boolean compatibilitySafe;

        CompiledProgram(String name, long vertexModule, long fragmentModule,
                        long geometryModule, int[] renderTargets,
                        Map<String, Number> packDirectives, boolean isFallback,
                        boolean compatibilitySafe) {
            this.name = name;
            this.vertexModule = vertexModule;
            this.fragmentModule = fragmentModule;
            this.geometryModule = geometryModule;
            this.renderTargets = renderTargets;
            this.packDirectives = packDirectives;
            this.isFallback = isFallback;
            this.compatibilitySafe = compatibilitySafe;
        }
    }

    // ── State ──
    private VkDevice device;
    private ShaderCompiler compiler;

    /** All created VkShaderModules (for cleanup) */
    private final Map<ProgramKey, Long> modules = new ConcurrentHashMap<>();

    /** Compiled programs (program name → CompiledProgram) */
    private final Map<String, CompiledProgram> programs = new LinkedHashMap<>();

    /** Failed programs (for diagnostics) */
    private final Map<String, String> failedPrograms = new LinkedHashMap<>();

    /** Fallback modules */
    private long fallbackVertexModule = 0;
    private long fallbackFragmentModule = 0;

    /** Per-pack transformed GLSL cache */
    private Path translatedCacheDir;

    private static final String TRANSLATED_CACHE_VERSION = "v30-extended-vertex";

    @FunctionalInterface
    public interface CompileStageListener {
        void onStage(String programName, String stage, String detail);
    }

    private volatile CompileStageListener compileStageListener;

    public ShaderModuleManager(VkDevice device, ShaderCompiler compiler) {
        this.device = device;
        this.compiler = compiler;
    }

    public void setCompileStageListener(CompileStageListener listener) {
        this.compileStageListener = listener;
    }

    private void emitStage(String programName, String stage, String detail) {
        CompileStageListener listener = this.compileStageListener;
        if (listener == null) return;
        try {
            listener.onStage(programName, stage, detail);
        } catch (Exception ignored) {
        }
    }

    /**
     * Configures transformed-source cache namespace for the currently loading shaderpack.
     */
    public void setTranslatedCacheNamespace(String shaderpackName) {
        try {
            Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
            Path root = gameDir.resolve(".vulkanium")
                    .resolve("shader_cache")
                    .resolve("translated")
                    .resolve(sanitizeName(shaderpackName));
            Files.createDirectories(root);
            ensureTranslatedCacheVersion(root);
            this.translatedCacheDir = root;
        } catch (Exception e) {
            LOGGER.debug("Failed to initialize translated shader cache namespace: {}", e.getMessage());
            this.translatedCacheDir = null;
        }
    }

    /**
     * Compiles a shader pack program (vertex + fragment + optional geometry).
     *
     * @param programName    Program name (e.g., "gbuffers_terrain")
     * @param vertexSource   Raw vertex shader GLSL from pack
     * @param fragmentSource Raw fragment shader GLSL from pack
     * @param geometrySource Raw geometry shader GLSL (null if none)
     * @param passType       Rendering pass type for vertex decode selection
     * @param samplerBindings Sampler name → binding index map
     * @return Compiled program, or fallback program if compilation fails
     */
    public CompiledProgram compileProgram(String programName, String vertexSource,
                                          String fragmentSource, String geometrySource,
                                          PassType passType,
                                          Map<String, Integer> samplerBindings) {
        LOGGER.debug("Compiling program: {} (pass: {})", programName, passType);

        try {
            // ── Stage 1: Preprocess ──
            emitStage(programName, "preprocess", "Preprocessing shader sources");
            OptiFineGlslPreprocessor.PreprocessResult vertPP =
                    OptiFineGlslPreprocessor.preprocess(vertexSource, true);
            OptiFineGlslPreprocessor.PreprocessResult fragPP =
                    OptiFineGlslPreprocessor.preprocess(fragmentSource, false);

                // Keep the pack's full render targets — we need all iris_FragDataN
                // variables declared so the shader compiles.  Since Vulkanium currently
                // has only 1 color attachment, non-zero targets are declared as dummy
                // local vec4 variables (writes compile but are discarded at runtime).
                final int[] finalRenderTargets = fragPP.renderTargets;

            // ── Stage 2: Transform ──
                emitStage(programName, "transform", "Transforming GLSL to Vulkan-compatible form");
            TransformParams vertParams = new TransformParams(
                    passType, true, false, false, samplerBindings, finalRenderTargets, programName);
            TransformParams fragParams = new TransformParams(
                    passType, false, true, false, samplerBindings, finalRenderTargets, programName);

                String transformedVert = getOrCreateTranslatedSource(
                    programName, "vert", vertPP.source, vertParams,
                    () -> transformWithAST(vertPP.source, vertParams));
                String transformedFrag = getOrCreateTranslatedSource(
                    programName, "frag", fragPP.source, fragParams,
                    () -> {
                    String transformed = transformWithAST(fragPP.source, fragParams);
                    // Inject fragment output declarations for all render targets.
                    // Fullscreen/composite passes use compact locations (0,1,2...)
                    // because each pass creates its own render pass with exactly
                    // renderTargets.length color attachments — targets like [8,9]
                    // would exceed maxColorAttachments (8) with sparse locations.
                    // GBuffer (world) programs share one render pass and use sparse
                    // locations (the colortex index itself) so all programs' outputs
                    // map correctly to the union render pass.
                    boolean compactMode = (passType == PassType.COMPOSITE);
                    transformed = OptiFineGlslPreprocessor.injectFragmentOutputs(
                            transformed, finalRenderTargets, compactMode);
                    return transformed;
                    });

            // ── Stage 2.5: Geometry shader preprocessing + transform (before reconciliation) ──
            String transformedGeom = null;
            if (geometrySource != null) {
                emitStage(programName, "geometry-preprocess", "Preprocessing geometry shader");
                final OptiFineGlslPreprocessor.PreprocessResult geomPP =
                        OptiFineGlslPreprocessor.preprocess(geometrySource, false);
                final TransformParams geomParams = new TransformParams(
                    passType, false, false, false, samplerBindings, finalRenderTargets);
                emitStage(programName, "geometry-transform", "Transforming geometry shader");
                transformedGeom = getOrCreateTranslatedSource(
                    programName, "geom", geomPP.source, geomParams,
                    () -> transformWithAST(geomPP.source, geomParams));
            }

            // ── Stage 2.6: Reconcile varying locations across stages ──
            // Many shaderpacks use different naming conventions for vertex outputs
            // (e.g., g_color) and fragment inputs (e.g., v_color). The per-stage
            // location assignment uses alphabetical order, which produces mismatches
            // when names differ. This reconciliation step matches them by normalized
            // name and assigns consistent locations across both stages.
            if (transformedGeom != null) {
                // 3-stage reconciliation: vertex→geometry, geometry→fragment
                String[] reconciledVG = VulkaniumGlslTransformer.reconcileVaryingLocations(
                        transformedVert, transformedGeom);
                transformedVert = reconciledVG[0];
                transformedGeom = reconciledVG[1];
                String[] reconciledGF = VulkaniumGlslTransformer.reconcileVaryingLocations(
                        transformedGeom, transformedFrag);
                transformedGeom = reconciledGF[0];
                transformedFrag = reconciledGF[1];
                LOGGER.debug("[GEOM] 3-stage varying reconciliation for '{}'", programName);
            } else {
                String[] reconciled = VulkaniumGlslTransformer.reconcileVaryingLocations(
                        transformedVert, transformedFrag);
                transformedVert = reconciled[0];
                transformedFrag = reconciled[1];
            }

            // ── Stage 3: Compile to SPIR-V ──
                emitStage(programName, "compile-vert", "Compiling vertex shader to SPIR-V");
            ShaderCompiler.CompilationResult vertResult = compiler.compile(
                    transformedVert, ShaderCompiler.ShaderStage.VERTEX, programName + ".vert");
                emitStage(programName, "compile-frag", "Compiling fragment shader to SPIR-V");
            ShaderCompiler.CompilationResult fragResult = compiler.compile(
                    transformedFrag, ShaderCompiler.ShaderStage.FRAGMENT, programName + ".frag");

            if (!vertResult.isSuccess()) {
                throw new RuntimeException("Vertex shader failed: " + vertResult.errorMessage);
            }
            if (!fragResult.isSuccess()) {
                throw new RuntimeException("Fragment shader failed: " + fragResult.errorMessage);
            }

            // ── Stage 4: Create VkShaderModules ──
            emitStage(programName, "create-modules", "Creating Vulkan shader modules");
            long vertModule = createShaderModule(vertResult.spirvBinary);
            long fragModule = createShaderModule(fragResult.spirvBinary);

            modules.put(new ProgramKey(programName, ShaderCompiler.ShaderStage.VERTEX), vertModule);
            modules.put(new ProgramKey(programName, ShaderCompiler.ShaderStage.FRAGMENT), fragModule);

            // Geometry shader compilation (preprocessing + transform already done in Stage 2.5)
            long geomModule = 0;
            if (transformedGeom != null) {
                emitStage(programName, "geometry-compile", "Compiling geometry shader to SPIR-V");
                ShaderCompiler.CompilationResult geomResult = compiler.compile(
                        transformedGeom, ShaderCompiler.ShaderStage.GEOMETRY, programName + ".geom");
                if (geomResult.isSuccess()) {
                    emitStage(programName, "geometry-module", "Creating geometry shader module");
                    geomModule = createShaderModule(geomResult.spirvBinary);
                    modules.put(new ProgramKey(programName, ShaderCompiler.ShaderStage.GEOMETRY), geomModule);
                    org.lwjgl.system.MemoryUtil.memFree(geomResult.spirvBinary);
                } else {
                    LOGGER.warn("Geometry shader for '{}' failed, skipping: {}", programName, geomResult.errorMessage);
                }
            }

                boolean compatibilitySafe = isCompatibilitySafeProgram(
                    transformedVert,
                    transformedFrag,
                    geomModule != 0
                );

            // Free SPIR-V buffers
            org.lwjgl.system.MemoryUtil.memFree(vertResult.spirvBinary);
            org.lwjgl.system.MemoryUtil.memFree(fragResult.spirvBinary);

            CompiledProgram program = new CompiledProgram(
                    programName, vertModule, fragModule, geomModule,
                        finalRenderTargets, fragPP.packDirectives, false,
                    compatibilitySafe);
            programs.put(programName, program);
            emitStage(programName, "done", "Program compiled successfully");

            LOGGER.debug("Program '{}' compiled successfully (targets: {})",
                        programName, Arrays.toString(finalRenderTargets));
            return program;

        } catch (Exception e) {
            emitStage(programName, "failed", "Compilation failed");
            LOGGER.error("Failed to compile program '{}': {}", programName, e.getMessage(), e);
            failedPrograms.put(programName, e.getMessage());
            return getFallbackProgram(programName);
        }
    }

    /**
     * Compiles a compute shader program.
     *
     * @param programName     Program name (e.g., "deferred", "composite3")
     * @param source          Raw compute shader GLSL from pack
     * @param samplerBindings Sampler name → binding index map (same as graphics)
     */
    public long compileComputeProgram(String programName, String source,
                                      Map<String, Integer> samplerBindings) {
        try {
            emitStage(programName, "compute-preprocess", "Preprocessing compute shader");
            OptiFineGlslPreprocessor.PreprocessResult pp =
                    OptiFineGlslPreprocessor.preprocess(source, false);
            TransformParams params = new TransformParams(
                    PassType.COMPUTE, false, false, true, samplerBindings, null, programName,
                    net.vulkanium.shaderpack.compute.ShaderpackComputeManager.MAX_SAMPLERS);
                emitStage(programName, "compute-transform", "Transforming compute shader");
                String transformed = getOrCreateTranslatedSource(
                    programName, "comp", pp.source, params,
                    () -> transformWithAST(pp.source, params));

            emitStage(programName, "compute-compile", "Compiling compute shader to SPIR-V");
            ShaderCompiler.CompilationResult result = compiler.compileCompute(
                    transformed, programName + ".comp");
            if (!result.isSuccess()) {
                throw new RuntimeException("Compute shader failed: " + result.errorMessage);
            }

            emitStage(programName, "compute-module", "Creating compute shader module");
            long module = createShaderModule(result.spirvBinary);
            modules.put(new ProgramKey(programName, ShaderCompiler.ShaderStage.COMPUTE), module);
            org.lwjgl.system.MemoryUtil.memFree(result.spirvBinary);

            emitStage(programName, "done", "Program compiled successfully");
            LOGGER.debug("Compute program '{}' compiled successfully", programName);
            return module;

        } catch (Exception e) {
            emitStage(programName, "failed", "Compilation failed");
            LOGGER.error("Failed to compile compute program '{}': {}", programName, e.getMessage(), e);
            failedPrograms.put(programName, e.getMessage());
            return 0;
        }
    }

    /**
     * Gets a previously compiled program by name.
     */
    public CompiledProgram getProgram(String programName) {
        return programs.get(programName);
    }

    /**
     * Gets a shader module by key.
     */
    public long getModule(ProgramKey key) {
        return modules.getOrDefault(key, 0L);
    }

    /**
     * Returns the map of failed programs (name → error message).
     */
    public Map<String, String> getFailedPrograms() {
        return Collections.unmodifiableMap(failedPrograms);
    }

    /**
     * Returns total number of compiled programs.
     */
    public int getTotalPrograms() {
        return programs.size();
    }

    /**
     * Returns total number of failed programs.
     */
    public int getFailedProgramCount() {
        return failedPrograms.size();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fallback Shaders
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates fallback shader modules for error recovery.
     */
    public void createFallbacks() {
        String fallbackVert = """
                #version 450
                layout(set = 0, binding = 0, std140) uniform VulkaniumUniforms {
                    mat4 iris_ModelViewMatrix;
                    mat4 iris_ModelViewMatrixInverse;
                    mat4 iris_ProjectionMatrix;
                    mat4 iris_ProjectionMatrixInverse;
                };
                layout(location = 0) in vec3 vkm_Position;
                layout(location = 1) in vec4 vkm_Color;
                layout(location = 0) out vec4 fragColor;
                void main() {
                    gl_Position = iris_ProjectionMatrix * iris_ModelViewMatrix * vec4(vkm_Position, 1.0);
                    fragColor = vkm_Color;
                }
                """;

        String fallbackFrag = """
                #version 450
                layout(location = 0) in vec4 fragColor;
                layout(location = 0) out vec4 outColor;
                void main() {
                    outColor = fragColor;
                }
                """;

        ByteBuffer vertSpirv = compiler.compileOrThrow(fallbackVert,
                ShaderCompiler.ShaderStage.VERTEX, "fallback.vert");
        ByteBuffer fragSpirv = compiler.compileOrThrow(fallbackFrag,
                ShaderCompiler.ShaderStage.FRAGMENT, "fallback.frag");

        fallbackVertexModule = createShaderModule(vertSpirv);
        fallbackFragmentModule = createShaderModule(fragSpirv);

        org.lwjgl.system.MemoryUtil.memFree(vertSpirv);
        org.lwjgl.system.MemoryUtil.memFree(fragSpirv);

        LOGGER.info("Fallback shader modules created");
    }

    private CompiledProgram getFallbackProgram(String programName) {
        if (fallbackVertexModule == 0 || fallbackFragmentModule == 0) {
            createFallbacks();
        }
        return new CompiledProgram(
                programName, fallbackVertexModule, fallbackFragmentModule,
                0, new int[]{0}, Map.of(), true, true);
    }

    private static boolean isCompatibilitySafeProgram(String transformedVert,
                                                      String transformedFrag,
                                                      boolean hasGeometry) {
        if (!usesSupportedDescriptorLayout(transformedVert)) return false;
        if (!usesSupportedDescriptorLayout(transformedFrag)) return false;
        return countSamplerLikeUniforms(transformedVert) + countSamplerLikeUniforms(transformedFrag)
            <= BasicPipeline.getMaxTextureBindings();
    }

    private static boolean usesSupportedDescriptorLayout(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("layout\\s*\\(\\s*set\\s*=\\s*(\\d+)\\s*,\\s*binding\\s*=\\s*(\\d+)\\s*\\)")
                .matcher(source);
        while (m.find()) {
            int set = Integer.parseInt(m.group(1));
            int binding = Integer.parseInt(m.group(2));
            boolean allowed = set == 0 && binding >= 0 && binding <= BasicPipeline.getMaxTextureBindings();
            if (!allowed) return false;
        }
        return true;
    }

    private static int countSamplerLikeUniforms(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:[\\w]+\\s+)*(?:sampler\\w+|[ui]?image\\w+)\\s+\\w+\\s*;")
                .matcher(source);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private void ensureTranslatedCacheVersion(Path root) {
        Path marker = root.resolve(".cache-version");
        try {
            String existing = Files.isRegularFile(marker) ? Files.readString(marker).trim() : "";
            if (!TRANSLATED_CACHE_VERSION.equals(existing)) {
                try (var stream = Files.list(root)) {
                    stream.filter(p -> !p.getFileName().toString().equals(".cache-version"))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
                Files.writeString(marker, TRANSLATED_CACHE_VERSION + "\n");
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to validate translated cache version: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Vulkan Module Creation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Transforms GLSL source using the full pipeline:
     *   1. jcpp C-preprocessor resolution (resolve #ifdef/#else/#endif)
     *   2. AST-based transformation (UBO injection, uniform remap, etc.)
     *   3. Post-print regex fixups (varying locations, legacy qualifiers)
     */
    private static String transformWithAST(String source, TransformParams params) {
        // Step 1: Resolve all preprocessor conditionals with jcpp
        String resolved = GlslCPreprocessor.preprocess(source);

        // Step 2: AST-based transformation
        String result = VulkaniumASTTransformer.transform(resolved, params);

        // Step 3: Post-print regex fixups
        result = VulkaniumASTTransformer.postPrintFixups(result, params);
        return result;
    }

    /**
     * Creates a VkShaderModule from SPIR-V binary data.
     */
    private long createShaderModule(ByteBuffer spirvBinary) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirvBinary);

            LongBuffer pModule = stack.mallocLong(1);
            int result = VK12.vkCreateShaderModule(device, createInfo, null, pModule);
            if (result != VK12.VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule failed: " + result);
            }

            return pModule.get(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Destroys all shader modules. Call on pack switch or shutdown.
     */
    public void destroyAll() {
        for (Map.Entry<ProgramKey, Long> entry : modules.entrySet()) {
            if (entry.getValue() != 0) {
                VK12.vkDestroyShaderModule(device, entry.getValue(), null);
            }
        }
        modules.clear();
        programs.clear();
        failedPrograms.clear();

        if (fallbackVertexModule != 0) {
            VK12.vkDestroyShaderModule(device, fallbackVertexModule, null);
            fallbackVertexModule = 0;
        }
        if (fallbackFragmentModule != 0) {
            VK12.vkDestroyShaderModule(device, fallbackFragmentModule, null);
            fallbackFragmentModule = 0;
        }

        LOGGER.info("All shader modules destroyed");
    }

    /**
     * Destroys a single program's modules.
     */
    public void destroyProgram(String programName) {
        for (ShaderCompiler.ShaderStage stage : ShaderCompiler.ShaderStage.values()) {
            ProgramKey key = new ProgramKey(programName, stage);
            Long module = modules.remove(key);
            if (module != null && module != 0
                    && module != fallbackVertexModule
                    && module != fallbackFragmentModule) {
                VK12.vkDestroyShaderModule(device, module, null);
            }
        }
        programs.remove(programName);
        failedPrograms.remove(programName);
    }

    private String getOrCreateTranslatedSource(String programName,
                                               String stage,
                                               String preprocessedSource,
                                               TransformParams params,
                                               Supplier<String> generator) {
        if (translatedCacheDir == null) {
            return generator.get();
        }

        String signature = computeHash(TRANSLATED_CACHE_VERSION
                + "|" + stage
                + "|" + preprocessedSource
                + "|" + paramsSignature(params));
        Path sourceFile = translatedCacheDir.resolve(programName + "." + stage + ".translated.glsl");
        Path metaFile = translatedCacheDir.resolve(programName + "." + stage + ".translated.meta");

        try {
            if (Files.isRegularFile(sourceFile) && Files.isRegularFile(metaFile)) {
                String existingSig = Files.readString(metaFile).trim();
                if (signature.equals(existingSig)) {
                    return Files.readString(sourceFile);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to read translated cache for {}.{}: {}", programName, stage, e.getMessage());
        }

        String translated = generator.get();

        try {
            Files.createDirectories(translatedCacheDir);
            Files.writeString(sourceFile, translated);
            Files.writeString(metaFile, signature + "\n");
        } catch (IOException e) {
            LOGGER.debug("Failed to write translated cache for {}.{}: {}", programName, stage, e.getMessage());
        }

        return translated;
    }

    private static String paramsSignature(TransformParams params) {
        StringBuilder sb = new StringBuilder();
        sb.append(params.passType).append('|')
                .append(params.isVertex).append('|')
                .append(params.isFragment).append('|')
                .append(params.isCompute).append('|')
                .append(Arrays.toString(params.renderTargets)).append('|');

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(params.samplerBindings.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Integer> entry : entries) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // collapseFragmentOutputsToZero removed — replaced by
    // injectFragmentOutputsSingleAttachment which declares non-zero targets
    // as dummy local vec4 variables instead of outputs.

    private static String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
