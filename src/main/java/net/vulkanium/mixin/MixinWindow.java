package net.vulkanium.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Replaces Minecraft's OpenGL window initialization with Vulkan.
 *
 * <p>Follows VulkanMod's proven approach:</p>
 * <ol>
 *   <li>Suppress all GL window hints → no-op</li>
 *   <li>Set {@code GLFW_CLIENT_API = GLFW_NO_API} before window creation</li>
 *   <li>Suppress {@code glfwMakeContextCurrent} → no-op</li>
 *   <li>Return {@code null} for {@code GL.createCapabilities()}</li>
 *   <li>After window creation: initialize Vulkan surface + swapchain</li>
 * </ol>
 */
@Mixin(Window.class)
public abstract class MixinWindow {

    @Shadow private long window;

    /**
     * Suppress all GL window hints — Vulkan doesn't need them.
     * VulkanMod pattern: redirect every glfwWindowHint call to a no-op.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void suppressGlWindowHints(int hint, int value) {
        // No-op: discard all GL-related window hints (GLFW_CONTEXT_VERSION_MAJOR, etc.)
        // We'll set GLFW_NO_API ourselves before glfwCreateWindow
    }

    /**
     * Just before glfwCreateWindow, set GLFW_CLIENT_API = GLFW_NO_API
     * and GLFW_RESIZABLE = GLFW_TRUE (suppressed by the hint redirect above).
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void setNoApiBeforeCreateWindow(CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        Vulkanium.LOGGER.info("MixinWindow: Set GLFW_CLIENT_API = GLFW_NO_API, GLFW_RESIZABLE = GLFW_TRUE");
    }

    /**
     * Suppress glfwMakeContextCurrent — there is no GL context.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void suppressMakeContextCurrent(long window) {
        // No-op: no GL context to make current
    }

    /**
     * Suppress GL.createCapabilities() — return null, no GL context exists.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities suppressCreateCapabilities() {
        // Return null: no GL context → no GL capabilities
        return null;
    }

    /**
     * After Window construction completes, initialize the Vulkan subsystem.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onWindowCreated(CallbackInfo ci) {
        Vulkanium.LOGGER.info("MixinWindow: Window created (handle={}), initializing Vulkan...", this.window);
        Vulkanium.onWindowCreated(this.window);
        VRenderSystem.initWindow(this.window);

        Vulkanium.LOGGER.info("MixinWindow: Vulkan init complete, vulkanReady={}", Vulkanium.isVulkanReady());
    }

    /**
     * Replace vsync control — delegate to Vulkan present mode selection.
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        if (Vulkanium.isVulkanReady()) {
            Vulkanium.setVsync(vsync);
        } else {
            // Vulkan not ready yet — ignore (no GL context either)
        }
    }

    /**
     * Replace display update — Vulkan doesn't use glfwSwapBuffers.
     * Frame presentation is handled by vkQueuePresent in the frame orchestrator.
     * Must call flipFrame so the render-call recording queue is drained.
     */
    @Overwrite
    public void updateDisplay() {
        RenderSystem.flipFrame(this.window);
        // Fullscreen toggle handled separately
    }

    /**
     * Handle framebuffer resize — schedule Vulkan swapchain recreation.
     *
     * <p>We must NOT cancel the vanilla callback because it updates
     * {@code framebufferWidth/Height} and calls {@code minecraft.resizeDisplay()},
     * which are essential for UI layout to adapt to the new window size.
     * The vanilla code only touches GL viewport (which our MixinRenderSystem intercepts),
     * so it's safe to let it run.</p>
     */
    @Inject(method = "onFramebufferResize", at = @At("HEAD"))
    private void onFramebufferResized(long window, int width, int height, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        Vulkanium.scheduleSwapchainRecreation(width, height);
    }
}
