package net.vulkanium.render.program;

import net.vulkanium.render.gbuffer.MRTGraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Manages compiled shader programs for all rendering passes.
 *
 * <p>Maps {@link ShaderKey} entries to compiled Vulkan pipeline + shader module pairs,
 * with fallback resolution when a pack doesn't supply a specific program.</p>
 *
 * <h3>Architecture</h3>
 * <p>Unlike Iris which creates GL programs at shader load time and stores them in a flat
 * array indexed by ShaderKey ordinal, Vulkanium creates Vulkan graphics pipelines that
 * combine shader modules + render pass + vertex format + blend state. Each unique
 * combination of these produces a distinct pipeline.</p>
 *
 * <h3>Framebuffer / RenderPass Sharing</h3>
 * <p>Programs that write to the same draw buffers re-use the same MRT render pass.
 * Two framebuffer variants exist per unique draw buffer set: before-translucent and
 * after-translucent, to handle ping-pong flip state correctly.</p>
 *
 * <h3>Pipeline Deduplication</h3>
 * <p>Programs that resolve to the same fallback + vertex format + blend state can share
 * the same Vulkan pipeline. The manager tracks these to avoid redundant pipeline compilations.</p>
 */
public class ShaderProgramManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ProgramMgr");

    // ── Compiled programs indexed by ShaderKey ──
    private final EnumMap<ShaderKey, CompiledProgram> programs = new EnumMap<>(ShaderKey.class);

    // ── Available program sources (set during pack load) ──
    private final EnumMap<ProgramId, ProgramSource> availableSources = new EnumMap<>(ProgramId.class);

    // ── Pipeline cache to avoid redundant compilations ──
    private final Map<PipelineCacheKey, Long> pipelineCache = new HashMap<>();

    // ── Stats ──
    private int compiledCount = 0;
    private int fallbackCount = 0;
    private int deduplicatedCount = 0;

    /**
     * Registers a shader source for a ProgramId, parsed from the shader pack.
     * Call this for each program found in the pack before compiling.
     */
    public void registerSource(ProgramId id, ProgramSource source) {
        availableSources.put(id, source);
    }

    /**
     * Compiles all shader programs, resolving fallbacks as needed.
     *
     * @param device      Logical device handle
     * @param renderPass  MRT render pass for gbuffer programs
     */
    public void compileAll(long device, long renderPass) {
        Predicate<ProgramId> available = availableSources::containsKey;

        for (ShaderKey key : ShaderKey.values()) {
            ProgramId resolved = key.getProgram().resolve(available);

            if (resolved == null) {
                // No program in the fallback chain — use built-in shader
                programs.put(key, createBuiltinProgram(key));
                continue;
            }

            boolean isFallback = resolved != key.getProgram();
            if (isFallback) fallbackCount++;

            ProgramSource source = availableSources.get(resolved);
            CompiledProgram compiled = compileProgram(device, renderPass, key, source);
            if (compiled != null) {
                programs.put(key, compiled);
                compiledCount++;
            }
        }

        LOGGER.info("Compiled {} programs ({} fallbacks, {} deduplicated)",
                compiledCount, fallbackCount, deduplicatedCount);
    }

    /**
     * Gets the compiled program for a shader key.
     */
    public CompiledProgram getProgram(ShaderKey key) {
        return programs.get(key);
    }

    /**
     * Gets the pipeline handle for a shader key.
     */
    public long getPipeline(ShaderKey key) {
        CompiledProgram program = programs.get(key);
        return program != null ? program.pipeline : 0;
    }

    /**
     * Gets the before-translucent program variant.
     */
    public CompiledProgram getBeforeTranslucent(ShaderKey key) {
        CompiledProgram program = programs.get(key);
        return program;
        // TODO: Return the before-translucent framebuffer variant
    }

    /**
     * Gets the after-translucent program variant.
     */
    public CompiledProgram getAfterTranslucent(ShaderKey key) {
        CompiledProgram program = programs.get(key);
        return program;
        // TODO: Return the after-translucent framebuffer variant
    }

    private CompiledProgram compileProgram(long device, long renderPass,
                                            ShaderKey key, ProgramSource source) {
        // TODO: Implementation:
        // 1. Get draw buffers from source directives
        // 2. Look up or create MRT render pass for those draw buffers
        // 3. Build pipeline key (shader hash + vertex format + blend state + render pass)
        // 4. Check pipeline cache for dedup
        // 5. If not cached:
        //    a. Preprocess GLSL (OptiFineGlslPreprocessor)
        //    b. Transform GLSL (VulkaniumGlslTransformer) with key's properties
        //    c. Compile to SPIR-V (ShaderCompiler)
        //    d. Create VkShaderModules
        //    e. Create VkPipeline with:
        //       - Vertex input from key.getVertexFormat()
        //       - Alpha test threshold as push constant
        //       - Fog mode as specialization constant
        //       - Blend state from source directives or key defaults
        //       - MRT write masks from draw buffers
        //    f. Store in pipelineCache
        // 6. Return CompiledProgram

        return null; // TODO
    }

    private CompiledProgram createBuiltinProgram(ShaderKey key) {
        // TODO: Create pipeline with built-in shaders (terrain.vert/frag or blit.vert/frag)
        // that provides basic rendering without pack shaders
        return new CompiledProgram(0, 0, 0, new int[]{0}, key);
    }

    // ── Lifecycle ──

    public void destroy(long device) {
        // Destroy all unique pipelines
        for (long pipeline : pipelineCache.values()) {
            if (pipeline != 0) {
                // TODO: vkDestroyPipeline(device, pipeline, null)
            }
        }

        programs.clear();
        availableSources.clear();
        pipelineCache.clear();
        compiledCount = fallbackCount = deduplicatedCount = 0;
        LOGGER.debug("Program manager destroyed");
    }

    // ── Inner types ──

    /**
     * Source data for a shader program parsed from a shader pack.
     */
    public static class ProgramSource {
        public String vertexSource;
        public String fragmentSource;
        public String geometrySource;  // nullable
        public int[] drawBuffers = {0};          // from DRAWBUFFERS/RENDERTARGETS
        public ProgramId.BlendOverride blend;   // nullable
        public float viewportScale = 1.0f;

        public int[] getDrawBuffers() { return drawBuffers; }
    }

    /**
     * A compiled Vulkan program ready for rendering.
     */
    public static class CompiledProgram {
        public final long pipeline;
        public final long vertexModule;
        public final long fragmentModule;
        public final int[] drawBuffers;
        public final ShaderKey key;

        /** The framebuffer to use before translucent rendering */
        public long framebufferBeforeTranslucent;
        /** The framebuffer to use after translucent rendering */
        public long framebufferAfterTranslucent;

        public CompiledProgram(long pipeline, long vertexModule, long fragmentModule,
                               int[] drawBuffers, ShaderKey key) {
            this.pipeline = pipeline;
            this.vertexModule = vertexModule;
            this.fragmentModule = fragmentModule;
            this.drawBuffers = drawBuffers;
            this.key = key;
        }
    }

    /**
     * Cache key for pipeline deduplication.
     */
    private record PipelineCacheKey(
            long vertexShaderHash, long fragmentShaderHash,
            ShaderKey.VertexFormatId vertexFormat,
            int blendStateHash, long renderPass) {}
}
