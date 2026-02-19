package net.vulkanium.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Sodium-style flat tick box (checkbox) widget.
 *
 * <p>Renders a 10×10 box at the right edge of the widget area:
 * <ul>
 *   <li>Unticked: 1px white border only</li>
 *   <li>Ticked: mint/teal (0xFF94E4D3) border + inner 6×6 fill (inset by 2px)</li>
 *   <li>Disabled: gray (0xFFAAAAAA)</li>
 * </ul></p>
 */
public class FlatTickBoxWidget extends AbstractWidget {

    private static final int BOX_SIZE = 10;
    private static final int BOX_INSET = 2;
    private static final int COLOR_TICKED = 0xFF94E4D3;   // Mint/teal accent
    private static final int COLOR_UNTICKED = 0xFFFFFFFF;  // White
    private static final int COLOR_DISABLED = 0xFFAAAAAA;  // Gray

    private final Font font;
    private boolean ticked;
    private final OnToggle onToggle;

    public FlatTickBoxWidget(int x, int y, int width, int height,
                              Component label, boolean initialValue, OnToggle onToggle) {
        super(x, y, width, height, label);
        this.font = Minecraft.getInstance().font;
        this.ticked = initialValue;
        this.onToggle = onToggle;
    }

    public boolean isTicked() {
        return ticked;
    }

    public void setTicked(boolean ticked) {
        this.ticked = ticked;
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

        // Tick box positioned at right edge
        int boxX = this.getX() + this.width - BOX_SIZE - 6;
        int boxY = this.getY() + (this.height - BOX_SIZE) / 2;

        int borderColor = !this.active ? COLOR_DISABLED : (ticked ? COLOR_TICKED : COLOR_UNTICKED);

        // Border (1px)
        graphics.fill(boxX, boxY, boxX + BOX_SIZE, boxY + 1, borderColor);           // top
        graphics.fill(boxX, boxY + BOX_SIZE - 1, boxX + BOX_SIZE, boxY + BOX_SIZE, borderColor); // bottom
        graphics.fill(boxX, boxY, boxX + 1, boxY + BOX_SIZE, borderColor);           // left
        graphics.fill(boxX + BOX_SIZE - 1, boxY, boxX + BOX_SIZE, boxY + BOX_SIZE, borderColor); // right

        // Inner fill when ticked
        if (ticked) {
            graphics.fill(boxX + BOX_INSET, boxY + BOX_INSET,
                    boxX + BOX_SIZE - BOX_INSET, boxY + BOX_SIZE - BOX_INSET,
                    borderColor);
        }

        // State label
        String stateText = ticked ? "ON" : "OFF";
        int textColor = this.active ? FlatButtonWidget.TEXT_DEFAULT : FlatButtonWidget.TEXT_DISABLED;
        int textX = boxX - font.width(stateText) - 6;
        int textY = this.getY() + (this.height - 8) / 2;
        graphics.drawString(font, stateText, textX, textY, textColor);

        // Focus border
        if (this.active && this.isFocused()) {
            drawBorder(graphics, this.getX(), this.getY(),
                    this.getX() + this.width, this.getY() + this.height,
                    FlatButtonWidget.TEXT_DEFAULT);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.ticked = !this.ticked;
        if (this.onToggle != null) {
            this.onToggle.onToggle(this, this.ticked);
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
    public interface OnToggle {
        void onToggle(FlatTickBoxWidget widget, boolean value);
    }
}
