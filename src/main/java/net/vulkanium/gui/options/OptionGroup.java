package net.vulkanium.gui.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A visual group of related options rendered together in the settings GUI.
 * Multiple groups form a single {@link OptionPage}.
 */
public class OptionGroup {
    private final List<Option<?>> options;

    private OptionGroup(List<Option<?>> options) {
        this.options = Collections.unmodifiableList(options);
    }

    public List<Option<?>> getOptions() {
        return this.options;
    }

    public static Builder createBuilder() {
        return new Builder();
    }

    public static class Builder {
        private final List<Option<?>> options = new ArrayList<>();

        public Builder add(Option<?> option) {
            this.options.add(option);
            return this;
        }

        public OptionGroup build() {
            if (this.options.isEmpty()) {
                throw new IllegalStateException("OptionGroup must have at least one option");
            }
            return new OptionGroup(new ArrayList<>(this.options));
        }
    }
}
