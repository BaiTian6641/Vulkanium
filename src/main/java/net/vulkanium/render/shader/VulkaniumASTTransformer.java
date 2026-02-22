package net.vulkanium.render.shader;

import io.github.douira.glsl_transformer.ast.node.*;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.*;
import io.github.douira.glsl_transformer.ast.node.expression.*;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.*;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.*;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.*;
import net.vulkanium.render.shader.VulkaniumGlslTransformer.PassType;
import net.vulkanium.render.shader.VulkaniumGlslTransformer.TransformParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST-based GLSL transformer using Iris's glsl-transformer library.
 *
 * <p>This replaces the regex-based {@link VulkaniumGlslTransformer} with proper
 * AST parsing, transformation, and re-printing. The pipeline is:</p>
 * <pre>
 *   1. OptiFineGlslPreprocessor  (text-level: strips #version, extracts DRAWBUFFERS, etc.)
 *   2. GlslCPreprocessor          (jcpp: resolves all #ifdef/#else/#endif/#define)
 *   3. VulkaniumASTTransformer   (AST-level: UBO injection, uniform remap, sampler binding,
 *                                  vertex decode, fragment output, varying locations)
 *   4. Post-AST fixups           (text-level: fragment output declarations)
 *   5. ShaderCompiler            (shaderc → SPIR-V)
 * </pre>
 *
 * <h3>Why AST over Regex?</h3>
 * <ul>
 *   <li>Correct handling of multi-line declarations, nested expressions, comments</li>
 *   <li>Reliable identifier renaming without false positives (e.g. "texture" in comments)</li>
 *   <li>Proper in/out location assignment by traversing declaration nodes</li>
 *   <li>Safe UBO block injection at the correct AST position</li>
 *   <li>Extensible for future transforms (type inference, dead code elimination)</li>
 * </ul>
 */
public class VulkaniumASTTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ASTTransform");

    // ═══════════════════════════════════════════════════════════════
    //  Singleton transformer instances (thread-safe, reusable)
    // ═══════════════════════════════════════════════════════════════

    /**
     * The core AST transformer. Source is expected to be fully preprocessed
     * by {@link GlslCPreprocessor} before reaching here — no #ifdef/#define
     * directives remain.
     */
    private static final SingleASTTransformer<VulkaniumJobParams> transformer;

    static {
        transformer = new SingleASTTransformer<>();
        transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        // No ChannelFilter needed — GlslCPreprocessor (jcpp) has already resolved
        // all preprocessor directives. The source is clean GLSL 450.
        transformer.setPrintType(PrintType.INDENTED);
    }

    /**
     * Job parameters passed through the transformer pipeline.
     */
    public static class VulkaniumJobParams implements JobParameters {
        public final TransformParams params;
        public final Map<String, Integer> samplerBindings;
        public final int[] renderTargets;

        public VulkaniumJobParams(TransformParams params, Map<String, Integer> samplerBindings,
                                  int[] renderTargets) {
            this.params = params;
            this.samplerBindings = samplerBindings != null ? samplerBindings : Collections.emptyMap();
            this.renderTargets = renderTargets != null ? renderTargets : new int[]{0};
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Uniform name → UBO member expression mapping
    // ═══════════════════════════════════════════════════════════════

    private static final Map<String, String> UNIFORM_REMAP = new LinkedHashMap<>();
    static {
        // Matrices
        UNIFORM_REMAP.put("gbufferModelView", "iris_GBufferModelView");
        UNIFORM_REMAP.put("gbufferModelViewInverse", "iris_GBufferModelViewInverse");
        UNIFORM_REMAP.put("gbufferProjection", "iris_GBufferProjection");
        UNIFORM_REMAP.put("gbufferProjectionInverse", "iris_GBufferProjectionInverse");
        UNIFORM_REMAP.put("gbufferPreviousModelView", "iris_PreviousModelViewMatrix");
        UNIFORM_REMAP.put("gbufferPreviousProjection", "iris_PreviousProjectionMatrix");
        UNIFORM_REMAP.put("modelViewMatrix", "iris_ModelViewMatrix");
        UNIFORM_REMAP.put("projectionMatrix", "iris_ProjectionMatrix");
        UNIFORM_REMAP.put("modelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        UNIFORM_REMAP.put("projectionMatrixInverse", "iris_ProjectionMatrixInverse");
        UNIFORM_REMAP.put("shadowModelView", "iris_ShadowModelView");
        UNIFORM_REMAP.put("shadowProjection", "iris_ShadowProjection");
        UNIFORM_REMAP.put("shadowModelViewInverse", "iris_ShadowModelViewInverse");
        UNIFORM_REMAP.put("shadowProjectionInverse", "iris_ShadowProjectionInverse");
        // GL legacy matrix aliases
        UNIFORM_REMAP.put("gl_ModelViewMatrix", "iris_ModelViewMatrix");
        UNIFORM_REMAP.put("gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
        UNIFORM_REMAP.put("gl_ProjectionMatrix", "iris_ProjectionMatrix");
        UNIFORM_REMAP.put("gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");
        UNIFORM_REMAP.put("gl_NormalMatrix", "mat3(iris_NormalMat4)");

        // Vectors
        UNIFORM_REMAP.put("cameraPosition", "iris_CameraPosition.xyz");
        UNIFORM_REMAP.put("previousCameraPosition", "iris_PreviousCameraPosition.xyz");
        UNIFORM_REMAP.put("sunPosition", "iris_SunPosition.xyz");
        UNIFORM_REMAP.put("moonPosition", "iris_MoonPosition.xyz");
        UNIFORM_REMAP.put("shadowLightPosition", "iris_ShadowLightPosition.xyz");
        UNIFORM_REMAP.put("upPosition", "iris_UpPosition.xyz");
        UNIFORM_REMAP.put("skyColor", "iris_SkyColor.xyz");
        UNIFORM_REMAP.put("fogColor", "iris_FogColor.rgb");
        UNIFORM_REMAP.put("entityColor", "iris_EntityColor");
        UNIFORM_REMAP.put("fogColor4", "iris_FogColor");  // Some packs use fogColor4 for vec4 fog
        UNIFORM_REMAP.put("chunkOffset", "iris_ChunkOffset.xyz");
        UNIFORM_REMAP.put("colorModulator", "iris_ColorModulator");

        // Scalars (packed into vec4s in UBO)
        UNIFORM_REMAP.put("viewWidth", "iris_ScreenSize.x");
        UNIFORM_REMAP.put("viewHeight", "iris_ScreenSize.y");
        UNIFORM_REMAP.put("aspectRatio", "iris_ViewParams.x");
        UNIFORM_REMAP.put("near", "iris_ViewParams.y");
        UNIFORM_REMAP.put("far", "iris_ViewParams.z");
        UNIFORM_REMAP.put("frameTimeCounter", "iris_Time.x");
        UNIFORM_REMAP.put("worldTime", "iris_Time.y");
        UNIFORM_REMAP.put("frameCounter", "int(iris_Time.z)");
        UNIFORM_REMAP.put("sunAngle", "iris_Time.w");
        UNIFORM_REMAP.put("fogStart", "iris_FogParams.x");
        UNIFORM_REMAP.put("fogEnd", "iris_FogParams.y");
        UNIFORM_REMAP.put("fogDensity", "iris_FogParams.z");
        UNIFORM_REMAP.put("fogShape", "int(iris_FogParams.w)");
        UNIFORM_REMAP.put("fogMode", "int(iris_FogParams.w)");
        UNIFORM_REMAP.put("rainStrength", "iris_Weather.x");
        UNIFORM_REMAP.put("wetness", "iris_Weather.y");
        UNIFORM_REMAP.put("thunderStrength", "iris_Weather.z");
        UNIFORM_REMAP.put("nightVision", "iris_PlayerState.x");
        UNIFORM_REMAP.put("blindness", "iris_PlayerState.y");
        UNIFORM_REMAP.put("darknessFactor", "iris_PlayerState.z");
        UNIFORM_REMAP.put("playerMood", "iris_PlayerState.w");
        UNIFORM_REMAP.put("moonPhase", "int(iris_WorldState.x)");
        UNIFORM_REMAP.put("isEyeInWater", "int(iris_WorldState.y)");
        UNIFORM_REMAP.put("centerDepthSmooth", "iris_DepthParams.x");
        UNIFORM_REMAP.put("alphaTestRef", "iris_AlphaTestRef.x");

        // Compound accessors
        UNIFORM_REMAP.put("eyeBrightness", "ivec2(iris_EyeBrightness.xy)");
        UNIFORM_REMAP.put("eyeBrightnessSmooth", "ivec2(iris_EyeBrightness.zw)");
        UNIFORM_REMAP.put("atlasSize", "ivec2(iris_AtlasSize.xy)");

        // Shadow params
        UNIFORM_REMAP.put("shadowMapResolution", "int(iris_ShadowParams.x)");
        UNIFORM_REMAP.put("shadowDistance", "iris_ShadowParams.y");
        UNIFORM_REMAP.put("shadowDistanceRenderMul", "iris_ShadowParams.z");

        // Held items
        UNIFORM_REMAP.put("heldItemId", "int(iris_HeldItems.x)");
        UNIFORM_REMAP.put("heldBlockLightValue", "int(iris_HeldItems.y)");
        UNIFORM_REMAP.put("heldItemId2", "int(iris_HeldItems.z)");
        UNIFORM_REMAP.put("heldBlockLightValue2", "int(iris_HeldItems.w)");

        // ── Extended custom uniforms (formerly missing → const 0) ──
        // Standard uniforms that had no UBO mapping:
        UNIFORM_REMAP.put("screenBrightness", "iris_CustomA.x");
        UNIFORM_REMAP.put("eyeAltitude", "iris_CustomA.y");
        UNIFORM_REMAP.put("worldDay", "int(iris_CustomA.z)");
        UNIFORM_REMAP.put("darknessLightFactor", "iris_CustomA.w");
        UNIFORM_REMAP.put("frameTime", "iris_CustomC.w");
        UNIFORM_REMAP.put("renderStage", "int(iris_RenderState.x)");

        // Hardcoded custom uniform expressions (Iris HardcodedCustomUniforms equivalents):
        UNIFORM_REMAP.put("framemod8", "mod(iris_Time.z, 8.0)");
        UNIFORM_REMAP.put("maxBlindnessDarkness", "max(iris_PlayerState.y, iris_PlayerState.z)");

        // CPU-smoothed custom uniforms (populated in DrawBatcher):
        UNIFORM_REMAP.put("isEyeInCave", "iris_CustomB.y");
        UNIFORM_REMAP.put("eyeBrightnessM", "iris_CustomB.z");
        UNIFORM_REMAP.put("eyeBrightnessM2", "iris_CustomB.w");
        UNIFORM_REMAP.put("rainFactor", "iris_CustomC.x");
        UNIFORM_REMAP.put("frameTimeSmooth", "iris_CustomC.y");

        // Camera integer/fractional position splits (Iris 1.8+ feature):
        UNIFORM_REMAP.put("cameraPositionFract", "fract(iris_CameraPosition.xyz)");
        UNIFORM_REMAP.put("previousCameraPositionFract", "fract(iris_PreviousCameraPosition.xyz)");
        UNIFORM_REMAP.put("cameraPositionInt", "ivec3(iris_CameraPositionInt.xyz)");
        UNIFORM_REMAP.put("previousCameraPositionInt", "ivec3(iris_PrevCameraPositionInt.xyz)");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Legacy GL builtin → expression mapping
    // ═══════════════════════════════════════════════════════════════

    private static final Map<String, String> GL_BUILTIN_REMAP = new LinkedHashMap<>();
    static {
        GL_BUILTIN_REMAP.put("gl_FogFragCoord", "0.0");
        GL_BUILTIN_REMAP.put("gl_FrontColor", "vec4(1.0)");

        // NOTE: gl_TextureMatrix[N] is handled in postPrintFixups (regex)
        // because the AST parses it as an array subscript expression,
        // so identifierIndex.has("gl_TextureMatrix[0]") returns false.
    }

    // Vertex builtins remapped per pass type
    private static final Map<String, String> COMPOSITE_VERTEX_REMAP = new LinkedHashMap<>();
    static {
        COMPOSITE_VERTEX_REMAP.put("gl_Vertex", "vkm_composite_Position()");
        COMPOSITE_VERTEX_REMAP.put("gl_Color", "vec4(1.0)");
        COMPOSITE_VERTEX_REMAP.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
        COMPOSITE_VERTEX_REMAP.put("gl_MultiTexCoord0", "vec4(vkm_composite_TexCoord, 0.0, 1.0)");
        COMPOSITE_VERTEX_REMAP.put("gl_MultiTexCoord1", "vec4(vkm_composite_TexCoord, 0.0, 1.0)");
        COMPOSITE_VERTEX_REMAP.put("vaPosition", "vec3(0.0)"); // dead code in composites, but needs declaration
        for (int i = 2; i <= 7; i++) {
            COMPOSITE_VERTEX_REMAP.put("gl_MultiTexCoord" + i, "vec4(0.0)");
        }
    }

    private static final Map<String, String> TERRAIN_VERTEX_REMAP = new LinkedHashMap<>();
    static {
        TERRAIN_VERTEX_REMAP.put("gl_Vertex", "iris_vk_Vertex");
        TERRAIN_VERTEX_REMAP.put("gl_Color", "iris_vk_Color");
        TERRAIN_VERTEX_REMAP.put("gl_Normal", "iris_compat_Normal");
        TERRAIN_VERTEX_REMAP.put("gl_MultiTexCoord0", "vec4(iris_vk_TexCoord0, 0.0, 1.0)");
        TERRAIN_VERTEX_REMAP.put("gl_MultiTexCoord1", "vec4(iris_vk_LightCoord, 0.0, 1.0)");
        TERRAIN_VERTEX_REMAP.put("gl_MultiTexCoord2", "vec4(iris_vk_LightCoord, 0.0, 1.0)");
        TERRAIN_VERTEX_REMAP.put("at_tangent", "iris_compat_Tangent");
        TERRAIN_VERTEX_REMAP.put("mc_Entity", "vec4(float(iris_vk_EntityId), 0.0, 0.0, 0.0)");
        TERRAIN_VERTEX_REMAP.put("at_midBlock", "vec4(0.0)");
        TERRAIN_VERTEX_REMAP.put("mc_midTexCoord", "vec4(iris_vk_MidTexCoord, 0.0, 1.0)");
        for (int i = 3; i <= 7; i++) {
            TERRAIN_VERTEX_REMAP.put("gl_MultiTexCoord" + i, "vec4(0.0)");
        }
    }

    private static final Map<String, String> ENTITY_VERTEX_REMAP = new LinkedHashMap<>();
    static {
        ENTITY_VERTEX_REMAP.put("gl_Vertex", "vec4(vkm_Entity_Position, 1.0)");
        ENTITY_VERTEX_REMAP.put("gl_Color", "vkm_Entity_Color");
        ENTITY_VERTEX_REMAP.put("gl_Normal", "vkm_Entity_Normal");
        ENTITY_VERTEX_REMAP.put("gl_MultiTexCoord0", "vec4(vkm_Entity_UV0, 0.0, 1.0)");
        ENTITY_VERTEX_REMAP.put("gl_MultiTexCoord1", "vec4(vec2(vkm_Entity_UV2), 0.0, 1.0)");
        ENTITY_VERTEX_REMAP.put("gl_MultiTexCoord2", "vec4(vec2(vkm_Entity_UV2), 0.0, 1.0)");
        ENTITY_VERTEX_REMAP.put("vaPosition", "vkm_Entity_Position");
        ENTITY_VERTEX_REMAP.put("vaNormal", "vkm_Entity_Normal");
        ENTITY_VERTEX_REMAP.put("at_tangent", "vec4(1.0, 0.0, 0.0, 1.0)");
        ENTITY_VERTEX_REMAP.put("mc_Entity", "vec4(-1.0, 0.0, 0.0, 0.0)");
        ENTITY_VERTEX_REMAP.put("at_midBlock", "vec4(0.0)");
        ENTITY_VERTEX_REMAP.put("mc_midTexCoord", "vec4(0.0, 0.0, 0.0, 1.0)");
        for (int i = 3; i <= 7; i++) {
            ENTITY_VERTEX_REMAP.put("gl_MultiTexCoord" + i, "vec4(0.0)");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  UBO block source (injected as external declaration)
    // ═══════════════════════════════════════════════════════════════

    private static final String VULKANIUM_UBO = """
            layout(set = 0, binding = 0, std140) uniform VulkaniumUniforms {
                mat4 iris_ModelViewMatrix;
                mat4 iris_ModelViewMatrixInverse;
                mat4 iris_ProjectionMatrix;
                mat4 iris_ProjectionMatrixInverse;
                mat4 iris_PreviousModelViewMatrix;
                mat4 iris_PreviousProjectionMatrix;
                mat4 iris_ShadowModelView;
                mat4 iris_ShadowProjection;
                mat4 iris_ShadowModelViewInverse;
                mat4 iris_ShadowProjectionInverse;
                mat4 iris_NormalMat4;
                mat4 iris_TextureMatrix;
                vec4 iris_CameraPosition;
                vec4 iris_PreviousCameraPosition;
                vec4 iris_SunPosition;
                vec4 iris_MoonPosition;
                vec4 iris_ShadowLightPosition;
                vec4 iris_UpPosition;
                vec4 iris_SkyColor;
                vec4 iris_FogColor;
                vec4 iris_EntityColor;
                vec4 iris_ChunkOffset;
                vec4 iris_ColorModulator;
                vec4 iris_CustomA;
                vec4 iris_CustomB;
                vec4 iris_CustomC;
                vec4 iris_CameraPositionInt;
                vec4 iris_PrevCameraPositionInt;
                vec4 iris_ScreenSize;
                vec4 iris_ViewParams;
                vec4 iris_Time;
                vec4 iris_FogParams;
                vec4 iris_Weather;
                vec4 iris_PlayerState;
                vec4 iris_EyeBrightness;
                vec4 iris_WorldState;
                vec4 iris_DepthParams;
                vec4 iris_AtlasSize;
                vec4 iris_RenderState;
                vec4 iris_BlocklightColor;
                vec4 iris_ShadowParams;
                vec4 iris_HeldItems;
                vec4 iris_BiomeData;
                vec4 iris_AlphaTestRef;
                vec4 iris_HdrParams;
                vec4 iris_HdrDisplay;
                mat4 iris_GBufferModelView;
                mat4 iris_GBufferModelViewInverse;
                mat4 iris_GBufferProjection;
                mat4 iris_GBufferProjectionInverse;
            };
            """;

    // ═══════════════════════════════════════════════════════════════
    //  Vertex decode preambles (injected as external declarations)
    // ═══════════════════════════════════════════════════════════════

    // UV.y is flipped (0.5 - y*0.5) to account for Vulkan's top-down texture layout.
    // Fullscreen triangle positions generated from gl_VertexIndex.
    // With the positive-viewport approach (no gl_Position.y flip), we use standard
    // Vulkan positive-height viewport. gl_FragCoord.y = 0 at NDC y=-1].
    // The scene is stored bottom-up in the framebuffer:
    //   - NDC y=-1 (scene bottom) → framebuffer row 0 → texture V=0
    //   - NDC y=+1 (scene top)    → framebuffer last row → texture V=1
    // This matches OpenGL convention: texcoord V=0 at bottom, V=1 at top.
    // NDC reconstruction (texcoord * 2 - 1) gives correct OpenGL NDC.
    private static final String COMPOSITE_VERTEX_PREAMBLE = """
            vec2 vkm_composite_TexCoord;
            vec4 vkm_composite_ClipPos;
            vec4 vkm_composite_Position() {
                float x = -1.0 + float((gl_VertexIndex & 1) << 2);
                float y = -1.0 + float((gl_VertexIndex & 2) << 1);
                vkm_composite_TexCoord = vec2(x * 0.5 + 0.5, y * 0.5 + 0.5);
                vkm_composite_ClipPos = vec4(x, y, 0.0, 1.0);
                return vec4(x, -y, 0.0, 1.0);
            }
            """;

    // Terrain vertex inputs — MUST match BasicPipeline.createAttributeDescriptions
    // location scheme for Vulkanium extended terrain format (36 bytes):
    //   0=Position(vec3,R32G32B32_SFLOAT), 1=UV0(vec2,R32G32_SFLOAT),
    //   2=Color(vec4,R8G8B8A8_UNORM), 3=UV2/lightmap(ivec2,R16G16_SINT),
    //   4=Normal(vec4,R8G8B8A8_SNORM), 5=mc_Entity(ivec2,R16G16_SINT)
    private static final String TERRAIN_VERTEX_INPUTS = """
            layout(location = 0) in vec3 vkm_Position;
            layout(location = 1) in vec2 vkm_TexCoord;
            layout(location = 2) in vec4 vkm_Color;
            layout(location = 3) in ivec2 vkm_LightCoord;
            layout(location = 4) in vec4 vkm_NormalPacked;
            layout(location = 5) in ivec2 vkm_Entity;
            """;

    private static final String TERRAIN_VERTEX_DECODED_VARS = """
            vec4 iris_vk_Vertex;
            vec4 iris_vk_Color;
            vec2 iris_vk_TexCoord0;
            vec2 iris_vk_LightCoord;
            vec3 iris_compat_Normal;
            vec4 iris_compat_Tangent;
            vec2 iris_vk_MidTexCoord;
            int  iris_vk_EntityId;
            """;

    private static final String TERRAIN_VERTEX_DECODE_FN = """
            void vkm_decodeVertex() {
                iris_vk_Vertex = vec4(vkm_Position + iris_ChunkOffset.xyz, 1.0);
                iris_vk_Color = vkm_Color;
                iris_vk_TexCoord0 = vkm_TexCoord;
                iris_vk_LightCoord = vec2(vkm_LightCoord);
                vec3 rawNormal = vkm_NormalPacked.xyz;
                float normalLen = length(rawNormal);
                iris_compat_Normal = normalLen > 0.0001 ? normalize(rawNormal) : vec3(0.0, 1.0, 0.0);
                // MC BLOCK format has no tangent — synthesize from normal
                vec3 tangentDir = abs(iris_compat_Normal.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
                iris_compat_Tangent = vec4(normalize(cross(iris_compat_Normal, tangentDir)), 1.0);
                iris_vk_MidTexCoord = vkm_TexCoord;
                iris_vk_EntityId = vkm_Entity.x; // block material ID from block.properties
            }
            """;

    // Entity vertex inputs — MUST match BasicPipeline.createAttributeDescriptions
    // location scheme for MC's entity formats (POSITION, UV0, COLOR, UV2, NORMAL):
    //   0=Position(vec3), 1=UV0(vec2), 2=Color(vec4), 3=UV2/lightmap(ivec2),
    //   4=Normal(vec3). UV1 (overlay) is skipped by the pipeline.
    private static final String ENTITY_VERTEX_INPUTS = """
            layout(location = 0) in vec3 vkm_Entity_Position;
            layout(location = 1) in vec2 vkm_Entity_UV0;
            layout(location = 2) in vec4 vkm_Entity_Color;
            layout(location = 3) in ivec2 vkm_Entity_UV2;
            layout(location = 4) in vec3 vkm_Entity_Normal;
            """;

    private static final String PARTICLE_VERTEX_INPUTS = """
            layout(location = 0) in vec3 vkm_Particle_Position;
            layout(location = 1) in vec2 vkm_Particle_UV0;
            layout(location = 2) in vec4 vkm_Particle_Color;
            layout(location = 3) in ivec2 vkm_Particle_UV2;
            """;

    private static final Map<String, String> PARTICLE_VERTEX_REMAP = new LinkedHashMap<>();
    static {
        PARTICLE_VERTEX_REMAP.put("gl_Vertex", "vec4(vkm_Particle_Position, 1.0)");
        PARTICLE_VERTEX_REMAP.put("gl_Color", "vkm_Particle_Color");
        PARTICLE_VERTEX_REMAP.put("gl_Normal", "vec3(0.0, 1.0, 0.0)");
        PARTICLE_VERTEX_REMAP.put("gl_MultiTexCoord0", "vec4(vkm_Particle_UV0, 0.0, 1.0)");
        PARTICLE_VERTEX_REMAP.put("gl_MultiTexCoord1", "vec4(vec2(vkm_Particle_UV2), 0.0, 1.0)");
        PARTICLE_VERTEX_REMAP.put("gl_MultiTexCoord2", "vec4(vec2(vkm_Particle_UV2), 0.0, 1.0)");
        for (int i = 3; i <= 7; i++) {
            PARTICLE_VERTEX_REMAP.put("gl_MultiTexCoord" + i, "vec4(0.0)");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Transforms a pre-processed GLSL source using AST parsing.
     *
     * <p>This is the main entry point. The source MUST have been preprocessed
     * by {@link GlslCPreprocessor} first (all #ifdef/#define resolved).</p>
     *
     * @param source Fully preprocessed GLSL source (no preprocessor directives except #version/#extension)
     * @param params Transform parameters (pass type, stage, samplers, targets)
     * @return Transformed GLSL 450 source ready for SPIR-V compilation
     */
    public static String transform(String source, TransformParams params) {
        return transformAST(source, params);
    }

    /**
     * AST-based transformation pipeline.
     */
    private static String transformAST(String source, TransformParams params) {
        // The SingleASTTransformer works by setting a transformation lambda,
        // then calling transform(input). We configure it per-call.
        // Since SingleASTTransformer is not thread-safe for the transformation
        // lambda, we synchronize on it.
        synchronized (transformer) {
            transformer.setTransformation((TranslationUnit tree, Root root) -> {
                transformTree(tree, root, params);
            });
            return transformer.transform(source);
        }
    }

    /**
     * Core AST transformation — operates on the parsed tree.
     */
    private static void transformTree(TranslationUnit tree, Root root, TransformParams params) {
        // ── 1. Inject VulkaniumUniforms UBO block ──
        injectUBO(tree, root);

        // ── 2. Remap uniform declarations → remove (now in UBO) ──
        removeRemappedUniforms(tree, root);

        // ── 3. Remap sampler declarations → add layout(set=1, binding=N) ──
        remapSamplerBindings(tree, root, params);

        // ── 4. Rename uniform references → UBO member expressions ──
        renameUniforms(tree, root, params);

        // ── 4.5. For COMPOSITE passes, replace per-draw GL matrices with identity ──
        // In Iris/OpenGL, gl_ModelViewMatrix = identity and gl_ProjectionMatrix = scale
        // matrix for composite fullscreen passes. gbufferModelView/gbufferProjection
        // remain as the camera matrices. Since we've separated gbufferProjection →
        // iris_GBufferProjection, we can safely replace per-draw matrix references
        // with mat4(1.0) for composites without affecting gbuffer matrix readers.
        if (params.passType == PassType.COMPOSITE) {
            replaceCompositeGLMatrices(tree, root);
        }

        // ── 5. Rename legacy GL builtins ──
        renameGLBuiltins(tree, root, params);

        // ── 6. Upgrade storage qualifiers (attribute→in, varying→in/out) ──
        upgradeStorageQualifiers(tree, root, params);

        // ── 7. Legacy texture function upgrade ──
        renameLegacyTextureFunctions(tree, root);

        // ── 8. Vertex-specific transforms ──
        if (params.isVertex) {
            transformVertex(tree, root, params);
        }

        // ── 9. GL builtin renames ──
        renameGLVertexIndex(tree, root);

        // ── 10. Assign layout(location=N) to bare in/out varyings ──
        assignVaryingLocations(tree, root, params);

        // ── 11. Wrap remaining non-opaque uniforms into secondary UBO ──
        // (Handled in postPrintFixups since detecting opaque vs non-opaque types
        //  via AST node inspection is unreliable without full type resolution)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Individual transform steps
    // ═══════════════════════════════════════════════════════════════

    /**
     * Injects the Vulkanium UBO interface block.
     */
    private static void injectUBO(TranslationUnit tree, Root root) {
        // Check if already injected (idempotent)
        if (root.identifierIndex.has("VulkaniumUniforms")) return;

        tree.parseAndInjectNode(transformer, ASTInjectionPoint.BEFORE_DECLARATIONS, VULKANIUM_UBO);
    }

    /**
     * Removes uniform declarations for names that are now in the UBO.
     */
    private static void removeRemappedUniforms(TranslationUnit tree, Root root) {
        // Collect all external declarations to remove
        Set<DeclarationExternalDeclaration> toRemove = new LinkedHashSet<>();

        for (String uniformName : UNIFORM_REMAP.keySet()) {
            if (!root.identifierIndex.has(uniformName)) continue;

            var identifiers = root.identifierIndex.getStream(uniformName).toList();
            for (var id : identifiers) {
                // Walk up to find if this identifier is in a uniform declaration
                var typeAndInit = id.getAncestor(TypeAndInitDeclaration.class);
                if (typeAndInit == null) continue;

                var extDecl = typeAndInit.getAncestor(DeclarationExternalDeclaration.class);
                if (extDecl == null) continue;

                String srcText = ASTPrinter.print(PrintType.COMPACT, extDecl);
                if (srcText.contains("uniform")) {
                    toRemove.add(extDecl);
                }
            }
        }

        // Remove collected declarations
        for (var decl : toRemove) {
            decl.detachAndDelete();
        }
    }

    /**
     * Remaps sampler/image uniform declarations to include layout(set=0, binding=N+1).
     * Binding 0 is reserved for the UBO, so samplers start at binding 1.
     */
    private static void remapSamplerBindings(TranslationUnit tree, Root root, TransformParams params) {
        if (params.samplerBindings == null || params.samplerBindings.isEmpty()) return;

        // Collect replacements first to avoid concurrent modification
        Map<DeclarationExternalDeclaration, String> replacements = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : params.samplerBindings.entrySet()) {
            String samplerName = entry.getKey();
            int binding = entry.getValue();

            if (!root.identifierIndex.has(samplerName)) continue;

            var identifiers = root.identifierIndex.getStream(samplerName).toList();
            for (var id : identifiers) {
                var typeAndInit = id.getAncestor(TypeAndInitDeclaration.class);
                if (typeAndInit == null) continue;
                var extDecl = typeAndInit.getAncestor(DeclarationExternalDeclaration.class);
                if (extDecl == null) continue;

                String srcText = ASTPrinter.print(PrintType.COMPACT, extDecl);
                if (!srcText.contains("sampler") && !srcText.contains("image")) continue;

                String type = extractType(srcText);
                if (type == null) continue;

                String replacement = "layout(set = 0, binding = " + (binding + 1) + ") uniform "
                        + type + " " + samplerName + ";";
                replacements.put(extDecl, replacement);
            }
        }

        // Apply replacements
        for (Map.Entry<DeclarationExternalDeclaration, String> entry : replacements.entrySet()) {
            try {
                var newDecl = transformer.parseSeparateExternalDeclaration(entry.getValue());
                entry.getKey().replaceByAndDelete(newDecl);
            } catch (Exception e) {
                LOGGER.debug("Failed to remap sampler: {}", e.getMessage());
            }
        }
    }

    /**
     * Renames uniform references to UBO member expressions.
     */
    private static void renameUniforms(TranslationUnit tree, Root root, TransformParams params) {
        for (Map.Entry<String, String> entry : UNIFORM_REMAP.entrySet()) {
            String oldName = entry.getKey();
            String newExpr = entry.getValue();

            if (!root.identifierIndex.has(oldName)) continue;

            // If the replacement is a simple identifier (no dots, no parens), use rename
            if (isSimpleIdentifier(newExpr)) {
                root.rename(oldName, newExpr);
            } else {
                // Complex expression replacement (e.g., "iris_CameraPosition.xyz")
                root.replaceReferenceExpressions(transformer, oldName, newExpr);
            }
        }
    }

    /**
     * For COMPOSITE passes, replaces per-draw GL matrix UBO members with identity.
     *
     * <p>After uniform remapping, gl_ModelViewMatrix → iris_ModelViewMatrix, etc.
     * For composite fullscreen passes, these per-draw matrices should be identity
     * (matching Iris behavior where gl_ModelViewMatrix = mat4(1.0) for composites).
     * The separate gbuffer matrices (iris_GBufferModelView, iris_GBufferProjection)
     * still carry the camera matrices for ray reconstruction.</p>
     */
    private static void replaceCompositeGLMatrices(TranslationUnit tree, Root root) {
        // Replace per-draw model-view matrices with identity
        if (root.identifierIndex.has("iris_ModelViewMatrix")) {
            root.replaceReferenceExpressions(transformer, "iris_ModelViewMatrix", "mat4(1.0)");
        }
        if (root.identifierIndex.has("iris_ModelViewMatrixInverse")) {
            root.replaceReferenceExpressions(transformer, "iris_ModelViewMatrixInverse", "mat4(1.0)");
        }
        // Replace per-draw projection matrices with identity
        if (root.identifierIndex.has("iris_ProjectionMatrix")) {
            root.replaceReferenceExpressions(transformer, "iris_ProjectionMatrix", "mat4(1.0)");
        }
        if (root.identifierIndex.has("iris_ProjectionMatrixInverse")) {
            root.replaceReferenceExpressions(transformer, "iris_ProjectionMatrixInverse", "mat4(1.0)");
        }
        // Replace normal matrix with identity
        if (root.identifierIndex.has("iris_NormalMat4")) {
            root.replaceReferenceExpressions(transformer, "iris_NormalMat4", "mat4(1.0)");
        }
    }

    /**
     * Renames legacy GL builtins to Vulkanium equivalents.
     */
    private static void renameGLBuiltins(TranslationUnit tree, Root root, TransformParams params) {
        for (Map.Entry<String, String> entry : GL_BUILTIN_REMAP.entrySet()) {
            String oldName = entry.getKey();
            String newExpr = entry.getValue();

            if (!root.identifierIndex.has(oldName)) continue;

            if (isSimpleIdentifier(newExpr)) {
                root.rename(oldName, newExpr);
            } else {
                root.replaceReferenceExpressions(transformer, oldName, newExpr);
            }
        }
    }

    /**
     * Upgrades attribute→in, varying→in/out based on shader stage.
     */
    private static void upgradeStorageQualifiers(TranslationUnit tree, Root root, TransformParams params) {
        // The lexer/parser handles this through the grammar in newer GLSL versions.
        // For legacy packs using #version 120/130 syntax that was already rewritten
        // to #version 450 by OptiFineGlslPreprocessor, the keyword remapping is
        // still needed. Since the preprocessor already sets 450, and the
        // ChannelFilter strips leftover directives, `attribute` and `varying`
        // may have been parsed as identifiers. Handle via regex post-processing
        // if the AST doesn't pick them up as storage qualifiers.
        // (This is handled in the post-print regex fixup step)
    }

    /**
     * Renames legacy texture functions (texture2D→texture, etc.).
     */
    private static void renameLegacyTextureFunctions(TranslationUnit tree, Root root) {
        renameFunctionIfPresent(root, "texture2D", "texture");
        renameFunctionIfPresent(root, "texture3D", "texture");
        renameFunctionIfPresent(root, "textureCube", "texture");
        renameFunctionIfPresent(root, "texture2DLod", "textureLod");
        renameFunctionIfPresent(root, "texture3DLod", "textureLod");
        renameFunctionIfPresent(root, "textureCubeLod", "textureLod");
        renameFunctionIfPresent(root, "texture2DGrad", "textureGrad");
        renameFunctionIfPresent(root, "texture2DGradARB", "textureGrad");
        renameFunctionIfPresent(root, "texture2DProj", "textureProj");
        renameFunctionIfPresent(root, "texture2DProjLod", "textureProjLod");

        // Rename gcolor/texture sampler → gtexture (avoid clash with texture() function)
        if (root.identifierIndex.has("gcolor")) {
            root.rename("gcolor", "gtexture");
        }
    }

    private static void renameFunctionIfPresent(Root root, String oldName, String newName) {
        if (root.identifierIndex.has(oldName)) {
            // Collect first to avoid concurrent modification of the identifier index
            var identifiers = root.identifierIndex.getStream(oldName).toList();
            for (var id : identifiers) {
                if (id.getParent() instanceof FunctionCallExpression) {
                    id.setName(newName);
                }
            }
        }
    }

    /**
     * Vertex-specific transforms: inject inputs and remap builtins.
     */
    private static void transformVertex(TranslationUnit tree, Root root, TransformParams params) {
        Map<String, String> remap;
        switch (params.passType) {
            case COMPOSITE -> {
                // Inject composite fullscreen triangle preamble
                injectCodeBlock(tree, COMPOSITE_VERTEX_PREAMBLE);
                remap = COMPOSITE_VERTEX_REMAP;

                // Ensure the composite position function is called at start of main
                // (shader may use vaPosition instead of gl_Vertex, so the remap
                //  gl_Vertex → vkm_composite_Position() might not trigger)
                tree.prependMainFunctionBody(transformer, "vkm_composite_Position();");

                // Force gl_Position override at end of main
                tree.appendMainFunctionBody(transformer,
                        "gl_Position = vkm_composite_ClipPos;");
            }
            case TERRAIN, SHADOW -> {
                injectCodeBlock(tree, TERRAIN_VERTEX_INPUTS);
                injectCodeBlock(tree, TERRAIN_VERTEX_DECODED_VARS);
                injectCodeBlock(tree, TERRAIN_VERTEX_DECODE_FN);
                tree.prependMainFunctionBody(transformer, "vkm_decodeVertex();");
                remap = TERRAIN_VERTEX_REMAP;

                // Vulkan Z-depth remap: OpenGL NDC z∈[-1,1] → Vulkan z∈[0,1]
                // No Y-flip needed — we use positive-height viewport with CW front face
                // so gl_FragCoord matches OpenGL convention (y=0 at bottom).
                tree.appendMainFunctionBody(transformer,
                        "gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;");
            }
            case ENTITY, HAND -> {
                injectCodeBlock(tree, ENTITY_VERTEX_INPUTS);
                remap = ENTITY_VERTEX_REMAP;

                // Vulkan Z-depth remap only — no Y-flip (positive viewport + CW front face)
                tree.appendMainFunctionBody(transformer,
                        "gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;");
            }
            case SKY -> {
                // Sky uses position + optional UV — entity-like format
                injectCodeBlock(tree, ENTITY_VERTEX_INPUTS);
                remap = ENTITY_VERTEX_REMAP;

                // Vulkan Z-depth remap only — no Y-flip (positive viewport + CW front face)
                tree.appendMainFunctionBody(transformer,
                        "gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;");
            }
            case PARTICLE -> {
                injectCodeBlock(tree, PARTICLE_VERTEX_INPUTS);
                remap = PARTICLE_VERTEX_REMAP;

                // Vulkan Z-depth remap only — no Y-flip (positive viewport + CW front face)
                tree.appendMainFunctionBody(transformer,
                        "gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;");
            }
            default -> {
                remap = Collections.emptyMap();
            }
        }

        // Remove legacy attribute declarations BEFORE remapping
        // (replaceReferenceExpressions removes identifiers from the index,
        //  so removeDeclarationsForNames can't find them after)
        removeDeclarationsForNames(tree, root,
                "at_tangent", "mc_Entity", "at_midBlock", "mc_midTexCoord",
                "vaPosition", "vaNormal");

        // Apply vertex builtin remapping
        for (Map.Entry<String, String> entry : remap.entrySet()) {
            String oldName = entry.getKey();
            String newExpr = entry.getValue();
            if (!root.identifierIndex.has(oldName)) continue;

            if (isSimpleIdentifier(newExpr)) {
                root.rename(oldName, newExpr);
            } else {
                root.replaceReferenceExpressions(transformer, oldName, newExpr);
            }
        }

        // Also handle ftransform() for terrain/entity/composite/sky/hand
        // NOTE: replaceReferenceExpressions may not match function-call syntax.
        // A fallback text replacement is applied in postPrintFixups.
        if (root.identifierIndex.has("ftransform")) {
            String ftransformExpr = getFtransformExpr(params.passType);
            root.replaceReferenceExpressions(transformer, "ftransform", ftransformExpr);
        }
    }

    /**
     * Renames gl_VertexID → gl_VertexIndex (Vulkan SPIR-V naming).
     */
    private static void renameGLVertexIndex(TranslationUnit tree, Root root) {
        if (root.identifierIndex.has("gl_VertexID")) {
            root.rename("gl_VertexID", "gl_VertexIndex");
        }
        if (root.identifierIndex.has("gl_InstanceID")) {
            root.rename("gl_InstanceID", "gl_InstanceIndex");
        }
    }

    /**
     * Returns the replacement expression for ftransform() based on the pass type.
     */
    private static String getFtransformExpr(PassType passType) {
        return switch (passType) {
            case COMPOSITE -> "vkm_composite_Position()";
            case TERRAIN, SHADOW ->
                    "(iris_ProjectionMatrix * iris_ModelViewMatrix * iris_vk_Vertex)";
            case ENTITY, HAND, SKY ->
                    "(iris_ProjectionMatrix * iris_ModelViewMatrix * vec4(vkm_Entity_Position, 1.0))";
            case PARTICLE ->
                    "(iris_ProjectionMatrix * iris_ModelViewMatrix * vec4(vkm_Particle_Position, 1.0))";
            default -> "vec4(0.0)";
        };
    }

    /**
     * Assigns layout(location=N) to in/out declarations that don't have them.
     * SPIR-V requires explicit location qualifiers on all user in/out variables.
     */
    private static void assignVaryingLocations(TranslationUnit tree, Root root, TransformParams params) {
        // This is the trickiest transform to do purely via AST because we need to:
        // 1. Enumerate all in/out declarations without layout qualifiers
        // 2. Assign sequential locations (sorted alphabetically for determinism)
        // 3. Modify the type qualifiers
        //
        // For now, we delegate this to regex post-processing on the printed output,
        // since the AST approach requires navigating TypeQualifier → LayoutQualifier
        // → NamedLayoutQualifierPart chains which is complex.
        // The regex version in VulkaniumGlslTransformer.assignVaryingLocations
        // already handles this correctly.
    }

    // ═══════════════════════════════════════════════════════════════
    //  Post-print regex fixups
    // ═══════════════════════════════════════════════════════════════

    /**
     * Applies post-print regex fixups to the AST-printed output.
     * These handle edge cases that are simpler to fix as text:
     *   - gl_TextureMatrix[N] (array access can't be matched by AST identifier index)
     *   - gl_Fog.* (struct member access on legacy builtin)
     *   - Compatibility fallbacks (MC_RENDER_STAGE_*, fsr*, Biome*Smooth, etc.)
     *   - Remaining non-opaque uniforms → const with zero default
     *   - Unbound sampler auto-binding
     */
    public static String postPrintFixups(String source, TransformParams params) {
        // 1. Fix any remaining attribute/varying keywords (from legacy packs)
        //    MUST run BEFORE location assignment so that 'varying' → 'in'/'out'
        //    conversion happens first, allowing assignVaryingLocationsOnly to
        //    find all bare in/out declarations.
        source = fixLegacyQualifiers(source, params);

        // 2. Assign layout(location=N) to bare in/out varyings
        source = VulkaniumGlslTransformer.assignVaryingLocationsOnly(source, params);

        // 2.5. Remove any surviving legacy vertex attribute declarations
        //      (AST removal may miss some if identifier was removed from index)
        if (params.isVertex) {
            source = removeSurvivingLegacyInputDecls(source);
        }

        // 3. Fix gl_TextureMatrix[N] → mat4(1.0)  (AST can't match array subscripts)
        source = fixGlTextureMatrix(source);

        // 4. Fix gl_Fog.* → UBO member expressions (AST can't match struct member access on builtins)
        source = fixGlFog(source);

        // 4.5. Replace ftransform() if AST didn't catch it
        //      (replaceReferenceExpressions may not match function-call syntax)
        if (params.isVertex && source.contains("ftransform")) {
            String ftExpr = getFtransformExpr(params.passType);
            source = source.replace("ftransform()", ftExpr);
        }

        // 4.55. Collapse double-swizzle artifacts from uniform remapping.
        //       e.g. fogColor → iris_FogColor.rgb, then fogColor.a → iris_FogColor.rgb.a
        //       which is invalid (.a on vec3). Collapse: .rgb.a → .a, .rgb.rgb → .rgb, etc.
        source = collapseDoubleSwizzles(source);

        // 4.6. For gbuffers_water vertex shader, ensure water material ID.
        //      With block.properties loaded, vkm_Entity.x provides the correct water ID.
        //      This override is a legacy fallback that only fires if the vertex decode
        //      still uses the hardcoded -1 (shouldn't happen with v21+).
        if (params.isVertex && params.programName != null
                && params.programName.contains("water")
                && source.contains("iris_vk_EntityId = -1")) {
            source = source.replace("iris_vk_EntityId = -1", "iris_vk_EntityId = 32000");
        }

        // 5. Apply compatibility fallbacks (MC_RENDER_STAGE_*, fsr*, Biome*Smooth, etc.)
        source = applyCompatibilityFallbacks(source);

        // 6. Convert remaining non-opaque uniforms to const with zero default
        //    (Vulkan forbids non-opaque uniforms outside blocks)
        source = convertRemainingBareUniforms(source);

        // 7. Fix const declarations that reference non-constant UBO members
        //    (e.g., const float x = iris_ShadowParams.x → float x = iris_ShadowParams.x)
        source = fixNonConstantConsts(source);

        // 8. Auto-assign layout bindings to unbound sampler/image uniforms
        source = assignUnboundSamplerBindings(source, params);

        // 9. Inject legacy shadow2D/shadow2DProj compatibility wrappers
        source = injectLegacyShadowFunctions(source);

        // 10. Fix UBO l-value assignments (shader modifies a uniform in-place → shadow with local variable)
        source = fixUBOLValueAssignments(source);

        return source;
    }

    // ── gl_TextureMatrix[N] fix ──
    //
    // gl_TextureMatrix[0] → identity (diffuse texture coords are already correct)
    // gl_TextureMatrix[1] → lightmap normalization matrix (same as Iris BuiltinReplacementUniforms)
    //   MC provides raw lightmap shorts in 0-240 range via gl_MultiTexCoord1.
    //   The matrix scales by 1/256 and offsets by 0.5/16, mapping to UV [0.03125, 0.96875]
    //   for the 16×16 lightmap texture.
    // gl_TextureMatrix[2+] → identity (unused)

    private static final Pattern GL_TEX_MATRIX_0 = Pattern.compile(
            "\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]");
    private static final Pattern GL_TEX_MATRIX_1 = Pattern.compile(
            "\\bgl_TextureMatrix\\s*\\[\\s*1\\s*\\]");
    private static final Pattern GL_TEX_MATRIX_OTHER = Pattern.compile(
            "\\bgl_TextureMatrix\\s*\\[\\s*[2-9]\\s*\\]");

    // Iris-compatible lightmap texture matrix: scale(1/256) + translate(1/32)
    private static final String LIGHTMAP_TEXTURE_MATRIX =
            "mat4(vec4(0.00390625, 0.0, 0.0, 0.0), "
          + "vec4(0.0, 0.00390625, 0.0, 0.0), "
          + "vec4(0.0, 0.0, 0.00390625, 0.0), "
          + "vec4(0.03125, 0.03125, 0.03125, 1.0))";

    private static String fixGlTextureMatrix(String source) {
        if (!source.contains("gl_TextureMatrix")) return source;
        source = GL_TEX_MATRIX_1.matcher(source).replaceAll(LIGHTMAP_TEXTURE_MATRIX);
        source = GL_TEX_MATRIX_0.matcher(source).replaceAll("mat4(1.0)");
        source = GL_TEX_MATRIX_OTHER.matcher(source).replaceAll("mat4(1.0)");
        return source;
    }

    // ── gl_Fog.* fix ──

    private static String fixGlFog(String source) {
        if (!source.contains("gl_Fog")) return source;
        source = replaceWord(source, "gl_Fog.color", "iris_FogColor.rgb");
        source = replaceWord(source, "gl_Fog.start", "iris_FogParams.x");
        source = replaceWord(source, "gl_Fog.end", "iris_FogParams.y");
        source = replaceWord(source, "gl_Fog.density", "iris_FogParams.z");
        source = replaceWord(source, "gl_Fog.scale",
                "(1.0 / max(iris_FogParams.y - iris_FogParams.x, 0.0001))");
        return source;
    }

    // ── Double-swizzle collapse ──

    /**
     * Collapses double-swizzle artifacts produced by uniform remapping.
     * For example, fogColor → iris_FogColor.rgb, then fogColor.a → iris_FogColor.rgb.a
     * which is invalid (.a on vec3). This method collapses .rgb.X → .X, .xyz.X → .X, etc.
     */
    private static final Pattern DOUBLE_SWIZZLE = Pattern.compile(
            "(iris_\\w+)\\.(rgb|xyz)\\.(([rgbaxyzwstpq]{1,4}))");

    private static String collapseDoubleSwizzles(String source) {
        if (!source.contains("iris_")) return source;
        return DOUBLE_SWIZZLE.matcher(source).replaceAll("$1.$3");
    }

    // ── Compatibility fallbacks ──

    private static final Map<String, String> COMPAT_REPLACEMENTS = new LinkedHashMap<>();
    static {
        // Render stage constants — ordinals match Iris WorldRenderingPhase enum
        // Reference: net.irisshaders.iris.pipeline.WorldRenderingPhase (Iris, LGPL-3.0)
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_NONE", "0");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_SKY", "1");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_SUNSET", "2");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_CUSTOM_SKY", "3");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_SUN", "4");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_MOON", "5");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_STARS", "6");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_VOID", "7");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_TERRAIN_SOLID", "8");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_TERRAIN_CUTOUT", "10");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_ENTITIES", "11");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_BLOCK_ENTITIES", "12");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_DESTROY", "13");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_OUTLINE", "14");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_DEBUG", "15");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_HAND_SOLID", "16");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_TRIPWIRE", "18");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_PARTICLES", "19");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_CLOUDS", "20");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_RAIN_SNOW", "21");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_WORLD_BORDER", "22");
        COMPAT_REPLACEMENTS.put("MC_RENDER_STAGE_HAND_TRANSLUCENT", "23");

        // ── Screen dimension aliases ──
        // These are standard Iris/OptiFine built-in uniforms that many shaders use
        // instead of viewWidth/viewHeight. Without remapping, they become const = 0
        // and break all texture coordinate computations.
        COMPAT_REPLACEMENTS.put("screenSize", "iris_ScreenSize.xy");
        COMPAT_REPLACEMENTS.put("pixelSize",
                "(vec2(1.0) / max(iris_ScreenSize.xy, vec2(1.0)))");

        // ── Camera position derivatives ──
        COMPAT_REPLACEMENTS.put("cameraPositionToPrevious",
                "(iris_PreviousCameraPosition.xyz - iris_CameraPosition.xyz)");

        // ── Relative eye position (Iris-specific, approx standing player) ──
        COMPAT_REPLACEMENTS.put("relativeEyePosition", "vec3(0.0, 1.62, 0.0)");

        // ── Partial matrix column/row extractions ──
        // Used by some shaderpacks for optimized matrix access instead of the full mat4
        COMPAT_REPLACEMENTS.put("gbufferProjection0", "iris_ProjectionMatrix[0].xyz");
        COMPAT_REPLACEMENTS.put("gbufferProjection1", "iris_ProjectionMatrix[1].xyz");
        COMPAT_REPLACEMENTS.put("gbufferProjectionInverse0", "iris_ProjectionMatrixInverse[0]");
        COMPAT_REPLACEMENTS.put("gbufferProjectionInverse1", "iris_ProjectionMatrixInverse[1].xyz");
        COMPAT_REPLACEMENTS.put("gbufferPreviousProjection0", "iris_PreviousProjectionMatrix[0].xyz");
        COMPAT_REPLACEMENTS.put("gbufferPreviousProjection1", "iris_PreviousProjectionMatrix[1].xyz");
        COMPAT_REPLACEMENTS.put("shadowModelView0", "iris_ShadowModelView[0].xyz");
        COMPAT_REPLACEMENTS.put("shadowModelView1", "iris_ShadowModelView[1].xyz");
        COMPAT_REPLACEMENTS.put("shadowModelView2", "iris_ShadowModelView[2].xyz");
        COMPAT_REPLACEMENTS.put("shadowModelViewInverse2", "iris_ShadowModelViewInverse[2].xyz");

        // FSR / TAA fallback aliases
        COMPAT_REPLACEMENTS.put("fsrScreenSize", "vec2(iris_ScreenSize.x, iris_ScreenSize.y)");
        COMPAT_REPLACEMENTS.put("fsrPixelSize",
                "(vec2(1.0) / max(vec2(iris_ScreenSize.x, iris_ScreenSize.y), vec2(1.0)))");
        COMPAT_REPLACEMENTS.put("fsrRenderScale", "vec2(1.0)");
        COMPAT_REPLACEMENTS.put("fsrJitter", "vec2(0.0)");
        COMPAT_REPLACEMENTS.put("jitterRaw", "vec2(0.0)");
        COMPAT_REPLACEMENTS.put("jitterSequenceLength", "1");
        COMPAT_REPLACEMENTS.put("iris_TaaJitter", "vec2(0.0)");
        COMPAT_REPLACEMENTS.put("fsrReconstructDepth2D", "colortex2");

        // Biome smooth fallbacks (pack-specific, zero when not populated)
        COMPAT_REPLACEMENTS.put("BiomeBasaltDeltasSmooth", "0.0");
        COMPAT_REPLACEMENTS.put("BiomeCrimsonForestSmooth", "0.0");
        COMPAT_REPLACEMENTS.put("BiomeNetherWastesSmooth", "0.0");
        COMPAT_REPLACEMENTS.put("BiomeSoulSandValleySmooth", "0.0");
        COMPAT_REPLACEMENTS.put("BiomeWarpedForestSmooth", "0.0");
    }

    private static String applyCompatibilityFallbacks(String source) {
        for (Map.Entry<String, String> entry : COMPAT_REPLACEMENTS.entrySet()) {
            String symbol = entry.getKey();
            if (!source.contains(symbol)) continue;
            source = replaceOutsideDeclarations(source, symbol, entry.getValue());
        }
        return source;
    }

    /**
     * Replaces a symbol everywhere EXCEPT in its own declaration line.
     * This avoids breaking `uniform float pixelSize;` or `vec2 screenSize = ...;`
     * while replacing usages.
     */
    private static String replaceOutsideDeclarations(String source, String symbol, String replacement) {
        // Matches qualified declarations: uniform/const/in/out TYPE NAME ...;
        // AND bare local declarations: TYPE NAME ...;
        // The qualifier group is optional to catch both forms.
        Pattern declNamePattern = Pattern.compile(
                "^\\s*(?:layout\\s*\\([^)]*\\)\\s*)*(?:(?:uniform|const|in|out|flat|smooth|noperspective)\\s+)*"
                        + "(?:readonly\\s+|writeonly\\s+|coherent\\s+|volatile\\s+|restrict\\s+|highp\\s+|mediump\\s+|lowp\\s+)*"
                        + "(?:int|uint|float|double|bool|void"
                        + "|[ibdu]?vec[234]"
                        + "|mat[234](?:x[234])?"
                        + "|dmat[234](?:x[234])?"
                        + "|[iu]?sampler[123]D(?:Array)?|[iu]?samplerCube(?:Array)?|sampler[12]DShadow|sampler2DRect"
                        + "|[A-Za-z_][A-Za-z0-9_]*)"  // also catch user-defined types
                        + "\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[[^\\]]*\\])?\\s*(?:=.*)?;\\s*$");

        String[] lines = source.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.stripLeading().startsWith("#")) continue;
            Matcher declMatcher = declNamePattern.matcher(line);
            if (declMatcher.matches() && symbol.equals(declMatcher.group(1))) continue;
            lines[i] = replaceWord(line, symbol, replacement);
        }
        return String.join("\n", lines);
    }

    // ── Remaining non-opaque uniform → const conversion ──

    private static final Pattern BARE_UNIFORM_PATTERN = Pattern.compile(
            "^(\\s*)uniform\\s+(int|uint|float|double|bool"
                    + "|[ibdu]?vec[234]"
                    + "|mat[234](?:x[234])?"
                    + "|dmat[234](?:x[234])?)\\s+(\\w+)\\s*;",
            Pattern.MULTILINE
    );

    /**
     * Converts remaining bare non-opaque uniform declarations to const with
     * zero-initialized defaults. This is safer than wrapping into a secondary
     * UBO because it avoids name clashes with other global symbols.
     */
    private static String convertRemainingBareUniforms(String source) {
        return BARE_UNIFORM_PATTERN.matcher(source).replaceAll(matchResult -> {
            String indent = matchResult.group(1);
            String type = matchResult.group(2);
            String name = matchResult.group(3);
            String defaultVal = getZeroDefault(type);
            return indent + "const " + type + " " + name + " = " + defaultVal + ";";
        });
    }

    private static String getZeroDefault(String type) {
        return switch (type) {
            case "float", "double" -> "0.0";
            case "int" -> "0";
            case "uint" -> "0u";
            case "bool" -> "false";
            case "vec2", "dvec2" -> "vec2(0.0)";
            case "vec3", "dvec3" -> "vec3(0.0)";
            case "vec4", "dvec4" -> "vec4(0.0)";
            case "ivec2" -> "ivec2(0)";
            case "ivec3" -> "ivec3(0)";
            case "ivec4" -> "ivec4(0)";
            case "uvec2" -> "uvec2(0u)";
            case "uvec3" -> "uvec3(0u)";
            case "uvec4" -> "uvec4(0u)";
            case "bvec2" -> "bvec2(false)";
            case "bvec3" -> "bvec3(false)";
            case "bvec4" -> "bvec4(false)";
            case "mat2" -> "mat2(0.0)";
            case "mat3" -> "mat3(0.0)";
            case "mat4" -> "mat4(0.0)";
            default -> type + "(0)";
        };
    }

    // ── Fix const declarations with non-constant initializers ──

    /**
     * Finds `const TYPE name = EXPR;` at global scope where EXPR references
     * a non-constant expression (UBO member or variable that itself is non-const).
     * Converts them to plain variable declarations since GLSL requires const
     * initializers to be truly constant (no uniform reads).
     *
     * We take the conservative approach: any const initializer that isn't purely
     * numeric literals, vector/matrix constructors of literals, or swizzles on
     * those, gets downgraded to non-const.
     */
    private static String fixNonConstantConsts(String source) {
        // Match global const declarations
        Pattern globalConst = Pattern.compile(
                "^(\\s*)const\\s+(\\w+)\\s+(\\w+)\\s*=\\s*(.*?);",
                Pattern.MULTILINE
        );
        // Pattern for "purely constant" initializers:
        // numeric literals, vector/matrix constructors with literals, boolean,
        // and basic arithmetic on those
        Pattern purelyConstant = Pattern.compile(
                "^[\\s\\d.eE+\\-*/(,)fFuU]+$" // purely numeric/arithmetic
                        + "|^(vec[234]|ivec[234]|uvec[234]|bvec[234]|mat[234]|mat[234]x[234])"
                        + "\\s*\\([\\s\\d.eE+\\-*/(,)fFuU]*\\)$" // constructor with literals
                        + "|^(true|false)$"
        );

        return globalConst.matcher(source).replaceAll(matchResult -> {
            String indent = matchResult.group(1);
            String type = matchResult.group(2);
            String name = matchResult.group(3);
            String init = matchResult.group(4).trim();

            // Keep const if initializer is purely constant
            if (purelyConstant.matcher(init).matches()) {
                return matchResult.group(); // keep as-is
            }

            // Downgrade to non-const
            return indent + type + " " + name + " = " + init + ";";
        });
    }

    // ── Unbound sampler auto-binding ──

    /**
     * Finds uniform sampler/image declarations without layout qualifiers
     * and auto-assigns layout(set=0, binding=N+1).
     * Binding 0 is reserved for the UBO, so samplers start at binding 1.
     *
     * <p>For compute shaders ({@code params.imageBindingOffset > 0}), storage
     * images ({@code image2D}, etc.) are assigned to a separate binding range
     * starting at {@code imageBindingOffset} so they align with the compute
     * descriptor set layout's STORAGE_IMAGE slots rather than colliding with
     * the COMBINED_IMAGE_SAMPLER or STORAGE_BUFFER ranges.</p>
     */
    private static String assignUnboundSamplerBindings(String source, TransformParams params) {
        int nextBinding = 28; // DEFAULT_SAMPLER_BINDINGS goes up to 27
        if (params.samplerBindings != null) {
            for (int b : params.samplerBindings.values()) {
                if (b >= nextBinding) nextBinding = b + 1;
            }
        }

        // For compute shaders, images go to a separate binding range that
        // aligns with VK_DESCRIPTOR_TYPE_STORAGE_IMAGE in the compute layout.
        // imageBindingOffset is the 0-based start of the image range (the +1
        // UBO offset is applied below when building the layout string).
        int nextImageBinding = params.imageBindingOffset > 0 ? params.imageBindingOffset : -1;

        // Match sampler/image uniform declarations that don't have binding=
        // This covers both:
        //   uniform sampler2D foo;
        //   layout(rgba16f) uniform writeonly image2D bar;
        Pattern unboundSampler = Pattern.compile(
                "^(\\s*)((?:layout\\s*\\([^)]*\\)\\s*)?)uniform\\s+((?:readonly|writeonly|coherent|volatile|restrict)\\s+)?((?:sampler|isampler|usampler|image|iimage|uimage|texture|itexture|utexture)\\w+)\\s+(\\w+)\\s*;",
                Pattern.MULTILINE
        );

        Matcher m = unboundSampler.matcher(source);
        StringBuffer sb = new StringBuffer();
        int count = 0;

        while (m.find()) {
            String indent = m.group(1);
            String existingLayout = m.group(2);  // e.g. "layout(rgba16f) " or ""
            String accessQual = m.group(3) != null ? m.group(3) : "";  // e.g. "writeonly "
            String type = m.group(4);
            String name = m.group(5);

            // Skip if already has binding= in its layout qualifier
            if (existingLayout != null && existingLayout.contains("binding")) {
                continue;
            }

            // Skip if this sampler already has a binding from the standard map
            if (params.samplerBindings != null && params.samplerBindings.containsKey(name)) {
                continue; // Already handled by remapSamplerBindings AST step
            }

            // Determine the correct binding based on descriptor type.
            // For compute shaders, images (image2D, etc.) must go to the
            // STORAGE_IMAGE binding range, not the sampler range.
            boolean isImageType = type.startsWith("image") || type.startsWith("iimage") || type.startsWith("uimage");
            int binding;
            if (isImageType && nextImageBinding >= 0) {
                // Compute shader: assign image to the storage image binding range
                binding = nextImageBinding;
                nextImageBinding++;
            } else {
                // Sampler (or image in a graphics shader): use the sampler range
                binding = nextBinding;
                nextBinding++;
            }

            // Merge existing format qualifiers with binding
            // +1 because binding 0 is reserved for the UBO in the single descriptor set
            String layoutContent = "set = 0, binding = " + (binding + 1);
            if (existingLayout != null && !existingLayout.isBlank()) {
                // Extract existing layout content (e.g., "rgba16f")
                Matcher layoutMatcher = Pattern.compile("layout\\s*\\((.*)\\)").matcher(existingLayout);
                if (layoutMatcher.find()) {
                    layoutContent = layoutMatcher.group(1).trim() + ", " + layoutContent;
                }
            }

            String replacement = indent + "layout(" + layoutContent + ") uniform " + accessQual + type + " " + name + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            LOGGER.debug("Auto-bound {} {} to binding {} ({})", isImageType ? "image" : "sampler", name, binding,
                    isImageType ? "storage-image" : "combined-image-sampler");
            count++;
        }
        m.appendTail(sb);

        if (count > 0) {
            LOGGER.debug("Auto-assigned bindings to {} unbound samplers/images", count);
        }
        return sb.toString();
    }

    // ── Helper: word-boundary replacement ──

    private static String replaceWord(String source, String from, String to) {
        return source.replaceAll(
                "(?<![A-Za-z0-9_.])" + Pattern.quote(from) + "(?![A-Za-z0-9_])",
                Matcher.quoteReplacement(to));
    }

    // ── Legacy shadow2D compatibility wrappers ──

    private static String injectLegacyShadowFunctions(String source) {
        if (!source.contains("shadow2D(") && !source.contains("shadow2DProj(")) {
            return source;
        }

        String wrappers = """
                // ── Vulkanium legacy shadow* compatibility ──
                vec4 shadow2D(sampler2DShadow s, vec3 coord) { float v = texture(s, coord); return vec4(v); }
                vec4 shadow2D(sampler2D s, vec3 coord) { return texture(s, coord.xy); }
                vec4 shadow2D(sampler2D s, vec2 coord) { return texture(s, coord); }
                vec4 shadow2DProj(sampler2DShadow s, vec4 coord) { float v = textureProj(s, coord); return vec4(v); }
                vec4 shadow2DProj(sampler2D s, vec4 coord) { return textureProj(s, coord); }
                """;

        // Insert after UBO block or after #version/#extension header
        int insertPos = source.indexOf("}; // VulkaniumUniforms");
        if (insertPos != -1) {
            insertPos = source.indexOf('\n', insertPos) + 1;
        } else {
            // Find end of preprocessor header
            String[] lines = source.split("\\n", -1);
            int pos = 0;
            for (String line : lines) {
                pos += line.length() + 1;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                insertPos = pos - line.length() - 1;
                break;
            }
            if (insertPos < 0) insertPos = 0;
        }

        return source.substring(0, insertPos) + wrappers + "\n" + source.substring(insertPos);
    }

    private static String fixLegacyQualifiers(String source, TransformParams params) {
        // attribute → in (vertex only)
        if (params.isVertex) {
            source = Pattern.compile("^(\\s*)attribute\\s+", Pattern.MULTILINE)
                    .matcher(source).replaceAll("$1in ");
        }
        // varying → in (fragment) or out (vertex)
        String replacement = params.isFragment ? "$1in " : "$1out ";
        source = Pattern.compile("^(\\s*)varying\\s+", Pattern.MULTILINE)
                .matcher(source).replaceAll(replacement);
        return source;
    }

    // Legacy vertex attribute names that should be removed after vertex transforms
    private static final Set<String> LEGACY_VERTEX_ATTRS = Set.of(
            "vaPosition", "vaNormal", "vaColor", "vaUV0",
            "at_tangent", "mc_Entity", "at_midBlock", "mc_midTexCoord"
    );

    /**
     * Removes surviving legacy vertex input declarations (in/attribute) that
     * the AST removal step may have missed due to identifier index issues.
     */
    private static String removeSurvivingLegacyInputDecls(String source) {
        // Match lines like: "in vec4 mc_Entity;" or "in vec3 vaPosition;"
        // Only remove if the name is in the legacy set
        return Pattern.compile(
                "^\\s*(?:in|attribute)\\s+\\w+\\s+(\\w+)\\s*;\\s*$",
                Pattern.MULTILINE
        ).matcher(source).replaceAll(matchResult -> {
            String name = matchResult.group(1);
            if (LEGACY_VERTEX_ATTRS.contains(name)) {
                return ""; // Remove the line
            }
            return matchResult.group(); // Keep non-legacy declarations
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helper methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Injects a multi-line code block as external declarations.
     * Handles both simple declarations (ending with ;) and function
     * definitions (ending with }) by tracking brace depth.
     */
    private static void injectCodeBlock(TranslationUnit tree, String codeBlock) {
        String[] lines = codeBlock.split("\\n");
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            current.append(line).append("\n");

            // Track brace depth
            for (char c : trimmed.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // Complete declaration/definition when:
            // - At brace depth 0 and line ends with ; (simple declaration)
            // - At brace depth 0 and line ends with } (function body closed)
            if (braceDepth == 0 && (trimmed.endsWith(";") || trimmed.endsWith("}"))) {
                String decl = current.toString().trim();
                if (!decl.isEmpty()) {
                    try {
                        tree.parseAndInjectNode(transformer,
                                ASTInjectionPoint.BEFORE_FUNCTIONS, decl);
                    } catch (Exception e) {
                        LOGGER.debug("Failed to inject code block: {}", e.getMessage());
                    }
                }
                current.setLength(0);
            }
        }

        // Handle any remaining content
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            try {
                tree.parseAndInjectNode(transformer,
                        ASTInjectionPoint.BEFORE_FUNCTIONS, remaining);
            } catch (Exception e) {
                LOGGER.debug("Failed to inject remaining code block: {}", e.getMessage());
            }
        }
    }

    /**
     * Removes legacy attribute/in declarations for specific variable names.
     */
    private static void removeDeclarationsForNames(TranslationUnit tree, Root root, String... names) {
        Set<DeclarationExternalDeclaration> toRemove = new LinkedHashSet<>();

        for (String name : names) {
            if (!root.identifierIndex.has(name)) continue;

            var identifiers = root.identifierIndex.getStream(name).toList();
            for (var id : identifiers) {
                var extDecl = id.getAncestor(DeclarationExternalDeclaration.class);
                if (extDecl == null) continue;

                String srcText = ASTPrinter.print(PrintType.COMPACT, extDecl);
                // Only remove if it's an input declaration (not a usage in an expression)
                if (srcText.contains(" in ") || srcText.contains("attribute ")) {
                    toRemove.add(extDecl);
                }
            }
        }

        for (var decl : toRemove) {
            decl.detachAndDelete();
        }
    }

    private static boolean isSimpleIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '(' || c == ')' || c == '[' || c == ']' || c == ' ') return false;
        }
        return true;
    }

    private static String extractType(String declText) {
        // Extract the type from a uniform declaration like "uniform sampler2D foo;"
        Matcher m = Pattern.compile("uniform\\s+(\\w+)\\s+").matcher(declText);
        if (m.find()) return m.group(1);
        return null;
    }

    // ── UBO l-value assignment fix ──
    // When a shader modifies a uniform (UBO member) in-place, e.g.:
    //   iris_SkyColor.xyz *= pdf;
    // We create a local shadow copy at first use and replace references within the function.

    private static final Pattern UBO_COMPOUND_ASSIGN = Pattern.compile(
            "\\b(iris_\\w+(?:\\.[xyzwrgba]+)?)\\s*(?:\\*=|\\+=|-=|/=|(?<![-+*/!<>=])=(?!=))");

    private static String fixUBOLValueAssignments(String source) {
        Matcher m = UBO_COMPOUND_ASSIGN.matcher(source);

        // Collect unique iris members that are l-values
        Map<String, String> memberToLocal = new LinkedHashMap<>();
        while (m.find()) {
            String member = m.group(1);
            // Skip non-UBO iris_* globals:
            // - iris_vk_*, iris_compat_* : vertex decode globals, freely assignable
            // - iris_FragData*           : fragment output variables, not UBO members
            if (member.startsWith("iris_vk_") || member.startsWith("iris_compat_")) continue;
            if (member.startsWith("iris_FragData")) continue;
            if (!memberToLocal.containsKey(member)) {
                String localName = "_vkm_" + member.replace(".", "_").replace("iris_", "");
                memberToLocal.put(member, localName);
            }
        }

        if (memberToLocal.isEmpty()) return source;

        for (Map.Entry<String, String> entry : memberToLocal.entrySet()) {
            source = shadowUBOMember(source, entry.getKey(), entry.getValue());
        }
        return source;
    }

    private static String shadowUBOMember(String source, String irisMember, String localName) {
        // Determine type from swizzle
        String swizzle = irisMember.contains(".") ?
                irisMember.substring(irisMember.lastIndexOf('.') + 1) : null;
        String type;
        if (swizzle == null) type = "vec4";
        else if (swizzle.length() == 1) type = "float";
        else type = "vec" + swizzle.length();

        // Find the first assignment (simple or compound) to this member
        Pattern firstAssign = Pattern.compile(
                "([ \\t]*)" + Pattern.quote(irisMember) + "\\s*(?:\\*=|\\+=|-=|/=|(?<![-+*/!<>=])=(?!=))");
        Matcher fm = firstAssign.matcher(source);
        if (!fm.find()) return source;

        int firstAssignStart = fm.start();
        String indent = fm.group(1);

        // Insert local variable declaration before the first assignment
        String localDecl = indent + type + " " + localName + " = " + irisMember + ";\n";
        source = source.substring(0, firstAssignStart) + localDecl + source.substring(firstAssignStart);

        // Now find the scope: count braces from start to injection point to get depth,
        // then replace from injection point to end of enclosing scope
        int braceDepth = 0;
        for (int i = 0; i < firstAssignStart; i++) {
            char c = source.charAt(i);
            if (c == '{') braceDepth++;
            if (c == '}') braceDepth--;
        }

        // Find end of enclosing function (where brace depth drops below current level)
        int searchFrom = firstAssignStart + localDecl.length();
        int tempDepth = braceDepth;
        int scopeEnd = source.length();
        for (int i = searchFrom; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') tempDepth++;
            if (c == '}') {
                tempDepth--;
                if (tempDepth < braceDepth) {
                    scopeEnd = i;
                    break;
                }
            }
        }

        // Replace irisMember with localName within the scope (after the declaration line)
        int declEnd = firstAssignStart + localDecl.length();
        String beforeScope = source.substring(0, declEnd);
        String withinScope = source.substring(declEnd, scopeEnd);
        String afterScope = source.substring(scopeEnd);

        withinScope = withinScope.replace(irisMember, localName);

        return beforeScope + withinScope + afterScope;
    }
}
