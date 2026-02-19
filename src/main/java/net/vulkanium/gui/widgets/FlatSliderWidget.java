package net.vulkanium.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Sodium-style flat slider widget for numeric options.
 *
 * <p>Renders a 1px white track line with a 2px wide white thumb indicator.
 * Value label is drawn to the right of the slider. Uses the same flat
 * translucent background as {@link FlatButtonWidget}.</p>
 */
public class FlatSliderWidget extends AbstractWidget {

    private static final int TRACK_COLOR = 0xFFFFFFFF;
    private static final int THUMB_COLOR = 0xFFFFFFFF;
    private static final int SLIDER_PADDING = 4;

    private final Font font;
    private double value; // 0.0 to 1.0
    private final ValueFormatter formatter;
    private final ValueConsumer consumer;
    private boolean dragging;

    public FlatSliderWidget(int x, int y, int width, int height,
                             Component name, double initialValue,
                             ValueFormatter formatter, ValueConsumer consumer) {
        super(x, y, width, height, name);
        this.font = Minecraft.getInstance().font;
        this.value = Mth.clamp(initialValue, 0.0, 1.0);
        this.formatter = formatter;
        this.consumer = consumer;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused();

        // Background fill (same as FlatButtonWidget)
        int bgColor = !this.active
                ? FlatButtonWidget.BG_DISABLED
                : (hovered ? FlatButtonWidget.BG_HOVERED : FlatButtonWidget.BG_DEFAULT);

        graphics.fill(this.getX(), this.getY(),
                this.getX() + this.width, this.getY() + this.height,
                bgColor);

        // Slider area: right portion of the widget
        int sliderAreaWidth = 90;
        int sliderX = this.getX() + this.width - sliderAreaWidth - SLIDER_PADDING;
        int sliderY = this.getY() + this.height / 2;
        int sliderEndX = this.getX() + this.width - SLIDER_PADDING;

        // Track: 1px white horizontal line
        graphics.fill(sliderX, sliderY, sliderEndX, sliderY + 1, TRACK_COLOR);

        // Thumb: 2px wide × 10px tall, centered on the value position
        int thumbX = sliderX + (int) ((sliderEndX - sliderX) * this.value);
        int thumbHeight = 10;
        int thumbTop = sliderY - thumbHeight / 2;
        graphics.fill(thumbX - 1, thumbTop, thumbX + 1, thumbTop + thumbHeight, THUMB_COLOR);

        // Value label (left of slider)
        String valueText = formatter.format(this.value);
        int labelX = sliderX - font.width(valueText) - 4;
        int labelY = this.getY() + (this.height - 8) / 2;
        graphics.drawString(font, valueText, labelX, labelY, FlatButtonWidget.TEXT_DEFAULT);

        // Focus border
        if (this.active && this.isFocused()) {
            drawBorder(graphics, this.getX(), this.getY(),
                    this.getX() + this.width, this.getY() + this.height,
                    FlatButtonWidget.TEXT_DEFAULT);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.dragging = true;
        updateValueFromMouse(mouseX);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        this.dragging = false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (this.dragging) {
            updateValueFromMouse(mouseX);
        }
    }

    private void updateValueFromMouse(double mouseX) {
        int sliderAreaWidth = 90;
        int sliderX = this.getX() + this.width - sliderAreaWidth - SLIDER_PADDING;
        int sliderEndX = this.getX() + this.width - SLIDER_PADDING;

        double newValue = (mouseX - sliderX) / (sliderEndX - sliderX);
        this.value = Mth.clamp(newValue, 0.0, 1.0);

        if (this.consumer != null) {
            this.consumer.accept(this.value);
        }
    }

    public void setValue(double value) {
        this.value = Mth.clamp(value, 0.0, 1.0);
    }

    public double getValue() {
        return this.value;
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
    public interface ValueFormatter {
        String format(double value);
    }

    @FunctionalInterface
    public interface ValueConsumer {
        void accept(double value);
    }
}
