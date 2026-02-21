package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanium.render.vertex.BlockSensitiveBufferBuilder;
import net.vulkanium.shaderpack.materialmap.BlockPropertyIdMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects block material IDs from {@code block.properties} into the
 * {@link BlockSensitiveBufferBuilder} before each block/liquid is rendered
 * during chunk section compilation.
 *
 * <p>This provides per-vertex {@code mc_Entity.x} data so shaderpacks can
 * identify block types (water, leaves, emissive blocks, etc.).</p>
 */
@Mixin(BlockRenderDispatcher.class)
public class MixinBlockRenderDispatcher {

    // ── Block rendering ──

    @Inject(method = "renderBatched", at = @At("HEAD"))
    private void vulkanium$beginBlockRender(BlockState state, BlockPos pos,
                                             BlockAndTintGetter level,
                                             PoseStack poseStack,
                                             VertexConsumer consumer,
                                             boolean checkSides,
                                             RandomSource random,
                                             CallbackInfo ci) {
        if (consumer instanceof BlockSensitiveBufferBuilder bb && BlockPropertyIdMap.isLoaded()) {
            short blockId = BlockPropertyIdMap.resolveBlockId(state);
            bb.beginBlock(blockId, (short) -1,
                    pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    @Inject(method = "renderBatched", at = @At("RETURN"))
    private void vulkanium$endBlockRender(BlockState state, BlockPos pos,
                                           BlockAndTintGetter level,
                                           PoseStack poseStack,
                                           VertexConsumer consumer,
                                           boolean checkSides,
                                           RandomSource random,
                                           CallbackInfo ci) {
        if (consumer instanceof BlockSensitiveBufferBuilder bb && BlockPropertyIdMap.isLoaded()) {
            bb.endBlock();
        }
    }

    // ── Liquid rendering ──

    @Inject(method = "renderLiquid", at = @At("HEAD"))
    private void vulkanium$beginLiquidRender(BlockPos pos,
                                              BlockAndTintGetter level,
                                              VertexConsumer consumer,
                                              BlockState blockState,
                                              FluidState fluidState,
                                              CallbackInfo ci) {
        if (consumer instanceof BlockSensitiveBufferBuilder bb && BlockPropertyIdMap.isLoaded()) {
            // Fluid state → legacy block state for ID lookup (e.g. water, lava)
            short blockId = BlockPropertyIdMap.resolveBlockId(fluidState.createLegacyBlock());
            bb.beginBlock(blockId, (short) 1,
                    pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    @Inject(method = "renderLiquid", at = @At("RETURN"))
    private void vulkanium$endLiquidRender(BlockPos pos,
                                            BlockAndTintGetter level,
                                            VertexConsumer consumer,
                                            BlockState blockState,
                                            FluidState fluidState,
                                            CallbackInfo ci) {
        if (consumer instanceof BlockSensitiveBufferBuilder bb && BlockPropertyIdMap.isLoaded()) {
            bb.endBlock();
        }
    }
}
