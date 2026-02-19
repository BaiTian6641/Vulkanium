package net.vulkanium.shaderpack;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderpackOptionParser {

    private static final Pattern DEFINE_WITH_CHOICES = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*([^\\s/]*)?.*?//\\s*\\[([^\\]]+)]",
            Pattern.MULTILINE);

    private static final Pattern CONST_WITH_CHOICES = Pattern.compile(
            "^\\s*const\\s+(?:bool|int|float)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]+);.*?//\\s*\\[([^\\]]+)]",
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

        Matcher constMatcher = CONST_WITH_CHOICES.matcher(glsl);
        while (constMatcher.find()) {
            String key = constMatcher.group(1);
            if (ignore(key)) continue;

            String defaultValue = constMatcher.group(2).trim();
            List<String> values = parseValues(constMatcher.group(3));
            if (values.isEmpty()) continue;
            upsert(discovered, overrides, key, defaultValue, values);
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
}
