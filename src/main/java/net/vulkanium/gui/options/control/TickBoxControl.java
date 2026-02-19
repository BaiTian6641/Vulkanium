package net.vulkanium.gui.options.control;

import net.vulkanium.gui.options.Option;

/**
 * Boolean toggle (tick box / switch) control.
 */
public class TickBoxControl implements Control<Boolean> {
    private final Option<Boolean> option;

    public TickBoxControl(Option<Boolean> option) {
        this.option = option;
    }

    @Override
    public Option<Boolean> getOption() {
        return this.option;
    }

    @Override
    public String getLabel() {
        return this.option.getValue() ? "ON" : "OFF";
    }

    /** Toggle the value */
    public void toggle() {
        this.option.setValue(!this.option.getValue());
    }
}
