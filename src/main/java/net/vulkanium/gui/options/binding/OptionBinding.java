package net.vulkanium.gui.options.binding;

/**
 * Bidirectional binding between an option value and its storage backend.
 *
 * @param <S> Storage data type
 * @param <T> Option value type
 */
public interface OptionBinding<S, T> {
    void setValue(S storage, T value);
    T getValue(S storage);
}
