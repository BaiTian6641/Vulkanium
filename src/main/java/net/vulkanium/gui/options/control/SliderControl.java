package net.vulkanium.gui.options.control;

import net.vulkanium.gui.options.Option;

/**
 * Slider control for integer-valued options within a [min, max] range.
 *
 * <p>Modeled after Sodium's SliderControl. The slider snaps to the
 * specified step interval and formats the value for display using
 * a {@link ControlValueFormatter}.</p>
 */
public class SliderControl implements Control<Integer> {
    private final Option<Integer> option;
    private final int min;
    private final int max;
    private final int step;
    private final ControlValueFormatter formatter;

    public SliderControl(Option<Integer> option, int min, int max, int step,
                         ControlValueFormatter formatter) {
        this.option = option;
        this.min = min;
        this.max = max;
        this.step = step;
        this.formatter = formatter;

        if (min > max) throw new IllegalArgumentException("min > max: " + min + " > " + max);
        if (step <= 0) throw new IllegalArgumentException("step must be positive: " + step);
    }

    @Override
    public Option<Integer> getOption() {
        return this.option;
    }

    @Override
    public String getLabel() {
        return this.formatter.format(this.option.getValue());
    }

    public int getMin() { return this.min; }
    public int getMax() { return this.max; }
    public int getStep() { return this.step; }

    /** Normalize slider position to [0, 1] */
    public double getProgress() {
        int val = clamp(this.option.getValue());
        return (double) (val - min) / (max - min);
    }

    /** Set value from normalized [0, 1] position, snapped to step */
    public void setFromProgress(double progress) {
        int raw = (int) Math.round(progress * (max - min)) + min;
        int snapped = Math.round((float) (raw - min) / step) * step + min;
        this.option.setValue(clamp(snapped));
    }

    private int clamp(int value) {
        return Math.max(min, Math.min(value, max));
    }
}
