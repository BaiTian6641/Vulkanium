package net.vulkanium.rt;

/**
 * Native decoder for the LabPBR material standard channel packing.
 *
 * <p>Decodes per-texel material inputs from {@code _s} and {@code _n} textures
 * into linear values used by Vulkanium's RT material model.</p>
 */
public final class LabPBRMaterialStandard {

    private LabPBRMaterialStandard() {}

    /**
     * Decoded data from a LabPBR specular texture sample ({@code _s}).
     */
    public record DecodedSpecular(
            float roughness,
            float metallic,
            float reflectanceF0,
            float ior,
            float porosity,
            float subsurface,
            float emissive,
            boolean hardcodedMetal,
            int hardcodedMetalId
    ) {}

    /**
     * Decoded data from a LabPBR normal texture sample ({@code _n}).
     */
    public record DecodedNormal(
            float normalX,
            float normalY,
            float normalZ,
            float ambientOcclusion,
            float height
    ) {}

    /**
     * Decodes a specular texel using LabPBR rules.
     */
    public static DecodedSpecular decodeSpecular(int r, int g, int b, int a) {
        float smoothness = toLinear01(r);
        float roughness = (1.0f - smoothness);
        roughness *= roughness;

        float reflectanceF0;
        float metallic;
        boolean hardcodedMetal = false;
        int hardcodedMetalId = -1;

        if (g >= 230) {
            hardcodedMetal = true;
            hardcodedMetalId = g;
            reflectanceF0 = 0.91f;
            metallic = 1.0f;
        } else {
            reflectanceF0 = toLinear01(g);
            metallic = clamp01((reflectanceF0 - 0.04f) / 0.96f);
        }

        float sqrtF0 = (float) Math.sqrt(clamp01(reflectanceF0));
        float ior = (1.0f + sqrtF0) / Math.max(1e-4f, (1.0f - sqrtF0));

        float porosity = 0.0f;
        float subsurface = 0.0f;
        if (b <= 64) {
            porosity = clamp01(b / 64.0f);
        } else {
            subsurface = clamp01((b - 65.0f) / 190.0f);
        }

        float emissive = (a >= 254) ? 0.0f : toLinear01(a);

        return new DecodedSpecular(
                roughness,
                metallic,
                reflectanceF0,
                ior,
                porosity,
                subsurface,
                emissive,
                hardcodedMetal,
                hardcodedMetalId
        );
    }

    /**
     * Decodes a normal texel using LabPBR rules.
     */
    public static DecodedNormal decodeNormal(int r, int g, int b, int a) {
        float nx = toLinear01(r) * 2.0f - 1.0f;
        float ny = 1.0f - toLinear01(g) * 2.0f;
        float nzSq = Math.max(0.0f, 1.0f - nx * nx - ny * ny);
        float nz = (float) Math.sqrt(nzSq);

        float ao = toLinear01(b);
        float height = clamp01(1.0f - toLinear01(a) * 0.25f);

        return new DecodedNormal(nx, ny, nz, ao, height);
    }

    private static float toLinear01(int value) {
        return clamp01(value / 255.0f);
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }
}
