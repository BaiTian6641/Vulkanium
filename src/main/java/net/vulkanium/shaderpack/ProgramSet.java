package net.vulkanium.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * A resolved set of shader programs for one dimension/context.
 *
 * <p>Reads all shader program sources from a {@link ShaderpackSource},
 * resolves fallback chains via {@link ProgramId}, and preprocesses GLSL
 * via {@link GlslPreprocessor}. The result is a map of ProgramId → ProgramSource
 * ready for SPIR-V compilation.</p>
 *
 * <h3>Load Flow</h3>
 * <ol>
 *   <li>Enumerate all {@link ProgramId} values</li>
 *   <li>For each, try to read {@code <name>.vsh} / {@code <name>.fsh} etc.</li>
 *   <li>If not found, walk the fallback chain until a source is found</li>
 *   <li>Preprocess all found sources (resolve #include, inject defines)</li>
 *   <li>Store in programSources map</li>
 * </ol>
 */
public class ProgramSet {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ProgramSet");

    @FunctionalInterface
    public interface DiscoveryProgressListener {
        void onProgress(int scanned, int total, String programName, String status);
    }

    /** Shader file extensions for each stage */
    private static final String[] VERTEX_EXTS = { ".vsh", ".vert" };
    private static final String[] FRAGMENT_EXTS = { ".fsh", ".frag" };
    private static final String[] GEOMETRY_EXTS = { ".gsh", ".geom" };
    private static final String[] COMPUTE_EXTS = { ".csh", ".comp" };

    /**
     * Dimension-specific shader directories, checked in order before root.
     * Shaderpacks like ComplementaryUnbound put stubs in world0/ (Overworld),
     * world-1/ (Nether), world1/ (End) — matching Iris/OptiFine convention.
     * An empty string means root shaders/ directory (fallback).
     */
    private static final String[] DIMENSION_DIRS = { "world0/", "world-1/", "world1/", "" };

    /** The dimension directory prefix used for the current load (e.g. "world0/" or "") */
    private String activeDimensionDir = "";

    /** Resolved programs keyed by their ORIGINAL ProgramId (not the fallback) */
    private final Map<ProgramId, ProgramSource> programSources = new EnumMap<>(ProgramId.class);

    /** Programs that were found directly (not via fallback) */
    private final Set<ProgramId> directlyProvided = EnumSet.noneOf(ProgramId.class);

    /** The preprocessor used for this set */
    private final GlslPreprocessor preprocessor;

    /** Shaderpack properties */
    private final ShaderpackProperties properties;

    /** User-selected shaderpack option overrides (macro name -> value). */
    private final Map<String, String> optionOverrides;

    private final DiscoveryProgressListener discoveryProgressListener;

    public ProgramSet(ShaderpackSource source, ShaderpackProperties properties) {
        this(source, properties, Collections.emptyMap());
    }

    public ProgramSet(ShaderpackSource source, ShaderpackProperties properties, Map<String, String> optionOverrides) {
        this(source, properties, optionOverrides, null);
    }

    public ProgramSet(ShaderpackSource source,
                      ShaderpackProperties properties,
                      Map<String, String> optionOverrides,
                      DiscoveryProgressListener discoveryProgressListener) {
        this.preprocessor = new GlslPreprocessor(source);
        this.properties = properties;
        this.optionOverrides = optionOverrides != null ? new HashMap<>(optionOverrides) : Collections.emptyMap();
        this.discoveryProgressListener = discoveryProgressListener;
        detectDimensionDirectory(source);
        loadPrograms(source);
    }

    private void emitDiscoveryProgress(int scanned, int total, String programName, String status) {
        if (discoveryProgressListener == null) return;
        try {
            discoveryProgressListener.onProgress(scanned, total, programName, status);
        } catch (Exception ignored) {
        }
    }

    /**
     * Detects which dimension directory the shaderpack uses by probing for
     * a well-known shader file (gbuffers_terrain.vsh) in each directory.
     * Defaults to Overworld (world0/) if found there, otherwise root.
     */
    private void detectDimensionDirectory(ShaderpackSource source) {
        // Probe for a common shader file in each dimension directory
        String[] probeNames = { "gbuffers_terrain", "composite", "final" };
        for (String dimDir : DIMENSION_DIRS) {
            for (String probe : probeNames) {
                for (String ext : VERTEX_EXTS) {
                    try {
                        String content = source.readShaderFile(dimDir + probe + ext);
                        if (content != null) {
                            activeDimensionDir = dimDir;
                            if (!dimDir.isEmpty()) {
                                LOGGER.info("[DISCOVER] Detected dimension directory: {} (found {}{})", dimDir, probe, ext);
                            } else {
                                LOGGER.info("[DISCOVER] Shaders at root level (found {}{})", probe, ext);
                            }
                            return;
                        }
                    } catch (IOException ignored) {}
                }
            }
        }
        LOGGER.warn("[DISCOVER] Could not detect dimension directory, using root");
    }

    /**
     * Loads all shader programs from the source.
     */
    private void loadPrograms(ShaderpackSource source) {
        int loaded = 0;
        int fallbackResolved = 0;
        int notFound = 0;
        int scanned = 0;
        int total = ProgramId.values().length;

        LOGGER.info("[DISCOVER] Scanning shader programs...");

        for (ProgramId programId : ProgramId.values()) {
            scanned++;
            // Walk the fallback chain to find the first available source
            ProgramSource resolved = null;
            ProgramId resolvedFrom = null;

            for (ProgramId candidate : programId.getFallbackChain()) {
                ProgramSource candidateSource = tryLoadProgram(source, candidate);
                if (candidateSource != null && candidateSource.hasAnyStage()) {
                    resolved = new ProgramSource(
                            programId,  // Use original programId, not the fallback's
                            candidateSource.vertexSource(),
                            candidateSource.fragmentSource(),
                            candidateSource.geometrySource(),
                            candidateSource.computeSource(),
                            candidateSource.tessControlSource(),
                            candidateSource.tessEvalSource());
                    resolvedFrom = candidate;
                    break;
                }
            }

            if (resolved != null) {
                programSources.put(programId, resolved);
                if (resolvedFrom == programId) {
                    directlyProvided.add(programId);
                    loaded++;
                    // Log stages found
                    StringBuilder stages = new StringBuilder();
                    if (resolved.hasVertex()) stages.append("vert ");
                    if (resolved.hasFragment()) stages.append("frag ");
                    if (resolved.hasGeometry()) stages.append("geom ");
                    if (resolved.hasCompute()) stages.append("comp ");
                    if (resolved.hasTessellation()) stages.append("tess ");
                    LOGGER.info("[DISCOVER]   ✓ {} — direct [{}]", programId.getSourceName(), stages.toString().trim());
                    emitDiscoveryProgress(scanned, total, programId.getSourceName(), "direct");
                } else {
                    fallbackResolved++;
                    LOGGER.debug("[DISCOVER]   ↳ {} → fallback from {}", programId.getSourceName(), resolvedFrom.getSourceName());
                    emitDiscoveryProgress(scanned, total, programId.getSourceName(), "fallback");
                }
            } else {
                notFound++;
                LOGGER.debug("[DISCOVER]   · {} — not found (no fallback available)", programId.getSourceName());
                emitDiscoveryProgress(scanned, total, programId.getSourceName(), "not found");
            }
        }

        LOGGER.info("[DISCOVER] Results: {} direct, {} fallback, {} not found, {} total",
                loaded, fallbackResolved, notFound, programSources.size());
    }

    /**
     * Tries to load a single program's shader sources.
     * Returns null if no shader files exist for this program.
     */
    private ProgramSource tryLoadProgram(ShaderpackSource source, ProgramId id) {
        String name = id.getSourceName();

        String vertex = tryReadShader(source, name, VERTEX_EXTS);
        String fragment = tryReadShader(source, name, FRAGMENT_EXTS);
        String geometry = tryReadShader(source, name, GEOMETRY_EXTS);
        String compute = tryReadShader(source, name, COMPUTE_EXTS);

        // Nothing found at all
        if (vertex == null && fragment == null && geometry == null && compute == null) {
            return null;
        }

        // Preprocess all found sources
        try {
            if (vertex != null) vertex = preprocessor.preprocess(vertex, name + ".vsh", optionOverrides);
            if (fragment != null) fragment = preprocessor.preprocess(fragment, name + ".fsh", optionOverrides);
            if (geometry != null) geometry = preprocessor.preprocess(geometry, name + ".gsh", optionOverrides);
            if (compute != null) compute = preprocessor.preprocess(compute, name + ".csh", optionOverrides);
        } catch (Exception e) {
            LOGGER.error("[DISCOVER]   ✗ {} — preprocessing FAILED: {} — {}",
                    name, e.getClass().getSimpleName(), e.getMessage());
            LOGGER.debug("[DISCOVER]     Stack trace:", e);
            return null;
        }

        return new ProgramSource(id, vertex, fragment, geometry, compute, null, null);
    }

    /**
     * Tries to read a shader file with multiple extension candidates.
     * Checks the active dimension directory first (e.g. world0/), then root as fallback.
     */
    private String tryReadShader(ShaderpackSource source, String baseName, String[] extensions) {
        // First: try the active dimension directory (e.g. "world0/gbuffers_terrain.vsh")
        if (!activeDimensionDir.isEmpty()) {
            for (String ext : extensions) {
                try {
                    String content = source.readShaderFile(activeDimensionDir + baseName + ext);
                    if (content != null) {
                        LOGGER.debug("[DISCOVER]     Found: {}{}{} ({} chars)", activeDimensionDir, baseName, ext, content.length());
                        return content;
                    }
                } catch (IOException e) {
                    LOGGER.warn("[DISCOVER]     Error reading {}{}{}: {}", activeDimensionDir, baseName, ext, e.getMessage());
                }
            }
        }

        // Fallback: try root directory (e.g. "gbuffers_terrain.vsh")
        for (String ext : extensions) {
            try {
                String content = source.readShaderFile(baseName + ext);
                if (content != null) {
                    LOGGER.debug("[DISCOVER]     Found: {}{} ({} chars)", baseName, ext, content.length());
                    return content;
                }
            } catch (IOException e) {
                LOGGER.warn("[DISCOVER]     Error reading {}{}: {}", baseName, ext, e.getMessage());
            }
        }
        return null;
    }

    // ─── Accessors ──────────────────────────────────────────────────────

    /**
     * Returns the resolved program source for a given program ID.
     * May return a fallback-resolved source if the direct program isn't available.
     */
    public ProgramSource getProgram(ProgramId id) {
        return programSources.get(id);
    }

    /**
     * Whether the shaderpack directly provides this program (not via fallback).
     */
    public boolean isDirectlyProvided(ProgramId id) {
        return directlyProvided.contains(id);
    }

    /**
     * Whether any source exists for this program (direct or fallback).
     */
    public boolean hasProgram(ProgramId id) {
        return programSources.containsKey(id);
    }

    /**
     * Returns all resolved programs.
     */
    public Map<ProgramId, ProgramSource> getAllPrograms() {
        return Collections.unmodifiableMap(programSources);
    }

    /**
     * Returns the set of programs directly provided by the shaderpack.
     */
    public Set<ProgramId> getDirectlyProvidedPrograms() {
        return Collections.unmodifiableSet(directlyProvided);
    }

    /**
     * Returns the shaderpack properties.
     */
    public ShaderpackProperties getProperties() {
        return properties;
    }
}
