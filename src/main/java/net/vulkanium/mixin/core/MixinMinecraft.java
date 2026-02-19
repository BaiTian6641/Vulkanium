package net.vulkanium.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Minecraft's main game loop for Vulkan frame lifecycle management.
 *
 * <p>Like VulkanMod's M_Minecraft mixin, this intercepts the main render
 * loop to insert Vulkan frame begin/end calls and handle shutdown.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Unique
    private static boolean vulkanium$stopLogged = false;

    /**
     * Frame lifecycle is handled by MixinGameRenderer (render HEAD/RETURN),
     * NOT here in runTick, to avoid double acquire/present per tick.
     */

    /**
     * On game shutdown, destroy all Vulkan resources before the process exits.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onShutdown(CallbackInfo ci) {
        Vulkanium.destroy();
    }

    /**
     * Log when MC sets running=false (only once).
     */
    @Inject(method = "stop", at = @At("HEAD"))
    private void onStop(CallbackInfo ci) {
        if (!vulkanium$stopLogged) {
            vulkanium$stopLogged = true;
            Vulkanium.LOGGER.info("[Minecraft] stop() called — will exit game loop");
        }
    }

    /**
     * Intercept the initial loading phase to log Vulkan readiness.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onMinecraftInit(CallbackInfo ci) {
        Vulkanium.LOGGER.info("[DEBUG] MixinMinecraft: <init> TAIL — vulkanReady={}", Vulkanium.isVulkanReady());
    }
}
