package net.vulkanium.gui.options;

/**
 * Interface for types that provide a localized display name.
 * Used by enum values displayed in cycling controls and option labels.
 */
public interface TextProvider {
    String getLocalizedName();
}
