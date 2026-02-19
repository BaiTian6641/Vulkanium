package net.vulkanium.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Sodium-style flat cycling control widget for enum/multi-value options.
 *
 * <p>Renders the current value text right-aligned. Clicking cycles through
 * available values. Uses the same flat translucent background as other
 * Vulkanium flat widgets.</p>
 */
public class FlatCyclingWidget extends AbstractWidget {

    private final Font font;
    private final OnCycle onCycle;
    private String currentValueText;

    public FlatCyclingWidget(int x, int y, int width, int height,
                              Component label, String currentValue, OnCycle onCycle) {
        super(x, y, width, height, label);
        this.font = Minecraft.getInstance().font;
        this.currentValueText = currentValue;
        this.onCycle = onCycle;
    }

    public void setCurrentValueText(String text) {
        this.currentValueText = text;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused();

        // Background fill
        int bgColor = !this.active
                ? FlatButtonWidget.BG_DISABLED
                : (hovered ? FlatButtonWidget.BG_HOVERED : FlatButtonWidget.BG_DEFAULT);

        graphics.fill(this.getX(), this.getY(),
                this.getX() + this.width, this.getY() + this.height,
                bgColor);

        // Current value text, right-aligned (Sodium style)
        int textColor = this.active ? FlatButtonWidget.TEXT_DEFAULT : FlatButtonWidget.TEXT_DISABLED;
        int textX = this.getX() + this.width - font.width(currentValueText) - 6;
        int textY = this.getY() + (this.height - 8) / 2;
        graphics.drawString(font, currentValueText, textX, textY, textColor);

        // Focus border
        if (this.active && this.isFocused()) {
            drawBorder(graphics, this.getX(), this.getY(),
                    this.getX() + this.width, this.getY() + this.height,
                    FlatButtonWidget.TEXT_DEFAULT);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.onCycle != null) {
            this.onCycle.onCycle(this);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    private void drawBorder(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y1 + 1, color);
        graphics.fill(x1, y2 - 1, x2, y2, color);
        graphics.fill(x1, y1, x1 + 1, y2, color);
        graphics.fill(x2 - 1, y1, x2, y2, color);
    }

    @FunctionalInterface
    public interface OnCycle {
        void onCycle(FlatCyclingWidget widget);
    }
}
