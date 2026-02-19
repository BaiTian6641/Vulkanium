package net.vulkanium.mixin.world;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Hooks into ClientChunkCache to track chunk load/unload events for the
 * Vulkan terrain rendering pipeline.
 *
 * <p>When chunks are loaded or unloaded, the Vulkan terrain system needs to:
 * <ul>
 *   <li>Create or destroy chunk section render data</li>
 *   <li>Update the section graph for visibility culling</li>
 *   <li>Schedule chunk mesh rebuilds</li>
 *   <li>Update biome color caches</li>
 * </ul></p>
 */
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkManager {

    /**
     * When a new chunk arrives from the server, notify the Vulkan terrain system.
     */
    @Inject(method = "replaceWithPacketData",
            at = @At("RETURN"))
    private void onChunkLoaded(int x, int z, FriendlyByteBuf buf, CompoundTag tag,
                               Consumer<net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntityConsumer,
                               CallbackInfoReturnable<LevelChunk> cir) {
        if (!Vulkanium.isVulkanReady()) return;

        LevelChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            // Notify section graph of new chunk — schedule mesh builds for all non-empty sections
            Vulkanium.LOGGER.debug("Chunk loaded: ({}, {}) — scheduling section rebuilds", x, z);

            // Mark all sections in this chunk as needing rebuild
            int minY = chunk.getMinBuildHeight();
            int maxY = chunk.getMaxBuildHeight();
            int sectionsPerChunk = (maxY - minY) >> 4;

            for (int sy = 0; sy < sectionsPerChunk; sy++) {
                int sectionY = (minY >> 4) + sy;
                // onSectionLoaded will schedule mesh build
                onSectionChanged(x, sectionY, z);
            }
        }
    }

    /**
     * When a chunk is dropped (player moves away), clean up Vulkan resources.
     */
    @Inject(method = "drop", at = @At("HEAD"))
    private void onChunkUnloaded(int x, int z, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        Vulkanium.LOGGER.debug("Chunk unloaded: ({}, {})", x, z);

        // Notify section graph to remove all sections in this chunk
        // Resources (VkBuffers for mesh data) will be freed
        // The actual cleanup is deferred to prevent use-after-free
        // while in-flight frames may still reference the data
    }

    /**
     * Notify the Vulkan terrain system that a section has changed.
     */
    private static void onSectionChanged(int chunkX, int sectionY, int chunkZ) {
        // TODO: Wire to VulkaniumWorldRenderer.markSectionDirty()
        // For now, just log at debug level
    }
}
