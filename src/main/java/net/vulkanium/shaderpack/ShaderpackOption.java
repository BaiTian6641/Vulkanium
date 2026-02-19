package net.vulkanium.shaderpack;

import java.util.List;

public record ShaderpackOption(
        String name,
        String defaultValue,
        List<String> allowedValues,
        String currentValue
) {
    public ShaderpackOption withCurrentValue(String value) {
        return new ShaderpackOption(name, defaultValue, allowedValues, value);
    }
}
