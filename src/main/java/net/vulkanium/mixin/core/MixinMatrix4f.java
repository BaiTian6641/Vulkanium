package net.vulkanium.mixin.core;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Adjusts projection matrices for Vulkan's clip space conventions.
 *
 * <p>OpenGL uses a depth range of [-1, 1] while Vulkan uses [0, 1].
 * JOML's projection methods accept a {@code zZeroToOne} parameter that produces
 * [0, 1] depth when true. This mixin overrides the standard 4/6-parameter
 * projection methods to delegate to the overloaded versions with {@code zZeroToOne = true},
 * matching VulkanMod's {@code Matrix4fM} approach.</p>
 *
 * <p>MC uses both {@code set*} (create from identity) and non-{@code set*}
 * (post-multiply onto existing) variants. All four must be intercepted:</p>
 * <ul>
 *   <li>{@code setOrtho}  — GUI orthographic projection (MC primary usage)</li>
 *   <li>{@code ortho}     — post-multiply ortho</li>
 *   <li>{@code setPerspective} — world/panorama perspective (MC primary usage)</li>
 *   <li>{@code perspective}    — post-multiply perspective</li>
 * </ul>
 */
@Mixin(Matrix4f.class)
public abstract class MixinMatrix4f {

    // Shadows for the zZeroToOne overloads — different method signatures, no conflict
    @Shadow(remap = false) public abstract Matrix4f perspective(float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne);
    @Shadow(remap = false) public abstract Matrix4f ortho(float left, float right, float bottom, float top, float zNear, float zFar, boolean zZeroToOne);
    @Shadow(remap = false) public abstract Matrix4f setOrtho(float left, float right, float bottom, float top, float zNear, float zFar, boolean zZeroToOne);
    @Shadow(remap = false) public abstract Matrix4f setPerspective(float fovy, float aspect, float zNear, float zFar, boolean zZeroToOne);

    /**
     * @author Vulkanium
     * @reason Vulkan [0,1] depth range — MUST modify 'this' (not create new Matrix4f!).
     *         MC calls e.g. {@code poseStack.last().pose().setOrtho(...)} and discards
     *         the return value, expecting in-place mutation via JOML convention.
     */
    @Overwrite(remap = false)
    public Matrix4f setOrtho(float left, float right, float bottom, float top, float zNear, float zFar) {
        // Delegate to the 7-arg overload (different signature → calls original JOML, no recursion)
        return this.setOrtho(left, right, bottom, top, zNear, zFar, true);
    }

    /** @author Vulkanium @reason Vulkan [0,1] depth range */
    @Overwrite(remap = false)
    public Matrix4f ortho(float left, float right, float bottom, float top, float zNear, float zFar) {
        return this.ortho(left, right, bottom, top, zNear, zFar, true);
    }

    /** @author Vulkanium @reason Vulkan [0,1] depth range */
    @Overwrite(remap = false)
    public Matrix4f perspective(float fovy, float aspect, float zNear, float zFar) {
        return this.perspective(fovy, aspect, zNear, zFar, true);
    }

    /**
     * @author Vulkanium
     * @reason Vulkan [0,1] depth range — MUST modify 'this' (not create new Matrix4f!).
     *         MC calls {@code matrix4f.setPerspective(...)} and discards the return value.
     */
    @Overwrite(remap = false)
    public Matrix4f setPerspective(float fovy, float aspect, float zNear, float zFar) {
        // Delegate to the 5-arg overload (different signature → calls original JOML, no recursion)
        return this.setPerspective(fovy, aspect, zNear, zFar, true);
    }
}
