package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Patches {@link VertexFormatElement#supportsUsage} to allow
 * {@link VertexFormatElement.Usage#GENERIC} at any element index.
 *
 * <p>Vanilla MC only allows GENERIC with index 0 (implicit via the
 * {@code default} branch returning {@code false}).  Vulkanium's extended
 * terrain format uses GENERIC index 11 for the {@code mc_Entity} element,
 * matching Iris's convention.</p>
 */
@Mixin(VertexFormatElement.class)
public class MixinVertexFormatElement {

    @Inject(method = "supportsUsage", at = @At("HEAD"), cancellable = true)
    private void vulkanium$allowGenericUsage(int index,
                                              VertexFormatElement.Usage usage,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (usage == VertexFormatElement.Usage.GENERIC) {
            cir.setReturnValue(true);
        }
    }
}
