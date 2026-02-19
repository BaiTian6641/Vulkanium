package net.vulkanium.shaderpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
