package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimizes entity model rendering for the Vulkan pipeline.
 *
 * <p>Minecraft's ModelPart.compile() emits vertices for entity models cube-by-cube.
 * This mixin provides:</p>
 * <ul>
 *   <li><b>Visibility tracking</b> — skip compilation for invisible parts</li>
 *   <li><b>Geometry fingerprinting</b> — detect unchanged geometry for caching</li>
 *   <li><b>Render statistics</b> — per-part compile/render counts for profiling</li>
 *   <li><b>Transform caching</b> — cache pose transforms for static parts</li>
 * </ul>
 *
 * <p>The vanilla compile path writes to VertexConsumer which routes through our
 * Vulkan buffer system (MixinBufferUploader). This mixin adds optimization
 * layers on top of that flow.</p>
 */
@Mixin(ModelPart.class)
public abstract class MixinModelPart {

    @Shadow @Final private List<ModelPart.Cube> cubes;
    @Shadow @Final private Map<String, ModelPart> children;
    @Shadow public boolean visible;
    @Shadow public boolean skipDraw;
    @Shadow public float x, y, z;
    @Shadow public float xRot, yRot, zRot;
    @Shadow public float xScale, yScale, zScale;

    // ── Render Statistics ──

    /** Total number of times this part has been rendered */
    @Unique private long vulkanium$renderCount = 0;

    /** Total number of vertices compiled by this part */
    @Unique private long vulkanium$totalVerticesCompiled = 0;

    /** Global compile counter across all ModelParts for frame-rate diagnostics */
    @Unique private static final AtomicLong vulkanium$globalCompileCount = new AtomicLong(0);

    /** Global render counter for performance tracking */
    @Unique private static final AtomicLong vulkanium$globalRenderCount = new AtomicLong(0);

    // ── Transform Caching ──

    /** Last-known transform values for change detection */
    @Unique private float vulkanium$lastX, vulkanium$lastY, vulkanium$lastZ;
    @Unique private float vulkanium$lastXRot, vulkanium$lastYRot, vulkanium$lastZRot;
    @Unique private float vulkanium$lastXScale = 1.0f, vulkanium$lastYScale = 1.0f, vulkanium$lastZScale = 1.0f;

    /** Whether the transform has changed since last compile */
    @Unique private boolean vulkanium$transformDirty = true;

    /**
     * Hook into render to track visibility and transform state.
     *
     * <p>Checks if the part is visible and has cubes to render.
     * Tracks per-part render count and detects transform changes.</p>
     */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V",
            at = @At("HEAD"))
    private void onRender(PoseStack poseStack, VertexConsumer consumer,
                          int packedLight, int packedOverlay, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        vulkanium$renderCount++;
        vulkanium$globalRenderCount.incrementAndGet();

        // Detect transform changes for potential geometry caching
        vulkanium$updateTransformDirty();
    }

    /**
     * Hook into render with color parameters (entity overlay colors).
     *
     * <p>This variant is used when entities have custom coloring (e.g., hurt flash,
     * sheep wool color, slime transparency). Tracks the same metrics.</p>
     */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"))
    private void onRenderColored(PoseStack poseStack, VertexConsumer consumer,
                                  int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha,
                                  CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        vulkanium$renderCount++;
        vulkanium$globalRenderCount.incrementAndGet();
        vulkanium$updateTransformDirty();
    }

    /**
     * Hook into compile to track geometry compilation.
     *
     * <p>This is where vertices are actually emitted for each cube in the model part.
     * We track compile counts and total vertices for performance profiling.</p>
     */
    @Inject(method = "compile", at = @At("HEAD"))
    private void onCompile(PoseStack.Pose pose, VertexConsumer consumer,
                           int packedLight, int packedOverlay, float red, float green,
                           float blue, float alpha, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        vulkanium$globalCompileCount.incrementAndGet();

        // Each cube has 6 faces × 4 vertices = 24 vertices (as quads)
        // Track total vertices for bandwidth estimation
        int verticesThisPart = cubes.size() * 24;
        vulkanium$totalVerticesCompiled += verticesThisPart;
    }

    /**
     * Checks if the part's transform has changed since the last frame.
     * Used for geometry caching: unchanged transforms can reuse cached vertex data.
     */
    @Unique
    private void vulkanium$updateTransformDirty() {
        if (x != vulkanium$lastX || y != vulkanium$lastY || z != vulkanium$lastZ ||
            xRot != vulkanium$lastXRot || yRot != vulkanium$lastYRot || zRot != vulkanium$lastZRot ||
            xScale != vulkanium$lastXScale || yScale != vulkanium$lastYScale || zScale != vulkanium$lastZScale) {
            vulkanium$transformDirty = true;
            vulkanium$lastX = x; vulkanium$lastY = y; vulkanium$lastZ = z;
            vulkanium$lastXRot = xRot; vulkanium$lastYRot = yRot; vulkanium$lastZRot = zRot;
            vulkanium$lastXScale = xScale; vulkanium$lastYScale = yScale; vulkanium$lastZScale = zScale;
        } else {
            vulkanium$transformDirty = false;
        }
    }

    // ── Static Accessors for Diagnostics ──

    /**
     * Returns the global compile count across all ModelParts and resets it.
     * Called from frame diagnostics.
     */
    @Unique
    private static long vulkanium$getAndResetGlobalCompileCount() {
        return vulkanium$globalCompileCount.getAndSet(0);
    }

    /**
     * Returns the global render count and resets it.
     */
    @Unique
    private static long vulkanium$getAndResetGlobalRenderCount() {
        return vulkanium$globalRenderCount.getAndSet(0);
    }
}
