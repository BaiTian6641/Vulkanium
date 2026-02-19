package net.vulkanium.gui.options.storage;

/**
 * Abstract storage backend for option persistence.
 * Each storage type manages save/load for a specific data object.
 *
 * @param <T> The data object type
 */
public interface OptionStorage<T> {

    /** Get the backing data object */
    T getData();

    /** Save all options to disk */
    void save();
}
