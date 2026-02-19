package net.vulkanium.gui.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A page in the settings GUI containing multiple {@link OptionGroup}s.
 * Each page appears as a tab in the Vulkanium options screen.
 */
public class OptionPage {
    private final String name;
    private final List<OptionGroup> groups;
    private final List<Option<?>> options;

    public OptionPage(String name, List<OptionGroup> groups) {
        this.name = name;
        this.groups = Collections.unmodifiableList(groups);

        List<Option<?>> flatOptions = new ArrayList<>();
        for (OptionGroup group : groups) {
            flatOptions.addAll(group.getOptions());
        }
        this.options = Collections.unmodifiableList(flatOptions);
    }

    public String getName() { return this.name; }
    public List<OptionGroup> getGroups() { return this.groups; }
    public List<Option<?>> getOptions() { return this.options; }
}
