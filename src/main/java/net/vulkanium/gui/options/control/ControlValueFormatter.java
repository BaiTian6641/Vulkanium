package net.vulkanium.gui.options.control;

import java.util.function.Function;

/**
 * Formats slider and other numeric option values for display.
 */
public interface ControlValueFormatter {

    String format(int value);

    // ─── Common Formatters ─────────────────────────────────────────

    static ControlValueFormatter percentage() {
        return value -> value + "%";
    }

    static ControlValueFormatter multiplier() {
        return value -> value + "x";
    }

    static ControlValueFormatter quantity(String unitSingular, String unitPlural) {
        return value -> value + " " + (value == 1 ? unitSingular : unitPlural);
    }

    static ControlValueFormatter quantityOrDisabled(String unit, String disabledLabel) {
        return value -> value == 0 ? disabledLabel : value + " " + unit;
    }

    static ControlValueFormatter resolution() {
        return value -> value + "x" + value;
    }

    static ControlValueFormatter megabytes() {
        return value -> value + " MB";
    }

    static ControlValueFormatter fpsLimit() {
        return value -> value >= 260 ? "Unlimited" : value + " FPS";
    }

    static ControlValueFormatter brightness() {
        return value -> switch (value) {
            case 0 -> "Moody";
            case 50 -> "Default";
            case 100 -> "Bright";
            default -> value + "%";
        };
    }

    static ControlValueFormatter guiScale() {
        return value -> value == 0 ? "Auto" : String.valueOf(value);
    }

    static ControlValueFormatter biomeBlend() {
        return value -> {
            int v = value * 2 + 1;
            return v + "x" + v;
        };
    }

    static ControlValueFormatter blocks() {
        return value -> value + " blocks";
    }

    static ControlValueFormatter custom(Function<Integer, String> fn) {
        return fn::apply;
    }
}
