package net.vulkanium.gui.options.control;

import net.vulkanium.gui.options.Option;
import net.vulkanium.gui.options.TextProvider;

/**
 * Cycling control that rotates through a fixed set of enum or discrete values.
 *
 * <p>Clicking the control cycles to the next value. Supports any type that
 * implements {@link TextProvider} for display names, or raw types with
 * a custom translator function.</p>
 *
 * @param <T> The value type (usually an enum)
 */
public class CyclingControl<T> implements Control<T> {
    private final Option<T> option;
    private final T[] values;
    private final String[] labels;

    @SuppressWarnings("unchecked")
    public CyclingControl(Option<T> option, Class<T> type) {
        this(option, type.getEnumConstants());
    }

    public CyclingControl(Option<T> option, T[] values) {
        this(option, values, null);
    }

    public CyclingControl(Option<T> option, T[] values, String[] labels) {
        this.option = option;
        this.values = values;

        if (labels != null) {
            this.labels = labels;
        } else {
            this.labels = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                T val = values[i];
                if (val instanceof TextProvider tp) {
                    this.labels[i] = tp.getLocalizedName();
                } else {
                    this.labels[i] = val.toString();
                }
            }
        }
    }

    @Override
    public Option<T> getOption() {
        return this.option;
    }

    @Override
    public String getLabel() {
        T current = this.option.getValue();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                return labels[i];
            }
        }
        return current.toString();
    }

    /** Cycle to the next value (wraps around) */
    public void cycleForward() {
        int idx = getCurrentIndex();
        int next = (idx + 1) % values.length;
        this.option.setValue(values[next]);
    }

    /** Cycle to the previous value (wraps around) */
    public void cycleBackward() {
        int idx = getCurrentIndex();
        int prev = (idx - 1 + values.length) % values.length;
        this.option.setValue(values[prev]);
    }

    public T[] getValues() { return this.values; }
    public String[] getLabels() { return this.labels; }

    private int getCurrentIndex() {
        T current = this.option.getValue();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) return i;
        }
        return 0;
    }
}
