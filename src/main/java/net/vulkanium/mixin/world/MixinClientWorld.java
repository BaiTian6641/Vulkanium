package net.vulkanium.mixin.world;

import net.vulkanium.Vulkanium;
import net.vulkanium.world.VulkaniumWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks block state changes for Vulkan terrain rebuilds.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientWorld {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void onSetBlockState(BlockPos pos, BlockState state,
                                 int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        if (!Vulkanium.isVulkanReady()) return;
        VulkaniumWorldRenderer.getInstance().onBlockChanged(pos.getX(), pos.getY(), pos.getZ());
    }
}
