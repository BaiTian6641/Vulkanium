package net.vulkanium.mixin.sky;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.vulkanium.Vulkanium;
import net.vulkanium.shaderpack.CloudSetting;
import net.vulkanium.shaderpack.VulkanShaderpackPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the cloud rendering setting when a shaderpack requests {@code clouds = off}.
 *
 * <p>Shaderpacks like iterationT, iterationRP set {@code clouds = off} in
 * {@code shaders.properties} because they render volumetric clouds in
 * composite/deferred passes (NUBIS.glsl). Without this override, vanilla
 * cloud geometry renders over the top of the shaderpack's volumetric clouds.</p>
 *
 * <p>Uses priority 1010 to apply after Sodium's MixinGameOptions (if present).</p>
 */
@Mixin(value = Options.class, priority = 1010)
public class MixinOptions_CloudsOverride {

    @Shadow
    @Final
    private OptionInstance<Integer> renderDistance;

    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true)
    private void vulkanium$overrideCloudsType(CallbackInfoReturnable<CloudStatus> cir) {
        // Vanilla does not render clouds on low render distances
        if (renderDistance.get() < 4) return;

        if (!Vulkanium.isShaderpackPipelineActive()) return;

        try {
            var pipeline = Vulkanium.getShaderpackManager().getActivePipeline();
            if (pipeline instanceof VulkanShaderpackPipeline vkPipeline) {
                CloudSetting setting = vkPipeline.getCloudSetting();
                switch (setting) {
                    case OFF -> cir.setReturnValue(CloudStatus.OFF);
                    case FAST -> cir.setReturnValue(CloudStatus.FAST);
                    case FANCY -> cir.setReturnValue(CloudStatus.FANCY);
                    case DEFAULT -> {} // don't override
                }
            }
        } catch (Exception e) {
            // Safety: don't crash if pipeline not ready during loading
        }
    }
}
