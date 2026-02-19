package net.vulkanium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Probes the system for Vulkan compatibility before any Vulkan objects are created.
 *
 * <p>Called from {@code Vulkanium.onInitializeClient()} to fail fast with a helpful
 * error message if the system doesn't support the engine's requirements.</p>
 */
public class VulkaniumCapabilities {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Capabilities");

    /**
     * Checks whether the system can run Vulkanium.
     *
     * @return A {@link CompatibilityResult} indicating success or the reason for failure
     */
    public static CompatibilityResult checkSystemCompatibility() {
        // 1. Check GLFW Vulkan support
        if (!GLFWVulkan.glfwVulkanSupported()) {
            return CompatibilityResult.failure("GLFW reports no Vulkan support. " +
                    "Ensure your GPU drivers are installed and up to date.");
        }

        // 2. Check instance-level Vulkan version
        try (MemoryStack stack = stackPush()) {
            IntBuffer pApiVersion = stack.ints(0);
            int result = vkEnumerateInstanceVersion(pApiVersion);
            if (result != VK_SUCCESS) {
                return CompatibilityResult.failure("Failed to query Vulkan instance version (error: " + result + ")");
            }

            int apiVersion = pApiVersion.get(0);
            int major = VK_API_VERSION_MAJOR(apiVersion);
            int minor = VK_API_VERSION_MINOR(apiVersion);
            int patch = VK_API_VERSION_PATCH(apiVersion);

            LOGGER.info("Vulkan instance version: {}.{}.{}", major, minor, patch);

            if (apiVersion < VK_API_VERSION_1_2) {
                return CompatibilityResult.failure(
                        "Vulkan 1.2 is required but only %d.%d.%d is available. Update your GPU drivers.".formatted(major, minor, patch));
            }
        }

        // 3. Check required instance extensions
        Set<String> requiredExtensions = getRequiredInstanceExtensions();
        Set<String> availableExtensions = getAvailableInstanceExtensions();

        Set<String> missing = new HashSet<>(requiredExtensions);
        missing.removeAll(availableExtensions);

        if (!missing.isEmpty()) {
            return CompatibilityResult.failure(
                    "Missing required Vulkan instance extensions: " + String.join(", ", missing));
        }

        // 4. Check that at least one physical device exists
        // (We can't do full device enumeration without creating an instance, so just check count)
        // This is best-effort; full checking happens in VulkaniumDevice.initialize()

        LOGGER.info("System compatibility check passed");
        return CompatibilityResult.success();
    }

    /**
     * Returns the set of instance extensions required by Vulkanium.
     */
    public static Set<String> getRequiredInstanceExtensions() {
        Set<String> extensions = new HashSet<>();

        // GLFW surface extensions
        PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (glfwExtensions != null) {
            for (int i = 0; i < glfwExtensions.capacity(); i++) {
                extensions.add(glfwExtensions.getStringUTF8(i));
            }
        }

        return extensions;
    }

    /**
     * Enumerates all available Vulkan instance extensions.
     */
    public static Set<String> getAvailableInstanceExtensions() {
        Set<String> available = new HashSet<>();

        try (MemoryStack stack = stackPush()) {
            IntBuffer extensionCount = stack.ints(0);
            vkEnumerateInstanceExtensionProperties((String) null, extensionCount, null);

            VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((String) null, extensionCount, extensions);

            for (int i = 0; i < extensions.capacity(); i++) {
                available.add(extensions.get(i).extensionNameString());
            }
        }

        return available;
    }

    /**
     * Enumerates available instance layers (for debug/validation).
     */
    public static Set<String> getAvailableInstanceLayers() {
        Set<String> available = new HashSet<>();

        try (MemoryStack stack = stackPush()) {
            IntBuffer layerCount = stack.ints(0);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer layers = VkLayerProperties.malloc(layerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(layerCount, layers);

            for (int i = 0; i < layers.capacity(); i++) {
                available.add(layers.get(i).layerNameString());
            }
        }

        return available;
    }

    // === Result Type ===

    public record CompatibilityResult(boolean compatible, String reason) {
        public static CompatibilityResult success() {
            return new CompatibilityResult(true, null);
        }

        public static CompatibilityResult failure(String reason) {
            return new CompatibilityResult(false, reason);
        }
    }
}
