package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks entity rendering for Vulkan entity ID push constants and suppresses
 * vanilla entity shadows when a shaderpack with shadow mapping is active.
 *
 * <p>Reference: Iris {@code MixinEntityRenderDispatcher} cancels the static
 * {@code renderShadow} method when the pipeline reports shadow mapping
 * is enabled ({@code shouldDisableVanillaEntityShadows()}).  Vulkanium
 * mirrors this: when any shaderpack is loaded the vanilla blob shadow
 * is unnecessary because the shaderpack renders its own shadow map.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDispatcher {

    @Unique
    private static final String RENDER_SHADOW =
            "renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V";

    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void onRender(E entity, double x, double y, double z,
                                              float yaw, float tickDelta,
                                              PoseStack poseStack,
                                              MultiBufferSource bufferSource,
                                              int light, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.setCurrentEntityId(entity.getId());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void afterRender(E entity, double x, double y, double z,
                                                 float yaw, float tickDelta,
                                                 PoseStack poseStack,
                                                 MultiBufferSource bufferSource,
                                                 int light, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.setCurrentEntityId(0);
    }

    /**
     * Suppress vanilla blob entity shadow when a shaderpack is active.
     * The shaderpack renders proper shadow-mapped shadows via its shadow pass.
     */
    @Inject(method = RENDER_SHADOW, at = @At("HEAD"), cancellable = true)
    private static void vulkanium$suppressVanillaShadow(PoseStack poseStack,
                                                         MultiBufferSource bufferSource,
                                                         Entity entity, float opacity,
                                                         float tickDelta, LevelReader level,
                                                         float radius, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        if (Vulkanium.getRenderMode() == net.vulkanium.render.RenderMode.SHADERPACK) {
            ci.cancel();
        }
    }
}
