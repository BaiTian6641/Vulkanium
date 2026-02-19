package net.vulkanium.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanium.Vulkanium;
import net.vulkanium.VulkaniumConfig;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.render.RenderMode;
import net.vulkanium.render.shader.ShaderCompiler;
import net.vulkanium.shaderpack.ShaderpackManager;
import net.vulkanium.shaderpack.VulkanShaderpackPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Iris-style shaderpack selection screen.
 *
 * <p>Lists all available shaderpacks found in the {@code shaderpacks/} directory.
 * Allows the user to select, load, and unload shaderpacks. The active shaderpack
 * is highlighted.</p>
 */
public class ShaderpackSelectionScreen extends Screen {

    private static final int LIST_WIDTH = 250;
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 40;
    private static final int LIST_BOTTOM_MARGIN = 50;

    private final Screen parentScreen;
    private final ShaderpackManager manager;

    private List<String> packNames = new ArrayList<>();
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    // Buttons
    private Button applyButton;
    private Button removeButton;
    private Button refreshButton;
    private Button clearCacheButton;
    private Button configureButton;
    private Button doneButton;

    private String statusMessage = "";
    private int statusColor = 0xAAAAAA;

    public ShaderpackSelectionScreen(Screen parent) {
        super(Component.literal("Shaderpack Selection"));
        this.parentScreen = parent;
        this.manager = Vulkanium.getShaderpackManager();
    }

    @Override
    protected void init() {
        super.init();

        // Refresh pack list
        refreshPackList();

        // Find the currently active pack in the list
        String activePack = manager != null ? manager.getActivePackName() : "";
        selectedIndex = -1;
        for (int i = 0; i < packNames.size(); i++) {
            if (packNames.get(i).equals(activePack)) {
                selectedIndex = i;
                break;
            }
        }

        // Buttons at the bottom
        int buttonY = this.height - 30;
        int buttonW = 74;
        int totalW = buttonW * 6 + 20;
        int startX = (this.width - totalW) / 2;

        applyButton = Button.builder(Component.literal("Apply"), btn -> applyShaderpack())
                .pos(startX, buttonY).size(buttonW, 20).build();
        removeButton = Button.builder(Component.literal("None"), btn -> removeShaderpack())
                .pos(startX + buttonW + 4, buttonY).size(buttonW, 20).build();
        refreshButton = Button.builder(Component.literal("Refresh"), btn -> refreshPackList())
                .pos(startX + (buttonW + 4) * 2, buttonY).size(buttonW, 20).build();
        clearCacheButton = Button.builder(Component.literal("Clear Cache"), btn -> clearCompiledShaderCache())
            .pos(startX + (buttonW + 4) * 3, buttonY).size(buttonW, 20).build();
        configureButton = Button.builder(Component.literal("Configure"), btn -> this.minecraft.setScreen(new ShaderpackConfigScreen(this)))
            .pos(startX + (buttonW + 4) * 4, buttonY).size(buttonW, 20).build();
        doneButton = Button.builder(Component.literal("Done"), btn -> onClose())
            .pos(startX + (buttonW + 4) * 5, buttonY).size(buttonW, 20).build();

        addRenderableWidget(applyButton);
        addRenderableWidget(removeButton);
        addRenderableWidget(refreshButton);
        addRenderableWidget(clearCacheButton);
        addRenderableWidget(configureButton);
        addRenderableWidget(doneButton);

        updateButtonStates();
    }

    private void refreshPackList() {
        packNames.clear();
        if (manager != null) {
            // Re-scan the directory
            java.nio.file.Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            manager.scanForPacks(gameDir.resolve("shaderpacks"));
            packNames.addAll(manager.getAvailablePacks());
        }
    }

    private void updateButtonStates() {
        applyButton.active = selectedIndex >= 0;
    }

    private void applyShaderpack() {
        if (selectedIndex < 0 || selectedIndex >= packNames.size()) return;
        String packName = packNames.get(selectedIndex);

        if (manager != null) {
            // Open the loading screen with progress display
            this.minecraft.setScreen(new ShaderpackLoadingScreen(this, packName));
        }
    }

    private void removeShaderpack() {
        if (manager != null) {
            manager.unloadPack();
            Vulkanium.setRenderMode(RenderMode.VANILLA);
            persistShaderpackSelection("", false);
            if (this.minecraft != null && this.minecraft.levelRenderer != null) {
                this.minecraft.levelRenderer.allChanged();
                this.minecraft.reloadResourcePacks();
            }
            selectedIndex = -1;
            statusMessage = "Shaderpack removed";
            statusColor = 0xAAAAAA;
            Vulkanium.LOGGER.info("Shaderpack removed");
        }
    }

    private void clearCompiledShaderCache() {
        int deleted = ShaderCompiler.clearCompiledShaderCacheFiles(Minecraft.getInstance().gameDirectory.toPath());

        statusMessage = deleted > 0
                ? "Cleared " + deleted + " cached shader files"
                : "Shader cache already empty";
        statusColor = deleted > 0 ? 0x55FF55 : 0xAAAAAA;
    }

    public static void persistShaderpackSelection(String packName, boolean enabled) {
        String selected = packName != null ? packName : "";

        VulkaniumConfig config = Vulkanium.getConfig();
        if (config != null) {
            config.shaderpackEnabled = enabled && !selected.isEmpty();
            config.selectedShaderpack = selected;
            config.save();
        }

        try {
            VulkaniumGameOptions options = VulkaniumGameOptions.loadFromDisk();
            options.shader.enableShaderpack = enabled && !selected.isEmpty();
            options.shader.selectedShaderpack = selected;
            VulkaniumGameOptions.writeToDisk(options);
        } catch (Exception e) {
            Vulkanium.LOGGER.warn("Failed to persist shaderpack selection: {}", e.getMessage());
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Check if click is in the list area
        int listX = (this.width - LIST_WIDTH) / 2;
        if (mouseX >= listX && mouseX <= listX + LIST_WIDTH
                && mouseY >= LIST_TOP && mouseY <= this.height - LIST_BOTTOM_MARGIN) {
            int clickedIndex = (int) ((mouseY - LIST_TOP + scrollOffset) / ENTRY_HEIGHT);
            if (clickedIndex >= 0 && clickedIndex < packNames.size()) {
                selectedIndex = clickedIndex;
                updateButtonStates();

                // Double-click to apply
                // (handled by tracking last click time, simplified here)
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, packNames.size() * ENTRY_HEIGHT - (this.height - LIST_TOP - LIST_BOTTOM_MARGIN));
        scrollOffset = (int) Math.max(0, Math.min(scrollOffset - delta * ENTRY_HEIGHT, maxScroll));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        // Active pack info
        String activePack = manager != null ? manager.getActivePackName() : "";
        String activeInfo = activePack.isEmpty() ? "No shaderpack active" : "Active: " + activePack;
        graphics.drawCenteredString(this.font, activeInfo, this.width / 2, 26, 0xAAAAAA);
        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, statusMessage, this.width / 2, 36, statusColor);
        }

        // Pack list
        int listX = (this.width - LIST_WIDTH) / 2;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        // List background
        graphics.fill(listX - 2, LIST_TOP - 2, listX + LIST_WIDTH + 2, listBottom + 2, 0x80000000);

        // Draw entries
        for (int i = 0; i < packNames.size(); i++) {
            int entryY = LIST_TOP + i * ENTRY_HEIGHT - scrollOffset;
            if (entryY + ENTRY_HEIGHT < LIST_TOP || entryY > listBottom) continue;

            String name = packNames.get(i);
            boolean isSelected = (i == selectedIndex);
            boolean isActive = name.equals(activePack);

            // Selection highlight
            if (isSelected) {
                graphics.fill(listX, entryY, listX + LIST_WIDTH, entryY + ENTRY_HEIGHT - 1, 0x60FFFFFF);
            }

            // Active indicator
            int textColor = isActive ? 0x55FF55 : 0xFFFFFF;
            String prefix = isActive ? "[✓] " : "    ";
            graphics.drawString(this.font, prefix + name, listX + 4, entryY + 6, textColor);
        }

        // Empty state
        if (packNames.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "No shaderpacks found — place them in .minecraft/shaderpacks/",
                    this.width / 2, LIST_TOP + 20, 0x888888);
        }

        // Render buttons
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
