package net.vulkanium.render.pipeline;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanium.Vulkanium;
import net.vulkanium.resource.SPIRVCompiler;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily creates and caches {@link BasicPipeline} instances per vertex format.
 * Each unique format gets its own GLSL shaders compiled to SPIR-V and a Vulkan pipeline.
 */
public class PipelineRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/PipelineReg");

    private final Map<String, BasicPipeline> pipelines = new ConcurrentHashMap<>();
    private VkDevice device;
    private long renderPass;
    private SPIRVCompiler compiler;

    public void initialize(VkDevice device, long renderPass, SPIRVCompiler compiler) {
        this.device = device;
        this.renderPass = renderPass;
        this.compiler = compiler;
    }

    /**
     * Gets or creates a pipeline for the given vertex format.
     */
    public BasicPipeline getPipeline(VertexFormat format) {
        String key = formatKey(format);
        return pipelines.computeIfAbsent(key, k -> createPipeline(format, k));
    }

    private BasicPipeline createPipeline(VertexFormat format, String key) {
        // Determine which shader combo to use based on format elements.
        // CRITICAL: Distinguish UV0 (texture coords) from UV2 (lightmap).
        // Clouds have POSITION_TEX_COLOR_NORMAL (UV0 but NO UV2), while terrain blocks
        // have POSITION_TEX_COLOR_UV2_NORMAL. Without this distinction, clouds get routed
        // to the "block" shader which reads UV2 for lightmap brightness → nearly black.
        boolean hasPosition = false, hasUV0 = false, hasUV2 = false, hasColor = false, hasNormal = false;
        for (VertexFormatElement element : format.getElements()) {
            switch (element.getUsage()) {
                case POSITION -> hasPosition = true;
                case UV -> {
                    if (element.getIndex() == 0) hasUV0 = true;       // Texture coords
                    else if (element.getIndex() == 2) hasUV2 = true;  // Lightmap
                    // UV1 (overlay) is tracked but not used for pipeline selection
                }
                case COLOR -> hasColor = true;
                case NORMAL -> hasNormal = true;
            }
        }

        String vertSrc, fragSrc;
        String name;

        if (hasUV0 && hasColor && hasNormal && hasUV2) {
            // Full block format: position + tex + color + lightmap + normal
            // Used for terrain chunks and block models with lightmap data
            name = "block";
            vertSrc = BLOCK_VERT;
            fragSrc = BLOCK_FRAG;
        } else if (hasUV0 && hasColor) {
            // Clouds (POSITION_TEX_COLOR_NORMAL), GUI elements, etc.
            // NO lightmap (UV2), so don't use block shader which reads UV2
            name = "position_tex_color";
            vertSrc = POSITION_TEX_COLOR_VERT;
            fragSrc = POSITION_TEX_COLOR_FRAG;
        } else if (hasUV0) {
            name = "position_tex";
            vertSrc = POSITION_TEX_VERT;
            fragSrc = POSITION_TEX_FRAG;
        } else if (hasColor) {
            name = "position_color";
            vertSrc = POSITION_COLOR_VERT;
            fragSrc = POSITION_COLOR_FRAG;
        } else {
            name = "position_only";
            vertSrc = POSITION_ONLY_VERT;
            fragSrc = POSITION_ONLY_FRAG;
        }

        LOGGER.info("Creating pipeline '{}' for format key '{}' (pos={} uv0={} uv2={} col={} norm={})",
                name, key, hasPosition, hasUV0, hasUV2, hasColor, hasNormal);

        ByteBuffer vertSpirv = compiler.compileVertex(vertSrc, name + ".vert");
        ByteBuffer fragSpirv = compiler.compileFragment(fragSrc, name + ".frag");

        if (vertSpirv == null || fragSpirv == null) {
            LOGGER.error("Failed to compile shaders for format '{}'", key);
            return null;
        }

        BasicPipeline pipeline = new BasicPipeline();
        pipeline.initialize(device, renderPass, name, vertSpirv, fragSpirv, format);
        return pipeline;
    }

    public long getDescriptorSetLayout() {
        // Return the first pipeline's descriptor set layout (all share the same layout)
        for (BasicPipeline p : pipelines.values()) {
            return p.getDescriptorSetLayout();
        }
        return 0; // No pipelines yet
    }

    private String formatKey(VertexFormat format) {
        StringBuilder sb = new StringBuilder();
        for (VertexFormatElement element : format.getElements()) {
            if (element.getUsage() != VertexFormatElement.Usage.PADDING) {
                sb.append(element.getUsage().name()).append(element.getByteSize()).append('_');
            }
        }
        return sb.toString();
    }

    public void destroy() {
        for (BasicPipeline p : pipelines.values()) {
            if (p != null) p.destroy();
        }
        pipelines.clear();
    }

    // ─── GLSL 450 Shaders ──────────────────────────────────────────────

    // Location assignments must match the VertexFormat element order:
    // BasicPipeline assigns locations sequentially (skipping PADDING)

    private static final String POSITION_TEX_COLOR_VERT = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange; // x=FogStart, y=FogEnd
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(location = 0) out vec2 fragTexCoord;
            layout(location = 1) out vec4 fragColor;
            layout(location = 2) out vec4 fragColorMod;
            layout(location = 3) out float vertexDistance;
            void main() {
                gl_Position = ubo.MVP * vec4(Position, 1.0);
                fragTexCoord = (ubo.TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                fragColor = Color;
                fragColorMod = ubo.ColorModulator;
                vertexDistance = length((ubo.MVP * vec4(Position, 1.0)).xyz);
            }
            """;
    private static final String POSITION_TEX_COLOR_FRAG = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(binding = 1) uniform sampler2D Sampler0;
            layout(location = 0) in vec2 fragTexCoord;
            layout(location = 1) in vec4 fragColor;
            layout(location = 2) in vec4 fragColorMod;
            layout(location = 3) in float vertexDistance;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 tex = texture(Sampler0, fragTexCoord);
                vec4 baseColor = tex * fragColor * fragColorMod;
                // Match vanilla: only discard exact-zero alpha.
                // Keeps soft particle/cloud edges intact with additive blending.
                if (baseColor.a == 0.0) discard;
                float fogStart = ubo.FogRange.x;
                float fogEnd = ubo.FogRange.y;
                float fogFactor = (fogEnd > fogStart)
                    ? clamp((fogEnd - vertexDistance) / (fogEnd - fogStart), 0.0, 1.0)
                    : 1.0;
                // Only mix fog into RGB; preserve original alpha so blending works correctly.
                outColor = vec4(mix(ubo.FogColor.rgb, baseColor.rgb, fogFactor), baseColor.a);
            }
            """;

    private static final String POSITION_TEX_VERT = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 0) out vec2 fragTexCoord;
            layout(location = 1) out vec4 fragColorMod;
            layout(location = 2) out float vertexDistance;
            void main() {
                gl_Position = ubo.MVP * vec4(Position, 1.0);
                fragTexCoord = (ubo.TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                fragColorMod = ubo.ColorModulator;
                vertexDistance = length((ubo.MVP * vec4(Position, 1.0)).xyz);
            }
            """;
    private static final String POSITION_TEX_FRAG = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(binding = 1) uniform sampler2D Sampler0;
            layout(location = 0) in vec2 fragTexCoord;
            layout(location = 1) in vec4 fragColorMod;
            layout(location = 2) in float vertexDistance;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 tex = texture(Sampler0, fragTexCoord);
                vec4 baseColor = tex * fragColorMod;
                // Match vanilla rendertype_position_tex behavior:
                // Only discard exact-zero alpha pixels. The sun/moon use additive
                // blending (SRC_ALPHA, ONE) where even tiny alpha values contribute
                // to the soft emission glow. A higher threshold clips those soft edges
                // and creates a hard-edged disk instead of a gradual glow.
                if (baseColor.a == 0.0) discard;
                // Vanilla position_tex does NOT apply fog. Sun/moon call setupNoFog()
                // (fogStart=MAX_VALUE) before rendering. Applying fog here would tint
                // the sun/moon toward FogColor during sunrise/sunset, making them appear
                // opaque instead of luminous. Matches vanilla rendertype_position_tex.fsh.
                outColor = baseColor;
            }
            """;

    private static final String POSITION_COLOR_VERT = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 0) out vec4 fragColor;
            layout(location = 1) out vec4 fragColorMod;
            layout(location = 2) out float vertexDistance;
            void main() {
                gl_Position = ubo.MVP * vec4(Position, 1.0);
                fragColor = Color;
                fragColorMod = ubo.ColorModulator;
                vertexDistance = length((ubo.MVP * vec4(Position, 1.0)).xyz);
            }
            """;
    private static final String POSITION_COLOR_FRAG = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec4 fragColor;
            layout(location = 1) in vec4 fragColorMod;
            layout(location = 2) in float vertexDistance;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 baseColor = fragColor * fragColorMod;
                float fogStart = ubo.FogRange.x;
                float fogEnd = ubo.FogRange.y;
                float fogFactor = (fogEnd > fogStart)
                    ? clamp((fogEnd - vertexDistance) / (fogEnd - fogStart), 0.0, 1.0)
                    : 1.0;
                outColor = vec4(mix(ubo.FogColor.rgb, baseColor.rgb, fogFactor), baseColor.a);
            }
            """;

    private static final String POSITION_ONLY_VERT = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec3 Position;
            layout(location = 0) out vec4 fragColorMod;
            layout(location = 1) out float vertexDistance;
            void main() {
                gl_Position = ubo.MVP * vec4(Position, 1.0);
                fragColorMod = ubo.ColorModulator;
                vertexDistance = length((ubo.MVP * vec4(Position, 1.0)).xyz);
            }
            """;
    private static final String POSITION_ONLY_FRAG = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec4 fragColorMod;
            layout(location = 1) in float vertexDistance;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 baseColor = fragColorMod;
                float fogStart = ubo.FogRange.x;
                float fogEnd = ubo.FogRange.y;
                float fogFactor = (fogEnd > fogStart)
                    ? clamp((fogEnd - vertexDistance) / (fogEnd - fogStart), 0.0, 1.0)
                    : 1.0;
                outColor = vec4(mix(ubo.FogColor.rgb, baseColor.rgb, fogFactor), baseColor.a);
            }
            """;

    // ─── BLOCK format shader: Position + Color + UV + UV2(lightmap) + Normal ───
    // Used for terrain chunks and 3D block models (GUI items, held items).
    // Adds directional lighting from normals (two light sources, matching MC's
    // Lighting.setupFor3DItems()) and basic lightmap brightness from UV2.

    private static final String BLOCK_VERT = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;
            layout(location = 4) in vec4 Normal;
            layout(location = 0) out vec2 fragTexCoord;
            layout(location = 1) out vec4 fragColor;
            layout(location = 2) out vec4 fragColorMod;
            layout(location = 3) out float vertexDistance;
            layout(location = 4) out float fragLightBrightness;
            layout(location = 5) out vec3 fragNormal;
            void main() {
                gl_Position = ubo.MVP * vec4(Position, 1.0);
                fragTexCoord = (ubo.TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                fragColor = Color;
                fragColorMod = ubo.ColorModulator;
                vertexDistance = length((ubo.MVP * vec4(Position, 1.0)).xyz);
                // Lightmap brightness from UV2 (block/sky light encoded as shorts 0-240)
                fragLightBrightness = clamp(float(max(UV2.x, UV2.y)) / 240.0, 0.03, 1.0);
                // Pass normal to fragment shader for directional lighting
                fragNormal = Normal.xyz;
            }
            """;
    private static final String BLOCK_FRAG = """
            #version 450
            layout(binding = 0) uniform UBO {
                mat4 MVP;
                vec4 ColorModulator;
                vec4 FogColor;
                vec4 FogRange;
                mat4 TextureMat;
            } ubo;
            layout(binding = 1) uniform sampler2D Sampler0;
            layout(location = 0) in vec2 fragTexCoord;
            layout(location = 1) in vec4 fragColor;
            layout(location = 2) in vec4 fragColorMod;
            layout(location = 3) in float vertexDistance;
            layout(location = 4) in float fragLightBrightness;
            layout(location = 5) in vec3 fragNormal;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 tex = texture(Sampler0, fragTexCoord);
                vec4 baseColor = tex * fragColor * fragColorMod;
                if (baseColor.a < 0.003) discard;

                // Apply lightmap brightness from UV2
                baseColor.rgb *= fragLightBrightness;

                // Directional diffuse lighting for entities and UI block models.
                // Terrain vertex colors already have per-face shading baked in
                // (top=1.0, bottom=0.5, sides=0.6-0.8), so we detect entities/UI by
                // checking for near-white vertex color (entities use white vertices).
                //
                // MC uses two directional lights via light0_Direction/light1_Direction,
                // matching RenderSystem.setupLevelDiffuseLighting().
                // Light directions approximate the vanilla MC entity lighting setup.
                float vcAvg = (fragColor.r + fragColor.g + fragColor.b) / 3.0;
                if (vcAvg > 0.95) {
                    vec3 n = normalize(fragNormal);
                    vec3 light0 = normalize(vec3(0.2, 1.0, -0.7));   // Main sun direction
                    vec3 light1 = normalize(vec3(-0.2, 1.0, 0.7));   // Fill light direction
                    float NdotL0 = max(dot(n, light0), 0.0);
                    float NdotL1 = max(dot(n, light1), 0.0);
                    float diffuse = 0.4 + 0.6 * max(NdotL0, NdotL1);
                    baseColor.rgb *= diffuse;
                }

                // Fog
                float fogStart = ubo.FogRange.x;
                float fogEnd = ubo.FogRange.y;
                float fogFactor = (fogEnd > fogStart)
                    ? clamp((fogEnd - vertexDistance) / (fogEnd - fogStart), 0.0, 1.0)
                    : 1.0;
                outColor = vec4(mix(ubo.FogColor.rgb, baseColor.rgb, fogFactor), baseColor.a);
            }
            """;
}
