package net.vulkanium.render.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates detailed shader debug reports for troubleshooting.
 *
 * <p>When a shader pack fails to load or produces incorrect output, this class
 * generates a comprehensive report containing all relevant information for
 * debugging. Reports are saved as text files in the game directory and can
 * be shared with pack developers or Vulkanium maintainers.</p>
 *
 * <h3>Report Contents</h3>
 * <ul>
 *   <li>System info (GPU, driver, Vulkan version, Java version)</li>
 *   <li>Shader pack info (name, version, features used)</li>
 *   <li>All compilation errors and warnings</li>
 *   <li>Transformed GLSL source (pre-SPIR-V)</li>
 *   <li>SPIR-V disassembly (if available)</li>
 *   <li>Pipeline configuration details</li>
 *   <li>Render target configuration</li>
 *   <li>Uniform bridge state</li>
 * </ul>
 */
public class ShaderDebugReport {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/DebugReport");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final Map<String, String> sections = new LinkedHashMap<>();
    private String packName = "Unknown";
    private String packVersion = "Unknown";

    /**
     * Creates a new debug report.
     */
    public ShaderDebugReport(String packName) {
        this.packName = packName;
    }

    // ── Section builders ──

    /**
     * Adds system information to the report.
     */
    public void addSystemInfo(String gpuName, String driverVersion, String vulkanVersion,
                               int apiVersion, String javaVersion, String osName) {
        StringBuilder sb = new StringBuilder();
        sb.append("GPU:           ").append(gpuName).append('\n');
        sb.append("Driver:        ").append(driverVersion).append('\n');
        sb.append("Vulkan API:    ").append(vulkanVersion).append('\n');
        sb.append("API Version:   ").append(apiVersion).append('\n');
        sb.append("Java:          ").append(javaVersion).append('\n');
        sb.append("OS:            ").append(osName).append('\n');
        sb.append("Vulkanium:     ").append(getVulkaniumVersion()).append('\n');
        sections.put("System Information", sb.toString());
    }

    /**
     * Adds shader pack metadata.
     */
    public void addPackInfo(String name, String version, String[] featuresUsed,
                             int programCount, int compositePassCount) {
        this.packName = name;
        this.packVersion = version;

        StringBuilder sb = new StringBuilder();
        sb.append("Pack:          ").append(name).append('\n');
        sb.append("Version:       ").append(version).append('\n');
        sb.append("Programs:      ").append(programCount).append('\n');
        sb.append("Composites:    ").append(compositePassCount).append('\n');
        sb.append("Features:\n");
        for (String feature : featuresUsed) {
            sb.append("  - ").append(feature).append('\n');
        }
        sections.put("Shader Pack Info", sb.toString());
    }

    /**
     * Adds compilation errors/warnings from {@link CompilationProgress}.
     */
    public void addCompilationResults(CompilationProgress progress) {
        StringBuilder sb = new StringBuilder();

        if (progress.getErrors().isEmpty() && progress.getWarnings().isEmpty()) {
            sb.append("No errors or warnings.\n");
        }

        if (!progress.getErrors().isEmpty()) {
            sb.append("=== ERRORS (").append(progress.getErrors().size()).append(") ===\n\n");
            for (CompilationProgress.CompilationError error : progress.getErrors()) {
                sb.append(error.toDisplayString()).append('\n');
                if (error.sourceSnippet() != null && !error.sourceSnippet().isEmpty()) {
                    sb.append("  Source: ").append(error.sourceSnippet()).append('\n');
                }
                sb.append('\n');
            }
        }

        if (!progress.getWarnings().isEmpty()) {
            sb.append("=== WARNINGS (").append(progress.getWarnings().size()).append(") ===\n\n");
            for (CompilationProgress.CompilationWarning warning : progress.getWarnings()) {
                sb.append(String.format("[%s] %s (line %d): %s\n",
                        warning.stage().getDisplayName(), warning.shaderName(),
                        warning.line(), warning.message()));
            }
        }

        // Timing
        sb.append("\n=== TIMING ===\n");
        long[] durations = progress.getStageDurationsNs();
        for (CompilationProgress.Stage stage : CompilationProgress.Stage.values()) {
            sb.append(String.format("  %-30s %7.1f ms\n",
                    stage.getDisplayName(), durations[stage.ordinal()] / 1_000_000.0));
        }

        sections.put("Compilation Results", sb.toString());
    }

    /**
     * Adds the transformed GLSL source for a specific shader.
     */
    public void addTransformedSource(String shaderName, String vertexGlsl, String fragmentGlsl) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Vertex Shader ---\n");
        sb.append(vertexGlsl).append("\n\n");
        sb.append("--- Fragment Shader ---\n");
        sb.append(fragmentGlsl).append("\n");
        sections.put("Transformed Source: " + shaderName, sb.toString());
    }

    /**
     * Adds render target configuration.
     */
    public void addRenderTargetConfig(String[] targetDescs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targetDescs.length; i++) {
            sb.append(String.format("  colortex%-2d: %s\n", i, targetDescs[i]));
        }
        sections.put("Render Target Configuration", sb.toString());
    }

    /**
     * Adds a custom section.
     */
    public void addSection(String title, String content) {
        sections.put(title, content);
    }

    // ── Output ──

    /**
     * Generates the complete report as a string.
     */
    public String generate() {
        StringBuilder report = new StringBuilder();
        report.append("╔══════════════════════════════════════════════════════════╗\n");
        report.append("║           VULKANIUM SHADER DEBUG REPORT                 ║\n");
        report.append("╚══════════════════════════════════════════════════════════╝\n\n");
        report.append("Generated: ").append(LocalDateTime.now().format(DATE_FMT)).append("\n");
        report.append("Pack: ").append(packName).append(" v").append(packVersion).append("\n\n");

        for (Map.Entry<String, String> entry : sections.entrySet()) {
            report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            report.append(" ").append(entry.getKey()).append("\n");
            report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            report.append(entry.getValue()).append("\n\n");
        }

        return report.toString();
    }

    /**
     * Saves the report to a file.
     *
     * @param outputDir Directory to save the report (e.g., .minecraft/vulkanium/debug/)
     * @return Path to the saved report file
     */
    public Path save(Path outputDir) {
        String filename = String.format("shader_debug_%s_%s.txt",
                packName.replaceAll("[^a-zA-Z0-9]", "_"),
                LocalDateTime.now().format(DATE_FMT));

        Path outputFile = outputDir.resolve(filename);

        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputFile, generate(), StandardCharsets.UTF_8);
            LOGGER.info("Shader debug report saved to: {}", outputFile);
        } catch (IOException e) {
            LOGGER.error("Failed to save shader debug report: {}", e.getMessage());
        }

        return outputFile;
    }

    private String getVulkaniumVersion() {
        // TODO: Read from fabric.mod.json or build properties
        return "0.1.0-alpha";
    }
}
