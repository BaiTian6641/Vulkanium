package net.vulkanium.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL preprocessor for shaderpack shaders.
 *
 * <p>Handles directives that standard GLSL compilers don't process,
 * matching the behavior expected by OptiFine/Iris shaderpacks:</p>
 * <ul>
 *   <li>{@code #include "path"} — File inclusion (relative to shaders/)</li>
 *   <li>{@code #define MC_VERSION XXXXX} — Injects Minecraft version constant</li>
 *   <li>{@code #define MC_GL_VERSION 330} — GL compatibility version</li>
 *   <li>{@code #define MC_RENDER_QUALITY 1.0} — Render quality</li>
 *   <li>{@code #ifdef VULKANIUM} — Vulkanium-specific defines (always true)</li>
 *   <li>{@code #version} validation and upgrade for Vulkan SPIR-V compatibility</li>
 * </ul>
 *
 * <h3>Include Resolution</h3>
 * <p>Includes are resolved relative to the shaderpack's {@code shaders/} directory.
 * Both {@code #include "path"} and {@code #include "/path"} are supported.
 * Circular includes are detected and produce a warning.</p>
 */
public class GlslPreprocessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GlslPreprocess");

    /** Pattern for #include "path" directives */
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "^\\s*#\\s*include\\s+\"([^\"]+)\"\\s*$", Pattern.MULTILINE);

    /** Pattern for #version directive */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^\\s*#\\s*version\\s+(\\d+)(\\s+\\w+)?\\s*$", Pattern.MULTILINE);

    /** Maximum include depth to prevent infinite recursion */
    private static final int MAX_INCLUDE_DEPTH = 32;

    /** MC version as an integer (1.20.1 → 12001) — used by OptiFineGlslPreprocessor */
    static final int MC_VERSION_INT = 12001;

    private final ShaderpackSource source;

    public GlslPreprocessor(ShaderpackSource source) {
        this.source = source;
    }

    /**
     * Preprocesses a shader source string.
     *
     * @param glsl     Raw GLSL source
     * @param filename Source filename for error reporting
     * @return Preprocessed GLSL with includes resolved and defines injected
     */
    public String preprocess(String glsl, String filename) {
        return preprocess(glsl, filename, Collections.emptyMap());
    }

    public String preprocess(String glsl, String filename, Map<String, String> optionOverrides) {
        if (glsl == null || glsl.isEmpty()) return glsl;

        // 1. Resolve #include directives
        Set<String> includeStack = new HashSet<>();
        includeStack.add(filename);
        String resolved = resolveIncludes(glsl, filename, includeStack, 0);

        // 2. Apply queued shaderpack option overrides (Iris-style #define value overrides)
        if (optionOverrides != null && !optionOverrides.isEmpty()) {
            resolved = applyOptionOverrides(resolved, optionOverrides);
        }

        // 3. Ensure #version is present and >= 330
        //    Note: standard defines (#define VULKANIUM, MC_VERSION, etc.) are injected
        //    by OptiFineGlslPreprocessor.buildCapabilityDefines() to avoid duplication
        resolved = validateVersion(resolved);

        return resolved;
    }

    private String applyOptionOverrides(String glsl, Map<String, String> overrides) {
        String updated = glsl;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) continue;

            String replacement = "#define " + key + " " + value;
            updated = updated.replaceAll("(?m)^\\s*#\\s*define\\s+" + Pattern.quote(key) + "\\b.*$", replacement);
        }
        return updated;
    }

    /**
     * Recursively resolves #include directives.
     */
    private String resolveIncludes(String glsl, String currentFile,
                                    Set<String> includeStack, int depth) {
        if (depth >= MAX_INCLUDE_DEPTH) {
            LOGGER.warn("Maximum include depth ({}) exceeded in {}", MAX_INCLUDE_DEPTH, currentFile);
            return glsl;
        }

        Matcher matcher = INCLUDE_PATTERN.matcher(glsl);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String includePath = matcher.group(1);

            // Normalize path (remove leading / if present)
            if (includePath.startsWith("/")) {
                includePath = includePath.substring(1);
            }

            // Check for circular includes
            if (includeStack.contains(includePath)) {
                LOGGER.warn("Circular include detected: {} → {} (skipping)", currentFile, includePath);
                matcher.appendReplacement(sb, "// CIRCULAR INCLUDE SKIPPED: " + includePath);
                continue;
            }

            // Read the included file
            try {
                String includeContent = source.readShaderFile(includePath);
                if (includeContent != null) {
                    // Recursively process includes in the included file
                    Set<String> newStack = new HashSet<>(includeStack);
                    newStack.add(includePath);
                    includeContent = resolveIncludes(includeContent, includePath, newStack, depth + 1);

                    // Add line directive markers for debugging
                    String replacement = "\n// BEGIN INCLUDE: " + includePath + "\n"
                            + includeContent + "\n"
                            + "// END INCLUDE: " + includePath + "\n";
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                } else {
                    LOGGER.warn("Include file not found: {} (from {})", includePath, currentFile);
                    matcher.appendReplacement(sb, "// INCLUDE NOT FOUND: " + includePath);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to read include file {}: {}", includePath, e.getMessage());
                matcher.appendReplacement(sb, "// INCLUDE ERROR: " + includePath);
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Standard defines — now injected by OptiFineGlslPreprocessor.buildCapabilityDefines() */
    // STANDARD_DEFINES removed: all defines consolidated in OptiFineGlslPreprocessor
    // to prevent "Macro redefined; different substitutions" errors from duplication.

    /**
     * Ensures the #version is at least 330 (required for compatibility with
     * Vulkan SPIR-V compilation via glslang/shaderc).
     * If the version is < 330, upgrades it to 330.
     * If no #version is present, adds "#version 330 core".
     */
    private String validateVersion(String glsl) {
        Matcher matcher = VERSION_PATTERN.matcher(glsl);

        if (matcher.find()) {
            int version = Integer.parseInt(matcher.group(1));
            if (version < 330) {
                LOGGER.debug("Upgrading shader #version {} → 330", version);
                return glsl.substring(0, matcher.start())
                        + "#version 330 core"
                        + glsl.substring(matcher.end());
            }
            return glsl;
        }

        // No version directive found — prepend one
        return "#version 330 core\n" + glsl;
    }
}
