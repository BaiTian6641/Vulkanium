package net.vulkanium.mixin.core;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.vulkanium.Vulkanium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the hotbar, experience bar, and other player HUD elements when the
 * camera is in second-person (front-facing) or third-person mode.
 *
 * <p>In non-first-person views the player model is visible, and the hotbar
 * is unnecessary / visually distracting. This mirrors the behaviour of many
 * cinematic and shader-pack mods.</p>
 */
@Mixin(Gui.class)
public abstract class MixinGui {

    @Shadow
    private Minecraft minecraft;

    /**
     * Cancels the entire HUD render when the camera is not first-person.
     * This hides hotbar, health, food, XP bar, crosshair, boss bar, etc.
     *
     * <p>The chat, debug (F3) overlay, and subtitle overlay are rendered
     * separately and are NOT affected.</p>
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vulkanium$hideHudInThirdPerson(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (minecraft == null || minecraft.options == null) return;

        CameraType cameraType = minecraft.options.getCameraType();
        if (cameraType != CameraType.FIRST_PERSON) {
            // Skip the main HUD render (hotbar, health, XP, crosshair, etc.)
            // Chat, tab list, and debug screen are rendered via different methods
            ci.cancel();
        }
    }
}
