package net.vulkanium.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads shader files from a ZIP archive.
 */
class ZipShaderpackSource implements ShaderpackSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ZipShaderpack");
    private final Path zipPath;

    ZipShaderpackSource(Path zipPath) {
        this.zipPath = zipPath;
    }

    @Override
    public String getName() {
        String filename = zipPath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    @Override
    public Path getRoot() {
        return zipPath;
    }

    @Override
    public String readShaderFile(String relativePath) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(zipPath)) {
            // Try "shaders/<relativePath>" first, then just "<relativePath>"
            Path file = fs.getPath("shaders", relativePath);
            if (Files.exists(file)) {
                try (InputStream is = Files.newInputStream(file)) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            // Some packs nest inside a folder: <packname>/shaders/<file>
            // Try to find any root-level directory that contains shaders/
            for (Path rootEntry : Files.newDirectoryStream(fs.getPath("/"))) {
                if (Files.isDirectory(rootEntry)) {
                    Path nested = rootEntry.resolve("shaders").resolve(relativePath);
                    if (Files.exists(nested)) {
                        LOGGER.debug("Found nested shader: {} → {}", relativePath, nested);
                        try (InputStream is = Files.newInputStream(nested)) {
                            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            file = fs.getPath(relativePath);
            if (Files.exists(file)) {
                try (InputStream is = Files.newInputStream(file)) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[ZIP] Error reading '{}' from {}: {}", relativePath, zipPath.getFileName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            LOGGER.error("[ZIP] Unexpected error reading '{}' from {}: {}", relativePath, zipPath.getFileName(), e.getMessage());
            throw new IOException("Failed to read from ZIP: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public boolean hasShaderFile(String relativePath) {
        try (FileSystem fs = FileSystems.newFileSystem(zipPath)) {
            Path file = fs.getPath("shaders", relativePath);
            if (Files.exists(file)) return true;
            // Check nested structure
            for (Path rootEntry : Files.newDirectoryStream(fs.getPath("/"))) {
                if (Files.isDirectory(rootEntry)) {
                    Path nested = rootEntry.resolve("shaders").resolve(relativePath);
                    if (Files.exists(nested)) return true;
                }
            }
            file = fs.getPath(relativePath);
            return Files.exists(file);
        } catch (IOException e) {
            return false;
        }
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
        try (FileSystem fs = FileSystems.newFileSystem(zipPath)) {
            // Find the shaders directory (could be root/shaders or nested)
            Path shadersDir = fs.getPath("shaders");
            if (!Files.isDirectory(shadersDir)) {
                // Check for nested structure
                for (Path rootEntry : Files.newDirectoryStream(fs.getPath("/"))) {
                    if (Files.isDirectory(rootEntry)) {
                        Path nested = rootEntry.resolve("shaders");
                        if (Files.isDirectory(nested)) {
                            shadersDir = nested;
                            break;
                        }
                    }
                }
            }
            if (!Files.isDirectory(shadersDir)) return defaults;

            try (Stream<Path> walk = Files.walk(shadersDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".glsl") || name.endsWith(".vsh")
                            || name.endsWith(".fsh")  || name.endsWith(".csh")
                            || name.endsWith(".gsh");
                    })
                    .forEach(p -> {
                        try (InputStream is = Files.newInputStream(p)) {
                            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            Matcher m = DEFINE_OPTION.matcher(content);
                            while (m.find()) {
                                String key = m.group(1);
                                String value = m.group(2).trim();
                                defaults.putIfAbsent(key, value);
                            }
                        } catch (IOException ignored) {}
                    });
            }
        } catch (IOException ignored) {}
        return defaults;
    }
}