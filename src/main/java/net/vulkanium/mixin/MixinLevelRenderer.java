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

    @Shadow @Nullable private ClientLevel level;

    /**
     * The model-view matrix that was active BEFORE the current renderChunkLayer started.
     * Saved at HEAD, restored at RETURN so entities/particles don't get double-rotated,
     * but without clobbering the camera matrix mid-layer (which broke translucent water).
     */
    @Unique
    private Matrix4f vulkanium$savedModelView;

    @Unique
    private Matrix4f vulkanium$savedProjection;

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

        // Save the current matrices BEFORE we set the camera matrices for this layer.
        // At RETURN we restore these so non-terrain draws (entities, particles) don't
        // get double-rotated. Previously we reset to identity which broke the TRANSLUCENT
        // layer — chunks drawn after the first drawWithShader() restore would see identity
        // instead of the proper camera matrix.
        vulkanium$savedModelView = new Matrix4f(net.vulkanium.compat.VRenderSystem.getModelViewMatrix());
        vulkanium$savedProjection = new Matrix4f(net.vulkanium.compat.VRenderSystem.getProjectionMatrix());

        // Set the camera-rotated model-view for this terrain layer
        net.vulkanium.compat.VRenderSystem.setModelViewMatrix(poseStack.last().pose());
        net.vulkanium.compat.VRenderSystem.setProjectionMatrix(projectionMatrix,
                net.vulkanium.compat.VRenderSystem.getVertexSorting());
    }

    /**
     * After each render layer finishes, reset ChunkOffset and restore the pre-layer
     * matrices. This is critical because:
     * 1. ChunkOffset must not leak into non-terrain draws (entities, particles)
     * 2. Entity vertices are already in view space — they don't need the terrain
     *    camera-rotated modelViewMat, so we restore what was there before this layer.
     *
     * Previously we reset to identity here, but that broke the TRANSLUCENT layer:
     * MixinVertexBuffer.onDrawWithShader() saves/restores VRenderSystem matrices
     * per-chunk. If the "previous" to restore was identity (from a prior layer's
     * RETURN), subsequent chunks in the TRANSLUCENT pass lost the camera rotation
     * and water/glass would glitch when rotating the view.
     */
    @Inject(method = "renderChunkLayer", at = @At("RETURN"))
    private void afterRenderSectionLayer(RenderType renderType, PoseStack poseStack,
                                          double camX, double camY, double camZ,
                                          Matrix4f projectionMatrix, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.onTerrainLayerEnd();
        net.vulkanium.compat.VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);
        // Restore the matrices that were active before this layer started
        if (vulkanium$savedModelView != null) {
            net.vulkanium.compat.VRenderSystem.setModelViewMatrix(vulkanium$savedModelView);
        }
        if (vulkanium$savedProjection != null) {
            net.vulkanium.compat.VRenderSystem.setProjectionMatrix(vulkanium$savedProjection,
                    net.vulkanium.compat.VRenderSystem.getVertexSorting());
        }
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
        Vulkanium.onWorldRenderStart();
        // Begin the main render pass for this frame
        // The actual draw commands are recorded in renderSectionLayer and entity rendering
        Vulkanium.LOGGER.trace("renderLevel: begin");
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
