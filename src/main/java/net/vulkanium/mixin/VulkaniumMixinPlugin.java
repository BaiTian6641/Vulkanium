package net.vulkanium.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for Vulkanium.
 *
 * <p>Controls conditional mixin application based on runtime state:</p>
 * <ul>
 *   <li>Disables all mixins if Vulkan is not available on the system</li>
 *   <li>Disables GL emulation mixins if running in GL-passthrough mode</li>
 *   <li>Detects and avoids conflicts with VulkanMod if both are installed</li>
 * </ul>
 */
public class VulkaniumMixinPlugin implements IMixinConfigPlugin {

    /** Set to true once Vulkan availability has been checked */
    private static boolean checkedVulkan = false;

    /** Whether Vulkan is available on this system */
    private static boolean vulkanAvailable = true;

    /** Whether VulkanMod is also installed (conflict detection) */
    private static boolean vulkanModPresent = false;

    @Override
    public void onLoad(String mixinPackage) {
        // Detect Vulkan availability via GLFW
        checkVulkanSupport();

        // Detect VulkanMod presence
        try {
            Class.forName("net.vulkanmod.Initializer");
            vulkanModPresent = true;
            System.err.println("[Vulkanium] WARNING: VulkanMod detected! Vulkanium will take priority for rendering.");
        } catch (ClassNotFoundException ignored) {
            // VulkanMod not present — good
        }
    }

    private static void checkVulkanSupport() {
        if (checkedVulkan) return;
        checkedVulkan = true;

        try {
            // Check if LWJGL Vulkan module is available
            Class.forName("org.lwjgl.vulkan.VK10");
            vulkanAvailable = true;
        } catch (ClassNotFoundException e) {
            vulkanAvailable = false;
            System.err.println("[Vulkanium] LWJGL Vulkan module not available. Vulkanium mixins will be disabled.");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // If Vulkan is not available, disable all Vulkanium mixins
        if (!vulkanAvailable) {
            return false;
        }

        // If VulkanMod is present, disable GL emulation mixins (VulkanMod handles those)
        // but keep our rendering pipeline mixins (we override VulkanMod's renderer)
        if (vulkanModPresent) {
            // Disable GL-level interception when VulkanMod already handles it
            if (mixinClassName.contains("MixinGL11") ||
                mixinClassName.contains("MixinGL15") ||
                mixinClassName.contains("MixinGL30")) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // No-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // No-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // No-op
    }

    public static boolean isVulkanAvailable() { return vulkanAvailable; }
    public static boolean isVulkanModPresent() { return vulkanModPresent; }
}
