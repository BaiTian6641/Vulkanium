package net.vulkanium.render.gbuffer;

import net.vulkanium.resource.DescriptorSetManager;
import net.vulkanium.resource.RenderTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages sampler descriptor bindings for G-buffer and shadow textures.
 *
 * <p>Shader packs sample from render targets via uniform samplers. This class
 * maintains the binding layout and creates descriptor sets that map sampler
 * names to their current texture (respecting ping-pong flip state).</p>
 *
 * <h3>Descriptor Set Layout (Set 1)</h3>
 * <pre>
 *   Binding 0-15:  colortex0–colortex15 (+ legacy aliases gcolor, gdepth, ...)
 *   Binding 16-18: depthtex0–depthtex2
 *   Binding 19-20: shadowtex0–shadowtex1
 *   Binding 21-28: shadowcolor0–shadowcolor7
 *   Binding 29:    noisetex
 *   Binding 30:    normals (PBR resource pack normal map)
 *   Binding 31:    specular (PBR resource pack specular map)
 * </pre>
 *
 * <p>Set 0 is reserved for the uniform buffer (VulkaniumUniforms UBO).
 * Set 1 is populated by this class with all texture samplers.</p>
 *
 * <h3>Legacy Sampler Aliases</h3>
 * <pre>
 *   gcolor    → colortex0    (binding 0)
 *   gdepth    → colortex1    (binding 1)
 *   gnormal   → colortex2    (binding 2)
 *   composite → colortex3    (binding 3)
 *   gaux1     → colortex4    (binding 4)
 *   gaux2     → colortex5    (binding 5)
 *   gaux3     → colortex6    (binding 6)
 *   gaux4     → colortex7    (binding 7)
 *   shadow    → shadowtex0   (binding 19)
 *   watershadow → shadowtex1 (binding 20)
 * </pre>
 */
public class GBufferSamplers {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GBufferSamplers");

    // Binding slot assignments (descriptor set 1)
    public static final int BINDING_COLORTEX_BASE = 0;     // 0-15
    public static final int BINDING_DEPTHTEX_BASE = 16;    // 16-18
    public static final int BINDING_SHADOWTEX_BASE = 19;   // 19-20
    public static final int BINDING_SHADOWCOLOR_BASE = 21; // 21-28
    public static final int BINDING_NOISETEX = 29;
    public static final int BINDING_NORMALS = 30;
    public static final int BINDING_SPECULAR = 31;
    public static final int TOTAL_BINDINGS = 32;

    /** Sampler name → binding index mapping */
    private static final Map<String, Integer> SAMPLER_BINDINGS = new HashMap<>();
    static {
        // Color targets
        for (int i = 0; i < 16; i++) {
            SAMPLER_BINDINGS.put("colortex" + i, BINDING_COLORTEX_BASE + i);
        }
        // Legacy aliases
        SAMPLER_BINDINGS.put("gcolor", 0);
        SAMPLER_BINDINGS.put("gdepth", 1);
        SAMPLER_BINDINGS.put("gnormal", 2);
        SAMPLER_BINDINGS.put("composite", 3);
        SAMPLER_BINDINGS.put("gaux1", 4);
        SAMPLER_BINDINGS.put("gaux2", 5);
        SAMPLER_BINDINGS.put("gaux3", 6);
        SAMPLER_BINDINGS.put("gaux4", 7);

        // Depth targets
        SAMPLER_BINDINGS.put("depthtex0", BINDING_DEPTHTEX_BASE);
        SAMPLER_BINDINGS.put("depthtex1", BINDING_DEPTHTEX_BASE + 1);
        SAMPLER_BINDINGS.put("depthtex2", BINDING_DEPTHTEX_BASE + 2);

        // Shadow targets
        SAMPLER_BINDINGS.put("shadowtex0", BINDING_SHADOWTEX_BASE);
        SAMPLER_BINDINGS.put("shadow", BINDING_SHADOWTEX_BASE);
        SAMPLER_BINDINGS.put("shadowtex1", BINDING_SHADOWTEX_BASE + 1);
        SAMPLER_BINDINGS.put("watershadow", BINDING_SHADOWTEX_BASE + 1);
        for (int i = 0; i < 8; i++) {
            SAMPLER_BINDINGS.put("shadowcolor" + i, BINDING_SHADOWCOLOR_BASE + i);
        }

        // Special textures
        SAMPLER_BINDINGS.put("noisetex", BINDING_NOISETEX);
        SAMPLER_BINDINGS.put("normals", BINDING_NORMALS);
        SAMPLER_BINDINGS.put("specular", BINDING_SPECULAR);

        // Iris/OptiFine aliases
        SAMPLER_BINDINGS.put("gtexture", 0); // colortex0 alias for gbuffers
        SAMPLER_BINDINGS.put("lightmap", BINDING_DEPTHTEX_BASE); // Sometimes bound differently
        SAMPLER_BINDINGS.put("texture", 0);  // Another legacy alias
        SAMPLER_BINDINGS.put("tex", 0);      // Short alias
    }

    /**
     * Resolves a sampler name to its descriptor binding index.
     *
     * @param name Sampler uniform name from GLSL
     * @return Binding index, or -1 if unknown
     */
    public static int getBinding(String name) {
        Integer binding = SAMPLER_BINDINGS.get(name);
        return binding != null ? binding : -1;
    }

    /**
     * Checks if a sampler name is known.
     */
    public static boolean isKnownSampler(String name) {
        return SAMPLER_BINDINGS.containsKey(name);
    }

    /**
     * Builds an array of image views + samplers for all bindings in set 1.
     * Suitable for writing a VkDescriptorSet.
     *
     * @param gBuffer        G-buffer target set (provides colortex + depthtex)
     * @param shadowTargets  Shadow render targets (provides shadowtex + shadowcolor), nullable
     * @param noiseTexView   Noise texture image view (0 if not available)
     * @param noiseSampler   Noise texture sampler (0 if not available)
     * @param normalsView    PBR normals image view (0 if not available)
     * @param normalsSampler PBR normals sampler
     * @param specularView   PBR specular image view (0 if not available)
     * @param specularSampler PBR specular sampler
     * @return Array of [imageView, sampler] pairs, indexed by binding slot
     */
    public static long[][] buildSamplerBindings(
            GBufferTargets gBuffer,
            ShadowTargetProvider shadowTargets,
            long noiseTexView, long noiseSampler,
            long normalsView, long normalsSampler,
            long specularView, long specularSampler) {

        long[][] bindings = new long[TOTAL_BINDINGS][2]; // [view, sampler]

        // Color targets (read state respects flip)
        for (int i = 0; i < 16; i++) {
            RenderTarget rt = gBuffer.getReadTarget(i);
            if (rt != null) {
                bindings[BINDING_COLORTEX_BASE + i][0] = rt.getImageView();
                bindings[BINDING_COLORTEX_BASE + i][1] = rt.getSampler();
            }
        }

        // Depth targets
        for (int i = 0; i < 3; i++) {
            RenderTarget dt = gBuffer.getDepthTarget(i);
            if (dt != null) {
                bindings[BINDING_DEPTHTEX_BASE + i][0] = dt.getImageView();
                bindings[BINDING_DEPTHTEX_BASE + i][1] = dt.getSampler();
            }
        }

        // Shadow targets (if available)
        if (shadowTargets != null) {
            for (int i = 0; i < 2; i++) {
                RenderTarget st = shadowTargets.getShadowDepthTarget(i);
                if (st != null) {
                    bindings[BINDING_SHADOWTEX_BASE + i][0] = st.getImageView();
                    bindings[BINDING_SHADOWTEX_BASE + i][1] = st.getSampler();
                }
            }
            for (int i = 0; i < 8; i++) {
                RenderTarget sc = shadowTargets.getShadowColorTarget(i);
                if (sc != null) {
                    bindings[BINDING_SHADOWCOLOR_BASE + i][0] = sc.getImageView();
                    bindings[BINDING_SHADOWCOLOR_BASE + i][1] = sc.getSampler();
                }
            }
        }

        // Special textures
        bindings[BINDING_NOISETEX][0] = noiseTexView;
        bindings[BINDING_NOISETEX][1] = noiseSampler;
        bindings[BINDING_NORMALS][0] = normalsView;
        bindings[BINDING_NORMALS][1] = normalsSampler;
        bindings[BINDING_SPECULAR][0] = specularView;
        bindings[BINDING_SPECULAR][1] = specularSampler;

        return bindings;
    }

    /**
     * Interface for shadow target providers (Shadow system provides these).
     */
    public interface ShadowTargetProvider {
        /** Gets shadow depth target (0 = shadowtex0, 1 = shadowtex1) */
        RenderTarget getShadowDepthTarget(int index);
        /** Gets shadow color target (0-7 = shadowcolor0-7) */
        RenderTarget getShadowColorTarget(int index);
    }
}
