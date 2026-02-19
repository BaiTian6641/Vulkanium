package net.vulkanium.mixin.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts NativeImage upload/download to route through Vulkan staging buffers.
 *
 * <p>NativeImage is Minecraft's wrapper around a CPU-side pixel buffer. Its
 * {@code _upload()} method calls glTexSubImage2D to push pixels to the GPU.
 * With Vulkan, we instead copy from the NativeImage's pixel buffer into a
 * staging buffer, then issue vkCmdCopyBufferToImage.</p>
 */
@Mixin(NativeImage.class)
public abstract class MixinNativeImage {

    @Shadow private long pixels;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private NativeImage.Format format;

    /**
     * Intercept texture upload — route through Vulkan image staging instead of glTexSubImage2D.
     */
    @Inject(method = "_upload", at = @At("HEAD"), cancellable = true)
    private void onUpload(int level, int xOffset, int yOffset, int unpackSkipPixels,
                          int unpackSkipRows, int uploadWidth, int uploadHeight,
                          boolean blur, boolean clamp, boolean mipmap, boolean close,
                          CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        try {
            // Calculate source offset in the pixel buffer
            int pixelSize = this.format.components();
            long srcOffset = ((long) unpackSkipRows * this.width + unpackSkipPixels) * pixelSize;

            // Upload region to the currently bound Vulkan texture via staging
            GlStateInterceptor.getInstance().onNativeImageUpload(
                    level, xOffset, yOffset,
                    uploadWidth, uploadHeight,
                    this.pixels + srcOffset,
                    this.width, // row stride
                    pixelSize,
                    blur, clamp, mipmap
            );

            if (close) {
                // Free the native pixel memory that vanilla would have freed
                ((NativeImage)(Object)this).close();
            }

            ci.cancel(); // Skip GL upload path
        } catch (Exception e) {
            Vulkanium.LOGGER.error("Failed to upload NativeImage via Vulkan", e);
            // Fall through to GL path (which will fail, but at least we logged it)
        }
    }

    /**
     * Intercept texture download — read from Vulkan image instead of glGetTexImage.
     */
    @Inject(method = "downloadTexture", at = @At("HEAD"), cancellable = true)
    private void onDownload(int level, boolean removeAlpha, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        try {
            // Read texture data from the currently bound Vulkan image
            GlStateInterceptor.getInstance().onNativeImageDownload(
                    level, this.pixels, this.width, this.height,
                    this.format.components(), removeAlpha
            );
            ci.cancel();
        } catch (Exception e) {
            Vulkanium.LOGGER.error("Failed to download NativeImage via Vulkan", e);
        }
    }
}
