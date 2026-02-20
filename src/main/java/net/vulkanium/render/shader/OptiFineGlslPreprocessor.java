package net.vulkanium.render.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 1: Text-level OptiFine GLSL pre-processor — runs BEFORE any AST parsing.
 *
 * <p>Handles constructs that are not valid GLSL 450 and would cause ANTLR parse
 * errors if not stripped/rewritten first. This is a surgical text-level transform,
 * NOT a replacement for the AST transformer.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Detect and strip {@code #version NNN compatibility} → save version info</li>
 *   <li>Parse {@code /* DRAWBUFFERS:0123 *}{@code /} and {@code /* RENDERTARGETS:0,1,2 *}{@code /}
 *       → extract target list, remove from source</li>
 *   <li>Parse {@code const int} directives (shadow resolution, buffer formats, etc.)</li>
 *   <li>Strip unsupported {@code #extension} directives</li>
 *   <li>Replace {@code gl_FragData[N]} with named outputs (before AST, since array
 *       indexing on built-in outputs isn't valid GLSL 450)</li>
 *   <li>Handle {@code #ifdef MC_*} feature defines for Vulkanium capabilities</li>
 *   <li>Detect alpha test references and extract alpha function/reference</li>
 * </ul>
 *
 * <h3>Why text-level?</h3>
 * <p>ANTLR's GLSL 450 parser cannot handle:</p>
 * <ul>
 *   <li>{@code #version 120 compatibility} (not a valid GLSL 450 version string)</li>
 *   <li>{@code gl_FragData[0]} (removed built-in in core profile)</li>
 *   <li>Comments with semantic meaning (DRAWBUFFERS directives) that must be
 *       parsed before the preprocessor runs</li>
 * </ul>
 *
 * <p>After this stage, the source is valid enough for ANTLR parsing in Stage 2.</p>
 */
public class OptiFineGlslPreprocessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GlslPreprocessor");

    // ── Pattern: #version NNN [profile] ──
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^\\s*#\\s*version\\s+(\\d+)(?:\\s+(compatibility|core|es))?",
                    Pattern.MULTILINE);

    // ── Pattern: /* DRAWBUFFERS:XXXX */ (digits packed, no separators) ──
    private static final Pattern DRAWBUFFERS_PATTERN =
            Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9]+)\\s*\\*/");

    // ── Pattern: /* RENDERTARGETS: 0,1,2,3 */ (comma-separated) ──
    private static final Pattern RENDERTARGETS_PATTERN =
            Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+)\\s*\\*/");

    // ── Pattern: gl_FragData[N] where N is a literal integer ──
    private static final Pattern FRAG_DATA_PATTERN =
            Pattern.compile("gl_FragData\\s*\\[\\s*(\\d+)\\s*]");

    // ── Pattern: const int directiveNameNN = value; ──
    private static final Pattern CONST_INT_PATTERN =
            Pattern.compile("^\\s*const\\s+int\\s+(\\w+)\\s*=\\s*(-?\\d+)\\s*;",
                    Pattern.MULTILINE);

    // ── Pattern: const float directiveName = value; ──
    private static final Pattern CONST_FLOAT_PATTERN =
            Pattern.compile("^\\s*const\\s+float\\s+(\\w+)\\s*=\\s*(-?[\\d.eE+-]+f?)\\s*;",
                    Pattern.MULTILINE);

    // ── Pattern: #extension GL_XXX : enable/require/disable ──
    private static final Pattern EXTENSION_PATTERN =
            Pattern.compile("^\\s*#\\s*extension\\s+(\\w+)\\s*:\\s*(\\w+)",
                    Pattern.MULTILINE);

    // ── Pattern: gl_FragColor (legacy single-output mode) ──
    private static final Pattern FRAG_COLOR_PATTERN =
            Pattern.compile("\\bgl_FragColor\\b");

        // ── Pattern: #define NAME ... and #undef NAME ──
        private static final Pattern DEFINE_NAME_PATTERN =
            Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
        private static final Pattern UNDEF_NAME_PATTERN =
            Pattern.compile("^\\s*#\\s*undef\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");

    // ── Supported extensions in Vulkan (kept) ──
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "GL_EXT_gpu_shader4",           // Integer textures, bitwise ops (emulated)
            "GL_ARB_explicit_attrib_location",
            "GL_ARB_shader_texture_lod",
            "GL_EXT_texture_array",
            "GL_ARB_gpu_shader5",
            "GL_ARB_shading_language_420pack",
            "GL_ARB_shading_language_packing"
    );

    // ── Known pack directives (const int/float with special meaning) ──
    private static final Set<String> KNOWN_DIRECTIVES = Set.of(
            "shadowMapResolution",
            "shadowDistance",
            "shadowDistanceRenderMul",
            "shadowIntervalSize",
            "generateShadowMipmap",
            "generateShadowColorMipmap",
            "shadowHardwareFiltering",
            "shadowHardwareFiltering0",
            "shadowHardwareFiltering1",
            "shadowtexMipmap",
            "shadowcolor0Mipmap",
            "shadowcolor1Mipmap",
            "wetnessHalflife",
            "drynessHalflife",
            "eyeBrightnessHalflife",
            "centerDepthHalflife",
            "noiseTextureResolution",
            "sunPathRotation",
            "ambientOcclusionLevel",
            "superSamplingLevel",
            // Buffer format directives
            "colortex0Format", "colortex1Format", "colortex2Format", "colortex3Format",
            "colortex4Format", "colortex5Format", "colortex6Format", "colortex7Format",
            "shadowcolor0Format", "shadowcolor1Format",
            // Clear control
            "colortex0Clear", "colortex1Clear", "colortex2Clear", "colortex3Clear",
            "colortex4Clear", "colortex5Clear", "colortex6Clear", "colortex7Clear"
    );

    /**
     * Result of pre-processing a single GLSL source string.
     */
    public static class PreprocessResult {
        /** Cleaned source ready for AST parsing */
        public final String source;
        /** Original GLSL version number (e.g., 120, 330, 450) */
        public final int glslVersion;
        /** Original profile (null, "compatibility", "core", "es") */
        public final String profile;
        /** Render targets from DRAWBUFFERS or RENDERTARGETS comments */
        public final int[] renderTargets;
        /** Extracted const int/float pack directives */
        public final Map<String, Number> packDirectives;
        /** Maximum gl_FragData index used (for output declaration count) */
        public final int maxFragDataIndex;
        /** Whether gl_FragColor was used (single-output mode) */
        public final boolean usesFragColor;
        /** Stripped extension names (for diagnostic) */
        public final List<String> strippedExtensions;

        PreprocessResult(String source, int glslVersion, String profile,
                         int[] renderTargets, Map<String, Number> packDirectives,
                         int maxFragDataIndex, boolean usesFragColor,
                         List<String> strippedExtensions) {
            this.source = source;
            this.glslVersion = glslVersion;
            this.profile = profile;
            this.renderTargets = renderTargets;
            this.packDirectives = packDirectives;
            this.maxFragDataIndex = maxFragDataIndex;
            this.usesFragColor = usesFragColor;
            this.strippedExtensions = strippedExtensions;
        }
    }

    /**
     * Pre-process a single GLSL shader source.
     *
     * @param source   Raw GLSL source from the shader pack
     * @param isVertex true if this is a vertex shader (skips fragment-specific transforms)
     * @return Preprocessed result with cleaned source and extracted metadata
     */
    public static PreprocessResult preprocess(String source, boolean isVertex) {
        int glslVersion = 120; // Default for packs that omit #version
        String profile = null;
        int maxFragDataIndex = -1;
        boolean usesFragColor = false;
        Map<String, Number> directives = new LinkedHashMap<>();
        List<String> strippedExtensions = new ArrayList<>();

        StringBuilder sb = new StringBuilder(source.length());
        String[] lines = source.split("\\n", -1);

        // ── Pass 1: Extract metadata and rewrite lines ──
        int[] renderTargets = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // ── #version directive ──
            Matcher versionMatch = VERSION_PATTERN.matcher(line);
            if (versionMatch.find()) {
                glslVersion = Integer.parseInt(versionMatch.group(1));
                profile = versionMatch.group(2);
                // Replace with #version 450 core (Vulkan target)
                sb.append("#version 450 core\n");
                continue;
            }

            // ── #extension directives ──
            Matcher extMatch = EXTENSION_PATTERN.matcher(line);
            if (extMatch.find()) {
                String extName = extMatch.group(1);
                if (!SUPPORTED_EXTENSIONS.contains(extName)) {
                    strippedExtensions.add(extName);
                    sb.append("// [Vulkanium] Stripped unsupported extension: ").append(extName).append("\n");
                    continue;
                }
                // Keep supported extensions but comment them (Vulkan GLSL has these built-in)
                sb.append("// [Vulkanium] Extension built-in: ").append(line.trim()).append("\n");
                continue;
            }

            // ── DRAWBUFFERS comment ──
            Matcher dbMatch = DRAWBUFFERS_PATTERN.matcher(line);
            if (dbMatch.find()) {
                String digits = dbMatch.group(1);
                renderTargets = new int[digits.length()];
                for (int j = 0; j < digits.length(); j++) {
                    renderTargets[j] = digits.charAt(j) - '0';
                }
                sb.append("// [Vulkanium] DRAWBUFFERS → targets: ").append(Arrays.toString(renderTargets)).append("\n");
                continue;
            }

            // ── RENDERTARGETS comment ──
            Matcher rtMatch = RENDERTARGETS_PATTERN.matcher(line);
            if (rtMatch.find()) {
                String[] parts = rtMatch.group(1).split("[,\\s]+");
                List<Integer> targets = new ArrayList<>();
                for (String part : parts) {
                    part = part.trim();
                    if (!part.isEmpty()) {
                        targets.add(Integer.parseInt(part));
                    }
                }
                renderTargets = targets.stream().mapToInt(Integer::intValue).toArray();
                sb.append("// [Vulkanium] RENDERTARGETS → targets: ").append(Arrays.toString(renderTargets)).append("\n");
                continue;
            }

            // ── const int/float directives ──
            Matcher constIntMatch = CONST_INT_PATTERN.matcher(line);
            if (constIntMatch.find()) {
                String name = constIntMatch.group(1);
                if (KNOWN_DIRECTIVES.contains(name)) {
                    directives.put(name, Integer.parseInt(constIntMatch.group(2)));
                }
            }
            Matcher constFloatMatch = CONST_FLOAT_PATTERN.matcher(line);
            if (constFloatMatch.find()) {
                String name = constFloatMatch.group(1);
                if (KNOWN_DIRECTIVES.contains(name)) {
                    String val = constFloatMatch.group(2).replaceAll("f$", "");
                    directives.put(name, Float.parseFloat(val));
                }
            }

            sb.append(line).append("\n");
        }

        String processed = sb.toString();

        // ── Pass 2: gl_FragData[N] → named outputs (fragment only) ──
        if (!isVertex) {
            final int[] resolvedRenderTargets = renderTargets;
            Matcher fragDataMatch = FRAG_DATA_PATTERN.matcher(processed);
            maxFragDataIndex = -1;
            while (fragDataMatch.find()) {
                int idx = Integer.parseInt(fragDataMatch.group(1));
                int mappedTarget = mapFragDataTarget(idx, resolvedRenderTargets);
                maxFragDataIndex = Math.max(maxFragDataIndex, mappedTarget);
            }
            // Replace gl_FragData[N] with iris_FragDataN
            processed = FRAG_DATA_PATTERN.matcher(processed).replaceAll(
                    matchResult -> {
                        int idx = Integer.parseInt(matchResult.group(1));
                        int mappedTarget = mapFragDataTarget(idx, resolvedRenderTargets);
                        return "iris_FragData" + mappedTarget;
                    });

            // Check for gl_FragColor (single-output legacy mode)
            usesFragColor = FRAG_COLOR_PATTERN.matcher(processed).find();
            if (usesFragColor) {
                int mappedTarget0 = mapFragDataTarget(0, resolvedRenderTargets);
                processed = FRAG_COLOR_PATTERN.matcher(processed).replaceAll("iris_FragData" + mappedTarget0);
                maxFragDataIndex = Math.max(maxFragDataIndex, mappedTarget0);
            }
        }

        // ── Pass 3: Inject Vulkanium capability defines ──
        // Insert after #version line
        int versionEnd = processed.indexOf('\n') + 1;
        String defines = buildCapabilityDefines();
        processed = processed.substring(0, versionEnd) + defines + processed.substring(versionEnd);

        // ── Pass 4: Make repeated macro overrides explicit (#undef before re-#define) ──
        // Shaderpacks frequently redefine option-style macros through nested includes.
        // Some drivers/shaderc paths treat this as hard error if substitution differs.
        // We preserve intended "last definition wins" behavior by inserting #undef
        // immediately before duplicate #define lines.
        processed = normalizeMacroRedefinitions(processed);

        // Default to [0] if no render targets specified
        if (renderTargets == null && maxFragDataIndex >= 0) {
            renderTargets = new int[maxFragDataIndex + 1];
            for (int i = 0; i <= maxFragDataIndex; i++) {
                renderTargets[i] = i;
            }
        }
        if (renderTargets == null) {
            renderTargets = new int[]{0};
        }

        return new PreprocessResult(processed, glslVersion, profile, renderTargets,
                directives, maxFragDataIndex, usesFragColor, strippedExtensions);
    }

    private static String normalizeMacroRedefinitions(String source) {
        String[] lines = source.split("\\n", -1);
        StringBuilder out = new StringBuilder(source.length() + 256);
        Set<String> defined = new HashSet<>();

        for (String line : lines) {
            Matcher undef = UNDEF_NAME_PATTERN.matcher(line);
            if (undef.find()) {
                defined.remove(undef.group(1));
                out.append(line).append('\n');
                continue;
            }

            Matcher define = DEFINE_NAME_PATTERN.matcher(line);
            if (define.find()) {
                String name = define.group(1);
                if (!name.startsWith("GL_") && !name.startsWith("__") && defined.contains(name)) {
                    out.append("#undef ").append(name).append('\n');
                    defined.remove(name);
                }
                defined.add(name);
            }

            out.append(line).append('\n');
        }

        return out.toString();
    }

    /**
     * Builds the Vulkanium capability #define block.
     *
     * <p>These defines are injected after #version so that pack shaders can use
     * {@code #ifdef} to detect Vulkanium capabilities. This is similar to Iris's
     * environment defines but extended for Vulkan-native features.</p>
     */
    private static String buildCapabilityDefines() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("// ── Vulkanium Capability Defines ──\n");

        // MC version and GLSL version defines
        sb.append("#define MC_VERSION 12001\n");       // 1.20.1
        sb.append("#define MC_GL_VERSION 330\n");
        sb.append("#define MC_GLSL_VERSION 330\n");

        // Standard Iris/OptiFine defines
        sb.append("#define MC_RENDER_QUALITY 1.0\n");
        sb.append("#define MC_SHADOW_QUALITY 1.0\n");
        sb.append("#define MC_NORMAL_MAP 1\n");
        sb.append("#define MC_SPECULAR_MAP 1\n");
        sb.append("#define MC_ANISOTROPIC_FILTERING 0\n");
        sb.append("#define MC_HAND_DEPTH 0.125\n");
        sb.append("#define MC_GL_VENDOR_OTHER 0\n");

        // Vulkanium-specific
        sb.append("#define VULKANIUM 1\n");
        sb.append("#define VULKANIUM_VERTEX_FORMAT 1\n"); // Signals 32-byte format with normals/tangents
        sb.append("#define VULKANIUM_MRT 1\n");           // Signals native MRT support
        sb.append("#define VULKANIUM_COMPUTE 1\n");       // Signals compute shader support
        sb.append("#define VULKANIUM_SPIRV 1\n");         // Signals SPIR-V compilation path

        // Vendor detection (populated at runtime)
        sb.append("// Vendor detection populated by uniform bridge\n");

        return sb.toString();
    }

    private static int mapFragDataTarget(int index, int[] renderTargets) {
        if (renderTargets != null && index >= 0 && index < renderTargets.length) {
            return renderTargets[index];
        }
        return index;
    }

    /**
     * Post-processes a pre-processed fragment shader to inject output declarations.
     *
     * <p>After the AST transformer runs, this injects the GLSL 450 output variable
     * declarations ({@code layout(location=N) out vec4 iris_FragDataN;}) that
     * replace the legacy {@code gl_FragData[N]} array.</p>
     *
     * @param source        AST-transformed source
     * @param renderTargets Target indices from DRAWBUFFERS/RENDERTARGETS
     * @return Source with output declarations injected
     */
    /**
     * Injects fragment output declarations for single-attachment rendering.
     *
     * <p>When Vulkanium only has one color attachment (the swapchain image),
     * only the first render target (index 0 in the DRAWBUFFERS array) is a real
     * {@code out} variable.  All other targets are declared as plain {@code vec4}
     * variables so the shader compiles but their writes are discarded.</p>
     *
     * <p>This avoids the Vulkan validation issue of declaring more fragment
     * outputs than the render pass has color attachments, while letting the
     * shader's MRT code compile unchanged.</p>
     *
     * @param source        AST-transformed source
     * @param renderTargets Target indices from DRAWBUFFERS/RENDERTARGETS
     * @return Source with output/dummy declarations injected
     */
    public static String injectFragmentOutputsSingleAttachment(String source, int[] renderTargets) {
        if (renderTargets == null || renderTargets.length == 0) {
            return source;
        }

        boolean hasIrisFragDataRefs = source.contains("iris_FragData");
        Pattern explicitOutPattern = Pattern.compile(
                "(?m)^\\s*layout\\s*\\([^)]*location\\s*=\\s*\\d+[^)]*\\)\\s*out\\s+\\w+\\s+\\w+\\s*;");
        boolean hasExplicitLocationOutputs = explicitOutPattern.matcher(source).find();

        if (hasExplicitLocationOutputs && !hasIrisFragDataRefs) {
            return source;
        }

        StringBuilder outputs = new StringBuilder();
        outputs.append("// ── Vulkanium Fragment Outputs (single-attachment mode) ──\n");

        // Determine which target index maps to the real output (colortex0 if present, else first)
        int realOutputIdx = 0;
        for (int i = 0; i < renderTargets.length; i++) {
            if (renderTargets[i] == 0) { realOutputIdx = i; break; }
        }

        for (int i = 0; i < renderTargets.length; i++) {
            int target = renderTargets[i];
            if (i == realOutputIdx) {
                // Real fragment output — goes to the single color attachment
                outputs.append("layout(location = 0) out vec4 iris_FragData")
                        .append(target).append(";\n");
            } else {
                // Dummy local variable — shader writes compile but data is discarded
                outputs.append("vec4 iris_FragData").append(target)
                        .append(" = vec4(0.0); // dummy (no attachment)\n");
            }
        }

        int insertPos = findInsertionPoint(source);
        return source.substring(0, insertPos) + outputs + source.substring(insertPos);
    }

    public static String injectFragmentOutputs(String source, int[] renderTargets) {
        if (renderTargets == null || renderTargets.length == 0) {
            return source;
        }

        boolean hasIrisFragDataRefs = source.contains("iris_FragData");
        Pattern explicitOutPattern = Pattern.compile(
                "(?m)^\\s*layout\\s*\\([^)]*location\\s*=\\s*\\d+[^)]*\\)\\s*out\\s+\\w+\\s+\\w+\\s*;");
        boolean hasExplicitLocationOutputs = explicitOutPattern.matcher(source).find();

        // If shader already declares explicit location-based fragment outputs and
        // does not reference iris_FragDataN, don't inject extra outputs (avoids
        // 'overlapping use of location N' errors on packs using modern explicit outs).
        if (hasExplicitLocationOutputs && !hasIrisFragDataRefs) {
            return source;
        }

        StringBuilder outputs = new StringBuilder();
        outputs.append("// ── Vulkanium Fragment Outputs ──\n");
        for (int i = 0; i < renderTargets.length; i++) {
            int target = renderTargets[i];
            // Use the colortex index as the layout location so it maps directly
            // to the MRT render pass subpass color attachment reference at that index.
            // The render pass uses VK_ATTACHMENT_UNUSED for gap indices.
            outputs.append("layout(location = ").append(target)
                    .append(") out vec4 iris_FragData").append(target).append(";\n");
        }

        // Insert after #version and defines block
        // Find the end of the defines section (look for first non-#define, non-comment, non-empty line)
        int insertPos = findInsertionPoint(source);
        return source.substring(0, insertPos) + outputs + source.substring(insertPos);
    }

    private static int findInsertionPoint(String source) {
        int pos = 0;
        String[] lines = source.split("\\n", -1);
        for (String line : lines) {
            pos += line.length() + 1;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            // Found first non-preprocessor, non-comment line
            return pos - line.length() - 1;
        }
        return pos;
    }
}
