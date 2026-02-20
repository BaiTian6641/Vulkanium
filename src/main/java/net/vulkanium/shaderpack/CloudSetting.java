package net.vulkanium.shaderpack;

/**
 * Cloud rendering mode from {@code shaders.properties}.
 *
 * <p>Shaderpacks can override Minecraft's cloud rendering setting using:
 * <pre>clouds = off|fast|fancy|default</pre>
 *
 * <p>Most advanced packs (iterationT, iterationRP, Complementary Unbound)
 * set {@code clouds = off} because they render volumetric clouds in
 * composite/deferred passes instead of using vanilla cloud geometry.</p>
 */
public enum CloudSetting {
    /** Use Minecraft's built-in setting (no override). */
    DEFAULT,

    /** Disable vanilla cloud rendering entirely. */
    OFF,

    /** Flat 2D cloud layer (vanilla fast mode). */
    FAST,

    /** 3D thick cloud layer (vanilla fancy mode). */
    FANCY;

    /**
     * Parses the {@code clouds} value from {@code shaders.properties}.
     *
     * @param value The property value (e.g., "off", "fast", "fancy")
     * @return The corresponding CloudSetting, or DEFAULT if unrecognized
     */
    public static CloudSetting fromString(String value) {
        if (value == null) return DEFAULT;
        return switch (value.trim().toLowerCase()) {
            case "off" -> OFF;
            case "fast" -> FAST;
            case "fancy" -> FANCY;
            default -> DEFAULT;
        };
    }
}
