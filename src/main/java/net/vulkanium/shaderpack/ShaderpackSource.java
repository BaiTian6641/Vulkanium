package net.vulkanium.shaderpack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Abstraction over shaderpack source locations (folder or ZIP file).
 *
 * <p>Shaderpacks can be distributed as either a directory structure or a
 * ZIP archive. This interface provides unified access to shader files
 * within either format.</p>
 *
 * <h2>Expected structure (inside "shaders/" folder):</h2>
 * <pre>
 * shaders/
 *   shaders.properties          — Configuration file
 *   gbuffers_terrain.vsh        — Vertex shaders
 *   gbuffers_terrain.fsh        — Fragment shaders
 *   composite.vsh / .fsh        — Composite pass shaders
 *   final.vsh / .fsh            — Final pass shaders
 *   block.properties            — Block ID mappings
 *   item.properties             — Item ID mappings
 * </pre>
 */
public interface ShaderpackSource {

    /**
     * Returns the display name of this shaderpack.
     */
    String getName();

    /**
     * Returns the root path of this shaderpack source.
     */
    Path getRoot();

    /**
     * Reads a shader file from the shaderpack.
     *
     * @param relativePath Path relative to "shaders/" (e.g., "gbuffers_terrain.vsh")
     * @return File contents as a string, or null if the file doesn't exist
     * @throws IOException if reading fails
     */
    String readShaderFile(String relativePath) throws IOException;

    /**
     * Checks whether a shader file exists in this shaderpack.
     *
     * @param relativePath Path relative to "shaders/"
     * @return true if the file exists
     */
    boolean hasShaderFile(String relativePath);

    /**
     * Reads the {@code shaders.properties} configuration file.
     *
     * @return Properties file contents, or null if not present
     * @throws IOException if reading fails
     */
    String readProperties() throws IOException;

    /**
     * Scans shader source files for option defaults.
     *
     * <p>Looks for {@code #define NAME VALUE // [choice1 choice2 ...]} patterns in
     * all {@code .glsl}, {@code .vsh}, {@code .fsh}, {@code .csh}, {@code .gsh} files
     * and returns a map of name → default value.  This is used to provide initial
     * define values to the shaders.properties preprocessor so that conditionals
     * like {@code #if SKYBOX_RESOLUTION == 64} can be evaluated correctly.</p>
     *
     * @return map of option name → default value string
     */
    default Map<String, String> scanOptionDefaults() {
        return Map.of(); // default implementation returns empty
    }

    /**
     * Creates a source from a directory path.
     */
    static ShaderpackSource fromDirectory(Path dir) {
        return new DirectoryShaderpackSource(dir);
    }

    /**
     * Creates a source from a ZIP file path.
     */
    static ShaderpackSource fromZip(Path zipFile) {
        return new ZipShaderpackSource(zipFile);
    }
}