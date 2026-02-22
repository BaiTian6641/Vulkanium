package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts BufferUploader.drawWithShader() — the main draw dispatch in Minecraft.
 *
 * <p>Uses @Inject(HEAD, cancellable) so that in GL dev mode, the original GL draw
 * path executes normally (vertex upload + GL draw call).</p>
 */
@Mixin(BufferUploader.class)
public abstract class MixinBufferUploader {

    @Inject(method = "drawWithShader", at = @At("HEAD"), cancellable = true)
    private static void onDrawWithShader(BufferBuilder.RenderedBuffer renderedBuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            try {
                BufferBuilder.DrawState drawState = renderedBuffer.drawState();
                if (drawState.vertexCount() <= 0) {
                    // No vertices — skip draw, finally block will release
                    ci.cancel();
                    return;
                }

                VertexFormat format = drawState.format();
                VertexFormat.Mode mode = drawState.mode();
                int vertexCount = drawState.vertexCount();
                boolean hasIndex = drawState.indexOnly();

                java.nio.ByteBuffer vertexData = renderedBuffer.vertexBuffer();

                // ─── Sync VRenderSystem to RenderSystem's canonical state ───
                // VertexBuffer.drawWithShader() persists its parameter MV/Proj
                // in VRenderSystem for the persistent VBO draw path.  But
                // BufferUploader draws (entities, sun/moon, particles, weather)
                // should use whatever RenderSystem.modelViewMatrix has:
                //   • identity during entity rendering (no applyModelViewMatrix yet)
                //   • identity during renderSky sun/moon (pre-transformed vertices)
                //   • camera rotation after applyModelViewMatrix for particles/weather
                // This matches vanilla MC where BufferUploader.drawWithShader reads
                // RenderSystem.getModelViewMatrix() for the MODEL_VIEW_MATRIX uniform.
                VRenderSystem.setModelViewMatrix(RenderSystem.getModelViewMatrix());
                VRenderSystem.setProjectionMatrix(
                        RenderSystem.getProjectionMatrix(),
                        VRenderSystem.getVertexSorting());

                VRenderSystem.uploadAndDraw(
                        vertexData,
                        renderedBuffer.indexBuffer(),
                        format,
                        mode,
                        vertexCount,
                        drawState.indexCount(),
                        hasIndex
                );
            } finally {
                renderedBuffer.release();
            }
            ci.cancel();
        }
        // If not Vulkan-ready, original GL draw path runs
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private static void onDraw(BufferBuilder.RenderedBuffer renderedBuffer, CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            // In Vulkan, all draws go through a pipeline — delegate to drawWithShader
            BufferUploader.drawWithShader(renderedBuffer);
            ci.cancel();
        }
    }

    @Inject(method = "reset", at = @At("HEAD"), cancellable = true)
    private static void onReset(CallbackInfo ci) {
        if (Vulkanium.isVulkanReady()) {
            ci.cancel(); // No GL VAO/VBO state to reset
        }
    }
}
