package net.vulkanium.shaderpack;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed representation of a shaderpack's {@code shaders.properties} file.
 *
 * <p>This file controls which render targets are used, their formats,
 * clear colors, and various rendering options like shadow map resolution.</p>
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Backslash line continuation (lines ending in {@code \})</li>
 *   <li>{@code #if DEFINE == VALUE} / {@code #ifdef DEFINE} / {@code #ifndef DEFINE} /
 *       {@code #else} / {@code #endif} preprocessor conditionals</li>
 *   <li>{@code iris.features.required} / {@code iris.features.optional} parsing</li>
 *   <li>Screen layout, slider, and profile directives</li>
 * </ul>
 */
public class ShaderpackProperties {

    private final Map<String, String> properties = new HashMap<>();

    /** Main option screen layout (from "screen=OPTION1 OPTION2 ...") */
    private List<String> mainScreenOptions = null;

    /** Sub-screen option layouts (from "screen.SCREEN_NAME=OPTION1 OPTION2 ...") */
    private final Map<String, List<String>> subScreenOptions = new LinkedHashMap<>();

    /** Main screen column count (from "screen.columns=N") */
    private int mainScreenColumnCount = 2;

    /** Sub-screen column counts (from "screen.SCREEN_NAME.columns=N") */
    private final Map<String, Integer> subScreenColumnCount = new HashMap<>();

    /** Options that use slider widgets (from "sliders=OPTION1 OPTION2 ...") */
    private final Set<String> sliderOptions = new HashSet<>();

    /** Required feature flags (from "iris.features.required=FEATURE1 FEATURE2") */
    private final Set<String> requiredFeatures = new LinkedHashSet<>();

    /** Optional feature flags (from "iris.features.optional=FEATURE1 FEATURE2") */
    private final Set<String> optionalFeatures = new LinkedHashSet<>();

    /** Profile definitions (from "profile.NAME=OPTION1=VALUE1 OPTION2=VALUE2") */
    private final Map<String, Map<String, String>> profiles = new LinkedHashMap<>();

    // ── Preprocessor condition matching ──
    private static final Pattern IF_DEFINE_EQ = Pattern.compile(
            "#if\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*(-?\\d+)");
    private static final Pattern IF_DEFINE_GT = Pattern.compile(
            "#if\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*>\\s*(-?\\d+)");
    private static final Pattern IF_DEFINE_LT = Pattern.compile(
            "#if\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*<\\s*(-?\\d+)");
    private static final Pattern IFDEF = Pattern.compile(
            "#ifdef\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern IFNDEF = Pattern.compile(
            "#ifndef\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** Known defines to evaluate preprocessor conditions against. */
    private Map<String, String> activeDefines = new HashMap<>();

    public ShaderpackProperties() {}

    /**
     * Parses a shaders.properties file content with preprocessor support.
     *
     * @param content    raw file content
     * @param optionDefines defines from user option overrides + defaults
     *                      (used to evaluate {@code #if DEFINE == VALUE} blocks)
     */
    public void parse(String content, Map<String, String> optionDefines) {
        if (content == null) return;

        // Merge environment defines with provided option defines
        activeDefines.clear();
        // Default IS_IRIS since Vulkanium is Iris-compatible
        activeDefines.put("IS_IRIS", "");
        if (optionDefines != null) {
            activeDefines.putAll(optionDefines);
        }

        // Phase 1: Join backslash-continued lines
        List<String> joinedLines = joinContinuedLines(content);

        // Phase 2: Evaluate #if/#ifdef/#ifndef/#else/#endif conditionals
        List<String> activeLines = evaluateConditionals(joinedLines);

        // Phase 3: Parse key=value pairs
        for (String line : activeLines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                properties.put(key, value);
                parseDirective(key, value);
            }
        }
    }

    /**
     * Backwards-compatible parse without option defines.
     */
    public void parse(String content) {
        parse(content, null);
    }

    // ─── Phase 1: Join backslash-continued lines ───────────────────

    private static List<String> joinContinuedLines(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : content.split("\n")) {
            String trimmed = rawLine.trim();
            if (trimmed.endsWith("\\")) {
                // Continuation: strip trailing backslash, append to accumulator
                current.append(trimmed, 0, trimmed.length() - 1).append(' ');
            } else {
                current.append(trimmed);
                result.add(current.toString());
                current.setLength(0);
            }
        }
        // Flush remaining
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    // ─── Phase 2: Evaluate preprocessor conditionals ───────────────

    private List<String> evaluateConditionals(List<String> lines) {
        List<String> result = new ArrayList<>();
        Deque<Boolean> conditionStack = new ArrayDeque<>(); // true = currently in active branch
        Deque<Boolean> elseUsed = new ArrayDeque<>();       // true = an active branch was found

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#if ") || trimmed.startsWith("#ifdef ") || trimmed.startsWith("#ifndef ")) {
                boolean condition = evaluateCondition(trimmed);
                boolean parentActive = conditionStack.isEmpty() || conditionStack.peek();
                conditionStack.push(parentActive && condition);
                elseUsed.push(parentActive && condition);
                continue;
            }

            if (trimmed.equals("#else")) {
                if (!conditionStack.isEmpty()) {
                    boolean anyBranchWasActive = elseUsed.peek();
                    boolean parentActive = conditionStack.size() <= 1 ||
                            new ArrayList<>(conditionStack).get(1); // parent level
                    conditionStack.pop();
                    conditionStack.push(parentActive && !anyBranchWasActive);
                }
                continue;
            }

            if (trimmed.startsWith("#elif ")) {
                if (!conditionStack.isEmpty()) {
                    boolean anyBranchWasActive = elseUsed.peek();
                    boolean parentActive = conditionStack.size() <= 1 ||
                            new ArrayList<>(conditionStack).get(1);
                    conditionStack.pop();
                    String elifCondition = "#if " + trimmed.substring(6);
                    boolean condition = evaluateCondition(elifCondition);
                    boolean active = parentActive && !anyBranchWasActive && condition;
                    conditionStack.push(active);
                    if (active) {
                        elseUsed.pop();
                        elseUsed.push(true);
                    }
                }
                continue;
            }

            if (trimmed.equals("#endif")) {
                if (!conditionStack.isEmpty()) {
                    conditionStack.pop();
                    elseUsed.pop();
                }
                continue;
            }

            // Regular line — include if all enclosing conditions are true
            if (conditionStack.isEmpty() || conditionStack.peek()) {
                result.add(line);
            }
        }

        return result;
    }

    private boolean evaluateCondition(String directive) {
        String trimmed = directive.trim();

        // #ifdef DEFINE
        Matcher m = IFDEF.matcher(trimmed);
        if (m.matches()) {
            return activeDefines.containsKey(m.group(1));
        }

        // #ifndef DEFINE
        m = IFNDEF.matcher(trimmed);
        if (m.matches()) {
            return !activeDefines.containsKey(m.group(1));
        }

        // #if DEFINE == VALUE
        m = IF_DEFINE_EQ.matcher(trimmed);
        if (m.matches()) {
            String name = m.group(1);
            int expected = Integer.parseInt(m.group(2));
            String actual = activeDefines.get(name);
            if (actual == null) return expected == 0; // undefined == 0
            try {
                return Integer.parseInt(actual.trim()) == expected;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // #if DEFINE > VALUE
        m = IF_DEFINE_GT.matcher(trimmed);
        if (m.matches()) {
            String name = m.group(1);
            int threshold = Integer.parseInt(m.group(2));
            String actual = activeDefines.get(name);
            if (actual == null) return 0 > threshold;
            try {
                return Integer.parseInt(actual.trim()) > threshold;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // #if DEFINE < VALUE
        m = IF_DEFINE_LT.matcher(trimmed);
        if (m.matches()) {
            String name = m.group(1);
            int threshold = Integer.parseInt(m.group(2));
            String actual = activeDefines.get(name);
            if (actual == null) return 0 < threshold;
            try {
                return Integer.parseInt(actual.trim()) < threshold;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // Unrecognized condition — assume false (skip block)
        return false;
    }

    // ─── Phase 3: Directive parsing ────────────────────────────────

    private void parseDirective(String key, String value) {
        // Screen layout directives
        if (key.equals("screen")) {
            mainScreenOptions = parseWhitespacedList(value);
        } else if (key.equals("screen.columns")) {
            try { mainScreenColumnCount = Integer.parseInt(value); }
            catch (NumberFormatException ignored) {}
        } else if (key.equals("sliders")) {
            sliderOptions.addAll(parseWhitespacedList(value));
        } else if (key.startsWith("screen.")) {
            String rest = key.substring(7); // after "screen."
            if (rest.endsWith(".columns")) {
                String screenName = rest.substring(0, rest.length() - 8);
                try { subScreenColumnCount.put(screenName, Integer.parseInt(value)); }
                catch (NumberFormatException ignored) {}
            } else if (!rest.contains(".")) {
                subScreenOptions.put(rest, parseWhitespacedList(value));
            }
        }
        // Iris feature flags
        else if (key.equals("iris.features.required")) {
            requiredFeatures.addAll(parseWhitespacedList(value));
        } else if (key.equals("iris.features.optional")) {
            optionalFeatures.addAll(parseWhitespacedList(value));
        }
        // Profile definitions
        else if (key.startsWith("profile.")) {
            String profileName = key.substring(8);
            Map<String, String> profileValues = new LinkedHashMap<>();
            for (String token : value.split("\\s+")) {
                int eqIdx = token.indexOf('=');
                if (eqIdx > 0) {
                    profileValues.put(token.substring(0, eqIdx), token.substring(eqIdx + 1));
                }
            }
            profiles.put(profileName, profileValues);
        }
    }

    private static List<String> parseWhitespacedList(String value) {
        List<String> list = new ArrayList<>();
        if (value == null || value.isBlank()) return list;
        for (String token : value.trim().split("\\s+")) {
            String cleaned = token.trim();
            if (!cleaned.isEmpty()) {
                list.add(cleaned);
            }
        }
        return list;
    }

    public String get(String key) { return properties.get(key); }
    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = properties.get(key);
        if (v != null) {
            try { return Integer.parseInt(v); }
            catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public float getFloat(String key, float defaultValue) {
        String v = properties.get(key);
        if (v != null) {
            try { return Float.parseFloat(v); }
            catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = properties.get(key);
        if (v != null) return Boolean.parseBoolean(v);
        return defaultValue;
    }

    // ─── Convenience accessors for common properties ───────────────────

    public int getShadowResolution() { return getInt("shadowResolution", 1024); }
    public float getShadowDistance() { return getFloat("shadowDistance", 128.0f); }
    public float getAmbientOcclusionLevel() { return getFloat("ambientOcclusionLevel", 1.0f); }
    public boolean isShadowHardwareFiltering() { return getBoolean("shadowHardwareFiltering", true); }
    public boolean isOldHandLight() { return getBoolean("oldHandLight", true); }
    public boolean isDynamicHandLight() { return getBoolean("dynamicHandLight", false); }
    public boolean isOldLighting() { return getBoolean("oldLighting", false); }

    /**
     * Returns the cloud rendering setting from {@code shaders.properties}.
     */
    public CloudSetting getCloudSetting() {
        return CloudSetting.fromString(get("clouds"));
    }

    /**
     * Gets the VkFormat-equivalent format string for a color texture attachment.
     */
    public String getColorTexFormat(int index) {
        return get("colortex" + index + "Format", index == 0 ? "RGBA8" : "");
    }

    /**
     * Whether a specific color texture attachment is used by the pack.
     */
    public boolean isColorTexUsed(int index) {
        return !getColorTexFormat(index).isEmpty();
    }

    /**
     * Gets clear color for a color texture attachment.
     * Format: "r g b a" (0.0–1.0)
     */
    public float[] getColorTexClear(int index) {
        String v = get("colortex" + index + "Clear");
        if (v == null) return new float[]{0, 0, 0, 0};
        String[] parts = v.split("\\s+");
        float[] result = new float[4];
        for (int i = 0; i < Math.min(parts.length, 4); i++) {
            try { result[i] = Float.parseFloat(parts[i]); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }

    /**
     * Returns all raw properties (for debugging).
     */
    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(properties);
    }

    // ─── Screen layout accessors ───────────────────────────────────

    /** Main option screen layout, or null if not defined (will dump all options). */
    public List<String> getMainScreenOptions() { return mainScreenOptions; }

    /** Sub-screen option layouts keyed by screen name. */
    public Map<String, List<String>> getSubScreenOptions() { return Collections.unmodifiableMap(subScreenOptions); }

    /** Main screen column count (default 2). */
    public int getMainScreenColumnCount() { return mainScreenColumnCount; }

    /** Sub-screen column count for a given screen name (default 2). */
    public int getSubScreenColumnCount(String screenName) {
        return subScreenColumnCount.getOrDefault(screenName, 2);
    }

    /** Whether a specific option uses a slider widget. */
    public boolean isSlider(String optionName) { return sliderOptions.contains(optionName); }

    /** All slider option names. */
    public Set<String> getSliderOptions() { return Collections.unmodifiableSet(sliderOptions); }

    // ─── Feature flags ─────────────────────────────────────────────

    /** Required Iris features (pack will not work correctly without them). */
    public Set<String> getRequiredFeatures() { return Collections.unmodifiableSet(requiredFeatures); }

    /** Optional Iris features (pack can work without them but with reduced quality). */
    public Set<String> getOptionalFeatures() { return Collections.unmodifiableSet(optionalFeatures); }

    // ─── Profile accessors ─────────────────────────────────────────

    /** Profile definitions keyed by profile name. Each profile maps option names to values. */
    public Map<String, Map<String, String>> getProfiles() { return Collections.unmodifiableMap(profiles); }
}
