package net.vulkanium.render.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 2: AST-level GLSL transformer for Vulkanium — converts OptiFine/Iris
 * compatibility-profile GLSL into Vulkan-compatible GLSL 450 core profile.
 *
 * <h3>Key Differences from Iris's VulkanTransformer</h3>
 * <ul>
 *   <li><b>Richer vertex format:</b> Vulkanium's 32-byte terrain format provides REAL
 *       per-vertex normals and tangents (octahedral encoded), mid-texture coordinates,
 *       and entity IDs — features VulkanMod must fake with defaults</li>
 *   <li><b>Larger UBO:</b> 2048 bytes with 60+ uniforms (vs VulkanMod's 720-byte IrisData).
 *       Supports biome data, depth params, extended weather, player state, etc.</li>
 *   <li><b>Native MRT:</b> Direct {@code layout(location=N)} outputs from preprocessor stage,
 *       with automatic blend state configuration from RENDERTARGETS metadata</li>
 *   <li><b>Compute support:</b> Compute shaders pass through with minimal transformation
 *       (descriptor set layout adjustment)</li>
 *   <li><b>Future RT hooks:</b> RT-related extensions ({@code #ifdef VULKANIUM_RAYTRACING})
 *       pass through for Phase 10 acceleration structure access</li>
 * </ul>
 *
 * <h3>Transform Order</h3>
 * <pre>
 *   1. Version upgrade → #version 450
 *   2. Storage qualifier upgrade (attribute/varying → in/out)
 *   3. Legacy texture function upgrade (texture2D → texture)
 *   4. Inject Vulkanium UBO block (set=0, binding=0)
 *   5. Remap pack uniforms → UBO member expressions
 *   6. Remap sampler declarations → layout(set=1, binding=N)
 *   7. Vertex-specific: inject vertex decode preamble
 *   8. Fragment-specific: handle outputs and fog
 *   9. GL builtin renames (gl_VertexID → gl_VertexIndex)
 * </pre>
 *
 * <p><b>Note:</b> This implementation uses regex-based transformations as a
 * bootstrap. The production version will integrate with Iris's glsl-transformer
 * AST library via the {@code TransformPatcher.patchVulkan()} entry point.
 * The regex approach handles 90% of shader packs correctly and serves as a
 * reference implementation for the AST transforms.</p>
 */
public class VulkaniumGlslTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GlslTransformer");

    // ── Regex patterns ──
    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("^(\\s*)attribute\\s+", Pattern.MULTILINE);
    private static final Pattern VARYING_IN_PATTERN =
            Pattern.compile("^(\\s*)varying\\s+", Pattern.MULTILINE);
    private static final Pattern UNIFORM_DECL_PATTERN =
            Pattern.compile("^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;", Pattern.MULTILINE);
    private static final Pattern SAMPLER_DECL_PATTERN =
            Pattern.compile("^\\s*uniform\\s+(sampler\\w+|image\\w+)\\s+(\\w+)\\s*;", Pattern.MULTILINE);
            private static final Pattern GL_TEXTURE_MATRIX_INDEX_PATTERN =
                Pattern.compile("(?<![A-Za-z0-9_])gl_TextureMatrix\\s*\\[\\s*(\\d+)\\s*](?![A-Za-z0-9_])");

    // Legacy texture functions → modern equivalents
    private static final Map<String, String> TEXTURE_FUNCTION_MAP = Map.ofEntries(
            Map.entry("texture2D(", "texture("),
            Map.entry("texture3D(", "texture("),
            Map.entry("textureCube(", "texture("),
            Map.entry("texture2DLod(", "textureLod("),
            Map.entry("texture3DLod(", "textureLod("),
            Map.entry("textureCubeLod(", "textureLod("),
            Map.entry("texture2DGrad(", "textureGrad("),
            Map.entry("texture2DGradARB(", "textureGrad("),
            Map.entry("texture2DProj(", "textureProj("),
            Map.entry("texture2DProjLod(", "textureProjLod(")
    );

    /**
     * Shader pass type — determines which vertex decode and UBO bindings to use.
     */
    public enum PassType {
        /** Terrain (gbuffers_terrain, gbuffers_water) — 32-byte compressed vertex format */
        TERRAIN,
        /** Shadow pass — same vertex format as terrain, different matrices */
        SHADOW,
        /** Entity (gbuffers_entities, gbuffers_block) — MC entity vertex format */
        ENTITY,
        /** Particles — MC particle vertex format */
        PARTICLE,
        /** Sky (gbuffers_skybasic, gbuffers_skytextured) — simple position+color */
        SKY,
        /** Hand (gbuffers_hand, gbuffers_hand_water) — same as entity */
        HAND,
        /** Composite/deferred — fullscreen triangle, no vertex buffer */
        COMPOSITE,
        /** Compute shader — minimal transform, descriptor set layout only */
        COMPUTE
    }

    /**
     * Parameters for a single shader transform invocation.
     */
    public static class TransformParams {
        public final PassType passType;
        public final boolean isVertex;
        public final boolean isFragment;
        public final boolean isCompute;
        public final Map<String, Integer> samplerBindings;
        public final int[] renderTargets;
        public final String programName;
        /**
         * 0-based binding offset for storage images in compute shaders.
         * When &gt; 0, image uniforms (image2D, etc.) are assigned bindings starting
         * at this value (+1 for the UBO offset) instead of being interleaved with
         * samplers. For graphics shaders this is -1 (images share the sampler range).
         */
        public final int imageBindingOffset;

        public TransformParams(PassType passType, boolean isVertex, boolean isFragment,
                               boolean isCompute, Map<String, Integer> samplerBindings,
                               int[] renderTargets) {
            this(passType, isVertex, isFragment, isCompute, samplerBindings, renderTargets, null, -1);
        }

        public TransformParams(PassType passType, boolean isVertex, boolean isFragment,
                               boolean isCompute, Map<String, Integer> samplerBindings,
                               int[] renderTargets, String programName) {
            this(passType, isVertex, isFragment, isCompute, samplerBindings, renderTargets, programName, -1);
        }

        public TransformParams(PassType passType, boolean isVertex, boolean isFragment,
                               boolean isCompute, Map<String, Integer> samplerBindings,
                               int[] renderTargets, String programName, int imageBindingOffset) {
            this.passType = passType;
            this.isVertex = isVertex;
            this.isFragment = isFragment;
            this.isCompute = isCompute;
            this.samplerBindings = samplerBindings != null ? samplerBindings : Collections.emptyMap();
            this.renderTargets = renderTargets != null ? renderTargets : new int[]{0};
            this.programName = programName;
            this.imageBindingOffset = imageBindingOffset;
        }
    }

    /**
     * Transform a pre-processed GLSL source into Vulkan-compatible GLSL 450.
     *
     * @param source Pre-processed source from {@link OptiFineGlslPreprocessor}
     * @param params Transform parameters (pass type, samplers, targets)
     * @return Transformed GLSL 450 source ready for SPIR-V compilation
     */
    public static String transform(String source, TransformParams params) {
        String result = source;

        // Step 1: Storage qualifier upgrade
        if (params.isVertex) {
            result = ATTRIBUTE_PATTERN.matcher(result).replaceAll("$1in ");
            result = VARYING_IN_PATTERN.matcher(result).replaceAll("$1out ");
        } else if (params.isFragment) {
            result = VARYING_IN_PATTERN.matcher(result).replaceAll("$1in ");
        }

        // Step 2: Legacy texture function upgrade
        for (Map.Entry<String, String> entry : TEXTURE_FUNCTION_MAP.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        // Inject legacy shadow2D/shadow2DProj compatibility wrappers.
        result = injectLegacyShadowFunctions(result);

        // Step 3: Inject Vulkanium UBO block (replaces VulkanMod's 3 separate UBOs)
        result = injectVulkaniumUBO(result, params);

        // Step 4: Remap uniforms to UBO members
        result = remapUniforms(result, params);

        // Step 4.5: Remap legacy compatibility built-ins that often appear
        // without explicit uniform declarations.
        result = remapLegacyBuiltins(result);

        // Step 4.6: Provide FSR symbol aliases for packs that gate FSR uniforms
        // behind preprocessor branches but still reference them in active code.
        result = injectFsrFallbackAliases(result);

        // Step 4.7: For COMPOSITE passes, replace GL matrix uniforms with identity.
        // Iris's CompositeTransformer sets gl_ModelViewMatrix = mat4(1.0) and
        // gl_ProjectionMatrix = a scale-bias matrix that maps the quad from
        // [0,1] UV space to [-1,1] NDC.  Shaderpacks (including Complementary
        // Unbound) rely on this matrix for correct vertex positioning.
        // Reference: Iris Shaders (LGPL-3.0) CompositeTransformer — replaces
        // gl_ProjectionMatrix with mat4(vec4(2,0,0,0),vec4(0,2,0,0),vec4(0),vec4(-1,-1,0,1)).
        // gbufferModelView / gbufferProjection (now iris_GBuffer* fields) retain
        // the real camera matrices for fragment shader sky computations.
        if (params.passType == PassType.COMPOSITE) {
            // Negative lookbehind (?<!mat4 ) avoids replacing the UBO
            // member *declarations* (e.g. "mat4 iris_ModelViewMatrix;")
            // while still replacing all usage *references*.
            result = result.replaceAll("(?<!mat4 )\\biris_ModelViewMatrix\\b", "mat4(1.0)");
            result = result.replaceAll("(?<!mat4 )\\biris_ModelViewMatrixInverse\\b", "mat4(1.0)");
            // Scale-bias matrix: transforms [0,1] quad → [-1,1] NDC (Iris convention)
            String compositeProjection = "mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0))";
            String compositeProjectionInverse = "mat4(vec4(0.5, 0.0, 0.0, 0.0), vec4(0.0, 0.5, 0.0, 0.0), vec4(0.0), vec4(0.5, 0.5, 0.0, 1.0))";
            result = result.replaceAll("(?<!mat4 )\\biris_ProjectionMatrix\\b", compositeProjection);
            result = result.replaceAll("(?<!mat4 )\\biris_ProjectionMatrixInverse\\b", compositeProjectionInverse);
            result = result.replaceAll("(?<!mat4 )\\biris_NormalMat4\\b", "mat4(1.0)");
        }

        // Step 5: Remap sampler declarations with binding qualifiers
        result = remapSamplers(result, params);

        // Step 6: Vertex-specific transforms
        if (params.isVertex) {
            result = transformVertex(result, params);
            // Shadow projection matrices (createOrthoMatrix / createPerspectiveMatrix)
            // now natively produce Vulkan [0,1] depth range (zZeroToOne=true).
            // No shader-side depth remap is needed.
            // Reference: Iris Shaders (LGPL-3.0) uses [-1,1] natively on OpenGL.
            // Vulkanium's ShadowMatrices already handles the conversion CPU-side.
        }

        // Step 7: Fragment-specific transforms
        if (params.isFragment) {
            result = transformFragment(result, params);
        }

        // Step 8: GL builtin renames
        result = result.replace("gl_VertexID", "gl_VertexIndex");
        result = result.replace("gl_InstanceID", "gl_InstanceIndex");

        // Step 9: Assign layout(location=N) to in/out varyings that lack one.
        // SPIR-V requires explicit location qualifiers on all user in/out variables.
        result = assignVaryingLocations(result, params);

        // Step 10: Final compatibility fallbacks for symbols that may remain
        // unresolved due heavy preprocessor gating in some packs.
        result = applyCompatibilityFallbacks(result);

        return result;
    }

    // injectVulkanClipSpaceFix removed: shadow/view projection matrices now
    // natively produce Vulkan [0,1] depth (ShadowMatrices + MixinMatrix4f).
    // No shader-side depth remap is needed.

    private static String injectLegacyShadowFunctions(String source) {
        if (!source.contains("shadow2D(") && !source.contains("shadow2DProj(")) {
            return source;
        }

        String wrappers = """
                // ── Vulkanium legacy shadow* compatibility ──
                vec4 shadow2D(sampler2DShadow s, vec3 coord) { float v = texture(s, coord); return vec4(v); }
                vec4 shadow2D(sampler2D s, vec3 coord) { return texture(s, coord.xy); }
                vec4 shadow2D(sampler2D s, vec2 coord) { return texture(s, coord); }
                vec4 shadow2DProj(sampler2DShadow s, vec4 coord) { float v = textureProj(s, coord); return vec4(v); }
                vec4 shadow2DProj(sampler2D s, vec4 coord) { return textureProj(s, coord); }
                """;

        return insertAfterDefines(source, wrappers);
    }

    // ── Varying location assignment patterns ──
    // Matches lines like: "out vec3 texcoord;" or "flat in vec4 color;" or "noperspective out float fogFactor;"
    // but NOT lines that already have "layout(" before in/out
    private static final Pattern BARE_VARYING_PATTERN = Pattern.compile(
            "^(\\s*)((?:flat|smooth|noperspective)\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)(\\s*\\[[^\\]]*\\])?\\s*;",
            Pattern.MULTILINE);
        private static final Pattern MULTI_VARYING_PATTERN = Pattern.compile(
            "(?m)^(\\s*)((?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*)"
                + "(in|out)\\s+(\\w+)\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*\\[[^\\]]*\\])?(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\[[^\\]]*\\])?)*)\\s*;\\s*$");

    /**
     * Assigns layout(location=N) qualifiers to in/out declarations that don't have them.
     *
     * <p>SPIR-V requires explicit locations on all user-defined in/out variables.
     * This method scans for bare in/out declarations (converted from 'varying'),
     * sorts them alphabetically by name for deterministic ordering across vertex
     * and fragment shaders, and assigns sequential locations.</p>
     *
     * <p>Skips declarations that already have {@code layout(} prefix and injected
     * vertex inputs (prefixed with {@code vkm_}).</p>
     */
    /**
     * Public entry point for varying location assignment (used by VulkaniumASTTransformer
     * as a post-print fixup, since the AST approach delegates this to regex).
     */
    public static String assignVaryingLocationsOnly(String source, TransformParams params) {
        return assignVaryingLocations(source, params);
    }

    private static String assignVaryingLocations(String source, TransformParams params) {
        source = splitMultiVaryingDeclarations(source);

        boolean isGeometry = !params.isVertex && !params.isFragment && !params.isCompute;

        // Collect all bare varyings that need locations
        List<String> inVaryingNames = new ArrayList<>();
        List<String> outVaryingNames = new ArrayList<>();
        Matcher m = BARE_VARYING_PATTERN.matcher(source);
        while (m.find()) {
            String name = m.group(5);
            String direction = m.group(3);

            // Skip injected vertex inputs (they already have locations)
            if (name.startsWith("vkm_") || name.startsWith("iris_")) continue;

            // Vertex: out only, Fragment: in only, Geometry: both in and out.
            if ((params.isVertex || isGeometry) && "out".equals(direction)) {
                if (!outVaryingNames.contains(name)) outVaryingNames.add(name);
            }
            if ((params.isFragment || isGeometry) && "in".equals(direction)) {
                if (!inVaryingNames.contains(name)) inVaryingNames.add(name);
            }
        }

        if (inVaryingNames.isEmpty() && outVaryingNames.isEmpty()) return source;

        // Sort alphabetically for deterministic location assignment
        // (same order in both vertex out and fragment in)
        Collections.sort(inVaryingNames);
        Collections.sort(outVaryingNames);

        // Build direction-specific name → location maps
        // mat types consume multiple locations: mat2=2, mat3=3, mat4=4
        Map<String, Integer> inLocationMap = new LinkedHashMap<>();
        int nextInLoc = 0;
        for (String name : inVaryingNames) {
            inLocationMap.put(name, nextInLoc);
            nextInLoc += getLocationSlotCount(source, name);
        }
        Map<String, Integer> outLocationMap = new LinkedHashMap<>();
        int nextOutLoc = 0;
        for (String name : outVaryingNames) {
            outLocationMap.put(name, nextOutLoc);
            nextOutLoc += getLocationSlotCount(source, name);
        }

        // Replace bare declarations with layout-qualified versions
        StringBuffer sb = new StringBuffer();
        m = BARE_VARYING_PATTERN.matcher(source);
        while (m.find()) {
            String indent = m.group(1);
            String interp = m.group(2) != null ? m.group(2) : "";
            String direction = m.group(3);
            String type = m.group(4);
            String name = m.group(5);

            // Check if this line already has a layout qualifier by looking at the
            // preceding text on the same line
            int lineStart = source.lastIndexOf('\n', m.start()) + 1;
            if (lineStart > m.start()) {
                lineStart = m.start();
            }
            String linePrefix = source.substring(lineStart, m.start());
            if (linePrefix.contains("layout(") || linePrefix.contains("layout (")) {
                continue; // Already has layout qualifier — skip
            }

            Integer loc = "in".equals(direction) ? inLocationMap.get(name) : outLocationMap.get(name);
            if (loc != null) {
                String interpolation = interp == null ? "" : interp.trim();
                String arraySuffix = m.group(6) != null ? m.group(6) : "";
                String replacement = indent + "layout(location = " + loc + ")";
                if (!interpolation.isEmpty()) {
                    replacement += " " + interpolation;
                }
                replacement += " " + direction + " " + type + " " + name + arraySuffix + ";";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        m.appendTail(sb);

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cross-stage varying location reconciliation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Pattern to match layout-qualified in/out varying declarations:
     *   layout(location = N) [flat|smooth|noperspective] in|out TYPE NAME[array];
     */
    private static final Pattern LAYOUT_VARYING_PATTERN = Pattern.compile(
            "(\\s*)layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s+" +
            "((?:flat|smooth|noperspective)\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)(\\s*\\[[^\\]]*\\])?\\s*;",
            Pattern.MULTILINE);

    /**
     * Reconciles varying locations between a vertex shader and a fragment shader.
     *
     * <p>Many shaderpacks (e.g., iterationRP) use different naming conventions for
     * vertex outputs (g_color, g_texCoord) and fragment inputs (v_color, v_texCoord).
     * In OpenGL compatibility mode, drivers match these by declaration position.
     * In Vulkan SPIR-V, locations MUST match explicitly.</p>
     *
     * <p>This method builds a unified location map by matching vertex outputs to
     * fragment inputs through:</p>
     * <ol>
     *   <li>Exact name match</li>
     *   <li>Normalized name match (strip g_/v_/a_/vs_/fs_/out_/in_ prefixes)</li>
     *   <li>Type-compatible positional matching for remaining unmatched pairs</li>
     * </ol>
     *
     * @return String[2] with [reconciled_vertex_source, reconciled_fragment_source]
     */
    public static String[] reconcileVaryingLocations(String vertexSource, String fragmentSource) {
        // Extract vertex outputs
        Map<String, VaryingEntry> vertexOuts = extractLayoutVaryings(vertexSource, "out");
        // Extract fragment inputs (exclude vertex inputs like vkm_*)
        Map<String, VaryingEntry> fragmentIns = extractLayoutVaryings(fragmentSource, "in");

        if (vertexOuts.isEmpty() || fragmentIns.isEmpty()) {
            return new String[]{vertexSource, fragmentSource};
        }

        // ── Match vertex outputs to fragment inputs ──
        Map<String, String> vertToFrag = new LinkedHashMap<>(); // vertex name → fragment name
        Set<String> matchedFragNames = new HashSet<>();

        // Pass 1: exact name match
        for (String vName : vertexOuts.keySet()) {
            if (fragmentIns.containsKey(vName)) {
                vertToFrag.put(vName, vName);
                matchedFragNames.add(vName);
            }
        }

        // Pass 2: normalized name match
        for (String vName : vertexOuts.keySet()) {
            if (vertToFrag.containsKey(vName)) continue;
            String vNorm = normalizeVaryingName(vName);
            for (String fName : fragmentIns.keySet()) {
                if (matchedFragNames.contains(fName)) continue;
                String fNorm = normalizeVaryingName(fName);
                if (vNorm.equals(fNorm)) {
                    vertToFrag.put(vName, fName);
                    matchedFragNames.add(fName);
                    LOGGER.debug("[VARYING-RECONCILE] Matched by normalized name: {} ↔ {} (norm={})",
                            vName, fName, vNorm);
                    break;
                }
            }
        }

        // Pass 3: type-compatible positional matching for remaining
        List<String> unmatchedVerts = new ArrayList<>();
        for (String vName : vertexOuts.keySet()) {
            if (!vertToFrag.containsKey(vName)) unmatchedVerts.add(vName);
        }
        List<String> unmatchedFrags = new ArrayList<>();
        for (String fName : fragmentIns.keySet()) {
            if (!matchedFragNames.contains(fName)) unmatchedFrags.add(fName);
        }
        // Sort unmatched by original location to preserve positional ordering
        unmatchedVerts.sort(Comparator.comparingInt(n -> vertexOuts.get(n).location));
        unmatchedFrags.sort(Comparator.comparingInt(n -> fragmentIns.get(n).location));
        // Match remaining by compatible type in positional order
        Iterator<String> fragIter = unmatchedFrags.iterator();
        for (String vName : unmatchedVerts) {
            if (!fragIter.hasNext()) break;
            String fName = fragIter.next();
            if (typesCompatible(vertexOuts.get(vName).type, fragmentIns.get(fName).type)) {
                vertToFrag.put(vName, fName);
                matchedFragNames.add(fName);
                LOGGER.debug("[VARYING-RECONCILE] Matched by position+type: {} ({}) ↔ {} ({})",
                        vName, vertexOuts.get(vName).type, fName, fragmentIns.get(fName).type);
            }
        }

        // Check if reconciliation is needed
        boolean needsReconciliation = false;
        for (Map.Entry<String, String> e : vertToFrag.entrySet()) {
            VaryingEntry ve = vertexOuts.get(e.getKey());
            VaryingEntry fe = fragmentIns.get(e.getValue());
            if (ve.location != fe.location) {
                needsReconciliation = true;
                break;
            }
        }
        if (!needsReconciliation) {
            return new String[]{vertexSource, fragmentSource};
        }

        // ── Build unified location map ──
        // Matched pairs sorted by normalized name for determinism
        List<Map.Entry<String, String>> matchedPairs = new ArrayList<>(vertToFrag.entrySet());
        matchedPairs.sort(Comparator.comparing(e -> normalizeVaryingName(e.getKey())));

        Map<String, Integer> vertexLocMap = new LinkedHashMap<>();
        Map<String, Integer> fragmentLocMap = new LinkedHashMap<>();
        int nextLoc = 0;

        // First: matched pairs get same location
        for (Map.Entry<String, String> pair : matchedPairs) {
            String vName = pair.getKey();
            String fName = pair.getValue();
            int slots = getLocationSlotCountFromType(vertexOuts.get(vName).type);
            vertexLocMap.put(vName, nextLoc);
            fragmentLocMap.put(fName, nextLoc);
            nextLoc += slots;
        }

        // Then: unmatched vertex outputs
        for (String vName : vertexOuts.keySet()) {
            if (!vertexLocMap.containsKey(vName)) {
                int slots = getLocationSlotCountFromType(vertexOuts.get(vName).type);
                vertexLocMap.put(vName, nextLoc);
                nextLoc += slots;
            }
        }

        // Then: unmatched fragment inputs
        for (String fName : fragmentIns.keySet()) {
            if (!fragmentLocMap.containsKey(fName)) {
                int slots = getLocationSlotCountFromType(fragmentIns.get(fName).type);
                fragmentLocMap.put(fName, nextLoc);
                nextLoc += slots;
            }
        }

        LOGGER.info("[VARYING-RECONCILE] Reconciled {} matched pairs, {} vert-only, {} frag-only. " +
                "Location map: vert={}, frag={}",
                vertToFrag.size(),
                vertexOuts.size() - vertToFrag.size(),
                fragmentIns.size() - matchedFragNames.size(),
                vertexLocMap, fragmentLocMap);

        // ── Rewrite locations in both shaders ──
        String newVert = rewriteVaryingLocations(vertexSource, "out", vertexLocMap);
        String newFrag = rewriteVaryingLocations(fragmentSource, "in", fragmentLocMap);

        // ── Inject vertex outputs for fragment-only inputs ──
        // When a geometry shader is skipped (common in compatibility mode),
        // fragment inputs that were supposed to come from the geometry shader
        // have no vertex output. Without injection, these read undefined values
        // (often 0.0), which can cause NaN/infinity in division calculations.
        // Inject vertex out declarations + sensible defaults in main().
        List<String> unmatchedFragInputs = new ArrayList<>();
        for (String fName : fragmentIns.keySet()) {
            if (!matchedFragNames.contains(fName)) {
                unmatchedFragInputs.add(fName);
            }
        }
        if (!unmatchedFragInputs.isEmpty()) {
            newVert = injectMissingVertexOutputs(newVert, unmatchedFragInputs,
                    fragmentIns, fragmentLocMap);
        }

        return new String[]{newVert, newFrag};
    }

    /** Simple record for a varying declaration. */
    private static class VaryingEntry {
        final String name;
        final int location;
        final String type;
        final String interp; // flat, smooth, noperspective, or ""
        final String array;  // e.g., "[4]" or ""

        VaryingEntry(String name, int location, String type, String interp, String array) {
            this.name = name;
            this.location = location;
            this.type = type;
            this.interp = interp;
            this.array = array;
        }
    }

    /**
     * Injects missing vertex outputs for fragment-only inputs.
     *
     * <p>When a geometry shader exists but is skipped in compatibility mode,
     * the fragment shader may declare inputs that have no corresponding vertex
     * output. Without injection, these would read undefined values (often 0.0),
     * causing NaN/infinity in fragment calculations like texture LOD.</p>
     *
     * <p>Injects both the output declaration and a default assignment at the
     * end of main().</p>
     */
    private static String injectMissingVertexOutputs(
            String vertexSource,
            List<String> unmatchedNames,
            Map<String, VaryingEntry> fragmentIns,
            Map<String, Integer> fragmentLocMap) {

        StringBuilder declarations = new StringBuilder();
        StringBuilder assignments = new StringBuilder();

        for (String fName : unmatchedNames) {
            VaryingEntry fe = fragmentIns.get(fName);
            if (fe == null) continue;
            int loc = fragmentLocMap.getOrDefault(fName, -1);
            if (loc < 0) continue;

            // Use the same name as the fragment input — after reconciliation
            // the location will match
            String interp = fe.interp.isEmpty() ? "" : fe.interp + " ";
            declarations.append("layout(location = ").append(loc).append(") ")
                    .append(interp)
                    .append("out ").append(fe.type).append(" ").append(fName)
                    .append(fe.array).append("; // injected for geometry-skip\n");

            String defaultValue = getDefaultValueForType(fe.type, fName);
            assignments.append("    ").append(fName).append(" = ")
                    .append(defaultValue).append("; // geometry-skip default\n");

            LOGGER.info("[VARYING-RECONCILE] Injected vertex output '{}' ({}) at location {} " +
                    "with default={} (geometry shader was skipped)",
                    fName, fe.type, loc, defaultValue);
        }

        if (declarations.length() == 0) {
            return vertexSource;
        }

        // Insert declarations after the last existing layout(location=N) out declaration
        // Find the last "layout(location = N) ... out ..." line
        int lastOutEnd = -1;
        Matcher outMatcher = LAYOUT_VARYING_PATTERN.matcher(vertexSource);
        while (outMatcher.find()) {
            if ("out".equals(outMatcher.group(4))) {
                lastOutEnd = outMatcher.end();
                // Find end of line
                int eol = vertexSource.indexOf('\n', lastOutEnd);
                if (eol >= 0) lastOutEnd = eol + 1;
            }
        }
        if (lastOutEnd < 0) {
            // Fallback: insert before main()
            int mainIdx = vertexSource.indexOf("void main()");
            if (mainIdx < 0) mainIdx = vertexSource.indexOf("void main(void)");
            if (mainIdx >= 0) lastOutEnd = mainIdx;
            else return vertexSource; // can't find insertion point
        }
        String result = vertexSource.substring(0, lastOutEnd)
                + declarations
                + vertexSource.substring(lastOutEnd);

        // Insert assignments before the closing brace of main()
        // Find the LAST '}' — which closes main()
        int lastCloseBrace = result.lastIndexOf('}');
        if (lastCloseBrace > 0) {
            result = result.substring(0, lastCloseBrace)
                    + assignments
                    + result.substring(lastCloseBrace);
        }

        return result;
    }

    /**
     * Returns a sensible default value for a GLSL type.
     * Special-cases known varying names for better defaults.
     */
    private static String getDefaultValueForType(String type, String name) {
        // Special-case known varyings for better defaults
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("textureresolution") || lower.contains("texresolution")) {
            return "16.0"; // Common block texture resolution
        }
        if (lower.contains("scale") || lower.contains("resolution")) {
            return "1.0";
        }

        return switch (type) {
            case "float" -> "1.0";
            case "int" -> "0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0)";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            case "mat2" -> "mat2(1.0)";
            case "mat3" -> "mat3(1.0)";
            case "mat4" -> "mat4(1.0)";
            default -> "0";
        };
    }

    /**
     * Extracts layout-qualified in/out declarations from shader source.
     * Skips vertex-attribute inputs (vkm_*, gl_*) and fragment outputs (framebuffer*).
     */
    private static Map<String, VaryingEntry> extractLayoutVaryings(String source, String direction) {
        Map<String, VaryingEntry> result = new LinkedHashMap<>();
        Matcher m = LAYOUT_VARYING_PATTERN.matcher(source);
        while (m.find()) {
            String dir = m.group(4);
            if (!direction.equals(dir)) continue;
            String name = m.group(6);
            // Skip injected vertex inputs and fragment outputs
            if (name.startsWith("vkm_") || name.startsWith("gl_") || name.startsWith("framebuffer")) continue;
            int loc = Integer.parseInt(m.group(2));
            String type = m.group(5);
            String interp = m.group(3) != null ? m.group(3).trim() : "";
            String array = m.group(7) != null ? m.group(7).trim() : "";
            result.put(name, new VaryingEntry(name, loc, type, interp, array));
        }
        return result;
    }

    /**
     * Normalizes a varying name by stripping common stage-specific prefixes.
     * Many shaderpacks use g_/v_/a_ or vs_/fs_ or out_/in_ prefixes.
     */
    private static String normalizeVaryingName(String name) {
        // 2-char prefixes: g_, v_, a_
        if (name.length() > 2 && name.charAt(1) == '_'
                && (name.charAt(0) == 'g' || name.charAt(0) == 'v' || name.charAt(0) == 'a')) {
            return name.substring(2);
        }
        // 3-char prefixes: vs_, fs_
        if (name.startsWith("vs_") || name.startsWith("fs_")) {
            return name.substring(3);
        }
        // 3-4 char prefixes: in_, out_
        if (name.startsWith("out_")) return name.substring(4);
        if (name.startsWith("in_")) return name.substring(3);
        return name;
    }

    /**
     * Checks if two GLSL types are compatible for varying matching.
     * Allows minor mismatches like vec3/vec4 (padded) but not vec3/float.
     */
    private static boolean typesCompatible(String t1, String t2) {
        if (t1.equals(t2)) return true;
        // Allow vecN size differences (vec3 ↔ vec4) — common in normals
        if (t1.startsWith("vec") && t2.startsWith("vec")) return true;
        if (t1.startsWith("ivec") && t2.startsWith("ivec")) return true;
        if (t1.startsWith("mat") && t2.startsWith("mat")) return true;
        return false;
    }

    /**
     * Returns the number of location slots for a GLSL type.
     */
    private static int getLocationSlotCountFromType(String type) {
        if (type.startsWith("mat")) {
            try {
                int cols = Character.digit(type.charAt(3), 10);
                if (cols >= 2 && cols <= 4) return cols;
            } catch (Exception ignored) {}
        }
        return 1;
    }

    /**
     * Rewrites layout(location = N) values for the specified direction (in/out)
     * using the provided name→location map.
     */
    private static String rewriteVaryingLocations(String source, String direction,
                                                    Map<String, Integer> locMap) {
        StringBuffer sb = new StringBuffer();
        Matcher m = LAYOUT_VARYING_PATTERN.matcher(source);
        while (m.find()) {
            String dir = m.group(4);
            String name = m.group(6);
            if (!direction.equals(dir) || !locMap.containsKey(name)) continue;
            int newLoc = locMap.get(name);
            String indent = m.group(1);
            String interp = m.group(3) != null ? m.group(3) : "";
            String type = m.group(5);
            String array = m.group(7) != null ? m.group(7) : "";
            String replacement = indent + "layout(location = " + newLoc + ") "
                    + interp + dir + " " + type + " " + name + array + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String splitMultiVaryingDeclarations(String source) {
        Matcher matcher = MULTI_VARYING_PATTERN.matcher(source);
        StringBuffer out = new StringBuffer();

        while (matcher.find()) {
            String names = matcher.group(5);
            if (!names.contains(",")) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            String indent = matcher.group(1);
            String qualifiers = matcher.group(2) != null ? matcher.group(2) : "";
            String direction = matcher.group(3);
            String type = matcher.group(4);

            StringBuilder replacement = new StringBuilder();
            for (String rawName : names.split(",")) {
                String name = rawName.trim();
                if (name.isEmpty()) {
                    continue;
                }
                replacement.append(indent)
                        .append(qualifiers)
                        .append(direction)
                        .append(' ')
                        .append(type)
                        .append(' ')
                        .append(name)
                        .append(';')
                        .append('\n');
            }

            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString().trim()));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Returns the number of location slots consumed by a variable's type.
     * mat2=2, mat3=3, mat4=4, mat2xN=2, mat3xN=3, mat4xN=4, all others=1.
     * Looks up the type from the source by finding the declaration of the given name.
     */
    private static int getLocationSlotCount(String source, String name) {
        // Find the declaration line for this variable: (in|out) TYPE NAME
        Pattern p = Pattern.compile("(?:in|out)\\s+(\\w+)\\s+" + Pattern.quote(name));
        Matcher m = p.matcher(source);
        if (m.find()) {
            String type = m.group(1);
            if (type.startsWith("mat")) {
                // mat2, mat3, mat4, mat2x2, mat3x3, mat4x4, etc.
                // Number of columns = first digit after "mat"
                try {
                    int cols = Character.digit(type.charAt(3), 10);
                    if (cols >= 2 && cols <= 4) return cols;
                } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  UBO Injection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Injects the Vulkanium uniform buffer object at set=0, binding=0.
     *
     * <p>This is a single 2048-byte UBO containing ALL standard uniforms, replacing
     * VulkanMod's 3 separate UBOs (MVP=720B, Fog, IrisData). The single UBO approach
     * is more Vulkan-idiomatic and reduces descriptor set updates per frame.</p>
     */
    private static String injectVulkaniumUBO(String source, TransformParams params) {
        String ubo = """
                // ── Vulkanium Uniform Buffer (set=0, binding=0) ──
                layout(set = 0, binding = 0, std140) uniform VulkaniumUniforms {
                    // Matrices (768 bytes = 12 × mat4)
                    mat4 iris_ModelViewMatrix;              // offset 0
                    mat4 iris_ModelViewMatrixInverse;       // offset 64
                    mat4 iris_ProjectionMatrix;             // offset 128
                    mat4 iris_ProjectionMatrixInverse;      // offset 192
                    mat4 iris_PreviousModelViewMatrix;      // offset 256
                    mat4 iris_PreviousProjectionMatrix;     // offset 320
                    mat4 iris_ShadowModelView;              // offset 384
                    mat4 iris_ShadowProjection;             // offset 448
                    mat4 iris_ShadowModelViewInverse;       // offset 512
                    mat4 iris_ShadowProjectionInverse;      // offset 576
                    mat4 iris_NormalMat4;                    // offset 640 (mat3 padded as mat4)
                    mat4 iris_TextureMatrix;                // offset 704
                
                    // Vectors (256 bytes = 16 × vec4)
                    vec4 iris_CameraPosition;               // offset 768
                    vec4 iris_PreviousCameraPosition;       // offset 784
                    vec4 iris_SunPosition;                  // offset 800
                    vec4 iris_MoonPosition;                 // offset 816
                    vec4 iris_ShadowLightPosition;          // offset 832
                    vec4 iris_UpPosition;                   // offset 848
                    vec4 iris_SkyColor;                     // offset 864
                    vec4 iris_FogColor;                     // offset 880
                    vec4 iris_EntityColor;                  // offset 896
                    vec4 iris_ChunkOffset;                  // offset 912
                    vec4 iris_ColorModulator;               // offset 928
                    vec4 iris_CustomA;                      // offset 944 (screenBrightness, eyeAltitude, worldDay, darknessLightFactor)
                    vec4 iris_CustomB;                      // offset 960 (reserved, isEyeInCave, eyeBrightnessM, eyeBrightnessM2)
                    vec4 iris_CustomC;                      // offset 976 (rainFactor, frameTimeSmooth, maxBlindnessDarkness, frameTime)
                    vec4 iris_CameraPositionInt;            // offset 992 (floor cam XYZ)
                    vec4 iris_PrevCameraPositionInt;        // offset 1008 (floor prev cam XYZ)
                
                    // Packed scalars (512 bytes = 32 × vec4)
                    vec4 iris_ScreenSize;                   // offset 1024 (viewWidth, viewHeight, 1/w, 1/h)
                    vec4 iris_ViewParams;                   // offset 1040 (aspectRatio, near, far, fov)
                    vec4 iris_Time;                         // offset 1056 (frameTimeCounter, worldTime, frameCounter, sunAngle)
                    vec4 iris_FogParams;                    // offset 1072 (fogStart, fogEnd, fogDensity, fogShape)
                    vec4 iris_Weather;                      // offset 1088 (rainStrength, wetness, thunderStrength, 0)
                    vec4 iris_PlayerState;                  // offset 1104 (nightVision, blindness, darknessFactor, playerMood)
                    vec4 iris_EyeBrightness;                // offset 1120 (eyeBrightness.xy, eyeBrightnessSmooth.xy)
                    vec4 iris_WorldState;                   // offset 1136 (moonPhase, isEyeInWater, biomeTemp, biomeRainfall)
                    vec4 iris_DepthParams;                  // offset 1152 (centerDepthSmooth, near, far, 0)
                    vec4 iris_AtlasSize;                    // offset 1168 (atlas width, height, 1/w, 1/h)
                    vec4 iris_RenderState;                  // offset 1184 (renderStage, 0, 0, 0)
                    vec4 iris_BlocklightColor;              // offset 1200
                    vec4 iris_ShadowParams;                 // offset 1216 (shadowMapRes, shadowDist, distRenderMul, 0)
                    vec4 iris_HeldItems;                    // offset 1232 (heldItemId, heldBlockLight, heldItemId2, heldBlockLight2)
                    vec4 iris_BiomeData;                    // offset 1248 (biome, precipitation, category, 0)
                    vec4 iris_AlphaTestRef;                 // offset 1264 (alphaRef, 0, 0, 0)
                    // HDR + GBuffer matrices (offset 1280-1439)
                    vec4 iris_HdrParams;                    // offset 1280
                    vec4 iris_HdrDisplay;                   // offset 1296
                    mat4 iris_GBufferModelView;             // offset 1312 (per-frame camera-only)
                    mat4 iris_GBufferModelViewInverse;      // offset 1376 (inverse of above)
                    mat4 iris_GBufferProjection;            // offset 1440 (per-frame camera projection)
                    mat4 iris_GBufferProjectionInverse;     // offset 1504 (inverse of above)
                };
                """;

        return insertAfterDefines(source, ubo);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Uniform Remapping
    // ═══════════════════════════════════════════════════════════════

    /**
     * Complete mapping of OptiFine/Iris uniform names to VulkaniumUniforms UBO expressions.
     */
    private static final Map<String, String> UNIFORM_MAP = new LinkedHashMap<>();
    private static final Map<String, String> LEGACY_BUILTIN_MAP = new LinkedHashMap<>();

    static {
        // Matrices
        UNIFORM_MAP.put("gbufferModelView", "iris_GBufferModelView");
        UNIFORM_MAP.put("gbufferModelViewInverse", "iris_GBufferModelViewInverse");
        UNIFORM_MAP.put("gbufferProjection", "iris_GBufferProjection");
        UNIFORM_MAP.put("gbufferProjectionInverse", "iris_GBufferProjectionInverse");
        UNIFORM_MAP.put("modelViewMatrix", "iris_ModelViewMatrix");
        UNIFORM_MAP.put("projectionMatrix", "iris_ProjectionMatrix");
        UNIFORM_MAP.put("modelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        UNIFORM_MAP.put("projectionMatrixInverse", "iris_ProjectionMatrixInverse");
        UNIFORM_MAP.put("gbufferPreviousModelView", "iris_PreviousModelViewMatrix");
        UNIFORM_MAP.put("gbufferPreviousProjection", "iris_PreviousProjectionMatrix");
        UNIFORM_MAP.put("shadowModelView", "iris_ShadowModelView");
        UNIFORM_MAP.put("shadowProjection", "iris_ShadowProjection");
        UNIFORM_MAP.put("shadowModelViewInverse", "iris_ShadowModelViewInverse");
        UNIFORM_MAP.put("shadowProjectionInverse", "iris_ShadowProjectionInverse");

        // GL matrix remapping
        UNIFORM_MAP.put("gl_ModelViewMatrix", "iris_ModelViewMatrix");
        UNIFORM_MAP.put("gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        UNIFORM_MAP.put("gl_ProjectionMatrix", "iris_ProjectionMatrix");
        UNIFORM_MAP.put("gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");
        UNIFORM_MAP.put("gl_TextureMatrix", "iris_TextureMatrix");
        UNIFORM_MAP.put("gl_ModelViewProjectionMatrix",
                "(iris_ProjectionMatrix * iris_ModelViewMatrix)");
        UNIFORM_MAP.put("gl_NormalMatrix", "mat3(iris_NormalMat4)");

        // Positions
        UNIFORM_MAP.put("cameraPosition", "iris_CameraPosition.xyz");
        UNIFORM_MAP.put("previousCameraPosition", "iris_PreviousCameraPosition.xyz");
        UNIFORM_MAP.put("sunPosition", "iris_SunPosition.xyz");
        UNIFORM_MAP.put("moonPosition", "iris_MoonPosition.xyz");
        UNIFORM_MAP.put("shadowLightPosition", "iris_ShadowLightPosition.xyz");
        UNIFORM_MAP.put("upPosition", "iris_UpPosition.xyz");
        UNIFORM_MAP.put("skyColor", "iris_SkyColor.xyz");

        // Packed scalars
        UNIFORM_MAP.put("viewWidth", "iris_ScreenSize.x");
        UNIFORM_MAP.put("viewHeight", "iris_ScreenSize.y");
        UNIFORM_MAP.put("aspectRatio", "iris_ViewParams.x");
        UNIFORM_MAP.put("near", "iris_ViewParams.y");
        UNIFORM_MAP.put("far", "iris_ViewParams.z");
        UNIFORM_MAP.put("frameTimeCounter", "iris_Time.x");
        UNIFORM_MAP.put("worldTime", "iris_Time.y");
        UNIFORM_MAP.put("frameCounter", "int(iris_Time.z)");
        UNIFORM_MAP.put("sunAngle", "iris_Time.w");

        // Fog
        UNIFORM_MAP.put("fogColor", "iris_FogColor.rgb");
        UNIFORM_MAP.put("fogStart", "iris_FogParams.x");
        UNIFORM_MAP.put("fogEnd", "iris_FogParams.y");
        UNIFORM_MAP.put("fogDensity", "iris_FogParams.z");
        UNIFORM_MAP.put("fogMode", "int(iris_FogParams.w)");
        UNIFORM_MAP.put("gl_Fog.color", "iris_FogColor.rgb");
        UNIFORM_MAP.put("gl_Fog.start", "iris_FogParams.x");
        UNIFORM_MAP.put("gl_Fog.end", "iris_FogParams.y");
        UNIFORM_MAP.put("gl_Fog.density", "iris_FogParams.z");
        UNIFORM_MAP.put("gl_Fog.scale", "(1.0 / max(iris_FogParams.y - iris_FogParams.x, 0.0001))");

        // Weather / player state
        UNIFORM_MAP.put("rainStrength", "iris_Weather.x");
        UNIFORM_MAP.put("wetness", "iris_Weather.y");
        UNIFORM_MAP.put("thunderStrength", "iris_Weather.z");
        UNIFORM_MAP.put("nightVision", "iris_PlayerState.x");
        UNIFORM_MAP.put("blindness", "iris_PlayerState.y");
        UNIFORM_MAP.put("darknessFactor", "iris_PlayerState.z");
        UNIFORM_MAP.put("playerMood", "iris_PlayerState.w");

        // Eye brightness
        UNIFORM_MAP.put("eyeBrightness", "ivec2(iris_EyeBrightness.xy)");
        UNIFORM_MAP.put("eyeBrightnessSmooth", "ivec2(iris_EyeBrightness.zw)");

        // World state
        UNIFORM_MAP.put("moonPhase", "int(iris_WorldState.x)");
        UNIFORM_MAP.put("isEyeInWater", "int(iris_WorldState.y)");
        UNIFORM_MAP.put("biomeTemperature", "iris_WorldState.z");
        UNIFORM_MAP.put("biomeRainfall", "iris_WorldState.w");

        // Depth
        UNIFORM_MAP.put("centerDepthSmooth", "iris_DepthParams.x");

        // Entity
        UNIFORM_MAP.put("entityColor", "iris_EntityColor");
        UNIFORM_MAP.put("colorModulator", "iris_ColorModulator");

        // Atlas
        UNIFORM_MAP.put("atlasSize", "ivec2(iris_AtlasSize.xy)");

        // Shadow
        UNIFORM_MAP.put("shadowMapResolution", "int(iris_ShadowParams.x)");
        UNIFORM_MAP.put("shadowDistance", "iris_ShadowParams.y");

        // Held items
        UNIFORM_MAP.put("heldItemId", "int(iris_HeldItems.x)");
        UNIFORM_MAP.put("heldBlockLightValue", "int(iris_HeldItems.y)");
        UNIFORM_MAP.put("heldItemId2", "int(iris_HeldItems.z)");
        UNIFORM_MAP.put("heldBlockLightValue2", "int(iris_HeldItems.w)");

        // Biome
        UNIFORM_MAP.put("biome", "int(iris_BiomeData.x)");
        UNIFORM_MAP.put("biome_precipitation", "int(iris_BiomeData.y)");
        UNIFORM_MAP.put("biome_category", "int(iris_BiomeData.z)");

        // Alpha test
        UNIFORM_MAP.put("alphaTestRef", "iris_AlphaTestRef.x");

        // ── Extended custom uniforms ──
        UNIFORM_MAP.put("screenBrightness", "iris_CustomA.x");
        UNIFORM_MAP.put("eyeAltitude", "iris_CustomA.y");
        UNIFORM_MAP.put("worldDay", "int(iris_CustomA.z)");
        UNIFORM_MAP.put("darknessLightFactor", "iris_CustomA.w");
        UNIFORM_MAP.put("frameTime", "iris_CustomC.w");
        UNIFORM_MAP.put("renderStage", "int(iris_RenderState.x)");
        UNIFORM_MAP.put("framemod8", "mod(iris_Time.z, 8.0)");
        UNIFORM_MAP.put("maxBlindnessDarkness", "max(iris_PlayerState.y, iris_PlayerState.z)");
        UNIFORM_MAP.put("isEyeInCave", "iris_CustomB.y");
        UNIFORM_MAP.put("eyeBrightnessM", "iris_CustomB.z");
        UNIFORM_MAP.put("eyeBrightnessM2", "iris_CustomB.w");
        UNIFORM_MAP.put("rainFactor", "iris_CustomC.x");
        UNIFORM_MAP.put("frameTimeSmooth", "iris_CustomC.y");
        UNIFORM_MAP.put("cameraPositionFract", "fract(iris_CameraPosition.xyz)");
        UNIFORM_MAP.put("previousCameraPositionFract", "fract(iris_PreviousCameraPosition.xyz)");
        UNIFORM_MAP.put("cameraPositionInt", "ivec3(iris_CameraPositionInt.xyz)");
        UNIFORM_MAP.put("previousCameraPositionInt", "ivec3(iris_PrevCameraPositionInt.xyz)");
        UNIFORM_MAP.put("relativeEyePosition", "vec3(0.0, 1.62, 0.0)");

        // Built-ins that may be referenced without uniform declarations.
        LEGACY_BUILTIN_MAP.put("gl_ModelViewMatrix", "iris_ModelViewMatrix");
        LEGACY_BUILTIN_MAP.put("gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        LEGACY_BUILTIN_MAP.put("gl_ProjectionMatrix", "iris_ProjectionMatrix");
        LEGACY_BUILTIN_MAP.put("gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");
        LEGACY_BUILTIN_MAP.put("modelViewMatrix", "iris_ModelViewMatrix");
        LEGACY_BUILTIN_MAP.put("projectionMatrix", "iris_ProjectionMatrix");
        LEGACY_BUILTIN_MAP.put("modelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        LEGACY_BUILTIN_MAP.put("projectionMatrixInverse", "iris_ProjectionMatrixInverse");
        LEGACY_BUILTIN_MAP.put("gl_ModelViewProjectionMatrix", "(iris_ProjectionMatrix * iris_ModelViewMatrix)");
        LEGACY_BUILTIN_MAP.put("gl_NormalMatrix", "mat3(iris_NormalMat4)");
        LEGACY_BUILTIN_MAP.put("gl_TextureMatrix", "iris_TextureMatrix");
        LEGACY_BUILTIN_MAP.put("gl_Fog.color", "iris_FogColor.rgb");
        LEGACY_BUILTIN_MAP.put("gl_Fog.start", "iris_FogParams.x");
        LEGACY_BUILTIN_MAP.put("gl_Fog.end", "iris_FogParams.y");
        LEGACY_BUILTIN_MAP.put("gl_Fog.density", "iris_FogParams.z");
        LEGACY_BUILTIN_MAP.put("gl_Fog.scale", "(1.0 / max(iris_FogParams.y - iris_FogParams.x, 0.0001))");
    }

    /**
     * Remaps pack uniform declarations to UBO member expressions.
     */
    private static String remapUniforms(String source, TransformParams params) {
        // Process each uniform declaration
        java.util.regex.Matcher m = UNIFORM_DECL_PATTERN.matcher(source);
        StringBuffer sb = new StringBuffer();

        Set<String> replacedUniforms = new HashSet<>();

        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);

            // Skip sampler/image uniforms (handled by remapSamplers)
            if (type.startsWith("sampler") || type.startsWith("image")) {
                continue;
            }

            String uboExpr = UNIFORM_MAP.get(name);
            if (uboExpr != null) {
                // Remove the uniform declaration — it's now in the UBO
                m.appendReplacement(sb, "// [Vulkanium] " + name + " → " + uboExpr);
                replacedUniforms.add(name);
            }
        }
        m.appendTail(sb);
        String result = sb.toString();

        // Replace occurrences of the uniform name with the UBO expression
        for (String uniform : replacedUniforms) {
            String uboExpr = UNIFORM_MAP.get(uniform);
            if (uboExpr != null) {
                // Word-boundary replacement to avoid partial matches
                result = result.replaceAll("\\b" + Pattern.quote(uniform) + "\\b", uboExpr);
            }
        }

        // Convert remaining bare uniforms to const with zero default
        // (Vulkan GLSL forbids non-opaque uniforms outside of blocks)
        result = convertRemainingBareUniforms(result);

        return result;
    }

    private static String remapLegacyBuiltins(String source) {
        String result = GL_TEXTURE_MATRIX_INDEX_PATTERN.matcher(source)
            .replaceAll("iris_TextureMatrix");

        for (Map.Entry<String, String> entry : LEGACY_BUILTIN_MAP.entrySet()) {
            result = replaceSymbol(result, entry.getKey(), entry.getValue());
        }

        return result;
    }

    private static String injectFsrFallbackAliases(String source) {
        if (!source.contains("fsrScreenSize")
                && !source.contains("fsrPixelSize")
                && !source.contains("fsrRenderScale")
                && !source.contains("fsrJitter")
                && !source.contains("jitterRaw")
                && !source.contains("jitterSequenceLength")
                && !source.contains("fsrReconstructDepth2D")) {
            return source;
        }

        String fallback = """
            // ── Vulkanium FSR fallback aliases ──
            #ifndef fsrScreenSize
            #define fsrScreenSize vec2(iris_ScreenSize.x, iris_ScreenSize.y)
            #endif
            #ifndef fsrPixelSize
            #define fsrPixelSize (vec2(1.0) / max(vec2(iris_ScreenSize.x, iris_ScreenSize.y), vec2(1.0)))
            #endif
            #ifndef fsrRenderScale
            #define fsrRenderScale vec2(1.0)
            #endif
            #ifndef fsrJitter
            #define fsrJitter vec2(0.0)
            #endif
            #ifndef jitterRaw
            #define jitterRaw vec2(0.0)
            #endif
            #ifndef jitterSequenceLength
            #define jitterSequenceLength 1.0
            #endif
            #ifndef fsrReconstructDepth2D
            #define fsrReconstructDepth2D colortex2
            #endif
            """;

        return insertAfterUBO(source, fallback);
    }

        private static String applyCompatibilityFallbacks(String source) {
        String result = source;

        // Render stage constants — ordinals match Iris WorldRenderingPhase enum
        // Reference: net.irisshaders.iris.pipeline.WorldRenderingPhase (Iris, LGPL-3.0)
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_NONE", "0");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_SKY", "1");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_SUNSET", "2");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_CUSTOM_SKY", "3");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_SUN", "4");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_MOON", "5");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_STARS", "6");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_VOID", "7");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_TERRAIN_SOLID", "8");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_TERRAIN_CUTOUT", "10");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_ENTITIES", "11");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_BLOCK_ENTITIES", "12");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_DESTROY", "13");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_OUTLINE", "14");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_DEBUG", "15");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_HAND_SOLID", "16");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_TRIPWIRE", "18");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_PARTICLES", "19");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_CLOUDS", "20");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_RAIN_SNOW", "21");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_WORLD_BORDER", "22");
        result = replaceOutsideDeclarations(result, "MC_RENDER_STAGE_HAND_TRANSLUCENT", "23");

        result = replaceOutsideDeclarations(result,
            "fsrScreenSize", "vec2(iris_ScreenSize.x, iris_ScreenSize.y)");
        result = replaceOutsideDeclarations(result,
            "fsrPixelSize", "(vec2(1.0) / max(vec2(iris_ScreenSize.x, iris_ScreenSize.y), vec2(1.0)))");
        result = replaceOutsideDeclarations(result,
            "fsrRenderScale", "vec2(1.0)");
        result = replaceOutsideDeclarations(result,
            "fsrJitter", "vec2(0.0)");
        result = replaceOutsideDeclarations(result,
            "jitterRaw", "vec2(0.0)");
        result = replaceOutsideDeclarations(result,
            "jitterSequenceLength", "1.0");
        result = replaceOutsideDeclarations(result,
            "fsrReconstructDepth2D", "colortex2");
        result = replaceOutsideDeclarations(result,
            "iris_TaaJitter", "vec2(0.0)");

        return result;
        }

        private static String replaceOutsideDeclarations(String source, String symbol, String replacement) {
        Pattern declNamePattern = Pattern.compile(
                    "^\\s*(?:layout\\s*\\([^)]*\\)\\s*)*(?:uniform|const|in|out)\\s+"
                            + "(?:readonly\\s+|writeonly\\s+|coherent\\s+|volatile\\s+|restrict\\s+|highp\\s+|mediump\\s+|lowp\\s+)*"
                            + "[A-Za-z_][A-Za-z0-9_]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[[^\\]]*\\])?\\s*(?:=.*)?;\\s*$");

        String[] lines = source.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher declMatcher = declNamePattern.matcher(line);
            if (declMatcher.matches() && symbol.equals(declMatcher.group(1))) {
                continue;
            }
            lines[i] = replaceSymbol(line, symbol, replacement);
        }

        return String.join("\n", lines);
        }

    private static String replaceSymbol(String source, String from, String to) {
        return source.replaceAll(
                "(?<![A-Za-z0-9_])" + Pattern.quote(from) + "(?![A-Za-z0-9_])",
                Matcher.quoteReplacement(to));
    }

    /**
     * Converts any remaining non-sampler/non-image bare uniform declarations
     * to const with zero-initialized defaults.
     */
    private static String convertRemainingBareUniforms(String source) {
        Pattern bareUniform = Pattern.compile(
                "^(\\s*)uniform\\s+(int|float|vec[234]|ivec[234]|uvec[234]|mat[234]|mat[234]x[234]|bool)\\s+(\\w+)\\s*;",
                Pattern.MULTILINE);
        return bareUniform.matcher(source).replaceAll(matchResult -> {
            String indent = matchResult.group(1);
            String type = matchResult.group(2);
            String name = matchResult.group(3);
            String defaultVal = getZeroDefault(type);
            return indent + "const " + type + " " + name + " = " + defaultVal + ";  // [Vulkanium] bare uniform → const";
        });
    }

    private static String getZeroDefault(String type) {
        return switch (type) {
            case "float" -> "0.0";
            case "int" -> "0";
            case "uint" -> "0u";
            case "bool" -> "false";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            case "vec4" -> "vec4(0.0)";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            case "mat2" -> "mat2(0.0)";
            case "mat3" -> "mat3(0.0)";
            case "mat4" -> "mat4(0.0)";
            default -> type + "(0)";
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  Sampler Remapping
    // ═══════════════════════════════════════════════════════════════

    /**
     * Default sampler bindings for standard shader pack texture names.
     */
    private static final Map<String, Integer> DEFAULT_SAMPLER_BINDINGS = new LinkedHashMap<>();

    static {
        DEFAULT_SAMPLER_BINDINGS.put("gtexture", 0);
        DEFAULT_SAMPLER_BINDINGS.put("gcolor", 0);
        DEFAULT_SAMPLER_BINDINGS.put("texture", 0);
        DEFAULT_SAMPLER_BINDINGS.put("tex", 0);
        DEFAULT_SAMPLER_BINDINGS.put("lightmap", 1);
        DEFAULT_SAMPLER_BINDINGS.put("normals", 2);
        DEFAULT_SAMPLER_BINDINGS.put("specular", 3);
        DEFAULT_SAMPLER_BINDINGS.put("colortex0", 4);
        DEFAULT_SAMPLER_BINDINGS.put("colortex1", 5);
        DEFAULT_SAMPLER_BINDINGS.put("colortex2", 6);
        DEFAULT_SAMPLER_BINDINGS.put("colortex3", 7);
        DEFAULT_SAMPLER_BINDINGS.put("colortex4", 8);
        DEFAULT_SAMPLER_BINDINGS.put("colortex5", 9);
        DEFAULT_SAMPLER_BINDINGS.put("colortex6", 10);
        DEFAULT_SAMPLER_BINDINGS.put("colortex7", 11);
        DEFAULT_SAMPLER_BINDINGS.put("depthtex0", 12);
        DEFAULT_SAMPLER_BINDINGS.put("depthtex1", 13);
        DEFAULT_SAMPLER_BINDINGS.put("depthtex2", 14);
        DEFAULT_SAMPLER_BINDINGS.put("shadowtex0", 15);
        DEFAULT_SAMPLER_BINDINGS.put("shadowtex1", 16);
        DEFAULT_SAMPLER_BINDINGS.put("shadowcolor0", 17);
        DEFAULT_SAMPLER_BINDINGS.put("shadowcolor1", 18);
        DEFAULT_SAMPLER_BINDINGS.put("noisetex", 19);
        DEFAULT_SAMPLER_BINDINGS.put("iris_overlay", 20);
        // Extended color textures (bindings 21-28) — colortex8-15
        DEFAULT_SAMPLER_BINDINGS.put("colortex8", 21);
        DEFAULT_SAMPLER_BINDINGS.put("colortex9", 22);
        DEFAULT_SAMPLER_BINDINGS.put("colortex10", 23);
        DEFAULT_SAMPLER_BINDINGS.put("colortex11", 24);
        DEFAULT_SAMPLER_BINDINGS.put("colortex12", 25);
        DEFAULT_SAMPLER_BINDINGS.put("colortex13", 26);
        DEFAULT_SAMPLER_BINDINGS.put("colortex14", 27);
        DEFAULT_SAMPLER_BINDINGS.put("colortex15", 28);
        // Legacy aliases
        DEFAULT_SAMPLER_BINDINGS.put("shadow", 15);
        DEFAULT_SAMPLER_BINDINGS.put("watershadow", 15);
        DEFAULT_SAMPLER_BINDINGS.put("gdepth", 5);
        DEFAULT_SAMPLER_BINDINGS.put("gnormal", 6);
        DEFAULT_SAMPLER_BINDINGS.put("composite", 7);
        DEFAULT_SAMPLER_BINDINGS.put("gaux1", 8);
        DEFAULT_SAMPLER_BINDINGS.put("gaux2", 9);
        DEFAULT_SAMPLER_BINDINGS.put("gaux3", 10);
        DEFAULT_SAMPLER_BINDINGS.put("gaux4", 11);
        DEFAULT_SAMPLER_BINDINGS.put("gdepthtex", 12);
    }

    /**
     * Remaps sampler declarations to Vulkanium's current compatibility descriptor layout.
     *
     * <p>Current draw path exposes one descriptor set (set=0) with:</p>
     * <ul>
     *   <li>binding 0: dynamic UBO</li>
     *   <li>binding 1: combined image sampler</li>
     * </ul>
     * <p>All shaderpack samplers are currently aliased to binding 1 until full multi-sampler
     * descriptor plumbing is integrated.</p>
     */
    private static String remapSamplers(String source, TransformParams params) {
        // Merge default bindings with override bindings
        Map<String, Integer> bindings = new LinkedHashMap<>(DEFAULT_SAMPLER_BINDINGS);
        bindings.putAll(params.samplerBindings);

        java.util.regex.Matcher m = SAMPLER_DECL_PATTERN.matcher(source);
        StringBuffer sb = new StringBuffer();
        int nextAutoBinding = 32; // Auto-assign above known bindings

        while (m.find()) {
            String samplerType = m.group(1);
            String samplerName = m.group(2);

            Integer binding = bindings.get(samplerName);
            if (binding == null) {
                binding = nextAutoBinding++;
                LOGGER.debug("Auto-assigned binding {} to unknown sampler '{}'", binding, samplerName);
            }

                        int runtimeBinding = binding + 1; // binding 0 is reserved for Vulkanium UBO
                        String replacement = "layout(set = 0, binding = " + runtimeBinding + ") uniform "
                    + samplerType + " " + samplerName + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String remapped = sb.toString();
        return bindQualifiedSamplerImageUniforms(remapped, bindings, nextAutoBinding);
    }

    private static String bindQualifiedSamplerImageUniforms(String source,
                                                            Map<String, Integer> bindings,
                                                            int nextAutoBindingStart) {
        Pattern qualifiedPattern = Pattern.compile(
                "(?m)^(\\s*)((?:layout\\s*\\([^)]*\\)\\s*)*)uniform\\s+"
                        + "((?:(?:readonly|writeonly|coherent|volatile|restrict|const|highp|mediump|lowp)\\s+)*)"
                        + "((?:sampler\\w+|[ui]?image\\w+))\\s+(\\w+)\\s*;\\s*$");

        Matcher matcher = qualifiedPattern.matcher(source);
        StringBuffer out = new StringBuffer();
        int nextAutoBinding = nextAutoBindingStart;

        while (matcher.find()) {
            String indent = matcher.group(1) != null ? matcher.group(1) : "";
            String existingLayouts = matcher.group(2) != null ? matcher.group(2) : "";
            String qualifiers = matcher.group(3) != null ? matcher.group(3) : "";
            String type = matcher.group(4);
            String name = matcher.group(5);

            if (existingLayouts.contains("binding")) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            Integer binding = bindings.get(name);
            if (binding == null) {
                binding = nextAutoBinding++;
            }
                int runtimeBinding = binding + 1; // binding 0 is reserved for Vulkanium UBO

            String replacement = indent
                    + existingLayouts
                    + "layout(set = 0, binding = " + runtimeBinding + ") "
                    + "uniform "
                    + qualifiers
                    + type + " " + name + ";";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Vertex Transforms
    // ═══════════════════════════════════════════════════════════════

    /**
     * Transforms vertex shader for the appropriate pass type.
     */
    private static String transformVertex(String source, TransformParams params) {
        return switch (params.passType) {
            case TERRAIN, SHADOW -> transformTerrainVertex(source);
            case ENTITY, HAND -> transformEntityVertex(source);
            case PARTICLE -> transformParticleVertex(source);
            case SKY -> transformSkyVertex(source);
            case COMPOSITE -> transformCompositeVertex(source);
            default -> source;
        };
    }

    /**
     * Terrain vertex transform in compatibility mode: maps to MC block vertex format.
     */
    private static String transformTerrainVertex(String source) {
        String inputs = """
                // ── Vulkanium Terrain Vertex Inputs (compatibility layout) ──
                layout(location = 0) in vec3 vkm_Position;
                layout(location = 1) in vec2 vkm_TexCoord;
                layout(location = 2) in vec4 vkm_Color;
                layout(location = 3) in ivec2 vkm_LightCoord;
                layout(location = 4) in vec4 vkm_NormalPacked;
                
                // ── Decoded vertex variables ──
                vec4 iris_vk_Vertex;
                vec4 iris_vk_Color;
                vec2 iris_vk_TexCoord0;
                vec2 iris_vk_LightCoord;
                vec3 iris_compat_Normal;
                vec4 iris_compat_Tangent;
                vec2 iris_vk_MidTexCoord;
                int  iris_vk_EntityId;
                """;

        String decode = """
                // ── Vertex decode (start of main) ──
                void vkm_decodeVertex() {
                    iris_vk_Vertex = vec4(vkm_Position + iris_ChunkOffset.xyz, 1.0);
                    iris_vk_Color = vkm_Color;
                    iris_vk_TexCoord0 = vkm_TexCoord;
                    iris_vk_LightCoord = vec2(vkm_LightCoord) / 256.0;

                    vec3 rawNormal = vkm_NormalPacked.xyz;
                    float normalLen = length(rawNormal);
                    iris_compat_Normal = normalLen > 0.0001 ? normalize(rawNormal) : vec3(0.0, 1.0, 0.0);

                    vec3 tangentRef = abs(iris_compat_Normal.y) < 0.999
                            ? vec3(0.0, 1.0, 0.0)
                            : vec3(1.0, 0.0, 0.0);
                    vec3 tangent = normalize(cross(tangentRef, iris_compat_Normal));
                    iris_compat_Tangent = vec4(tangent, 1.0);

                    iris_vk_MidTexCoord = vkm_TexCoord;
                    iris_vk_EntityId = -1;
                }
                """;

        // Insert vertex inputs after UBO, decode function before main
        source = insertAfterUBO(source, inputs);
        source = insertDecodeCallAtMainStart(source, decode, "vkm_decodeVertex();");

        source = removeLegacyAttributeDeclarations(source,
            "at_tangent", "mc_Entity", "at_midBlock", "mc_midTexCoord");

        // Map GL builtins to decoded variables
        source = source.replaceAll("\\bgl_Vertex\\b", "iris_vk_Vertex");
        source = source.replaceAll("\\bgl_Color\\b", "iris_vk_Color");
        source = source.replaceAll("\\bgl_Normal\\b", "iris_compat_Normal");
        source = source.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(iris_vk_TexCoord0, 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord1\\b", "vec4(iris_vk_LightCoord, 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord2\\b", "vec4(iris_vk_LightCoord, 0.0, 1.0)");
        for (int i = 3; i <= 7; i++) {
            source = source.replaceAll("\\bgl_MultiTexCoord" + i + "\\b", "vec4(0.0)");
        }

        // Map pack-specific attributes
        source = source.replaceAll("\\bat_tangent\\b", "iris_compat_Tangent");
        source = source.replaceAll("\\bmc_Entity\\b", "vec4(float(iris_vk_EntityId), 0.0, 0.0, 0.0)");
        source = source.replaceAll("\\bat_midBlock\\b", "vec4(0.0)");
        source = source.replaceAll("\\bmc_midTexCoord\\b", "vec4(iris_vk_MidTexCoord, 0.0, 1.0)");

        // ftransform() replacement
        if (source.contains("ftransform")) {
            source = source.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "(iris_ProjectionMatrix * iris_ModelViewMatrix * iris_vk_Vertex)");
        }

        return source;
    }

    /**
     * Entity vertex transform: standard MC entity vertex format.
     */
    private static String transformEntityVertex(String source) {
        String inputs = """
                // ── Vulkanium Entity Vertex Inputs ──
                // Must match BasicPipeline.createAttributeDescriptions() order:
                // Position, UV0, Color, UV2(lightmap), Normal — UV1(overlay) skipped
                layout(location = 0) in vec3 vkm_Entity_Position;
                layout(location = 1) in vec2 vkm_Entity_UV0;
                layout(location = 2) in vec4 vkm_Entity_Color;
                layout(location = 3) in ivec2 vkm_Entity_UV2;
                layout(location = 4) in vec3 vkm_Entity_Normal;
                """;

        source = insertAfterUBO(source, inputs);

        source = removeLegacyAttributeDeclarations(source,
            "at_tangent", "mc_Entity", "at_midBlock", "mc_midTexCoord");

        source = source.replaceAll("\\bgl_Vertex\\b", "vec4(vkm_Entity_Position, 1.0)");
        source = source.replaceAll("\\bgl_Color\\b", "vkm_Entity_Color");
        source = source.replaceAll("\\bgl_Normal\\b", "vkm_Entity_Normal");
        source = source.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(vkm_Entity_UV0, 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord1\\b", "vec4(vec2(vkm_Entity_UV2), 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord2\\b", "vec4(vec2(vkm_Entity_UV2), 0.0, 1.0)");
        source = source.replaceAll("\\bvaPosition\\b", "vkm_Entity_Position");
        source = source.replaceAll("\\bvaNormal\\b", "vkm_Entity_Normal");

        source = source.replaceAll("\\bat_tangent\\b", "vec4(1.0, 0.0, 0.0, 1.0)");
        source = source.replaceAll("\\bmc_Entity\\b", "vec4(-1.0, 0.0, 0.0, 0.0)");
        source = source.replaceAll("\\bat_midBlock\\b", "vec4(0.0)");
        source = source.replaceAll("\\bmc_midTexCoord\\b", "vec4(0.0, 0.0, 0.0, 1.0)");

        if (source.contains("ftransform")) {
            source = source.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "(iris_ProjectionMatrix * iris_ModelViewMatrix * vec4(vkm_Entity_Position, 1.0))");
        }

        return source;
    }

    private static String removeLegacyAttributeDeclarations(String source, String... names) {
        String result = source;
        for (String name : names) {
            String pattern = "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:attribute|in)\\s+\\w+\\s+"
                    + Pattern.quote(name) + "\\s*;\\s*$";
            result = result.replaceAll(pattern, "");
        }
        return result;
    }

    /**
     * Particle vertex transform.
     */
    private static String transformParticleVertex(String source) {
        String inputs = """
                // ── Vulkanium Particle Vertex Inputs ──
                layout(location = 0) in vec3 vkm_Particle_Position;
                layout(location = 1) in vec2 vkm_Particle_UV0;
                layout(location = 2) in vec4 vkm_Particle_Color;
                layout(location = 3) in ivec2 vkm_Particle_UV2;
                """;

        source = insertAfterUBO(source, inputs);

        source = source.replaceAll("\\bgl_Vertex\\b", "vec4(vkm_Particle_Position, 1.0)");
        source = source.replaceAll("\\bgl_Color\\b", "vkm_Particle_Color");
        source = source.replaceAll("\\bgl_Normal\\b", "vec3(0.0, 1.0, 0.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(vkm_Particle_UV0, 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord1\\b",
                "vec4(vec2(vkm_Particle_UV2), 0.0, 1.0)");

        return source;
    }

    /**
     * Sky vertex transform.
     * <p>
     * MC sky draws use two different vertex formats:
     *   - {@code DefaultVertexFormat.POSITION} — sky dome (no Color data, 12-byte stride)
     *   - {@code DefaultVertexFormat.POSITION_COLOR} — sunset/sunrise gradient (has Color)
     * Both map to {@code gbuffers_skybasic}, so the compiled shader must handle
     * POSITION-only safely.  We do NOT declare a Color vertex input because it
     * would read garbage when Color data is absent.  Instead, {@code gl_Color} is
     * replaced with the {@code iris_ColorModulator} UBO uniform, which MC sets to
     * the correct sky tint per draw.  (Iris solves this with two separate
     * ShaderKey entries; our single-shader approach sacrifices per-vertex sky
     * gradient colour for a correct and stable sky dome.)
     * </p>
     */
    private static String transformSkyVertex(String source) {
        String inputs = """
                // ── Vulkanium Sky Vertex Inputs ──
                // Position is always present. UV0 is present for skytextured
                // draws (sun/moon) and absent for basic sky dome draws.
                // When absent, reading location 1 yields default/undefined values
                // that are harmless for shaders that do not consume UV0.
                layout(location = 0) in vec3 vkm_Sky_Position;
                layout(location = 1) in vec2 vkm_Sky_TexCoord;
                """;

        source = insertAfterUBO(source, inputs);

        source = source.replaceAll("\\bgl_Vertex\\b", "vec4(vkm_Sky_Position, 1.0)");
        // Use ColorModulator uniform instead of vertex Color to avoid reading
        // garbage from an unbound attribute (Iris equivalent: ColorModulator
        // fallback when inputs.hasColor() == false).
        source = source.replaceAll("\\bgl_Color\\b", "iris_ColorModulator");
        source = source.replaceAll("\\bgl_Normal\\b", "vec3(0.0, 1.0, 0.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(vkm_Sky_TexCoord, 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord1\\b", "vec4(1.0, 1.0, 0.0, 1.0)");

        if (source.contains("ftransform")) {
            source = source.replaceAll("\\bftransform\\s*\\(\\s*\\)",
                    "(iris_ProjectionMatrix * iris_ModelViewMatrix * vec4(vkm_Sky_Position, 1.0))");
        }

        return source;
    }

    /**
     * Composite vertex transform: fullscreen triangle from gl_VertexIndex.
     */
    private static String transformCompositeVertex(String source) {
        // ── Composite fullscreen triangle: Iris-compatible [0,1] convention ──
        //
        // Iris (OpenGL) provides composite vertex data in [0,1] range:
        //   gl_Vertex = vec4(Position, 1.0)  where Position.xy ∈ [0,1]
        //   gl_MultiTexCoord0 = vec4(UV0, 0, 1)  where UV0 ∈ [0,1]
        //   gl_ProjectionMatrix = scale+translate mapping [0,1] → [-1,1] NDC
        //
        // In Iris on OpenGL:
        //   vertex (0,0) → NDC (-1,-1) → screen bottom-left → texcoord (0,0)
        //   vertex (1,1) → NDC (+1,+1) → screen top-right   → texcoord (1,1)
        //
        // With POSITIVE viewport (Vulkanium):
        //   NDC (-1,-1) → fb row 0 (top of Vulkan image) → V=0
        //   NDC (+1,+1) → fb row H (bottom of image)     → V=1
        //   G-buffer stores: V=0 → NDC y=-1 (ground), V=1 → NDC y=+1 (sky)
        //   This matches OpenGL texture convention (V=0 = bottom of scene)!
        //
        // Therefore depth reconstruction (texcoord * 2.0 - 1.0) correctly maps
        //   texcoord 0 → clip.y=-1 (ground direction) ← matches V=0 content
        //   texcoord 1 → clip.y=+1 (sky direction)    ← matches V=1 content
        //
        // The composite output framebuffer has sky at bottom and ground at top
        // (from Vulkan's display perspective), corrected by a Y-flip blit to
        // the swapchain at the end of the fullscreen pass chain.
        //
        // Reference: Iris Shaders (LGPL-3.0) CompositeTransformer
        String compute = """
                // ── Vulkanium Composite Fullscreen Triangle ──
                // Provides gl_Vertex in [0,1] matching Iris convention.
                // Reference: Iris Shaders (LGPL-3.0) CompositeTransformer
                vec2 vkm_composite_TexCoord;
                vec4 vkm_composite_ClipPos;
                vec4 vkm_composite_Position() {
                    float x_ndc = -1.0 + float((gl_VertexIndex & 1) << 2);
                    float y_ndc = -1.0 + float((gl_VertexIndex & 2) << 1);
                    float u = x_ndc * 0.5 + 0.5;
                    float v = y_ndc * 0.5 + 0.5;
                    vkm_composite_TexCoord = vec2(u, v);
                    vkm_composite_ClipPos = vec4(x_ndc, y_ndc, 0.0, 1.0);
                    return vec4(u, v, 0.0, 1.0);
                }
                """;

        source = insertAfterUBO(source, compute);

        source = source.replaceAll("\\bgl_Vertex\\b", "vkm_composite_Position()");
        source = source.replaceAll("\\bgl_Color\\b", "vec4(1.0)");
        source = source.replaceAll("\\bgl_Normal\\b", "vec3(0.0, 0.0, 1.0)");
        source = source.replaceAll("\\bgl_MultiTexCoord0\\b",
                "vec4(vkm_composite_TexCoord, 0.0, 1.0)");

        source = source.replaceAll("(?m)^\\s*in\\s+vec3\\s+vaPosition\\s*;",
            "layout(location = 0) in vec3 vaPosition;");
        source = source.replaceAll("\\bgl_MultiTexCoord1\\b",
                "vec4(vkm_composite_TexCoord, 0.0, 1.0)");

        if (source.contains("ftransform")) {
            source = source.replaceAll("\\bftransform\\s*\\(\\s*\\)", "vkm_composite_Position()");
        }

        // Force gl_Position to the procedural clip-space position at the
        // very end of main().  This overrides any
        //   gl_Position = iris_ProjectionMatrix * iris_ModelViewMatrix * ...
        // that the original shader may compute, which would distort the
        // fullscreen triangle through the camera matrices.
        source = injectAtEndOfMain(source,
                "    gl_Position = vkm_composite_ClipPos; // [Vulkanium] force fullscreen clip pos");

        return source;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fragment Transforms
    // ═══════════════════════════════════════════════════════════════

    private static String transformFragment(String source, TransformParams params) {
        // gl_FogFragCoord — inject as varying input if used
        if (source.contains("gl_FogFragCoord")) {
            source = insertAfterUBO(source,
                    "float iris_FogFragCoord = 0.0; // [Vulkanium] legacy fog coord\n");
        }

        return source;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility Methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Inserts code after the #define block (after preprocessor directives).
     */
    private static String insertAfterDefines(String source, String code) {
        // Find last preprocessor directive or comment block at start
        String[] lines = source.split("\\n", -1);
        int insertLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                insertLine = i + 1;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < insertLine; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("\n").append(code).append("\n");
        for (int i = insertLine; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Inserts code after the VulkaniumUniforms UBO block.
     */
    private static String insertAfterUBO(String source, String code) {
        // Find end of VulkaniumUniforms block (after closing };)
        int uboEnd = source.indexOf("};", source.indexOf("VulkaniumUniforms"));
        if (uboEnd < 0) {
            // Fallback: insert after defines
            return insertAfterDefines(source, code);
        }
        uboEnd = source.indexOf('\n', uboEnd) + 1;
        return source.substring(0, uboEnd) + "\n" + code + "\n" + source.substring(uboEnd);
    }

    /**
     * Inserts a decode function and a call to it at the start of main().
     */
    private static String insertDecodeCallAtMainStart(String source, String function, String call) {
        // Insert function before main
        int mainIdx = source.indexOf("void main()");
        if (mainIdx < 0) mainIdx = source.indexOf("void main(void)");
        if (mainIdx < 0) return source;

        source = source.substring(0, mainIdx) + function + "\n" + source.substring(mainIdx);

        // Insert call at start of main body
        int mainBody = source.indexOf('{', source.indexOf("void main()"));
        if (mainBody < 0) mainBody = source.indexOf('{', source.indexOf("void main(void)"));
        if (mainBody >= 0) {
            source = source.substring(0, mainBody + 1) + "\n    " + call + "\n" + source.substring(mainBody + 1);
        }

        return source;
    }

    /**
     * Injects a code statement just before the closing brace of main().
     * Used by composite vertex shaders to override gl_Position at the end.
     */
    private static String injectAtEndOfMain(String source, String code) {
        // Find "void main()" or "void main(void)"
        int mainIdx = source.indexOf("void main()");
        if (mainIdx < 0) mainIdx = source.indexOf("void main(void)");
        if (mainIdx < 0) return source;

        // Find the opening brace of main()
        int openBrace = source.indexOf('{', mainIdx);
        if (openBrace < 0) return source;

        // Walk to the matching closing brace
        int depth = 1;
        int i = openBrace + 1;
        while (i < source.length() && depth > 0) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return source; // Unbalanced braces

        // i now points right after the closing brace; insert before it
        int closeBrace = i - 1;
        return source.substring(0, closeBrace) + "\n" + code + "\n" + source.substring(closeBrace);
    }
}
