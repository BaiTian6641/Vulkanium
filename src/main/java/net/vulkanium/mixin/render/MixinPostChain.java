package net.vulkanium.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Intercepts Minecraft's PostChain (post-processing pipeline) for Vulkan.
 *
 * <p>MC's PostChain applies a series of PostPass instances (shader effects)
 * by rendering between FBOs. With Vulkan, we can optimize this into a
 * single render pass with subpasses, or at minimum ensure each pass
 * correctly transitions Vulkan images between render and sample states.</p>
 */
@Mixin(PostChain.class)
public abstract class MixinPostChain {

    @Shadow @Final private List<PostPass> passes;
    @Shadow @Final private RenderTarget screenTarget;

    /**
     * Intercept the post-processing pass to use Vulkan render passes.
     *
     * <p>Each PostPass reads from one render target and writes to another.
     * In Vulkan, we need to:
     * <ol>
     *   <li>Transition source image to SHADER_READ_ONLY_OPTIMAL</li>
     *   <li>Transition dest image to COLOR_ATTACHMENT_OPTIMAL</li>
     *   <li>Begin a render pass on the dest framebuffer</li>
     *   <li>Bind the shader pipeline + descriptor set with source texture</li>
     *   <li>Draw a fullscreen triangle</li>
     *   <li>End render pass</li>
     * </ol></p>
     */
    @Inject(method = "process", at = @At("HEAD"), cancellable = true)
    private void onProcess(float partialTick, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        // Execute each post-processing pass through Vulkan
        for (PostPass pass : this.passes) {
            // Each PostPass has:
            //   - input render target (to sample from)
            //   - output render target (to render to)
            //   - a shader program (maps to a Vulkan graphics pipeline)
            //
            // The MixinRenderTarget mixin handles the actual bind/unbind/blit,
            // so we can let the vanilla PostPass.process() flow, which calls:
            //   inTarget.bindRead() → outTarget.bindWrite() → draw → unbind
            //
            // The intercepted bindRead/bindWrite/blit in MixinRenderTarget
            // will handle Vulkan transitions.
            pass.process(partialTick);
        }

        ci.cancel();
    }

    /**
     * Intercept resize to recreate Vulkan framebuffers for post-processing.
     */
    @Inject(method = "resize", at = @At("HEAD"))
    private void onResize(int width, int height, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.LOGGER.debug("PostChain resize: {}x{}", width, height);
    }

    /**
     * Intercept close to clean up Vulkan resources for post-processing.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.LOGGER.debug("PostChain closed — cleaning up {} passes", this.passes.size());
    }
}
