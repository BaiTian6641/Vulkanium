package net.vulkanium.gui.options;

import net.vulkanium.gui.options.binding.OptionBinding;
import net.vulkanium.gui.options.control.Control;
import net.vulkanium.gui.options.storage.OptionStorage;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Concrete implementation of {@link Option} with a builder pattern.
 *
 * <p>Mirrors Sodium's {@code OptionImpl} — each option binds a typed value
 * to a storage backend via an {@link OptionBinding}, and renders itself
 * with a {@link Control}.</p>
 *
 * @param <S> The storage data type (e.g. VulkaniumGameOptions)
 * @param <T> The option value type (Boolean, Integer, Enum, etc.)
 */
public class OptionImpl<S, T> implements Option<T> {
    private final OptionStorage<S> storage;
    private final OptionBinding<S, T> binding;
    private final Control<T> control;
    private final EnumSet<OptionFlag> flags;

    private final String name;
    private final String tooltip;
    private final OptionImpact impact;
    private final boolean enabled;

    private T value;
    private T modifiedValue;

    private OptionImpl(OptionStorage<S> storage,
                       String name,
                       String tooltip,
                       OptionBinding<S, T> binding,
                       Function<OptionImpl<S, T>, Control<T>> control,
                       EnumSet<OptionFlag> flags,
                       OptionImpact impact,
                       boolean enabled) {
        this.storage = storage;
        this.name = name;
        this.tooltip = tooltip;
        this.binding = binding;
        this.impact = impact;
        this.flags = flags;
        this.control = control.apply(this);
        this.enabled = enabled;
        this.reset();
    }

    @Override public String getName() { return this.name; }
    @Override public String getTooltip() { return this.tooltip; }
    @Override public OptionImpact getImpact() { return this.impact; }
    @Override public Control<T> getControl() { return this.control; }
    @Override public T getValue() { return this.modifiedValue; }

    @Override
    public void setValue(T value) {
        this.modifiedValue = value;
    }

    @Override
    public void reset() {
        this.value = this.binding.getValue(this.storage.getData());
        this.modifiedValue = this.value;
    }

    @Override public boolean isAvailable() { return this.enabled; }

    @Override
    public boolean hasChanged() {
        return !this.value.equals(this.modifiedValue);
    }

    @Override
    public void applyChanges() {
        this.binding.setValue(this.storage.getData(), this.modifiedValue);
        this.value = this.modifiedValue;
    }

    @Override
    public Collection<OptionFlag> getFlags() {
        return Collections.unmodifiableSet(this.flags);
    }

    // ─── Builder ───────────────────────────────────────────────────────

    public static <S, T> Builder<S, T> createBuilder(Class<T> type, OptionStorage<S> storage) {
        return new Builder<>(storage);
    }

    public static class Builder<S, T> {
        private final OptionStorage<S> storage;
        private String name;
        private String tooltip;
        private OptionBinding<S, T> binding;
        private Function<OptionImpl<S, T>, Control<T>> control;
        private OptionImpact impact;
        private final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        private boolean enabled = true;

        private Builder(OptionStorage<S> storage) {
            this.storage = storage;
        }

        public Builder<S, T> setName(String name) {
            this.name = name;
            return this;
        }

        public Builder<S, T> setTooltip(String tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder<S, T> setBinding(BiConsumer<S, T> setter, Function<S, T> getter) {
            this.binding = new OptionBinding<>() {
                @Override public void setValue(S storage, T value) { setter.accept(storage, value); }
                @Override public T getValue(S storage) { return getter.apply(storage); }
            };
            return this;
        }

        public Builder<S, T> setBinding(OptionBinding<S, T> binding) {
            this.binding = binding;
            return this;
        }

        public Builder<S, T> setControl(Function<OptionImpl<S, T>, Control<T>> control) {
            this.control = control;
            return this;
        }

        public Builder<S, T> setImpact(OptionImpact impact) {
            this.impact = impact;
            return this;
        }

        public Builder<S, T> setFlags(OptionFlag... flags) {
            Collections.addAll(this.flags, flags);
            return this;
        }

        public Builder<S, T> setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public OptionImpl<S, T> build() {
            if (this.name == null) throw new IllegalStateException("Option name is required");
            if (this.binding == null) throw new IllegalStateException("Option binding is required");
            if (this.control == null) throw new IllegalStateException("Option control is required");

            return new OptionImpl<>(
                    this.storage, this.name, this.tooltip,
                    this.binding, this.control, this.flags,
                    this.impact, this.enabled
            );
        }
    }
}
