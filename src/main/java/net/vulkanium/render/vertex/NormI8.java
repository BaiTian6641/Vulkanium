package net.vulkanium.render.vertex;

/**
 * Utilities for packing/unpacking 8-bit-per-component normal vectors.
 * Each component is in the range [-1.0, 1.0] mapped to [-127, 127].
 *
 * <p>Bit layout (little-endian int):
 * <pre>
 * | 31..24 (W) | 23..16 (Z) | 15..8 (Y) | 7..0 (X) |
 * </pre>
 *
 * <p>The W component stores tangent handedness (±1) for TBN construction.</p>
 *
 * <p>Ported from Iris/Sodium NormI8, licensed under LGPLv3.</p>
 */
public final class NormI8 {
    private static final float COMPONENT_RANGE = 127.0f;
    private static final float NORM = 1.0f / COMPONENT_RANGE;

    private NormI8() {}

    /**
     * Pack four float components into a single 32-bit integer.
     */
    public static int pack(float x, float y, float z, float w) {
        return ((int) (x * 127) & 0xFF)
             | (((int) (y * 127) & 0xFF) << 8)
             | (((int) (z * 127) & 0xFF) << 16)
             | (((int) (w * 127) & 0xFF) << 24);
    }

    public static float unpackX(int norm) {
        return ((byte) (norm & 0xFF)) * NORM;
    }

    public static float unpackY(int norm) {
        return ((byte) ((norm >> 8) & 0xFF)) * NORM;
    }

    public static float unpackZ(int norm) {
        return ((byte) ((norm >> 16) & 0xFF)) * NORM;
    }

    public static float unpackW(int norm) {
        return ((byte) ((norm >> 24) & 0xFF)) * NORM;
    }
}
