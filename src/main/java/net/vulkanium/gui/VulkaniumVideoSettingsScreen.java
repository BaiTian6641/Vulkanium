package net.vulkanium.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanium.Vulkanium;
import net.vulkanium.VulkaniumGameOptions;
import net.vulkanium.gui.options.*;
import net.vulkanium.gui.options.control.*;
import net.vulkanium.gui.widgets.FlatButtonWidget;
import net.vulkanium.gui.widgets.FlatCyclingWidget;
import net.vulkanium.gui.widgets.FlatSliderWidget;
import net.vulkanium.gui.widgets.FlatTickBoxWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * Vulkanium's video settings screen with Sodium-style flat UI.
 *
 * <p>Uses flat translucent rectangles instead of vanilla button textures.
 * Layout matches Sodium's design language:
 * <ul>
 *   <li>Tab buttons with text-width sizing and teal accent underline</li>
 *   <li>200×18 option rows with translucent backgrounds</li>
 *   <li>Flat sliders with 1px track and 2px thumb</li>
 *   <li>Tick boxes with mint accent fill when active</li>
 *   <li>Dark translucent palette: 0x90000000 default, 0xE0000000 hover</li>
 * </ul></p>
 */
public class VulkaniumVideoSettingsScreen extends Screen {

    private final Screen parentScreen;
    private final VulkaniumOptionsScreen controller;

    // Layout constants (Sodium-style)
    private static final int TAB_START_X = 6;
    private static final int TAB_START_Y = 6;
    private static final int TAB_HEIGHT = 18;
    private static final int TAB_GAP = 6;

    private static final int OPTION_START_Y = 28;
    private static final int OPTION_ROW_WIDTH = 340;
    private static final int OPTION_ROW_HEIGHT = 20;
    private static final int LABEL_WIDTH = 180;
    private static final int CONTROL_WIDTH = 150;
    private static final int CONTROL_GAP = 10;
    private static final int GROUP_GAP = 6;

    private static final int BOTTOM_BUTTON_Y_OFFSET = 30;

    // Scroll state
    private int scrollOffset = 0;
    private int maxScroll = 0;

    // Tab buttons
    private final List<FlatButtonWidget> tabButtons = new ArrayList<>();
    // Option control widgets for the current page
    private final List<OptionControlWidget> optionWidgets = new ArrayList<>();
    // Bottom bar buttons
    private final List<FlatButtonWidget> bottomButtons = new ArrayList<>();

    public VulkaniumVideoSettingsScreen(Screen parent) {
        super(Component.literal("Vulkanium Video Settings"));
        this.parentScreen = parent;
        this.controller = new VulkaniumOptionsScreen(VulkaniumGameOptions.loadFromDisk());
    }

    public static Screen createScreen(Screen parent) {
        return new VulkaniumVideoSettingsScreen(parent);
    }

    @Override
    protected void init() {
        super.init();
        this.scrollOffset = 0;
        this.tabButtons.clear();

        // ─── Tab Buttons (Sodium-style: text-width + padding, teal underline) ───
        List<OptionPage> pages = controller.getPages();
        if (!pages.isEmpty()) {
            int tabX = TAB_START_X;
            for (int i = 0; i < pages.size(); i++) {
                final int pageIndex = i;
                OptionPage page = pages.get(i);
                Component tabLabel = Component.literal(page.getName());
                int tabWidth = this.font.width(tabLabel) + 12;

                FlatButtonWidget tabBtn = new FlatButtonWidget(
                        tabX, TAB_START_Y, tabWidth, TAB_HEIGHT, tabLabel,
                        btn -> {
                            controller.setPageByIndex(pageIndex);
                            updateTabSelection();
                            rebuildOptionWidgets();
                        });
                tabBtn.setSelected(i == controller.getCurrentPageIndex());
                this.addRenderableWidget(tabBtn);
                this.tabButtons.add(tabBtn);

                tabX += tabWidth + TAB_GAP;
            }
        }

        // ─── Bottom Buttons (flat style) ────────────────────────────────
        bottomButtons.clear();
        int bottomY = this.height - BOTTOM_BUTTON_Y_OFFSET;
        int btnWidth = 80;
        int gap = 6;
        int totalWidth = btnWidth * 4 + gap * 3;
        int startX = (this.width - totalWidth) / 2;

        FlatButtonWidget shaderpackBtn = new FlatButtonWidget(
                startX, bottomY, btnWidth, 20,
                Component.literal("Shaderpacks"),
                btn -> this.minecraft.setScreen(new ShaderpackScreen(this)));
        this.addRenderableWidget(shaderpackBtn);
        bottomButtons.add(shaderpackBtn);

        FlatButtonWidget undoBtn = new FlatButtonWidget(
                startX + btnWidth + gap, bottomY, btnWidth, 20,
                Component.literal("Undo"),
                btn -> {
                    controller.undoChanges();
                    rebuildOptionWidgets();
                });
        this.addRenderableWidget(undoBtn);
        bottomButtons.add(undoBtn);

        FlatButtonWidget applyBtn = new FlatButtonWidget(
                startX + (btnWidth + gap) * 2, bottomY, btnWidth, 20,
                Component.literal("Apply"),
                btn -> controller.applyChanges());
        this.addRenderableWidget(applyBtn);
        bottomButtons.add(applyBtn);

        FlatButtonWidget doneBtn = new FlatButtonWidget(
                startX + (btnWidth + gap) * 3, bottomY, btnWidth, 20,
                Component.literal("Done"),
                btn -> {
                    if (controller.hasPendingChanges()) {
                        controller.applyChanges();
                    }
                    this.minecraft.setScreen(this.parentScreen);
                });
        this.addRenderableWidget(doneBtn);
        bottomButtons.add(doneBtn);

        // Build option widgets for the current page
        rebuildOptionWidgets();
    }

    private void updateTabSelection() {
        int currentIdx = controller.getCurrentPageIndex();
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).setSelected(i == currentIdx);
        }
    }

    /**
     * Rebuilds the option control widgets when the page changes.
     */
    private void rebuildOptionWidgets() {
        for (OptionControlWidget w : optionWidgets) {
            this.removeWidget(w.widget);
        }
        optionWidgets.clear();
        this.scrollOffset = 0;

        OptionPage page = controller.getCurrentPage();
        if (page == null) return;

        // Option area: centered, Sodium uses x=6 but we center for wider screens
        int optionX = (this.width - OPTION_ROW_WIDTH) / 2;
        int y = OPTION_START_Y;
        int bottomLimit = this.height - BOTTOM_BUTTON_Y_OFFSET - 10;

        for (OptionGroup group : page.getGroups()) {
            for (Option<?> option : group.getOptions()) {
                Control<?> control = option.getControl();

                AbstractWidget widget = createFlatControlWidget(option, control, optionX, y);
                if (widget != null) {
                    this.addRenderableWidget(widget);
                    optionWidgets.add(new OptionControlWidget(option, widget, y));
                }
                y += OPTION_ROW_HEIGHT;
            }
            y += GROUP_GAP;
        }

        this.maxScroll = Math.max(0, y - bottomLimit);
    }

    /**
     * Creates a flat-styled widget for a given option control.
     */
    /**
     * Creates a flat-styled control widget positioned in the RIGHT column.
     * The label is drawn separately in the LEFT column during render().
     */
    @SuppressWarnings("unchecked")
    private AbstractWidget createFlatControlWidget(Option<?> option, Control<?> control, int x, int y) {
        // Controls go in the RIGHT column (after LABEL_WIDTH + gap)
        int controlX = x + LABEL_WIDTH + CONTROL_GAP;
        int controlW = CONTROL_WIDTH;

        if (control instanceof SliderControl slider) {
            return new FlatSliderWidget(controlX, y, controlW, OPTION_ROW_HEIGHT,
                    Component.literal(""),
                    slider.getProgress(),
                    value -> slider.getLabel(),
                    value -> slider.setFromProgress(value));
        } else if (control instanceof TickBoxControl tickBox) {
            Option<Boolean> boolOpt = (Option<Boolean>) option;
            return new FlatTickBoxWidget(controlX, y, controlW, OPTION_ROW_HEIGHT,
                    Component.literal(""),
                    boolOpt.getValue(),
                    (widget, val) -> {
                        tickBox.toggle();
                        widget.setTicked(boolOpt.getValue());
                    });
        } else if (control instanceof CyclingControl<?> cycling) {
            return new FlatCyclingWidget(controlX, y, controlW, OPTION_ROW_HEIGHT,
                    Component.literal(""),
                    cycling.getLabel(),
                    widget -> {
                        cycling.cycleForward();
                        widget.setCurrentValueText(cycling.getLabel());
                    });
        } else {
            // Fallback: flat button showing control label
            return new FlatButtonWidget(controlX, y, controlW, OPTION_ROW_HEIGHT,
                    Component.literal(control.getLabel()),
                    btn -> {});
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Dark translucent background (Sodium-style)
        this.renderBackground(graphics);

        // ─── Option rows: left label column + right control column ──────
        int optionX = (this.width - OPTION_ROW_WIDTH) / 2;
        int labelX = optionX + 6;
        int bottomLimit = this.height - BOTTOM_BUTTON_Y_OFFSET - 10;

        for (OptionControlWidget ow : optionWidgets) {
            int renderY = ow.baseY - scrollOffset;
            if (renderY >= OPTION_START_Y - 5 && renderY < bottomLimit) {
                // Row background (translucent dark bar across full row width)
                graphics.fill(optionX, renderY, optionX + OPTION_ROW_WIDTH, renderY + OPTION_ROW_HEIGHT, 0x60000000);

                // LEFT column: option name label
                int textY = renderY + (OPTION_ROW_HEIGHT - 8) / 2;
                graphics.drawString(this.font, ow.option.getName(), labelX, textY, 0xFFFFFFFF);

                // Impact badge (between label and control)
                OptionImpact impact = ow.option.getImpact();
                if (impact != null) {
                    int badgeColor = switch (impact) {
                        case LOW -> 0xFF55FF55;
                        case MEDIUM -> 0xFFFFFF55;
                        case HIGH -> 0xFFFF5555;
                        case EXTREME -> 0xFFFF0000;
                        case VARIES -> 0xFF55FFFF;
                    };
                    String badgeText = impact.name().substring(0, 1);
                    int badgeX = optionX + LABEL_WIDTH - 4;
                    graphics.drawString(this.font, badgeText, badgeX, textY, badgeColor);
                }

                // RIGHT column: update widget position based on scroll
                ow.widget.setX(optionX + LABEL_WIDTH + CONTROL_GAP);
                ow.widget.setY(renderY);
                ow.widget.visible = true;
            } else {
                ow.widget.visible = false;
            }
        }

        // ─── Bottom gradient bar ────────────────────────────────────────
        int bottomBarY = this.height - BOTTOM_BUTTON_Y_OFFSET - 10;
        graphics.fillGradient(0, bottomBarY, this.width, bottomBarY + 12, 0x00000000, 0xE0000000);
        graphics.fill(0, bottomBarY + 12, this.width, this.height, 0xE0000000);

        // ─── Tooltip on hover ───────────────────────────────────────────
        for (OptionControlWidget ow : optionWidgets) {
            int renderY = ow.baseY - scrollOffset;
            if (mouseY >= renderY && mouseY < renderY + OPTION_ROW_HEIGHT &&
                    mouseX >= optionX && mouseX < optionX + OPTION_ROW_WIDTH) {
                String tooltip = ow.option.getTooltip();
                if (tooltip != null && !tooltip.isEmpty()) {
                    // Sodium-style tooltip: translucent box
                    renderFlatTooltip(graphics, tooltip, mouseX, mouseY);
                }
            }
        }

        // GPU info line
        String gpuInfo = "Vulkanium " + (Vulkanium.getVersion() != null ? Vulkanium.getVersion() : "dev");
        graphics.drawString(this.font, gpuInfo, 4, this.height - 10, 0x555555);

        // Pending changes indicator
        if (controller.hasPendingChanges()) {
            graphics.drawString(this.font, "* Unsaved changes",
                    this.width - this.font.width("* Unsaved changes") - 6,
                    this.height - 10, 0xFFFF5555);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Renders a Sodium-style flat tooltip box.
     */
    private void renderFlatTooltip(GuiGraphics graphics, String text, int mouseX, int mouseY) {
        int padding = 4;
        int maxWidth = Math.min(200, this.width - 20);

        // Simple word wrapping
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && this.font.width(line + " " + word) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                if (line.length() > 0) line.append(" ");
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());

        if (lines.isEmpty()) return;

        int tooltipWidth = 0;
        for (String l : lines) {
            tooltipWidth = Math.max(tooltipWidth, this.font.width(l));
        }
        tooltipWidth += padding * 2;
        int tooltipHeight = lines.size() * 10 + padding * 2;

        int tx = mouseX + 8;
        int ty = mouseY - tooltipHeight - 4;
        if (tx + tooltipWidth > this.width) tx = this.width - tooltipWidth - 4;
        if (ty < 4) ty = mouseY + 12;

        // Background
        graphics.fill(tx, ty, tx + tooltipWidth, ty + tooltipHeight, 0xE0000000);
        // Border
        graphics.fill(tx, ty, tx + tooltipWidth, ty + 1, 0xFF333333);
        graphics.fill(tx, ty + tooltipHeight - 1, tx + tooltipWidth, ty + tooltipHeight, 0xFF333333);
        graphics.fill(tx, ty, tx + 1, ty + tooltipHeight, 0xFF333333);
        graphics.fill(tx + tooltipWidth - 1, ty, tx + tooltipWidth, ty + tooltipHeight, 0xFF333333);

        // Text
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(i),
                    tx + padding, ty + padding + i * 10, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll > 0) {
            scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - delta * 10));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }

    // ─── Helper record ─────────────────────────────────────────────────

    private record OptionControlWidget(Option<?> option, AbstractWidget widget, int baseY) {}
}
