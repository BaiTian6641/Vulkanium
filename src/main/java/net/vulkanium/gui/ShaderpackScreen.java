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
import net.vulkanium.shaderpack.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Iris-style unified shaderpack management screen.
 *
 * <p>Two-panel design toggled by a button:</p>
 * <ul>
 *   <li><b>Pack List mode</b> — shows available shaderpacks, select/apply/remove</li>
 *   <li><b>Pack Settings mode</b> — shows per-pack configurable options parsed from
 *       {@code shaders.properties} screen layout directives and shader source annotations</li>
 * </ul>
 *
 * <p>Modeled after Iris {@code ShaderPackScreen} with stack-based sub-screen navigation
 * for hierarchical option menus defined by the shaderpack author.</p>
 */
public class ShaderpackScreen extends Screen {

    // ═══════════════════════════════════════════════════════════════
    //  Layout constants
    // ═══════════════════════════════════════════════════════════════

    private static final int LIST_WIDTH = 260;
    private static final int ENTRY_HEIGHT = 22;
    private static final int LIST_TOP = 48;
    private static final int LIST_BOTTOM_MARGIN = 64;
    private static final int OPTION_SIDE_MARGIN = 20;
    private static final int OPTION_TOP = 48;
    private static final int OPTION_BOTTOM_MARGIN = 64;
    private static final int OPTION_ENTRY_HEIGHT = 22;
    private static final int TOOLTIP_DELAY_TICKS = 20;

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════

    private final Screen parentScreen;
    private final ShaderpackManager manager;

    /** True = showing pack settings; false = showing pack selection list */
    private boolean settingsMode = false;

    // ── Pack list state ──
    private List<String> packNames = new ArrayList<>();
    private int selectedIndex = -1;
    private int listScrollOffset = 0;

    // ── Pack settings state ──
    private final List<OptionEntry> currentScreenOptions = new ArrayList<>();
    private final Deque<String> navigationStack = new ArrayDeque<>();
    private String currentSubScreen = null; // null = main screen
    private int optionScrollOffset = 0;
    private int optionColumns = 2;

    /** All discovered options from shader source annotations */
    private Map<String, ShaderpackOption> allOptions = new LinkedHashMap<>();

    /** User's pending option overrides (not yet applied) */
    private Map<String, String> pendingOverrides = new LinkedHashMap<>();

    /** Screen layout from shaders.properties */
    private ShaderpackProperties packProperties;

    // ── Tooltip state ──
    private String hoveredOptionName = null;
    private int hoverTicks = 0;

    // ── Slider drag state ──
    /** Index of the option being slider-dragged, or -1 */
    private int draggingSliderIndex = -1;
    /** Cached slider bounds during drag (x, y, w, h) */
    private int[] draggingSliderBounds = null;

    // ── Buttons ──
    private Button switchModeButton;
    private Button applyButton;
    private Button cancelButton;
    private Button doneButton;
    private Button openFolderButton;
    private Button noneButton;
    private Button clearCacheButton;

    // ── Status ──
    private String notification = "";
    private int notificationColor = 0xAAAAAA;
    private int notificationTimer = 0;

    public ShaderpackScreen(Screen parent) {
        super(Component.literal("Shaderpacks"));
        this.parentScreen = parent;
        this.manager = Vulkanium.getShaderpackManager();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        super.init();

        refreshPackList();
        refreshPackSettings();

        // Find active pack in list
        String activePack = manager != null ? manager.getActivePackName() : "";
        selectedIndex = -1;
        for (int i = 0; i < packNames.size(); i++) {
            if (packNames.get(i).equals(activePack)) {
                selectedIndex = i;
                break;
            }
        }

        buildButtons();
    }

    private void buildButtons() {
        clearWidgets();

        int bottomY = this.height - 28;
        int topButtonY = this.height - 52;
        int centerX = this.width / 2;

        // ── Top row ──
        openFolderButton = Button.builder(
                        Component.literal("Open Shader Pack Folder"),
                        btn -> openShaderpackFolder())
                .pos(centerX - 158, topButtonY).size(152, 20).build();

        String switchLabel = settingsMode ? "Shader Pack List" : "Shader Pack Settings";
        switchModeButton = Button.builder(
                        Component.literal(switchLabel),
                        btn -> toggleMode())
                .pos(centerX + 6, topButtonY).size(152, 20).build();
        switchModeButton.active = canShowSettings();

        addRenderableWidget(openFolderButton);
        addRenderableWidget(switchModeButton);

        // ── Bottom row ──
        if (settingsMode) {
            // Settings mode: Apply | Done
            applyButton = Button.builder(Component.literal("Apply"), btn -> applyChanges())
                    .pos(centerX - 104, bottomY).size(100, 20).build();
            doneButton = Button.builder(Component.literal("Done"), btn -> onDone())
                    .pos(centerX + 4, bottomY).size(100, 20).build();

            addRenderableWidget(applyButton);
            addRenderableWidget(doneButton);
        } else {
            // List mode: None | Clear Cache | Apply | Done
            int bw = 80;
            int totalW = bw * 4 + 12;
            int startX = centerX - totalW / 2;

            noneButton = Button.builder(Component.literal("None"), btn -> removeShaderpack())
                    .pos(startX, bottomY).size(bw, 20).build();
            clearCacheButton = Button.builder(Component.literal("Clear Cache"), btn -> clearCache())
                    .pos(startX + bw + 4, bottomY).size(bw, 20).build();
            applyButton = Button.builder(Component.literal("Apply"), btn -> applySelectedPack())
                    .pos(startX + (bw + 4) * 2, bottomY).size(bw, 20).build();
            doneButton = Button.builder(Component.literal("Done"), btn -> onDone())
                    .pos(startX + (bw + 4) * 3, bottomY).size(bw, 20).build();

            applyButton.active = selectedIndex >= 0;

            addRenderableWidget(noneButton);
            addRenderableWidget(clearCacheButton);
            addRenderableWidget(applyButton);
            addRenderableWidget(doneButton);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Pack list management
    // ═══════════════════════════════════════════════════════════════

    private void refreshPackList() {
        packNames.clear();
        if (manager != null) {
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            manager.scanForPacks(gameDir.resolve("shaderpacks"));
            packNames.addAll(manager.getAvailablePacks());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Pack settings management
    // ═══════════════════════════════════════════════════════════════

    private void refreshPackSettings() {
        allOptions.clear();
        pendingOverrides.clear();
        packProperties = null;
        navigationStack.clear();
        currentSubScreen = null;

        if (manager != null && manager.getActivePipeline() instanceof VulkanShaderpackPipeline pipeline) {
            // Load discovered options
            for (ShaderpackOption opt : pipeline.getDiscoveredOptions()) {
                allOptions.put(opt.name(), opt);
            }

            // Load current overrides
            try {
                VulkaniumGameOptions opts = VulkaniumGameOptions.loadFromDisk();
                if (opts.shader.shaderpackOptionOverrides != null) {
                    pendingOverrides.putAll(opts.shader.shaderpackOptionOverrides);
                }
            } catch (Exception e) {
                Vulkanium.LOGGER.warn("Failed to load option overrides: {}", e.getMessage());
            }

            // Get properties for screen layout
            packProperties = pipeline.getProperties();
        }

        rebuildOptionScreen();
    }

    /**
     * Rebuilds the option entries for the current screen based on shaders.properties layout.
     */
    private void rebuildOptionScreen() {
        currentScreenOptions.clear();
        optionScrollOffset = 0;

        if (packProperties == null && allOptions.isEmpty()) return;

        List<String> layout;
        if (currentSubScreen == null) {
            // Main screen
            layout = packProperties != null ? packProperties.getMainScreenOptions() : null;
            optionColumns = packProperties != null ? packProperties.getMainScreenColumnCount() : 2;
        } else {
            // Sub-screen
            layout = packProperties != null ? packProperties.getSubScreenOptions().get(currentSubScreen) : null;
            optionColumns = packProperties != null ? packProperties.getSubScreenColumnCount(currentSubScreen) : 2;
        }

        if (optionColumns < 1) optionColumns = 1;
        if (optionColumns > 4) optionColumns = 4;

        Set<String> usedOptions = new HashSet<>();

        if (layout != null) {
            // Use the pack-defined layout
            for (String element : layout) {
                if (element.equals("<empty>") || element.isEmpty()) {
                    currentScreenOptions.add(OptionEntry.empty());
                } else if (element.startsWith("[") && element.endsWith("]")) {
                    // Sub-screen link: [SCREEN_NAME]
                    String screenName = element.substring(1, element.length() - 1);
                    currentScreenOptions.add(OptionEntry.link(screenName));
                    usedOptions.add(element);
                } else if (element.equals("*")) {
                    // Wildcard: dump all unused options
                    for (Map.Entry<String, ShaderpackOption> entry : allOptions.entrySet()) {
                        if (!usedOptions.contains(entry.getKey())) {
                            currentScreenOptions.add(OptionEntry.option(entry.getValue()));
                            usedOptions.add(entry.getKey());
                        }
                    }
                } else if (allOptions.containsKey(element)) {
                    currentScreenOptions.add(OptionEntry.option(allOptions.get(element)));
                    usedOptions.add(element);
                } else {
                    // Unknown element — might be a sub-screen link without brackets
                    if (packProperties != null && packProperties.getSubScreenOptions().containsKey(element)) {
                        currentScreenOptions.add(OptionEntry.link(element));
                    } else {
                        // Try as an option anyway, or show as placeholder
                        currentScreenOptions.add(OptionEntry.placeholder(element));
                    }
                }
            }
        } else {
            // No screen layout defined — dump all options (like Iris's "*" wildcard)
            for (ShaderpackOption opt : allOptions.values()) {
                currentScreenOptions.add(OptionEntry.option(opt));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════════

    private void toggleMode() {
        if (!settingsMode && !canShowSettings()) return;
        settingsMode = !settingsMode;
        if (settingsMode) {
            refreshPackSettings();
        }
        buildButtons();
    }

    private boolean canShowSettings() {
        return manager != null && manager.isPackLoaded();
    }

    private void applySelectedPack() {
        if (selectedIndex < 0 || selectedIndex >= packNames.size()) return;
        String packName = packNames.get(selectedIndex);
        if (manager != null) {
            this.minecraft.setScreen(new ShaderpackLoadingScreen(this, packName));
        }
    }

    private void removeShaderpack() {
        if (manager != null) {
            manager.unloadPack();
            Vulkanium.setRenderMode(RenderMode.VANILLA);
            ShaderpackSelectionScreen.persistShaderpackSelection("", false);
            if (this.minecraft != null && this.minecraft.levelRenderer != null) {
                this.minecraft.levelRenderer.allChanged();
                this.minecraft.reloadResourcePacks();
            }
            selectedIndex = -1;
            showNotification("Shaderpack removed", 0xAAAAAA);
        }
    }

    private void clearCache() {
        int deleted = ShaderCompiler.clearCompiledShaderCacheFiles(
                Minecraft.getInstance().gameDirectory.toPath());
        showNotification(deleted > 0
                ? "Cleared " + deleted + " cached shader files"
                : "Shader cache already empty",
                deleted > 0 ? 0x55FF55 : 0xAAAAAA);
    }

    private void applyChanges() {
        // Save pending option overrides
        try {
            VulkaniumGameOptions opts = VulkaniumGameOptions.loadFromDisk();
            opts.shader.shaderpackOptionOverrides = new LinkedHashMap<>(pendingOverrides);
            VulkaniumGameOptions.writeToDisk(opts);
        } catch (IOException e) {
            showNotification("Failed to save: " + e.getMessage(), 0xFF5555);
            return;
        }

        // Reload the shaderpack with new settings
        if (manager != null && manager.isPackLoaded()) {
            String activePack = manager.getActivePackName();
            if (activePack != null && !activePack.isEmpty()) {
                this.minecraft.setScreen(new ShaderpackLoadingScreen(this, activePack));
                return;
            }
        }

        showNotification("Settings saved", 0x55FF55);
    }

    private void openShaderpackFolder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
        try {
            Files.createDirectories(dir);
            net.minecraft.Util.getPlatform().openFile(dir.toFile());
        } catch (IOException e) {
            showNotification("Failed to open folder", 0xFF5555);
        }
    }

    private void onDone() {
        this.minecraft.setScreen(parentScreen);
    }

    private void navigateToSubScreen(String screenName) {
        if (currentSubScreen != null) {
            navigationStack.push(currentSubScreen);
        } else {
            navigationStack.push("__MAIN__");
        }
        currentSubScreen = screenName;
        rebuildOptionScreen();
    }

    private void navigateBack() {
        if (!navigationStack.isEmpty()) {
            String prev = navigationStack.pop();
            currentSubScreen = prev.equals("__MAIN__") ? null : prev;
            rebuildOptionScreen();
        }
    }

    private void cycleOption(ShaderpackOption option, int delta) {
        List<String> values = option.allowedValues();
        if (values == null || values.size() <= 1) return;

        String current = pendingOverrides.getOrDefault(option.name(), option.currentValue());
        int idx = values.indexOf(current);
        if (idx < 0) idx = 0;
        int next = Math.floorMod(idx + delta, values.size());
        String newValue = values.get(next);

        pendingOverrides.put(option.name(), newValue);
    }

    private void showNotification(String message, int color) {
        notification = message;
        notificationColor = color;
        notificationTimer = 100; // 5 seconds
    }

    // ═══════════════════════════════════════════════════════════════
    //  Input handling
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESCAPE
            if (settingsMode) {
                if (!navigationStack.isEmpty()) {
                    navigateBack();
                    return true;
                }
                settingsMode = false;
                buildButtons();
                return true;
            }
            onDone();
            return true;
        }
        if (keyCode == 258) { // TAB — switch modes
            if (canShowSettings()) {
                toggleMode();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        if (settingsMode) {
            return handleSettingsClick(mouseX, mouseY, button);
        } else {
            return handleListClick(mouseX, mouseY, button);
        }
    }

    private boolean handleListClick(double mouseX, double mouseY, int button) {
        int listX = (this.width - LIST_WIDTH) / 2;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;
        if (mouseX >= listX && mouseX <= listX + LIST_WIDTH
                && mouseY >= LIST_TOP && mouseY <= listBottom) {
            int clickedIndex = (int) ((mouseY - LIST_TOP + listScrollOffset) / ENTRY_HEIGHT);
            if (clickedIndex >= 0 && clickedIndex < packNames.size()) {
                selectedIndex = clickedIndex;
                if (applyButton != null) applyButton.active = true;
                return true;
            }
        }
        return false;
    }

    private boolean handleSettingsClick(double mouseX, double mouseY, int button) {
        int areaX = OPTION_SIDE_MARGIN;
        int areaWidth = this.width - OPTION_SIDE_MARGIN * 2;
        int areaTop = OPTION_TOP;
        int areaBottom = this.height - OPTION_BOTTOM_MARGIN;

        // Back button at top-left
        if (currentSubScreen != null && mouseX >= areaX && mouseX <= areaX + 50
                && mouseY >= areaTop - 16 && mouseY <= areaTop) {
            navigateBack();
            return true;
        }

        if (mouseX < areaX || mouseX > areaX + areaWidth
                || mouseY < areaTop || mouseY > areaBottom) {
            return false;
        }

        int colWidth = areaWidth / optionColumns;
        int row = (int) ((mouseY - areaTop + optionScrollOffset) / OPTION_ENTRY_HEIGHT);
        int col = (int) ((mouseX - areaX) / colWidth);

        int index = row * optionColumns + col;
        if (index < 0 || index >= currentScreenOptions.size()) return false;

        OptionEntry entry = currentScreenOptions.get(index);
        if (entry.type == OptionEntry.Type.LINK) {
            navigateToSubScreen(entry.linkTarget);
            return true;
        } else if (entry.type == OptionEntry.Type.OPTION && entry.option != null) {
            boolean isSlider = packProperties != null && packProperties.isSlider(entry.option.name());
            if (isSlider && entry.option.allowedValues().size() > 2) {
                // Slider: map click position to value
                int entryX = areaX + col * colWidth + 2;
                int entryW = colWidth - 4;
                setSliderValueFromMouse(entry.option, mouseX, entryX, entryW);
                draggingSliderIndex = index;
                draggingSliderBounds = new int[]{entryX, 0, entryW, 0}; // y/h not needed for drag
                return true;
            } else {
                int delta = (button == 1) ? -1 : 1; // right-click = previous
                cycleOption(entry.option, delta);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (settingsMode) {
            int totalRows = (currentScreenOptions.size() + optionColumns - 1) / optionColumns;
            int maxScroll = Math.max(0, totalRows * OPTION_ENTRY_HEIGHT
                    - (this.height - OPTION_TOP - OPTION_BOTTOM_MARGIN));
            optionScrollOffset = (int) Math.max(0,
                    Math.min(optionScrollOffset - delta * OPTION_ENTRY_HEIGHT * 2, maxScroll));
        } else {
            int listBottom = this.height - LIST_BOTTOM_MARGIN;
            int maxScroll = Math.max(0, packNames.size() * ENTRY_HEIGHT - (listBottom - LIST_TOP));
            listScrollOffset = (int) Math.max(0,
                    Math.min(listScrollOffset - delta * ENTRY_HEIGHT, maxScroll));
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingSliderIndex >= 0 && draggingSliderIndex < currentScreenOptions.size()
                && draggingSliderBounds != null) {
            OptionEntry entry = currentScreenOptions.get(draggingSliderIndex);
            if (entry.type == OptionEntry.Type.OPTION && entry.option != null) {
                setSliderValueFromMouse(entry.option, mouseX,
                        draggingSliderBounds[0], draggingSliderBounds[2]);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingSliderIndex >= 0) {
            draggingSliderIndex = -1;
            draggingSliderBounds = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Maps a mouse X position within a slider track to the nearest allowed value.
     */
    private void setSliderValueFromMouse(ShaderpackOption option, double mouseX, int trackX, int trackW) {
        List<String> values = option.allowedValues();
        if (values == null || values.size() <= 1) return;

        // Slider track area is the right half of the entry
        int sliderX = trackX + trackW / 2;
        int sliderW = trackW / 2 - 8;
        if (sliderW <= 0) sliderW = 1;

        double fraction = (mouseX - sliderX) / sliderW;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        int idx = (int) Math.round(fraction * (values.size() - 1));
        idx = Math.max(0, Math.min(values.size() - 1, idx));

        pendingOverrides.put(option.name(), values.get(idx));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tick
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        super.tick();
        if (notificationTimer > 0) {
            notificationTimer--;
            if (notificationTimer == 0) notification = "";
        }
        // Hover timer for tooltips
        hoverTicks++;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Rendering
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        if (settingsMode) {
            renderSettingsMode(graphics, mouseX, mouseY);
        } else {
            renderListMode(graphics, mouseX, mouseY);
        }

        // Notification
        if (!notification.isEmpty()) {
            graphics.drawCenteredString(this.font, notification,
                    this.width / 2, this.height - OPTION_BOTTOM_MARGIN - 14, notificationColor);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        onDone();
    }

    // ═══════════════════════════════════════════════════════════════
    //  List mode rendering
    // ═══════════════════════════════════════════════════════════════

    private void renderListMode(GuiGraphics graphics, int mouseX, int mouseY) {
        // Title
        graphics.drawCenteredString(this.font, "Select a Shader Pack", this.width / 2, 8, 0xFFFFFF);

        // Active pack info
        String activePack = manager != null ? manager.getActivePackName() : "";
        String activeInfo = activePack.isEmpty() ? "No shaderpack active" : "Active: " + activePack;
        graphics.drawCenteredString(this.font, activeInfo, this.width / 2, 22, 0xAAAAAA);

        // Compatibility info
        if (manager != null && manager.getActivePipeline() instanceof VulkanShaderpackPipeline pipeline
                && !pipeline.isCompatibilityWorldRenderingSupported()) {
            graphics.drawCenteredString(this.font,
                    "\u26A0 " + truncate(pipeline.getCompatibilityIssueMessage(), 80),
                    this.width / 2, 34, 0xFFAA55);
        }

        // Pack list
        int listX = (this.width - LIST_WIDTH) / 2;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        // List background
        graphics.fill(listX - 2, LIST_TOP - 2, listX + LIST_WIDTH + 2, listBottom + 2, 0x80000000);

        // Entries
        for (int i = 0; i < packNames.size(); i++) {
            int entryY = LIST_TOP + i * ENTRY_HEIGHT - listScrollOffset;
            if (entryY + ENTRY_HEIGHT < LIST_TOP || entryY > listBottom) continue;

            String name = packNames.get(i);
            boolean isSelected = (i == selectedIndex);
            boolean isActive = name.equals(activePack);

            if (isSelected) {
                graphics.fill(listX, entryY, listX + LIST_WIDTH, entryY + ENTRY_HEIGHT - 1, 0x60FFFFFF);
            }

            int textColor = isActive ? 0x55FF55 : 0xFFFFFF;
            String prefix = isActive ? "\u2713 " : "  ";
            graphics.drawString(this.font, prefix + name, listX + 4, entryY + 6, textColor);
        }

        if (packNames.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "No shaderpacks found \u2014 place packs in .minecraft/shaderpacks/",
                    this.width / 2, LIST_TOP + 20, 0x888888);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Settings mode rendering
    // ═══════════════════════════════════════════════════════════════

    private void renderSettingsMode(GuiGraphics graphics, int mouseX, int mouseY) {
        String packName = manager != null ? manager.getActivePackName() : "";

        // Title
        if (currentSubScreen != null) {
            graphics.drawCenteredString(this.font, formatScreenName(currentSubScreen),
                    this.width / 2, 8, 0xFFFFFF);
            // Back indicator
            graphics.drawString(this.font, "\u2190 Back", OPTION_SIDE_MARGIN, OPTION_TOP - 14, 0x8888FF);
        } else {
            graphics.drawCenteredString(this.font,
                    packName.isEmpty() ? "Shader Pack Settings" : packName + " Settings",
                    this.width / 2, 8, 0xFFFFFF);
        }

        // Subtitle
        if (currentSubScreen == null) {
            String subtitle = "Click/drag sliders \u2022 Right-click to reverse \u2022 Tab to switch views";
            graphics.drawCenteredString(this.font, subtitle, this.width / 2, 22, 0x888888);
        }

        if (currentScreenOptions.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    "No configurable options found in this shaderpack",
                    this.width / 2, OPTION_TOP + 30, 0x888888);
            return;
        }

        int areaX = OPTION_SIDE_MARGIN;
        int areaWidth = this.width - OPTION_SIDE_MARGIN * 2;
        int areaTop = OPTION_TOP;
        int areaBottom = this.height - OPTION_BOTTOM_MARGIN;
        int colWidth = areaWidth / optionColumns;

        // Background
        graphics.fill(areaX - 2, areaTop - 2, areaX + areaWidth + 2, areaBottom + 2, 0x60000000);

        // Scissor for scrolling
        graphics.enableScissor(areaX, areaTop, areaX + areaWidth, areaBottom);

        String newHovered = null;

        for (int i = 0; i < currentScreenOptions.size(); i++) {
            int row = i / optionColumns;
            int col = i % optionColumns;
            int x = areaX + col * colWidth + 2;
            int y = areaTop + row * OPTION_ENTRY_HEIGHT - optionScrollOffset;
            int w = colWidth - 4;
            int h = OPTION_ENTRY_HEIGHT - 2;

            if (y + h < areaTop || y > areaBottom) continue;

            OptionEntry entry = currentScreenOptions.get(i);
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
                    && mouseY >= areaTop && mouseY < areaBottom;

            renderOptionEntry(graphics, entry, x, y, w, h, hovered);

            if (hovered && entry.type == OptionEntry.Type.OPTION && entry.option != null) {
                newHovered = entry.option.name();
            }
        }

        graphics.disableScissor();

        // Track hovered option for tooltip
        if (!Objects.equals(newHovered, hoveredOptionName)) {
            hoveredOptionName = newHovered;
            hoverTicks = 0;
        }

        // Render tooltip after delay
        if (hoveredOptionName != null && hoverTicks >= TOOLTIP_DELAY_TICKS) {
            renderOptionTooltip(graphics, mouseX, mouseY);
        }
    }

    private void renderOptionEntry(GuiGraphics graphics, OptionEntry entry,
                                    int x, int y, int w, int h, boolean hovered) {
        switch (entry.type) {
            case EMPTY -> {
                // Empty spacer — no render
            }
            case LINK -> {
                // Sub-screen link button
                int bgColor = hovered ? 0xA0334488 : 0xA0222244;
                graphics.fill(x, y, x + w, y + h, bgColor);
                String label = formatScreenName(entry.linkTarget) + " \u25B6";
                int textWidth = this.font.width(label);
                graphics.drawString(this.font, label, x + (w - textWidth) / 2, y + 6, 0xAAAAFF);
            }
            case PLACEHOLDER -> {
                // Unknown option — grayed out
                int bgColor = hovered ? 0x60444444 : 0x40333333;
                graphics.fill(x, y, x + w, y + h, bgColor);
                graphics.drawString(this.font, truncate(entry.placeholderName, w / 6), x + 4, y + 6, 0x666666);
            }
            case OPTION -> {
                ShaderpackOption opt = entry.option;
                if (opt == null) return;

                String currentValue = pendingOverrides.getOrDefault(opt.name(), opt.currentValue());
                boolean isModified = !currentValue.equals(opt.defaultValue());
                boolean isBoolean = opt.allowedValues().size() == 2
                        && opt.allowedValues().containsAll(List.of("true", "false"));
                boolean isSlider = packProperties != null && packProperties.isSlider(opt.name())
                        && !isBoolean && opt.allowedValues().size() > 2;

                int bgColor = hovered ? 0xA0333355 : 0x80222233;
                if (isModified) bgColor = hovered ? 0xA0443322 : 0x80332211;
                graphics.fill(x, y, x + w, y + h, bgColor);

                // Option name (left half)
                String name = formatOptionName(opt.name());
                int nameMaxW = w / 2 - 4;
                graphics.drawString(this.font, truncate(name, nameMaxW / 6), x + 4, y + 6, 0xDDDDDD);

                if (isSlider) {
                    // ── Slider rendering ──
                    int sliderX = x + w / 2;
                    int sliderW = w / 2 - 8;
                    int sliderY = y + h / 2;

                    List<String> values = opt.allowedValues();
                    int valueIdx = values.indexOf(currentValue);
                    if (valueIdx < 0) valueIdx = 0;
                    double fraction = values.size() > 1
                            ? (double) valueIdx / (values.size() - 1) : 0.5;

                    // Track background
                    int trackH = 3;
                    int trackY = sliderY - trackH / 2;
                    graphics.fill(sliderX, trackY, sliderX + sliderW, trackY + trackH, 0x80AAAAAA);

                    // Filled portion
                    int filledW = (int) (fraction * sliderW);
                    int fillColor = isModified ? 0xFF6688FF : 0xFF55CC55;
                    graphics.fill(sliderX, trackY, sliderX + filledW, trackY + trackH, fillColor);

                    // Thumb
                    int thumbX = sliderX + filledW - 3;
                    int thumbColor = hovered ? 0xFFFFFFFF : 0xFFCCCCCC;
                    graphics.fill(thumbX, sliderY - 5, thumbX + 6, sliderY + 5, thumbColor);

                    // Value text to the right of the slider
                    String valueStr = currentValue;
                    int valueColor = isModified ? 0x6688FF : 0xBBBBBB;
                    graphics.drawString(this.font, valueStr,
                            sliderX + sliderW + 4, y + 6, valueColor);
                } else {
                    // ── Standard click-to-cycle rendering ──
                    String valueStr;
                    int valueColor;
                    if (isBoolean) {
                        boolean boolVal = "true".equalsIgnoreCase(currentValue);
                        valueStr = boolVal ? "ON" : "OFF";
                        valueColor = isModified
                                ? (boolVal ? 0x55FF55 : 0xFF5555)
                                : 0xFFFFFF;
                    } else {
                        valueStr = currentValue;
                        valueColor = isModified ? 0x6688FF : 0xBBBBBB;
                    }

                    int valueW = this.font.width(valueStr);
                    graphics.drawString(this.font, valueStr, x + w - valueW - 4, y + 6, valueColor);

                    // Modified indicator
                    if (isModified) {
                        graphics.drawString(this.font, "*", x + w - valueW - 12, y + 4, 0xFFAA44);
                    }
                }
            }
        }
    }

    private void renderOptionTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        ShaderpackOption opt = allOptions.get(hoveredOptionName);
        if (opt == null) return;

        List<String> lines = new ArrayList<>();
        lines.add(opt.name());
        String currentValue = pendingOverrides.getOrDefault(opt.name(), opt.currentValue());
        lines.add("Current: " + currentValue);
        lines.add("Default: " + opt.defaultValue());
        if (opt.allowedValues().size() <= 8) {
            lines.add("Values: " + String.join(", ", opt.allowedValues()));
        } else {
            lines.add("Values: " + opt.allowedValues().size() + " options");
        }

        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, this.font.width(line));
        }

        int tooltipW = maxWidth + 12;
        int tooltipH = lines.size() * 12 + 8;
        int tx = Math.min(mouseX + 8, this.width - tooltipW - 4);
        int ty = Math.max(mouseY - tooltipH - 4, 4);

        // Tooltip background
        graphics.fill(tx - 2, ty - 2, tx + tooltipW + 2, ty + tooltipH + 2, 0xF0100020);
        graphics.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, 0xF0282848);

        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xFFFFFF : 0xBBBBBB;
            graphics.drawString(this.font, lines.get(i), tx + 4, ty + 4 + i * 12, color);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (maxLen < 3) return text.length() > 0 ? "..." : "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "\u2026";
    }

    private static String formatOptionName(String name) {
        // Convert SCREAMING_SNAKE to Title Case
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                capitalize = true;
            } else {
                sb.append(capitalize ? Character.toUpperCase(c) : Character.toLowerCase(c));
                capitalize = false;
            }
        }
        return sb.toString();
    }

    private static String formatScreenName(String name) {
        if (name == null) return "Settings";
        // Display the screen name nicely
        return formatOptionName(name);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Option entry data model
    // ═══════════════════════════════════════════════════════════════

    /**
     * Represents one element on the option screen: an option widget, a sub-screen link,
     * an empty spacer, or an unknown placeholder.
     */
    static class OptionEntry {
        enum Type { OPTION, LINK, EMPTY, PLACEHOLDER }

        final Type type;
        final ShaderpackOption option;
        final String linkTarget;
        final String placeholderName;

        private OptionEntry(Type type, ShaderpackOption option, String linkTarget, String placeholderName) {
            this.type = type;
            this.option = option;
            this.linkTarget = linkTarget;
            this.placeholderName = placeholderName;
        }

        static OptionEntry option(ShaderpackOption opt) { return new OptionEntry(Type.OPTION, opt, null, null); }
        static OptionEntry link(String target) { return new OptionEntry(Type.LINK, null, target, null); }
        static OptionEntry empty() { return new OptionEntry(Type.EMPTY, null, null, null); }
        static OptionEntry placeholder(String name) { return new OptionEntry(Type.PLACEHOLDER, null, null, name); }
    }
}
