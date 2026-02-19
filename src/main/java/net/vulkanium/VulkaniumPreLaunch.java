package net.vulkanium;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-launch entrypoint for Vulkanium.
 * Runs before Minecraft's main class to set up critical system properties
 * and ensure Vulkan drivers are accessible.
 */
public class VulkaniumPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/PreLaunch");

    @Override
    public void onPreLaunch() {
        LOGGER.info("Vulkanium pre-launch: configuring Vulkan environment");

        // Ensure LWJGL loads Vulkan natives
        // On some systems, GLFW must be told not to create an OpenGL context
        // This will be handled by our Window mixin
    }
}
