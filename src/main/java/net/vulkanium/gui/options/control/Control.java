package net.vulkanium.gui.options.control;

import net.vulkanium.gui.options.Option;

/**
 * A UI control that renders and manages input for a single {@link Option}.
 * Concrete implementations: {@link SliderControl}, {@link TickBoxControl}, {@link CyclingControl}.
 *
 * @param <T> Option value type
 */
public interface Control<T> {

    /** The option this control is bound to */
    Option<T> getOption();

    /** Human-readable label for the current value */
    String getLabel();

    /** Number of display rows this control occupies */
    default int getRows() { return 1; }
}
