package net.vulkanium.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Sodium-style flat button with translucent background, no vanilla textures.
 *
 * <p>Color palette:
 * <ul>
 *   <li>Default: {@code 0x90000000} (56% opaque black)</li>
 *   <li>Hovered: {@code 0xE0000000} (88% opaque black)</li>
 *   <li>Disabled: {@code 0x60000000} (38% opaque black)</li>
 *   <li>Text: {@code 0xFFFFFFFF} (white)</li>
 *   <li>Accent underline: {@code 0xFF94E4D3} (teal/mint)</li>
 * </ul></p>
 */
public class FlatButtonWidget extends AbstractWidget {

    // ─── Color Constants (Sodium palette) ──────────────────────────────
    public static final int BG_DEFAULT   = 0x90000000;
    public static final int BG_HOVERED   = 0xE0000000;
    public static final int BG_DISABLED  = 0x60000000;
    public static final int TEXT_DEFAULT  = 0xFFFFFFFF;
    public static final int TEXT_DISABLED = 0x90FFFFFF;
    public static final int ACCENT_COLOR = 0xFF94E4D3;

    private final Font font;
    private final OnPress onPress;
    private boolean selected;

    public FlatButtonWidget(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message);
        this.font = Minecraft.getInstance().font;
        this.onPress = onPress;
    }

    /**
     * Creates a FlatButtonWidget with auto-sized width based on text.
     */
    public static FlatButtonWidget create(int x, int y, int height, Component message, OnPress onPress) {
        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(message);
        return new FlatButtonWidget(x, y, textWidth + 12, height, message, onPress);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused();

        // Background fill
        int bgColor;
        int textColor;
        if (!this.active) {
            bgColor = BG_DISABLED;
            textColor = TEXT_DISABLED;
        } else if (hovered) {
            bgColor = BG_HOVERED;
            textColor = TEXT_DEFAULT;
        } else {
            bgColor = BG_DEFAULT;
            textColor = TEXT_DEFAULT;
        }

        graphics.fill(this.getX(), this.getY(),
                this.getX() + this.width, this.getY() + this.height,
                bgColor);

        // Center text
        int strWidth = font.width(this.getMessage());
        int textX = this.getX() + (this.width - strWidth) / 2;
        int textY = this.getY() + (this.height - 8) / 2;
        graphics.drawString(font, this.getMessage(), textX, textY, textColor);

        // Selected tab indicator: 1px teal accent bar at bottom
        if (this.active && this.selected) {
            graphics.fill(this.getX(), this.getY() + this.height - 1,
                    this.getX() + this.width, this.getY() + this.height,
                    ACCENT_COLOR);
        }

        // Focus border (1px white outline)
        if (this.active && this.isFocused()) {
            drawBorder(graphics, this.getX(), this.getY(),
                    this.getX() + this.width, this.getY() + this.height,
                    TEXT_DEFAULT);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.onPress != null) {
            this.onPress.onPress(this);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    /**
     * Draws a 1px border around a rectangle (Sodium style).
     */
    private void drawBorder(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y1 + 1, color);     // top
        graphics.fill(x1, y2 - 1, x2, y2, color);     // bottom
        graphics.fill(x1, y1, x1 + 1, y2, color);     // left
        graphics.fill(x2 - 1, y1, x2, y2, color);     // right
    }

    @FunctionalInterface
    public interface OnPress {
        void onPress(FlatButtonWidget button);
    }
}
