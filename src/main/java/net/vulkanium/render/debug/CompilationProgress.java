package net.vulkanium.render.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Tracks shader compilation progress and reports errors to the user.
 *
 * <p>Loading a shader pack involves hundreds of shader compilations
 * (preprocess → transform → compile → link). This class tracks progress
 * and provides real-time feedback to the UI.</p>
 *
 * <h3>Progress Tracking</h3>
 * <pre>
 *   Stage 1/4: Preprocessing GLSL sources       [====>      ] 45%
 *   Stage 2/4: Transforming for Vulkan           [========>  ] 80%
 *   Stage 3/4: Compiling to SPIR-V               [==>        ] 23%
 *   Stage 4/4: Creating Vulkan pipelines         [>          ]  5%
 * </pre>
 */
public class CompilationProgress {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Compile");

    // ── Stages ──
    public enum Stage {
        PREPROCESSING("Preprocessing GLSL"),
        TRANSFORMING("Transforming for Vulkan"),
        COMPILING("Compiling to SPIR-V"),
        LINKING("Creating pipelines");

        private final String displayName;
        Stage(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    // ── State ──
    private Stage currentStage = Stage.PREPROCESSING;
    private final AtomicInteger currentItem = new AtomicInteger(0);
    private int totalItems = 0;
    private String currentShaderName = "";

    // ── Errors ──
    private final List<CompilationError> errors = new ArrayList<>();
    private final List<CompilationWarning> warnings = new ArrayList<>();

    // ── Callbacks ──
    private Consumer<CompilationProgress> progressCallback;
    private Consumer<CompilationError> errorCallback;

    // ── Timing ──
    private long stageStartTimeNs = 0;
    private final long[] stageDurationsNs = new long[Stage.values().length];
    private long totalStartTimeNs = 0;

    /**
     * Starts tracking a new compilation run.
     */
    public void begin(int totalShaders) {
        this.totalItems = totalShaders;
        this.currentItem.set(0);
        this.currentStage = Stage.PREPROCESSING;
        this.errors.clear();
        this.warnings.clear();
        this.totalStartTimeNs = System.nanoTime();
        this.stageStartTimeNs = totalStartTimeNs;

        LOGGER.info("Beginning shader compilation ({} programs)", totalShaders);
    }

    /**
     * Advances to the next compilation stage.
     */
    public void nextStage(Stage stage, int totalInStage) {
        // Record duration of previous stage
        long now = System.nanoTime();
        stageDurationsNs[currentStage.ordinal()] = now - stageStartTimeNs;

        this.currentStage = stage;
        this.totalItems = totalInStage;
        this.currentItem.set(0);
        this.stageStartTimeNs = now;

        LOGGER.debug("Stage: {} ({} items)", stage.getDisplayName(), totalInStage);
    }

    /**
     * Advances progress within the current stage.
     */
    public void advance(String shaderName) {
        this.currentShaderName = shaderName;
        this.currentItem.incrementAndGet();

        if (progressCallback != null) {
            progressCallback.accept(this);
        }
    }

    /**
     * Reports a compilation error.
     */
    public void reportError(String shaderName, String message, int line, String source) {
        CompilationError error = new CompilationError(shaderName, message, line, source, currentStage);
        errors.add(error);

        LOGGER.error("Shader error in {}: {} (line {})", shaderName, message, line);

        if (errorCallback != null) {
            errorCallback.accept(error);
        }
    }

    /**
     * Reports a compilation warning.
     */
    public void reportWarning(String shaderName, String message, int line) {
        warnings.add(new CompilationWarning(shaderName, message, line, currentStage));
        LOGGER.warn("Shader warning in {}: {} (line {})", shaderName, message, line);
    }

    /**
     * Marks compilation as complete.
     */
    public void complete() {
        long totalNs = System.nanoTime() - totalStartTimeNs;
        stageDurationsNs[currentStage.ordinal()] = System.nanoTime() - stageStartTimeNs;

        LOGGER.info("Shader compilation complete in {:.1f}ms ({} errors, {} warnings)",
                totalNs / 1_000_000.0, errors.size(), warnings.size());

        for (Stage s : Stage.values()) {
            LOGGER.debug("  {}: {:.1f}ms", s.getDisplayName(), stageDurationsNs[s.ordinal()] / 1_000_000.0);
        }
    }

    // ── Getters ──

    public Stage getCurrentStage() { return currentStage; }
    public int getCurrentItem() { return currentItem.get(); }
    public int getTotalItems() { return totalItems; }
    public String getCurrentShaderName() { return currentShaderName; }

    public float getStageProgress() {
        return totalItems > 0 ? (float) currentItem.get() / totalItems : 0;
    }

    public float getOverallProgress() {
        int stageIndex = currentStage.ordinal();
        int totalStages = Stage.values().length;
        float stageWeight = 1.0f / totalStages;
        return stageIndex * stageWeight + getStageProgress() * stageWeight;
    }

    public List<CompilationError> getErrors() { return errors; }
    public List<CompilationWarning> getWarnings() { return warnings; }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public long[] getStageDurationsNs() { return stageDurationsNs; }

    // ── Callbacks ──

    public void setProgressCallback(Consumer<CompilationProgress> callback) {
        this.progressCallback = callback;
    }

    public void setErrorCallback(Consumer<CompilationError> callback) {
        this.errorCallback = callback;
    }

    // ── Inner types ──

    public record CompilationError(
            String shaderName,
            String message,
            int line,
            String sourceSnippet,
            Stage stage
    ) {
        public String toDisplayString() {
            return String.format("[%s] %s (line %d): %s",
                    stage.getDisplayName(), shaderName, line, message);
        }
    }

    public record CompilationWarning(
            String shaderName,
            String message,
            int line,
            Stage stage
    ) {}
}
