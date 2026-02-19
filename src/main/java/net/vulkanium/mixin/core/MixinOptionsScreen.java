package net.vulkanium.mixin.core;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanium.Vulkanium;
import net.vulkanium.gui.VulkaniumVideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects the "Video Settings..." button on MC's Options screen to open
 * Vulkanium's own video settings screen, following the Sodium/VulkanMod pattern.
 *
 * <p>In MC 1.20.1, the "Video Settings..." button's onPress handler is a lambda
 * compiled as the synthetic method {@code method_19828} (Intermediary name).
 * This lambda returns a new {@code VideoOptionsScreen}. We intercept it to
 * return {@link VulkaniumVideoSettingsScreen} instead.</p>
 */
@Mixin(OptionsScreen.class)
public abstract class MixinOptionsScreen extends Screen {

    @Shadow private Screen lastScreen;
    @Shadow private Options options;

    protected MixinOptionsScreen(Component title) {
        super(title);
    }

    /**
     * Intercept the lambda that creates VideoOptionsScreen when "Video Settings..." is clicked.
     * This replaces the entire screen, so the vanilla button text and position are preserved.
     */
    @Inject(method = "method_19828", at = @At("HEAD"), cancellable = true)
    private void redirectVideoSettings(CallbackInfoReturnable<Screen> cir) {
        if (!Vulkanium.isVulkanReady()) return;
        cir.setReturnValue(VulkaniumVideoSettingsScreen.createScreen(this));
    }
}
