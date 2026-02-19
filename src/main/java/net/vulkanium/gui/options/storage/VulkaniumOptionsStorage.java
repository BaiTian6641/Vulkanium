package net.vulkanium.gui.options.storage;

import net.vulkanium.VulkaniumGameOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Option storage backed by {@link VulkaniumGameOptions}.
 * Handles loading/saving the Vulkanium-specific configuration.
 */
public class VulkaniumOptionsStorage implements OptionStorage<VulkaniumGameOptions> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Storage");
    private final VulkaniumGameOptions data;

    public VulkaniumOptionsStorage(VulkaniumGameOptions data) {
        this.data = data;
    }

    @Override
    public VulkaniumGameOptions getData() {
        return this.data;
    }

    @Override
    public void save() {
        try {
            VulkaniumGameOptions.writeToDisk(this.data);
        } catch (IOException e) {
            LOGGER.error("Failed to save Vulkanium options: {}", e.getMessage());
        }
    }
}
