package net.vulkanium.mixin.render;

import com.mojang.blaze3d.systems.TimerQuery;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Prevents TimerQuery from accessing GL capabilities in Vulkan mode.
 * TimerQuery.getInstance() triggers GL.getCapabilities() which crashes
 * when no GL context exists.
 */
@Mixin(TimerQuery.class)
public abstract class MixinTimerQuery {

    @Inject(method = "getInstance", at = @At("HEAD"), cancellable = true)
    private static void onGetInstance(CallbackInfoReturnable<Optional<TimerQuery>> cir) {
        if (Vulkanium.isVulkanReady()) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
