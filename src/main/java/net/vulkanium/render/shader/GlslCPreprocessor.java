package net.vulkanium.render.shader;

import org.anarres.cpp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * C-preprocessor resolution using jcpp (org.anarres.cpp).
 *
 * <p>This resolves ALL preprocessor directives — {@code #ifdef}, {@code #else},
 * {@code #endif}, {@code #define}, {@code #if}, {@code #elif} — producing clean
 * GLSL 450 source that glsl-transformer's ANTLR parser can handle.</p>
 *
 * <p>The approach mirrors Iris's {@code JcppProcessor}: we replace {@code #version}
 * and {@code #extension} with {@code #warning} markers before jcpp processes the
 * source, then restore them afterwards. This preserves these directives through
 * the C preprocessor, which would otherwise discard them.</p>
 *
 * <h3>Pipeline position:</h3>
 * <pre>
 *   OptiFineGlslPreprocessor (text cleanup, capability defines injected)
 *     → GlslCPreprocessor (THIS — resolve #ifdef/#define, strip directives)
 *       → VulkaniumASTTransformer (AST transforms on clean GLSL)
 *         → shaderc (GLSL 450 → SPIR-V)
 * </pre>
 */
public class GlslCPreprocessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/CPreproc");

    // Markers that jcpp will pass through as #warning (restored after processing)
    private static final String VERSION_MARKER = "#warning VKM_JCPP_GLSL_VERSION";
    private static final String EXTENSION_MARKER = "#warning VKM_JCPP_GLSL_EXTENSION";
    private static final String LINE_MARKER = "#warning VKM_JCPP_GLSL_LINE";

    /**
     * Resolves all C preprocessor directives in the given GLSL source.
     *
     * <p>After this method returns, the source contains no {@code #ifdef},
     * {@code #else}, {@code #endif}, {@code #define}, {@code #if}, {@code #elif},
     * or {@code #undef} directives. Only {@code #version} and {@code #extension}
     * are preserved.</p>
     *
     * @param source            GLSL source (already passed through OptiFineGlslPreprocessor)
     * @param extraDefines      Additional macro definitions (e.g., pass-specific)
     * @return Fully preprocessed GLSL source
     */
    public static String preprocess(String source, Map<String, String> extraDefines) {
        // Guard: protect #version and #extension from jcpp (it doesn't understand them)
        source = source.replace("#version", VERSION_MARKER);
        source = source.replace("#extension", EXTENSION_MARKER);
        source = source.replace("#line", LINE_MARKER);

        // Remove null characters that some packs include
        source = source.replace("\u0000", "");

        VulkaniumGlslListener listener = new VulkaniumGlslListener();

        try {
            @SuppressWarnings("resource")
            Preprocessor pp = new Preprocessor();

            // Add extra defines without modifying source text
            if (extraDefines != null) {
                for (Map.Entry<String, String> entry : extraDefines.entrySet()) {
                    pp.addMacro(entry.getKey(), entry.getValue());
                }
            }

            pp.setListener(listener);
            pp.addInput(new StringLexerSource(source, true));
            pp.addFeature(Feature.KEEPCOMMENTS);

            StringBuilder builder = new StringBuilder(source.length());

            for (;;) {
                Token tok = pp.token();
                if (tok == null) break;
                if (tok.getType() == Token.EOF) break;
                builder.append(tok.getText());
            }

            builder.append("\n");

            // Prepend collected #version/#extension lines
            String result = listener.collectLines() + builder;

            return result;

        } catch (LexerException | IOException e) {
            LOGGER.warn("jcpp preprocessing failed, returning source with directives intact: {}",
                    e.getMessage());
            // Restore markers and return original
            source = source.replace(VERSION_MARKER, "#version");
            source = source.replace(EXTENSION_MARKER, "#extension");
            source = source.replace(LINE_MARKER, "#line");
            return source;
        }
    }

    /**
     * Convenience overload with no extra defines.
     */
    public static String preprocess(String source) {
        return preprocess(source, null);
    }

    /**
     * Listener that collects #version and #extension directives from jcpp #warning
     * markers, restoring them as proper directives at the top of the output.
     */
    private static class VulkaniumGlslListener extends DefaultPreprocessorListener {
        private final StringBuilder collected = new StringBuilder();

        @Override
        public void handleWarning(Source source, int line, int column, String msg) throws LexerException {
            if (msg.startsWith(VERSION_MARKER)) {
                // Restore: "#warning VKM_JCPP_GLSL_VERSION 450 core" → "#version 450 core"
                collected.append(msg.replace(VERSION_MARKER, "#version ").trim());
                collected.append('\n');
            } else if (msg.startsWith(EXTENSION_MARKER)) {
                collected.append(msg.replace(EXTENSION_MARKER, "#extension ").trim());
                collected.append('\n');
            } else if (msg.startsWith(LINE_MARKER)) {
                collected.append(msg.replace(LINE_MARKER, "#line ").trim());
                collected.append('\n');
            } else if (msg.contains("Preprocessor directive not a word")
                    || msg.contains("Unknown preprocessor directive")) {
                // Suppress warnings for GLSL-specific preprocessor directives that jcpp
                // doesn't understand (e.g., #pragma, #error in GLSL context)
                LOGGER.debug("jcpp warning suppressed: {}", msg);
            } else {
                super.handleWarning(source, line, column, msg);
            }
        }

        public String collectLines() {
            return collected.toString();
        }
    }
}
