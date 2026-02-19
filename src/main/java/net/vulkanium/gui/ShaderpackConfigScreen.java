package net.vulkanium.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanium.Vulkanium;
import net.vulkanium.VulkaniumConfig;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.shaderpack.ShaderpackManager;

import java.io.IOException;

/**
 * Engine-level shader compilation settings page.
 *
 * <p>Configures Vulkanium's shader pipeline behavior — shadow resolution overrides,
 * SPIR-V optimization level, geometry shader support, and render target format.
 * These are engine settings that affect how shaderpacks are compiled and rendered,
 * NOT per-pack options (those are handled by {@link ShaderpackScreen}).</p>
 *
 * <p>Changes to these settings require a shader reload to take effect.</p>
 */
public class ShaderpackConfigScreen extends Screen {

    private static final int[] SHADOW_RES_CHOICES = {0, 512, 1024, 2048, 4096};
    private static final float[] SHADOW_DIST_CHOICES = {64.0f, 96.0f, 128.0f, 192.0f, 256.0f};

    private final Screen parentScreen;
    private final ShaderpackManager manager;

    private int shadowResolution;
    private float maxShadowDistance;
    private boolean forceRgba8;
    private boolean disableGeometryShaders;
    private int spirvOptimizationLevel;

    private Button shadowResButton;
    private Button shadowDistButton;
    private Button forceRgba8Button;
    private Button geometryButton;
    private Button spirvOptButton;
    private Button reloadButton;

    private String statusMessage = "";
    private int statusColor = 0xAAAAAA;

    public ShaderpackConfigScreen(Screen parent) {
        super(Component.literal("Shader Engine Settings"));
        this.parentScreen = parent;
        this.manager = Vulkanium.getShaderpackManager();
    }

    @Override
    protected void init() {
        super.init();
        loadCurrentValues();

        int centerX = this.width / 2;
        int y = 50;
        int w = 260;
        int h = 20;
        int gap = 26;

        shadowResButton = Button.builder(Component.empty(), btn -> {
                    shadowResolution = cycleInt(SHADOW_RES_CHOICES, shadowResolution, 1);
                    refreshLabels();
                })
                .pos(centerX - w / 2, y).size(w, h).build();
        addRenderableWidget(shadowResButton);

        y += gap;
        shadowDistButton = Button.builder(Component.empty(), btn -> {
                    maxShadowDistance = cycleFloat(SHADOW_DIST_CHOICES, maxShadowDistance, 1);
                    refreshLabels();
                })
                .pos(centerX - w / 2, y).size(w, h).build();
        addRenderableWidget(shadowDistButton);

        y += gap;
        forceRgba8Button = Button.builder(Component.empty(), btn -> {
                    forceRgba8 = !forceRgba8;
                    refreshLabels();
                })
                .pos(centerX - w / 2, y).size(w, h).build();
        addRenderableWidget(forceRgba8Button);

        y += gap;
        geometryButton = Button.builder(Component.empty(), btn -> {
                    disableGeometryShaders = !disableGeometryShaders;
                    refreshLabels();
                })
                .pos(centerX - w / 2, y).size(w, h).build();
        addRenderableWidget(geometryButton);

        y += gap;
        spirvOptButton = Button.builder(Component.empty(), btn -> {
                    spirvOptimizationLevel = (spirvOptimizationLevel + 1) % 3;
                    refreshLabels();
                })
                .pos(centerX - w / 2, y).size(w, h).build();
        addRenderableWidget(spirvOptButton);

        // Bottom buttons
        int bottomY = this.height - 30;
        int bw = 100;
        int startX = centerX - (bw * 3 + 8) / 2;

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> save())
                .pos(startX, bottomY).size(bw, 20).build());

        reloadButton = Button.builder(Component.literal("Save & Reload"), btn -> saveAndReload())
                .pos(startX + bw + 4, bottomY).size(bw, 20).build();
        reloadButton.active = manager != null && manager.isPackLoaded();
        addRenderableWidget(reloadButton);

        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .pos(startX + (bw + 4) * 2, bottomY).size(bw, 20).build());

        refreshLabels();
    }

    private void loadCurrentValues() {
        VulkaniumGameOptions options = VulkaniumGameOptions.loadFromDisk();
        shadowResolution = options.quality.shadowResolution;
        maxShadowDistance = options.quality.maxShadowDistance;
        forceRgba8 = options.shader.forceRGBA8;
        disableGeometryShaders = options.shader.disableGeometryShaders;
        spirvOptimizationLevel = Math.max(0, Math.min(2, options.shader.spirvOptimizationLevel));
    }

    private void refreshLabels() {
        shadowResButton.setMessage(Component.literal("Shadow Resolution: " +
                (shadowResolution == 0 ? "Pack Default" : shadowResolution + "x" + shadowResolution)));
        shadowDistButton.setMessage(Component.literal("Max Shadow Distance: " + (int) maxShadowDistance + " blocks"));
        forceRgba8Button.setMessage(Component.literal("Force RGBA8 Targets: " + (forceRgba8 ? "ON" : "OFF")));
        geometryButton.setMessage(Component.literal("Geometry Shaders: " + (disableGeometryShaders ? "Disabled" : "Enabled")));
        spirvOptButton.setMessage(Component.literal("SPIR-V Optimization: " + optLabel(spirvOptimizationLevel)));
        if (reloadButton != null) reloadButton.active = manager != null && manager.isPackLoaded();
    }

    private void save() {
        VulkaniumGameOptions options = VulkaniumGameOptions.loadFromDisk();
        options.quality.shadowResolution = shadowResolution;
        options.quality.maxShadowDistance = maxShadowDistance;
        options.shader.forceRGBA8 = forceRgba8;
        options.shader.disableGeometryShaders = disableGeometryShaders;
        options.shader.spirvOptimizationLevel = spirvOptimizationLevel;

        try {
            VulkaniumGameOptions.writeToDisk(options);
        } catch (IOException e) {
            statusMessage = "Failed to save: " + e.getMessage();
            statusColor = 0xFF5555;
            return;
        }

        VulkaniumConfig config = Vulkanium.getConfig();
        if (config != null) {
            config.shadowResolutionOverride = shadowResolution;
            config.shadowResolution = shadowResolution;
            config.maxShadowDistance = maxShadowDistance;
            config.shadowDistance = Math.max(2, Math.round(maxShadowDistance / 16.0f));
            config.save();
        }

        statusMessage = "Saved. Reload shaderpack to apply changes.";
        statusColor = 0x55FF55;
    }

    private void saveAndReload() {
        save();
        if (manager != null && manager.isPackLoaded()) {
            String activePack = manager.getActivePackName();
            if (activePack != null && !activePack.isEmpty()) {
                this.minecraft.setScreen(new ShaderpackLoadingScreen(this, activePack));
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                "These settings affect how shaders are compiled and rendered",
                this.width / 2, 28, 0x888888);

        String activePack = manager != null ? manager.getActivePackName() : "";
        if (!activePack.isEmpty()) {
            graphics.drawCenteredString(this.font, "Active: " + activePack,
                    this.width / 2, 38, 0xAAAAAA);
        }

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, statusMessage,
                    this.width / 2, this.height - 44, statusColor);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ── Helpers ──

    private static int cycleInt(int[] values, int current, int delta) {
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { idx = i; break; }
        }
        return values[Math.floorMod(idx + delta, values.length)];
    }

    private static float cycleFloat(float[] values, float current, int delta) {
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.001f) { idx = i; break; }
        }
        return values[Math.floorMod(idx + delta, values.length)];
    }

    private static String optLabel(int level) {
        return switch (level) {
            case 0 -> "None";
            case 1 -> "Size";
            default -> "Performance";
        };
    }
}
