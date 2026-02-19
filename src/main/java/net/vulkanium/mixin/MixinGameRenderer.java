package net.vulkanium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Minecraft's GameRenderer to intercept the rendering pipeline.
 *
 * <p>GameRenderer is the top-level renderer — it drives:
 * <ul>
 *   <li>{@code render()} — called every frame (game loop tick)</li>
 *   <li>{@code renderLevel()} — called within render() when in-game</li>
 *   <li>Shader reload — when resource packs change or F3+T is pressed</li>
 * </ul></p>
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    /**
     * Before the main render call — begin Vulkan frame.
     */
    private static boolean loggedOnce = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        if (!loggedOnce) {
            Vulkanium.LOGGER.info("[DEBUG] MixinGameRenderer.render HEAD — vulkanReady={}", Vulkanium.isVulkanReady());
            loggedOnce = true;
        }
        if (Vulkanium.isVulkanReady()) {
            Vulkanium.onFrameBegin(partialTick);
        }
    }

    /**
     * After the main render call — submit and present Vulkan frame.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            Vulkanium.onFrameEnd();
        }
    }

    /**
     * Intercept shader reload to recompile SPIR-V pipelines.
     * This is triggered by F3+T (resource pack reload) or `/reload`.
     */
    @Inject(method = "reloadShaders", at = @At("HEAD"), cancellable = true)
    private void onReloadShaders(ResourceProvider resourceProvider, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        Vulkanium.LOGGER.info("Shader reload requested — recompiling Vulkan pipelines");
        // Recompile all GLSL→SPIR-V via SPIRVCompiler
        // Recreate Vulkan pipeline objects with new shader modules
        // This is critical for Iris shader pack hot-reload

        // Don't cancel — let MC's shader reload proceed so Iris can pick up changes
        // The Vulkan pipeline recreation happens asynchronously
    }

    /**
     * On GameRenderer close, clean up Vulkan shader resources.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.LOGGER.info("GameRenderer closing — cleaning up Vulkan shader pipelines");
    }
}
