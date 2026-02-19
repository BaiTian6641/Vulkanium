package net.vulkanium.mixin.render;

import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * Intercepts shader program binding to map MC shaders to Vulkan pipeline variants.
 */
@Mixin(ShaderInstance.class)
public abstract class MixinShaderProgram {

    @Shadow
    private String name;

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void onBind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.setActiveShaderName(this.name);
        ci.cancel();
    }

    @Inject(method = "clear", at = @At("HEAD"), cancellable = true)
    private void onUnbind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        VRenderSystem.setActiveShaderName(null);
        ci.cancel();
    }
}
