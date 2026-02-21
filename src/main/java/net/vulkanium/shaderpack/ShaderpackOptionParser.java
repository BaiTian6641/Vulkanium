package net.vulkanium.shaderpack;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderpackOptionParser {

    // Highest priority: #define with bracket choices  e.g.  #define FOO 1.0 // [0.5 1.0 2.0]
    private static final Pattern DEFINE_WITH_CHOICES = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*([^\\s/]*)?.*?//\\s*\\[([^\\]]+)]",
            Pattern.MULTILINE);

    private static final Pattern CONST_WITH_CHOICES = Pattern.compile(
            "^\\s*const\\s+(?:bool|int|float)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]+);.*?//\\s*\\[([^\\]]+)]",
            Pattern.MULTILINE);

    // Lower priority: bare  #define NAME  (boolean toggle, currently ON)
    private static final Pattern DEFINE_BARE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?://.*)?$",
            Pattern.MULTILINE);

    // Lower priority: commented-out  // #define NAME  (boolean toggle, currently OFF)
    private static final Pattern DEFINE_COMMENTED = Pattern.compile(
            "^\\s*//\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?://.*)?$",
            Pattern.MULTILINE);

    // Lower priority: #define NAME value  (without bracket annotation)
    private static final Pattern DEFINE_PLAIN = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([^\\s/]+)\\s*(?://(?!\\s*\\[).*)?$",
            Pattern.MULTILINE);

    private static final Set<String> IGNORED_PREFIXES = Set.of("MC_", "IRIS_", "VULKANIUM_");

    private ShaderpackOptionParser() {}

    public static List<ShaderpackOption> parse(ProgramSet programSet, Map<String, String> overrides) {
        if (programSet == null) return List.of();

        Map<String, ShaderpackOption> discovered = new LinkedHashMap<>();

        for (ProgramSource source : programSet.getAllPrograms().values()) {
            collect(source.vertexSource(), discovered, overrides);
            collect(source.fragmentSource(), discovered, overrides);
            collect(source.geometrySource(), discovered, overrides);
            collect(source.computeSource(), discovered, overrides);
        }

        return List.copyOf(discovered.values());
    }

    private static void collect(String glsl, Map<String, ShaderpackOption> discovered, Map<String, String> overrides) {
        if (glsl == null || glsl.isEmpty()) return;

        // ── Pass 1: bracket-annotated #define NAME value // [v1 v2 v3] ──
        Matcher defineMatcher = DEFINE_WITH_CHOICES.matcher(glsl);
        while (defineMatcher.find()) {
            String key = defineMatcher.group(1);
            if (ignore(key)) continue;

            String defaultValue = defineMatcher.group(2);
            if (defaultValue == null || defaultValue.isBlank()) {
                defaultValue = "true";
            }

            List<String> values = parseValues(defineMatcher.group(3));
            if (values.isEmpty()) continue;
            upsert(discovered, overrides, key, defaultValue.trim(), values);
        }

        // ── Pass 2: bracket-annotated const type NAME = value; // [v1 v2 v3] ──
        Matcher constMatcher = CONST_WITH_CHOICES.matcher(glsl);
        while (constMatcher.find()) {
            String key = constMatcher.group(1);
            if (ignore(key)) continue;

            String defaultValue = constMatcher.group(2).trim();
            List<String> values = parseValues(constMatcher.group(3));
            if (values.isEmpty()) continue;
            upsert(discovered, overrides, key, defaultValue, values);
        }

        // ── Pass 3: bare boolean toggles: #define NAME (currently ON) ──
        Matcher bareMatcher = DEFINE_BARE.matcher(glsl);
        while (bareMatcher.find()) {
            String key = bareMatcher.group(1);
            if (ignore(key)) continue;
            if (discovered.containsKey(key)) continue; // bracket-annotated takes priority
            upsert(discovered, overrides, key, "ON", List.of("ON", "OFF"));
        }

        // ── Pass 4: commented-out boolean toggles: // #define NAME (currently OFF) ──
        Matcher commentedMatcher = DEFINE_COMMENTED.matcher(glsl);
        while (commentedMatcher.find()) {
            String key = commentedMatcher.group(1);
            if (ignore(key)) continue;
            if (discovered.containsKey(key)) continue; // already found as active or bracket-annotated
            upsert(discovered, overrides, key, "OFF", List.of("ON", "OFF"));
        }

        // ── Pass 5: plain #define NAME value (no bracket annotation) ──
        Matcher plainMatcher = DEFINE_PLAIN.matcher(glsl);
        while (plainMatcher.find()) {
            String key = plainMatcher.group(1);
            if (ignore(key)) continue;
            if (discovered.containsKey(key)) continue;

            String value = plainMatcher.group(2).trim();
            // For numeric values, generate a reasonable set of choices
            List<String> values = generateNumericRange(value);
            if (values == null) {
                // Non-numeric: just provide the single known value
                values = List.of(value);
            }
            upsert(discovered, overrides, key, value, values);
        }
    }

    private static void upsert(Map<String, ShaderpackOption> discovered,
                               Map<String, String> overrides,
                               String key,
                               String defaultValue,
                               List<String> values) {
        if (discovered.containsKey(key)) return;

        String current = overrides.getOrDefault(key, defaultValue);
        if (!values.contains(current) && !values.isEmpty()) {
            current = values.get(0);
        }

        discovered.put(key, new ShaderpackOption(key, defaultValue, values, current));
    }

    private static List<String> parseValues(String raw) {
        if (raw == null || raw.isBlank()) return List.of();

        List<String> values = new ArrayList<>();
        for (String token : raw.trim().split("\\s+")) {
            String cleaned = token.trim();
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private static boolean ignore(String key) {
        for (String prefix : IGNORED_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * For plain #define NAME value (no bracket annotation), attempts to generate
     * a reasonable set of numeric choices around the given default value.
     * Returns null if the value is not numeric.
     */
    private static List<String> generateNumericRange(String value) {
        try {
            // Try integer first
            int intVal = Integer.parseInt(value);
            // Generate powers-of-2 or linear steps around the value
            Set<String> set = new LinkedHashSet<>();
            if (intVal >= 0 && intVal <= 64) {
                for (int v = 0; v <= Math.max(intVal * 2, 8); v++) {
                    set.add(String.valueOf(v));
                    if (set.size() >= 16) break;
                }
            } else {
                // Larger integers: provide doubling/halving steps
                set.add(String.valueOf(intVal / 4));
                set.add(String.valueOf(intVal / 2));
                set.add(value);
                set.add(String.valueOf(intVal * 2));
                set.add(String.valueOf(intVal * 4));
            }
            if (!set.contains(value)) set.add(value);
            return new ArrayList<>(set);
        } catch (NumberFormatException e1) {
            try {
                double dVal = Double.parseDouble(value);
                // Float: generate a few steps around the value
                Set<String> set = new LinkedHashSet<>();
                set.add(formatDouble(dVal * 0.25));
                set.add(formatDouble(dVal * 0.5));
                set.add(formatDouble(dVal * 0.75));
                set.add(value); // keep original formatting
                set.add(formatDouble(dVal * 1.25));
                set.add(formatDouble(dVal * 1.5));
                set.add(formatDouble(dVal * 2.0));
                return new ArrayList<>(set);
            } catch (NumberFormatException e2) {
                return null; // not numeric
            }
        }
    }

    private static String formatDouble(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        // Up to 4 decimal places, strip trailing zeros
        String s = String.format("%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", ".0");
        return s;
    }
}
