package net.vulkanium.gui.options;

/**
 * Performance impact indicator for options.
 * Displayed as colored text in the settings UI to help users
 * understand the cost of each setting.
 */
public enum OptionImpact implements TextProvider {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    EXTREME("Extreme"),
    VARIES("Varies");

    private final String name;

    OptionImpact(String name) {
        this.name = name;
    }

    @Override
    public String getLocalizedName() {
        return this.name;
    }
}
