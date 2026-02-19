package net.vulkanium.gui.options;

import net.vulkanium.gui.options.control.Control;

import java.util.Collection;

/**
 * A single configurable option displayed in the settings GUI.
 *
 * <p>Modeled after Sodium's {@code Option<T>} interface. Each option has:
 * <ul>
 *   <li>A display name and tooltip</li>
 *   <li>A typed value with get/set</li>
 *   <li>A {@link Control} that renders the UI widget</li>
 *   <li>An {@link OptionImpact} indicator</li>
 *   <li>A set of {@link OptionFlag}s indicating what reload is needed on change</li>
 * </ul></p>
 *
 * @param <T> The value type of this option (Boolean, Integer, Enum, etc.)
 */
public interface Option<T> {

    /** Display name shown in the option row */
    String getName();

    /** Tooltip shown when hovering */
    String getTooltip();

    /** Performance impact badge (null = no badge) */
    OptionImpact getImpact();

    /** The UI control widget */
    Control<T> getControl();

    /** Current (possibly modified) value */
    T getValue();

    /** Set a new value (does not save until applyChanges) */
    void setValue(T value);

    /** Revert to the value currently persisted in storage */
    void reset();

    /** Whether this option is currently adjustable */
    boolean isAvailable();

    /** Whether the user has changed this option from its stored value */
    boolean hasChanged();

    /** Commit the modified value to storage */
    void applyChanges();

    /** Flags indicating what kind of reload this option requires */
    Collection<OptionFlag> getFlags();
}
