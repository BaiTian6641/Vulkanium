package net.vulkanium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.vulkanium.Vulkanium;
import net.vulkanium.render.program.WorldRenderingPhase;
import net.vulkanium.world.VulkaniumWorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Core LevelRenderer mixin that redirects Minecraft's world rendering to Vulkan.
 *
 * <p>This is the most critical rendering mixin — it intercepts:
 * <ul>
 *   <li>{@code setupRender} — visibility culling + chunk build scheduling</li>
 *   <li>{@code renderSectionLayer} — chunk terrain draw calls per-layer</li>
 *   <li>{@code renderLevel} — the full frame render (sky, terrain, entities, particles)</li>
 *   <li>{@code setLevel} — level load/unload for resource management</li>
 * </ul></p>
 *
 * <p>Mirrors VulkanMod's M_LevelRenderer + Sodium's WorldRendererMixin in scope.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {

    /**
     * Target method descriptors for LevelRenderer injection points.
     *
     * <p>Reference from Iris: net.irisshaders.iris.mixin.MixinLevelRenderer
     * — Sky sub-phase injection (SUN, MOON, STARS, SUNSET, SKY, CUSTOM_SKY, VOID)
     *   inside renderSky() to set WorldRenderingPhase for shaderpack renderStage uniform.</p>
     */
    private static final String RENDER_SKY = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V";
    private static final String RENDER_CLOUDS = "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V";

    @Shadow @Nullable private ClientLevel level;

    /**
     * The model-view matrix that was active BEFORE the current renderChunkLayer started.
     * Saved at HEAD, restored at RETURN so entities/particles don't get double-rotated,
     * but without clobbering the camera matrix mid-layer (which broke translucent water).
     */
    /**
     * Intercept setupRender to use Vulkan-optimized frustum culling and chunk scheduling.
     *
     * <p>MC's vanilla setupRender does BFS through RenderSection, tests frustum via GL.
     * We replace this with our SectionGraph + GPU frustum culler.</p>
     */
    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void onSetupRender(Camera camera, net.minecraft.client.renderer.culling.Frustum frustum,
                                boolean hasForcedFrustum, boolean isSpectator, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady() || this.level == null) return;

        try {
            // Delegate terrain setup to VulkaniumWorldRenderer
            // This runs the SectionGraph visibility update, schedules chunk rebuilds,
            // and prepares indirect draw buffers
            double x = camera.getPosition().x;
            double y = camera.getPosition().y;
            double z = camera.getPosition().z;

            Vulkanium.LOGGER.trace("setupRender: camera at ({}, {}, {})", x, y, z);

            VulkaniumWorldRenderer.getInstance().setupTerrain(
                    x, y, z,
                    Minecraft.getInstance().getFrameTime(),
                    isSpectator
            );
        } catch (Exception e) {
            Vulkanium.LOGGER.error("Error in Vulkan setupRender", e);
        }
    }

    /**
     * Intercept renderSectionLayer to draw terrain through Vulkan command buffers.
     *
     * <p>This is called per render layer (SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT).
     * We let vanilla iterate visible chunk sections and call VertexBuffer.drawWithShader(),
     * which our MixinVertexBuffer intercepts and renders through Vulkan.</p>
     */
    @Inject(method = "renderChunkLayer", at = @At("HEAD"))
    private void onRenderSectionLayer(RenderType renderType, PoseStack poseStack,
                                       double camX, double camY, double camZ,
                                       Matrix4f projectionMatrix, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        Vulkanium.onTerrainLayerStart(renderType.toString());

        // Set the camera-rotated model-view for this terrain layer.
        // No save/restore needed — MixinVertexBuffer.onDrawWithShader sets the MV
        // from MC's arguments per-draw, and after the layer finishes, the last
        // chunk's draw leaves VRenderSystem with the camera rotation (correct state).
        net.vulkanium.compat.VRenderSystem.setModelViewMatrix(poseStack.last().pose());
        net.vulkanium.compat.VRenderSystem.setProjectionMatrix(projectionMatrix,
                net.vulkanium.compat.VRenderSystem.getVertexSorting());
    }

    /**
     * After each render layer finishes, reset ChunkOffset.
     * The model-view and projection are NOT restored — they stay as whatever
     * the last per-draw call set (camera rotation from MC's drawWithShader
     * arguments), which is the correct rendering context.
     */
    @Inject(method = "renderChunkLayer", at = @At("RETURN"))
    private void afterRenderSectionLayer(RenderType renderType, PoseStack poseStack,
                                          double camX, double camY, double camZ,
                                          Matrix4f projectionMatrix, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.onTerrainLayerEnd();
        net.vulkanium.compat.VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);
    }

    /**
     * Hook the main level render to manage Vulkan render pass lifecycle.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevelStart(PoseStack poseStack, float partialTick,
                                     long finishNanoTime, boolean renderBlockOutline,
                                     Camera camera, GameRenderer gameRenderer,
                                     LightTexture lightTexture, Matrix4f projectionMatrix,
                                     CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.onWorldRenderStart(
                poseStack.last().pose(), projectionMatrix, partialTick);
        // Begin the main render pass for this frame
        // The actual draw commands are recorded in renderSectionLayer and entity rendering
        Vulkanium.LOGGER.trace("renderLevel: begin");
    }

    /**
     * Set CLOUDS phase before vanilla renderClouds() is called inside renderLevel().
     *
     * <p>This allows {@code mapShaderNameToProgramId()} to know we're in the
     * cloud rendering phase and route draws to {@code gbuffers_clouds}.</p>
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE", target = RENDER_CLOUDS))
    private void vulkanium$beginClouds(PoseStack poseStack, float partialTick,
                                        long finishNanoTime, boolean renderBlockOutline,
                                        Camera camera, GameRenderer gameRenderer,
                                        LightTexture lightTexture, Matrix4f projectionMatrix,
                                        CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.CLOUDS);
    }

    /**
     * Reset phase after vanilla renderClouds() completes.
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE", target = RENDER_CLOUDS, shift = At.Shift.AFTER))
    private void vulkanium$endClouds(PoseStack poseStack, float partialTick,
                                      long finishNanoTime, boolean renderBlockOutline,
                                      Camera camera, GameRenderer gameRenderer,
                                      LightTexture lightTexture, Matrix4f projectionMatrix,
                                      CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.NONE);
    }

    // ─── Sky sub-phase injection ─────────────────────────────────────────────
    // Reference from Iris: net.irisshaders.iris.mixin.MixinLevelRenderer
    // Sets WorldRenderingPhase for each sub-phase of renderSky() so that
    // shaderpacks can distinguish SUN, MOON, STARS, SUNSET, etc. via the
    // renderStage uniform. Without these, everything inside renderSky is
    // just "sky" and phase-dependent shader logic won't activate.

    /**
     * Before renderSky() is invoked in renderLevel(), set CUSTOM_SKY phase.
     * This catches any custom sky rendering (e.g. FabricSkyboxes) that happens
     * before the vanilla sky code calls levelFogColor().
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE", target = RENDER_SKY))
    private void vulkanium$beginSky(PoseStack poseStack, float partialTick,
                                     long finishNanoTime, boolean renderBlockOutline,
                                     Camera camera, GameRenderer gameRenderer,
                                     LightTexture lightTexture, Matrix4f projectionMatrix,
                                     CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.CUSTOM_SKY);
    }

    /**
     * After renderSky() completes in renderLevel(), reset phase to NONE.
     */
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE", target = RENDER_SKY, shift = At.Shift.AFTER))
    private void vulkanium$endSky(PoseStack poseStack, float partialTick,
                                   long finishNanoTime, boolean renderBlockOutline,
                                   Camera camera, GameRenderer gameRenderer,
                                   LightTexture lightTexture, Matrix4f projectionMatrix,
                                   CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.NONE);
    }

    /**
     * Inside renderSky(): when levelFogColor() is called, vanilla sky rendering begins.
     * Transition from CUSTOM_SKY → SKY.
     */
    @Inject(method = "renderSky",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V"))
    private void vulkanium$beginNormalSky(PoseStack poseStack, Matrix4f projectionMatrix,
                                          float f, Camera camera, boolean bl,
                                          Runnable runnable, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SKY);
    }

    /**
     * Inside renderSky(): when SUN_LOCATION field is accessed, sun rendering begins.
     * Reference from Iris: iris$setSunRenderStage
     */
    @Inject(method = "renderSky",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;SUN_LOCATION:Lnet/minecraft/resources/ResourceLocation;"))
    private void vulkanium$beginSun(PoseStack poseStack, Matrix4f projectionMatrix,
                                     float f, Camera camera, boolean bl,
                                     Runnable runnable, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SUN);
    }

    /**
     * Inside renderSky(): when MOON_LOCATION field is accessed, moon rendering begins.
     * Reference from Iris: iris$setMoonRenderStage
     */
    @Inject(method = "renderSky",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"))
    private void vulkanium$beginMoon(PoseStack poseStack, Matrix4f projectionMatrix,
                                      float f, Camera camera, boolean bl,
                                      Runnable runnable, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.MOON);
    }

    /**
     * Inside renderSky(): when getSunriseColor() is invoked, sunset/sunrise rendering begins.
     * Reference from Iris: iris$setSunsetRenderStage
     */
    @Inject(method = "renderSky",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"))
    private void vulkanium$beginSunset(PoseStack poseStack, Matrix4f projectionMatrix,
                                        float f, Camera camera, boolean bl,
                                        Runnable runnable, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.SUNSET);
    }

    /**
     * Inside renderSky(): when getStarBrightness() is invoked, star rendering begins.
     * Reference from Iris: iris$setStarRenderStage
     */
    @Inject(method = "renderSky",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
    private void vulkanium$beginStars(PoseStack poseStack, Matrix4f projectionMatrix,
                                       float f, Camera camera, boolean bl,
                                       Runnable runnable, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.STARS);
    }

    /**
     * Inside renderSky(): when getEyePosition() is invoked, void rendering begins.
     * Reference from Iris: iris$setVoidRenderStage
     */
    @Inject(method = "renderSky",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private void vulkanium$beginVoid(PoseStack poseStack, Matrix4f projectionMatrix,
                                      float f, Camera camera, boolean bl,
                                      Runnable runnable, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        WorldRenderingPhase.setPhase(WorldRenderingPhase.Phase.VOID);
    }

    /**
     * After level rendering, trigger RT shadow pass integration.
     * This is where we would dispatch shadow map rendering or RT shadow rays
     * before the frame is submitted.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevelEnd(PoseStack poseStack, float partialTick,
                                   long finishNanoTime, boolean renderBlockOutline,
                                   Camera camera, GameRenderer gameRenderer,
                                   LightTexture lightTexture, Matrix4f projectionMatrix,
                                   CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.onWorldRenderEnd();

        // Notify Vulkanium that world rendering is complete — RT shadow passes
        // and post-processing can now run. The actual RT dispatch happens in
        // Vulkanium.onFrameEnd() after the main render pass ends.
        Vulkanium.LOGGER.trace("renderLevel: end — RT shadow pass ready");
    }

    /**
     * On level load, initialize Vulkan terrain resources.
     */
    @Inject(method = "setLevel", at = @At("RETURN"))
    private void onSetLevel(@Nullable ClientLevel level, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        if (level != null) {
            Vulkanium.LOGGER.info("Level loaded — initializing Vulkan terrain for '{}'",
                    level.dimension().location());
            VulkaniumWorldRenderer.getInstance().onWorldLoad(vulkanium$getRenderDistance());
        } else {
            Vulkanium.LOGGER.info("Level unloaded — cleaning up Vulkan terrain resources");
            VulkaniumWorldRenderer.getInstance().onWorldUnload();
        }
    }

    /**
     * On allChanged (settings change, render distance change), recreate terrain.
     */
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void onAllChanged(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.LOGGER.info("LevelRenderer.allChanged — rebuilding Vulkan terrain");
        VulkaniumWorldRenderer.getInstance().onRenderDistanceChange(vulkanium$getRenderDistance());
    }

    @Unique
    private int vulkanium$getRenderDistance() {
        try {
            return Minecraft.getInstance().options.renderDistance().get();
        } catch (Throwable ignored) {
            return 12;
        }
    }
}
