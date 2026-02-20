package net.vulkanium.shaderpack;

import java.util.*;

/**
 * Parsed representation of a shaderpack's {@code shaders.properties} file.
 *
 * <p>This file controls which render targets are used, their formats,
 * clear colors, and various rendering options like shadow map resolution.</p>
 *
 * <h2>Key properties:</h2>
 * <ul>
 *   <li>{@code shadowResolution} — Shadow map resolution (e.g., 1024, 2048)</li>
 *   <li>{@code shadowDistance} — Shadow render distance</li>
 *   <li>{@code ambientOcclusionLevel} — AO strength</li>
 *   <li>{@code colortex0Format} through {@code colortex15Format} — Render target formats</li>
 *   <li>{@code shadowHardwareFiltering} — PCF shadow filtering</li>
 *   <li>{@code oldHandLight} — Use old-style held light behavior</li>
 *   <li>{@code dynamicHandLight} — Enable dynamic hand light</li>
 *   <li>{@code oldLighting} — Use Minecraft's old vanilla lighting model</li>
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

    public ShaderpackProperties() {}

    /**
     * Parses a shaders.properties file content.
     */
    public void parse(String content) {
        if (content == null) return;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                properties.put(key, value);
                parseScreenDirective(key, value);
            }
        }
    }

    /**
     * Parses screen.* directives for option menu layout.
     * <p>Format follows OptiFine/Iris conventions:</p>
     * <pre>
     *   screen=OPTION1 OPTION2 [SUBSCREEN] &lt;empty&gt; OPTION3
     *   screen.SUBSCREEN=OPTION4 OPTION5
     *   screen.columns=2
     *   screen.SUBSCREEN.columns=3
     *   sliders=OPTION1 OPTION2
     * </pre>
     */
    private void parseScreenDirective(String key, String value) {
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
     *
     * <p>Shaderpacks like iterationT, iterationRP set {@code clouds = off} because
     * they render volumetric clouds in composite/deferred passes. Complementary
     * Unbound conditionally discards in gbuffers_clouds but still renders vanilla
     * geometry as a base.</p>
     *
     * @return The cloud setting (OFF, FAST, FANCY, or DEFAULT)
     */
    public CloudSetting getCloudSetting() {
        return CloudSetting.fromString(get("clouds"));
    }

    /**
     * Gets the VkFormat-equivalent format string for a color texture attachment.
     *
     * @param index Color texture index (0–15)
     * @return Format string (e.g., "RGBA8", "RGBA16F", "R11F_G11F_B10F")
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
}
