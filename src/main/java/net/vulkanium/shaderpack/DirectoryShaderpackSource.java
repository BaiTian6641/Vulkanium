package net.vulkanium.shaderpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads shader files from an unpacked directory structure.
 */
class DirectoryShaderpackSource implements ShaderpackSource {
    private final Path root;
    private final Path shadersDir;

    DirectoryShaderpackSource(Path root) {
        this.root = root;
        // Check for "shaders/" subdirectory
        Path sub = root.resolve("shaders");
        this.shadersDir = Files.isDirectory(sub) ? sub : root;
    }

    @Override
    public String getName() {
        return root.getFileName().toString();
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public String readShaderFile(String relativePath) throws IOException {
        Path file = shadersDir.resolve(relativePath);
        if (Files.exists(file)) {
            return Files.readString(file);
        }
        return null;
    }

    @Override
    public boolean hasShaderFile(String relativePath) {
        return Files.exists(shadersDir.resolve(relativePath));
    }

    @Override
    public String readProperties() throws IOException {
        return readShaderFile("shaders.properties");
    }

    private static final Pattern DEFINE_OPTION = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([^\\s/]+).*?//\\s*\\[",
            Pattern.MULTILINE);

    @Override
    public Map<String, String> scanOptionDefaults() {
        Map<String, String> defaults = new HashMap<>();
        try (Stream<Path> walk = Files.walk(shadersDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".glsl") || name.endsWith(".vsh")
                        || name.endsWith(".fsh")  || name.endsWith(".csh")
                        || name.endsWith(".gsh");
                })
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        Matcher m = DEFINE_OPTION.matcher(content);
                        while (m.find()) {
                            String key = m.group(1);
                            String value = m.group(2).trim();
                            defaults.putIfAbsent(key, value);
                        }
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
        return defaults;
    }
}