package net.vulkanium.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages shaderpack discovery, loading, and lifecycle.
 *
 * <p>Scans the {@code shaderpacks/} directory for available packs (directories
 * or ZIP files), and provides load/unload/switch functionality.</p>
 *
 * <h2>Usage from Vulkanium main loop:</h2>
 * <pre>
 * // On init:
 * shaderpackManager.scanForPacks(gameDir.resolve("shaderpacks"));
 *
 * // When user selects a shaderpack:
 * shaderpackManager.loadPack("SEUS PTGI E12");
 *
 * // In frame loop (when renderMode == SHADERPACK):
 * ShaderpackPipeline pipeline = shaderpackManager.getActivePipeline();
 * pipeline.onFrameBegin(cmd, frameIndex);
 * // ... MC draws routed through pipeline phases ...
 * pipeline.onFrameEnd(cmd, frameIndex);
 * </pre>
 */
public class ShaderpackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Shaderpack");

    /** Directory where shaderpacks are stored */
    private Path shaderpackDir;

    /** List of discovered shaderpack names */
    private final List<String> availablePacks = new ArrayList<>();

    /** Currently loaded shaderpack pipeline (null if none) */
    private ShaderpackPipeline activePipeline;

    /** Name of the currently loaded pack */
    private String activePackName = "";

    /**
     * Scans the shaderpacks directory for available packs.
     *
     * @param dir Path to the shaderpacks directory
     */
    public void scanForPacks(Path dir) {
        this.shaderpackDir = dir;
        availablePacks.clear();

        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
                LOGGER.info("Created shaderpacks directory: {}", dir);
            } catch (IOException e) {
                LOGGER.warn("Failed to create shaderpacks directory: {}", e.getMessage());
            }
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    // Check for "shaders/" subdirectory
                    if (Files.isDirectory(entry.resolve("shaders"))) {
                        availablePacks.add(name);
                        LOGGER.info("Found shaderpack (dir): {}", name);
                    }
                } else if (name.endsWith(".zip")) {
                    availablePacks.add(name.substring(0, name.length() - 4));
                    LOGGER.info("Found shaderpack (zip): {}", name);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan shaderpacks directory: {}", e.getMessage());
        }

        LOGGER.info("Found {} shaderpacks in {}", availablePacks.size(), dir);
    }

    /**
     * Returns the list of discovered shaderpack names.
     */
    public List<String> getAvailablePacks() {
        return Collections.unmodifiableList(availablePacks);
    }

    /**
     * Loads a shaderpack by name.
     *
     * @param packName Name of the shaderpack to load
     * @return true if loaded successfully
     */
    public boolean loadPack(String packName) {
        // Unload current pack first
        if (activePipeline != null) {
            activePipeline.unload();
            activePipeline = null;
            activePackName = "";
        }

        if (packName == null || packName.isEmpty()) {
            LOGGER.info("Shaderpack unloaded (no pack selected)");
            return true;
        }

        if (shaderpackDir == null) {
            LOGGER.warn("Cannot load shaderpack: no shaderpacks directory configured");
            return false;
        }

        // Resolve source (directory or zip)
        ShaderpackSource source = null;
        Path dirPath = shaderpackDir.resolve(packName);
        Path zipPath = shaderpackDir.resolve(packName + ".zip");

        if (Files.isDirectory(dirPath)) {
            source = ShaderpackSource.fromDirectory(dirPath);
            LOGGER.info("[SHADERPACK] Source type: Directory → {}", dirPath);
        } else if (Files.exists(zipPath)) {
            source = ShaderpackSource.fromZip(zipPath);
            LOGGER.info("[SHADERPACK] Source type: ZIP → {}", zipPath);
        }

        if (source == null) {
            LOGGER.warn("[SHADERPACK] Pack not found: '{}' (checked dir: {}, zip: {})", packName, dirPath, zipPath);
            return false;
        }

        // Create and load pipeline
        LOGGER.info("╔══════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  Loading Shaderpack: {}", packName);
        LOGGER.info("║  Source: {}", source.getRoot());
        LOGGER.info("╚══════════════════════════════════════════════════════════════╝");

        long startTime = System.nanoTime();
        VulkanShaderpackPipeline pipeline = new VulkanShaderpackPipeline();
        boolean success = false;

        try {
            success = pipeline.load(source);
        } catch (Exception e) {
            LOGGER.error("[SHADERPACK] ✗ CRITICAL: Unhandled exception during load of '{}'", packName, e);
            LOGGER.error("[SHADERPACK]   Exception type: {}", e.getClass().getName());
            LOGGER.error("[SHADERPACK]   Message: {}", e.getMessage());
            if (e.getCause() != null) {
                LOGGER.error("[SHADERPACK]   Caused by: {} — {}", e.getCause().getClass().getName(), e.getCause().getMessage());
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        if (success) {
            activePipeline = pipeline;
            activePackName = packName;
            LOGGER.info("[SHADERPACK] ✓ Shaderpack '{}' loaded successfully in {}ms", packName, elapsed);
            return true;
        } else {
            LOGGER.warn("┌──────────────────────────────────────────────────────────────┐");
            LOGGER.warn("│  SHADERPACK LOAD FAILED: {}", packName);
            LOGGER.warn("│  Time: {}ms", elapsed);
            LOGGER.warn("│  Check log above for shader compilation errors.");
            LOGGER.warn("│  Common issues:");
            LOGGER.warn("│    • Unsupported GLSL features (geometry shaders, SSBOs)");
            LOGGER.warn("│    • Missing #include files");
            LOGGER.warn("│    • Incompatible #version directives");
            LOGGER.warn("│    • Pack requires features not yet implemented");
            LOGGER.warn("└──────────────────────────────────────────────────────────────┘");
            pipeline.unload();
            return false;
        }
    }

    /**
     * Unloads the currently active shaderpack.
     */
    public void unloadPack() {
        if (activePipeline != null) {
            activePipeline.unload();
            activePipeline = null;
        }
        activePackName = "";
    }

    /**
     * Loads a shaderpack with progress callbacks for loading screen display.
     *
     * <p>This method should be called from a background thread.
     * The progress listener receives real-time updates about each compilation stage.</p>
     *
     * @param packName         Name of the shaderpack to load
     * @param progressListener Callback for progress updates
     * @return true if loaded successfully
     */
    public boolean loadPackWithProgress(String packName,
                                         Consumer<VulkanShaderpackPipeline.LoadProgress> progressListener) {
        // Unload current pack first
        if (activePipeline != null) {
            activePipeline.unload();
            activePipeline = null;
            activePackName = "";
        }

        if (packName == null || packName.isEmpty()) {
            LOGGER.info("Shaderpack unloaded (no pack selected)");
            return true;
        }

        if (shaderpackDir == null) {
            LOGGER.warn("Cannot load shaderpack: no shaderpacks directory configured");
            return false;
        }

        // Resolve source (directory or zip)
        ShaderpackSource source = null;
        Path dirPath = shaderpackDir.resolve(packName);
        Path zipPath = shaderpackDir.resolve(packName + ".zip");

        if (Files.isDirectory(dirPath)) {
            source = ShaderpackSource.fromDirectory(dirPath);
            LOGGER.info("[SHADERPACK] Source type: Directory -> {}", dirPath);
        } else if (Files.exists(zipPath)) {
            source = ShaderpackSource.fromZip(zipPath);
            LOGGER.info("[SHADERPACK] Source type: ZIP -> {}", zipPath);
        }

        if (source == null) {
            LOGGER.warn("[SHADERPACK] Pack not found: '{}' (checked dir: {}, zip: {})", packName, dirPath, zipPath);
            return false;
        }

        LOGGER.info("Loading shaderpack with progress: {}", packName);

        long startTime = System.nanoTime();
        VulkanShaderpackPipeline pipeline = new VulkanShaderpackPipeline();

        // Wire progress listener into the pipeline
        if (progressListener != null) {
            pipeline.setProgressListener(progressListener);
        }

        boolean success = false;
        try {
            success = pipeline.load(source);
        } catch (Exception e) {
            LOGGER.error("[SHADERPACK] Unhandled exception during progress load of '{}'", packName, e);
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        if (success) {
            activePipeline = pipeline;
            activePackName = packName;
            LOGGER.info("[SHADERPACK] Shaderpack '{}' loaded successfully in {}ms", packName, elapsed);
            return true;
        } else {
            LOGGER.warn("[SHADERPACK] Shaderpack '{}' load FAILED ({}ms)", packName, elapsed);
            pipeline.unload();
            return false;
        }
    }

    /**
     * Returns the currently active shaderpack pipeline.
     *
     * @return Active pipeline, or null if no shaderpack is loaded
     */
    public ShaderpackPipeline getActivePipeline() {
        return activePipeline;
    }

    /**
     * Returns the name of the currently loaded shaderpack.
     */
    public String getActivePackName() {
        return activePackName;
    }

    /**
     * Whether a shaderpack is currently loaded and active.
     */
    public boolean isPackLoaded() {
        return activePipeline != null && activePipeline.isLoaded();
    }

    /**
     * Cleanup all resources.
     */
    public void destroy() {
        unloadPack();
        availablePacks.clear();
    }
}
