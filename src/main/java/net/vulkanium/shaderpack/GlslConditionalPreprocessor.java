package net.vulkanium.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL conditional preprocessor — evaluates {@code #ifdef}/{@code #if}/{@code #else}/{@code #endif}
 * blocks and strips dead code paths before passing to shaderc.
 *
 * <h3>Why This Exists</h3>
 * <p>After {@code #include} resolution, complex shader packs (BSL, Complementary, etc.)
 * produce GLSL sources with deeply nested {@code #ifdef} blocks (sometimes 50+ levels).
 * shaderc's internal preprocessor (glslang) has a stack limit of ~64 for nested
 * conditionals, causing "Out of stack space" errors. By evaluating conditionals ourselves
 * and emitting only the active branches, we produce flat GLSL that shaderc can compile.</p>
 *
 * <h3>What This Handles</h3>
 * <ul>
 *   <li>{@code #define NAME [VALUE]} — adds to symbol table</li>
 *   <li>{@code #undef NAME} — removes from symbol table</li>
 *   <li>{@code #ifdef NAME} — evaluates whether NAME is defined</li>
 *   <li>{@code #ifndef NAME} — evaluates whether NAME is NOT defined</li>
 *   <li>{@code #if EXPR} — evaluates integer expressions with {@code defined()},
 *       {@code &&}, {@code ||}, {@code !}, {@code ==}, {@code !=}, comparison ops</li>
 *   <li>{@code #elif EXPR} — alternative branch</li>
 *   <li>{@code #else} — fallback branch</li>
 *   <li>{@code #endif} — end conditional block</li>
 * </ul>
 *
 * <h3>What This Preserves</h3>
 * <ul>
 *   <li>{@code #define} lines from active branches (needed for macro expansion by shaderc)</li>
 *   <li>{@code #version} directives</li>
 *   <li>{@code #extension} directives</li>
 *   <li>{@code #line} directives</li>
 *   <li>All non-preprocessor lines from active branches</li>
 * </ul>
 *
 * <h3>Integration Point</h3>
 * <p>Called in {@link net.vulkanium.render.shader.ShaderModuleManager#compileProgram}
 * after all text transformations (OptiFineGlslPreprocessor + VulkaniumGlslTransformer)
 * and before {@link net.vulkanium.render.shader.ShaderCompiler#compile}.</p>
 */
public class GlslConditionalPreprocessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/CondPreproc");

    // ── Patterns ──

    /** #define NAME or #define NAME VALUE or #define NAME(args) ... */
    private static final Pattern DEFINE_PATTERN =
            Pattern.compile("^\\s*#\\s*define\\s+(\\w+)(?:\\s+(.*))?$");

    /** #undef NAME */
    private static final Pattern UNDEF_PATTERN =
            Pattern.compile("^\\s*#\\s*undef\\s+(\\w+)");

    /** #ifdef NAME */
    private static final Pattern IFDEF_PATTERN =
            Pattern.compile("^\\s*#\\s*ifdef\\s+(\\w+)");

    /** #ifndef NAME */
    private static final Pattern IFNDEF_PATTERN =
            Pattern.compile("^\\s*#\\s*ifndef\\s+(\\w+)");

    /** #if EXPR */
    private static final Pattern IF_PATTERN =
            Pattern.compile("^\\s*#\\s*if\\s+(.+)$");

    /** #elif EXPR */
    private static final Pattern ELIF_PATTERN =
            Pattern.compile("^\\s*#\\s*elif\\s+(.+)$");

    /** #else */
    private static final Pattern ELSE_PATTERN =
            Pattern.compile("^\\s*#\\s*else\\s*$");

    /** #endif */
    private static final Pattern ENDIF_PATTERN =
            Pattern.compile("^\\s*#\\s*endif");

        /** Any conditional directive left after processing (safety strip). */
        private static final Pattern CONDITIONAL_DIRECTIVE_PATTERN =
            Pattern.compile("(?m)^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b.*$");

    /** defined(NAME) or defined NAME in expressions */
    private static final Pattern DEFINED_FUNC_PATTERN =
            Pattern.compile("defined\\s*\\(\\s*(\\w+)\\s*\\)|defined\\s+(\\w+)");

    /**
     * State for one level of the #if/#ifdef/#else/#endif stack.
     */
    private static class CondLevel {
        /** Whether ANY branch at this level has been taken (true). */
        boolean anyBranchTaken;
        /** Whether the CURRENT branch (the one we're in right now) is active. */
        boolean currentBranchActive;

        CondLevel(boolean active) {
            this.currentBranchActive = active;
            this.anyBranchTaken = active;
        }
    }

    /**
     * Processes GLSL source, evaluating all conditional directives and emitting
     * only the active code paths. {@code #define} lines from active branches are
     * preserved for shaderc's macro expansion.
     *
     * @param source  Fully-transformed GLSL source (after include resolution + transforms)
     * @return Flattened GLSL with no conditional nesting
     */
    public static String process(String source) {
        if (source == null || source.isEmpty()) return source;

        source = normalizeLineContinuations(source);

        // Symbol table: name → defined (we only track existence, not value, for most symbols)
        // For #if comparisons, we also track integer values
        Map<String, String> defines = new HashMap<>();

        // Parse initial #define lines to seed the symbol table
        // (OptiFineGlslPreprocessor already injected our capability defines)

        Deque<CondLevel> stack = new ArrayDeque<>();
        StringBuilder output = new StringBuilder(source.length());
        String[] lines = source.split("\n", -1);

        int strippedIfBlocks = 0;
        int strippedLines = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // ── #ifdef NAME ──
            Matcher m = IFDEF_PATTERN.matcher(trimmed);
            if (m.matches()) {
                String name = m.group(1);
                boolean parentActive = isActive(stack);
                boolean condition = parentActive && defines.containsKey(name);
                stack.push(new CondLevel(condition));
                strippedIfBlocks++;
                continue; // Don't emit the #ifdef line
            }

            // ── #ifndef NAME ──
            m = IFNDEF_PATTERN.matcher(trimmed);
            if (m.matches()) {
                String name = m.group(1);
                boolean parentActive = isActive(stack);
                boolean condition = parentActive && !defines.containsKey(name);
                stack.push(new CondLevel(condition));
                strippedIfBlocks++;
                continue;
            }

            // ── #if EXPR ──
            m = IF_PATTERN.matcher(trimmed);
            if (m.matches()) {
                String expr = m.group(1).trim();
                boolean parentActive = isActive(stack);
                boolean condition = false;
                if (parentActive) {
                    try {
                        condition = evaluateExpression(expr, defines);
                    } catch (Exception e) {
                        // Can't evaluate → assume false (conservative)
                        LOGGER.debug("Cannot evaluate #if expression at line {}: '{}' — assuming false", i + 1, expr);
                    }
                }
                stack.push(new CondLevel(condition));
                strippedIfBlocks++;
                continue;
            }

            // ── #elif EXPR ──
            m = ELIF_PATTERN.matcher(trimmed);
            if (m.matches()) {
                if (stack.isEmpty()) {
                    LOGGER.warn("Orphaned #elif at line {}", i + 1);
                    continue;
                }
                CondLevel level = stack.peek();
                if (level.anyBranchTaken) {
                    // A previous branch was already taken → this branch is inactive
                    level.currentBranchActive = false;
                } else {
                    // No branch taken yet → evaluate this condition
                    boolean parentActive = isParentActive(stack);
                    if (parentActive) {
                        String expr = m.group(1).trim();
                        try {
                            boolean condition = evaluateExpression(expr, defines);
                            level.currentBranchActive = condition;
                            if (condition) level.anyBranchTaken = true;
                        } catch (Exception e) {
                            level.currentBranchActive = false;
                        }
                    } else {
                        level.currentBranchActive = false;
                    }
                }
                continue;
            }

            // ── #else ──
            m = ELSE_PATTERN.matcher(trimmed);
            if (m.matches()) {
                if (stack.isEmpty()) {
                    LOGGER.warn("Orphaned #else at line {}", i + 1);
                    continue;
                }
                CondLevel level = stack.peek();
                boolean parentActive = isParentActive(stack);
                // #else is active only if parent is active AND no previous branch was taken
                level.currentBranchActive = parentActive && !level.anyBranchTaken;
                if (level.currentBranchActive) level.anyBranchTaken = true;
                continue;
            }

            // ── #endif ──
            m = ENDIF_PATTERN.matcher(trimmed);
            if (m.matches()) {
                if (stack.isEmpty()) {
                    LOGGER.warn("Orphaned #endif at line {}", i + 1);
                } else {
                    stack.pop();
                }
                continue;
            }

            // ── Other lines: emit only if active ──
            if (isActive(stack)) {
                // Track #define / #undef in active branches
                Matcher defM = DEFINE_PATTERN.matcher(trimmed);
                if (defM.matches()) {
                    String name = defM.group(1);
                    String value = defM.group(2);
                    defines.put(name, value != null ? value.trim() : "");
                    // Emit the #define so shaderc can expand function-like macros
                    output.append(line).append('\n');
                    continue;
                }

                Matcher undefM = UNDEF_PATTERN.matcher(trimmed);
                if (undefM.matches()) {
                    defines.remove(undefM.group(1));
                    // Don't emit #undef (shaderc doesn't need it since we handle conditionals)
                    continue;
                }

                // Regular line or other preprocessor directive (#version, #extension, #line, etc.)
                output.append(line).append('\n');
            } else {
                strippedLines++;
            }
        }

        if (!stack.isEmpty()) {
            LOGGER.warn("Unmatched #if/#ifdef blocks: {} levels still open after processing", stack.size());
        }

        String flattened = output.toString();
        Matcher leftover = CONDITIONAL_DIRECTIVE_PATTERN.matcher(flattened);
        int leftoverCount = 0;
        while (leftover.find()) leftoverCount++;

        if (leftoverCount > 0) {
            LOGGER.warn("Conditional preprocessor left {} conditional directives; stripping as safety fallback", leftoverCount);
            flattened = CONDITIONAL_DIRECTIVE_PATTERN.matcher(flattened)
                    .replaceAll("// [Vulkanium] stripped leftover conditional");
        }

        LOGGER.debug("Conditional preprocessor: stripped {} #if blocks, removed {} inactive lines, leftover conditionals {}",
                strippedIfBlocks, strippedLines, leftoverCount);
        return flattened;
    }

    private static String normalizeLineContinuations(String source) {
        // Join preprocessor line continuations (trailing backslash) so
        // directives like #if ... \\n+        //               && ... are parsed as one expression.
        return source.replaceAll("\\\\\\r?\\n", " ");
    }

    /**
     * Returns true if the current position is in an active (emitting) branch.
     * All levels in the stack must be active.
     */
    private static boolean isActive(Deque<CondLevel> stack) {
        for (CondLevel level : stack) {
            if (!level.currentBranchActive) return false;
        }
        return true;
    }

    /**
     * Returns true if all PARENT levels (excluding the top) are active.
     * Used for #elif/#else evaluation: the parent branch must be active
     * for this level's condition to be evaluated.
     */
    private static boolean isParentActive(Deque<CondLevel> stack) {
        Iterator<CondLevel> it = stack.iterator();
        if (it.hasNext()) it.next(); // Skip top level
        while (it.hasNext()) {
            if (!it.next().currentBranchActive) return false;
        }
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Expression Evaluator
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Evaluates a preprocessor expression to a boolean result.
     * Supports: defined(NAME), integer literals, &&, ||, !, ==, !=, <, >, <=, >=, +, -, *, /
     *
     * @param expr    The expression string (e.g., "defined(VULKANIUM) && MC_VERSION >= 12001")
     * @param defines Currently defined symbols
     * @return true if the expression evaluates to non-zero
     */
    static boolean evaluateExpression(String expr, Map<String, String> defines) {
        // Step 1: Replace defined(NAME) and defined NAME with 1 or 0
        String processed = replaceDefined(expr, defines);

        // Step 2: Replace known macro names with their integer values (if they have one)
        processed = replaceMacroValues(processed, defines);

        // Step 3: Evaluate the resulting integer expression
        long result = evalExpr(processed.trim());
        return result != 0;
    }

    /**
     * Replaces all defined(NAME) / defined NAME occurrences with "1" or "0".
     */
    private static String replaceDefined(String expr, Map<String, String> defines) {
        Matcher m = DEFINED_FUNC_PATTERN.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            m.appendReplacement(sb, defines.containsKey(name) ? "1" : "0");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Replaces remaining identifier tokens with their define values (if numeric)
     * or with 0 (undefined identifier in #if context = 0 per C preprocessor rules).
     */
    private static String replaceMacroValues(String expr, Map<String, String> defines) {
        // Tokenize and replace identifiers
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) {
                result.append(c);
                i++;
                continue;
            }

            // Number literal
            if (Character.isDigit(c) || (c == '-' && i + 1 < expr.length() && Character.isDigit(expr.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                while (i < expr.length() && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == 'x' || expr.charAt(i) == 'X'
                        || (expr.charAt(i) >= 'a' && expr.charAt(i) <= 'f')
                        || (expr.charAt(i) >= 'A' && expr.charAt(i) <= 'F')
                        || expr.charAt(i) == 'u' || expr.charAt(i) == 'U'
                        || expr.charAt(i) == 'l' || expr.charAt(i) == 'L')) {
                    i++;
                }
                result.append(expr, start, i);
                continue;
            }

            // Identifier
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < expr.length() && Character.isJavaIdentifierPart(expr.charAt(i))) {
                    i++;
                }
                String ident = expr.substring(start, i);

                // Check if it's a defined macro with a numeric value
                if (defines.containsKey(ident)) {
                    String value = defines.get(ident);
                    if (value != null && !value.isEmpty()) {
                        // Try to resolve the value as an integer
                        try {
                            long v = parseLong(value.trim());
                            result.append(v);
                        } catch (NumberFormatException e) {
                            // Value is not numeric — treat as 1 (defined = truthy)
                            result.append("1");
                        }
                    } else {
                        // Defined but empty value → treat as 1
                        result.append("1");
                    }
                } else {
                    // Undefined identifier → 0 (per C preprocessor rules)
                    result.append("0");
                }
                continue;
            }

            // Operators and other characters
            result.append(c);
            i++;
        }
        return result.toString();
    }

    /**
     * Parses a string to a long, handling hex (0x...) and octal (0...) prefixes,
     * and trailing suffixes like 'u', 'U', 'l', 'L'.
     */
    private static long parseLong(String s) {
        // Strip trailing type suffixes
        s = s.replaceAll("[uUlL]+$", "");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
        if (s.startsWith("0") && s.length() > 1 && !s.contains(".")) {
            try {
                return Long.parseLong(s, 8);
            } catch (NumberFormatException e) {
                // Fall through to decimal
            }
        }
        return Long.parseLong(s);
    }

    // ── Recursive descent expression parser ──
    // Precedence (low to high): ||, &&, |, ^, &, == !=, < > <= >=, << >>, + -, * / %, unary ! ~ -

    private static int pos;
    private static String input;

    private static long evalExpr(String expr) {
        // Use thread-local to avoid issues with concurrent preprocessing
        pos = 0;
        input = expr;
        long result = parseOr();
        return result;
    }

    private static void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private static long parseOr() {
        long left = parseAnd();
        skipWhitespace();
        while (pos + 1 < input.length() && input.charAt(pos) == '|' && input.charAt(pos + 1) == '|') {
            pos += 2;
            long right = parseAnd();
            left = (left != 0 || right != 0) ? 1 : 0;
            skipWhitespace();
        }
        return left;
    }

    private static long parseAnd() {
        long left = parseBitOr();
        skipWhitespace();
        while (pos + 1 < input.length() && input.charAt(pos) == '&' && input.charAt(pos + 1) == '&') {
            pos += 2;
            long right = parseBitOr();
            left = (left != 0 && right != 0) ? 1 : 0;
            skipWhitespace();
        }
        return left;
    }

    private static long parseBitOr() {
        long left = parseBitXor();
        skipWhitespace();
        while (pos < input.length() && input.charAt(pos) == '|'
                && (pos + 1 >= input.length() || input.charAt(pos + 1) != '|')) {
            pos++;
            long right = parseBitXor();
            left = left | right;
            skipWhitespace();
        }
        return left;
    }

    private static long parseBitXor() {
        long left = parseBitAnd();
        skipWhitespace();
        while (pos < input.length() && input.charAt(pos) == '^') {
            pos++;
            long right = parseBitAnd();
            left = left ^ right;
            skipWhitespace();
        }
        return left;
    }

    private static long parseBitAnd() {
        long left = parseEquality();
        skipWhitespace();
        while (pos < input.length() && input.charAt(pos) == '&'
                && (pos + 1 >= input.length() || input.charAt(pos + 1) != '&')) {
            pos++;
            long right = parseEquality();
            left = left & right;
            skipWhitespace();
        }
        return left;
    }

    private static long parseEquality() {
        long left = parseComparison();
        skipWhitespace();
        while (pos + 1 < input.length()) {
            if (input.charAt(pos) == '=' && input.charAt(pos + 1) == '=') {
                pos += 2;
                long right = parseComparison();
                left = (left == right) ? 1 : 0;
            } else if (input.charAt(pos) == '!' && input.charAt(pos + 1) == '=') {
                pos += 2;
                long right = parseComparison();
                left = (left != right) ? 1 : 0;
            } else {
                break;
            }
            skipWhitespace();
        }
        return left;
    }

    private static long parseComparison() {
        long left = parseShift();
        skipWhitespace();
        while (pos < input.length()) {
            if (pos + 1 < input.length() && input.charAt(pos) == '<' && input.charAt(pos + 1) == '=') {
                pos += 2;
                long right = parseShift();
                left = (left <= right) ? 1 : 0;
            } else if (pos + 1 < input.length() && input.charAt(pos) == '>' && input.charAt(pos + 1) == '=') {
                pos += 2;
                long right = parseShift();
                left = (left >= right) ? 1 : 0;
            } else if (input.charAt(pos) == '<' && (pos + 1 >= input.length() || input.charAt(pos + 1) != '<')) {
                pos++;
                long right = parseShift();
                left = (left < right) ? 1 : 0;
            } else if (input.charAt(pos) == '>' && (pos + 1 >= input.length() || input.charAt(pos + 1) != '>')) {
                pos++;
                long right = parseShift();
                left = (left > right) ? 1 : 0;
            } else {
                break;
            }
            skipWhitespace();
        }
        return left;
    }

    private static long parseShift() {
        long left = parseAddSub();
        skipWhitespace();
        while (pos + 1 < input.length()) {
            if (input.charAt(pos) == '<' && input.charAt(pos + 1) == '<') {
                pos += 2;
                long right = parseAddSub();
                left = left << right;
            } else if (input.charAt(pos) == '>' && input.charAt(pos + 1) == '>') {
                pos += 2;
                long right = parseAddSub();
                left = left >> right;
            } else {
                break;
            }
            skipWhitespace();
        }
        return left;
    }

    private static long parseAddSub() {
        long left = parseMulDiv();
        skipWhitespace();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '+') {
                pos++;
                long right = parseMulDiv();
                left = left + right;
            } else if (c == '-') {
                pos++;
                long right = parseMulDiv();
                left = left - right;
            } else {
                break;
            }
            skipWhitespace();
        }
        return left;
    }

    private static long parseMulDiv() {
        long left = parseUnary();
        skipWhitespace();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '*') {
                pos++;
                long right = parseUnary();
                left = left * right;
            } else if (c == '/') {
                pos++;
                long right = parseUnary();
                left = right != 0 ? left / right : 0;
            } else if (c == '%') {
                pos++;
                long right = parseUnary();
                left = right != 0 ? left % right : 0;
            } else {
                break;
            }
            skipWhitespace();
        }
        return left;
    }

    private static long parseUnary() {
        skipWhitespace();
        if (pos >= input.length()) return 0;
        char c = input.charAt(pos);

        if (c == '!') {
            pos++;
            long val = parseUnary();
            return val == 0 ? 1 : 0;
        }
        if (c == '~') {
            pos++;
            long val = parseUnary();
            return ~val;
        }
        if (c == '-' && (pos + 1 < input.length() && (Character.isDigit(input.charAt(pos + 1)) || input.charAt(pos + 1) == '('))) {
            pos++;
            long val = parsePrimary();
            return -val;
        }
        if (c == '+') {
            pos++;
            return parseUnary();
        }
        return parsePrimary();
    }

    private static long parsePrimary() {
        skipWhitespace();
        if (pos >= input.length()) return 0;
        char c = input.charAt(pos);

        // Parenthesized expression
        if (c == '(') {
            pos++;
            long val = parseOr();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ')') pos++;
            return val;
        }

        // Number literal (decimal, hex, octal)
        if (Character.isDigit(c)) {
            int start = pos;
            // Handle hex prefix
            if (c == '0' && pos + 1 < input.length()
                    && (input.charAt(pos + 1) == 'x' || input.charAt(pos + 1) == 'X')) {
                pos += 2;
                while (pos < input.length() && isHexDigit(input.charAt(pos))) pos++;
            } else {
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            // Skip trailing suffixes (u, U, l, L)
            while (pos < input.length() && "uUlL".indexOf(input.charAt(pos)) >= 0) pos++;
            String numStr = input.substring(start, pos);
            try {
                return parseLong(numStr);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // If we get here, it's an unexpected character — return 0
        // (This handles cases like remaining identifiers that weren't macro-expanded)
        if (Character.isJavaIdentifierStart(c)) {
            while (pos < input.length() && Character.isJavaIdentifierPart(input.charAt(pos))) pos++;
            return 0; // Unknown identifier → 0
        }

        // Skip unexpected character
        pos++;
        return 0;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
