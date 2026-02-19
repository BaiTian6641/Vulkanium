package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks entity rendering for Vulkan entity ID push constants.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDispatcher {

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
}
