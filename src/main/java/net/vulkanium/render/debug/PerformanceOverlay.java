package net.vulkanium.render.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time performance overlay for Vulkanium.
 *
 * <p>Displays an F3-style overlay with detailed GPU and rendering statistics.
 * Data is collected each frame from various subsystems and formatted for
 * on-screen display. The overlay can be toggled via config or keybind.</p>
 *
 * <h3>Displayed Metrics</h3>
 * <ul>
 *   <li>FPS and frame time (ms) with min/max/avg over 1-second window</li>
 *   <li>Per-phase timing: shadow, gbuffer, deferred, composite, final</li>
 *   <li>GPU memory: allocated, used, budget (VK_EXT_memory_budget)</li>
 *   <li>Draw calls, triangles, sections rendered/culled</li>
 *   <li>Buffer memory breakdown (vertex, index, uniform, staging)</li>
 *   <li>Pipeline cache hit rate and compilation counts</li>
 *   <li>Transfer queue throughput</li>
 * </ul>
 */
public class PerformanceOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/PerfOverlay");

    private static final int HISTORY_SIZE = 120; // 2 seconds at 60 fps

    // ── Frame timing ──

    private final long[] frameTimesNs = new long[HISTORY_SIZE];
    private int frameTimeIndex = 0;
    private long lastFrameStartNs = System.nanoTime();
    private final AtomicLong frameCount = new AtomicLong(0);

    // ── Per-phase GPU timing (nanoseconds from timestamp queries) ──

    public enum Phase {
        SHADOW("Shadow"),
        GBUFFER_OPAQUE("GBuf Opaque"),
        GBUFFER_TRANSLUCENT("GBuf Transl"),
        DEFERRED("Deferred"),
        COMPOSITE("Composite"),
        FINAL("Final"),
        PRESENT("Present");

        private final String label;
        Phase(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final long[] phaseTimesNs = new long[Phase.values().length];

    // ── Draw statistics ──

    private int drawCalls;
    private int triangles;
    private int sectionsRendered;
    private int sectionsCulled;
    private int entitiesRendered;
    private int blockEntitiesRendered;

    // ── Memory statistics (bytes) ──

    private long gpuMemoryAllocated;
    private long gpuMemoryUsed;
    private long gpuMemoryBudget;
    private long vertexBufferMemory;
    private long indexBufferMemory;
    private long uniformBufferMemory;
    private long stagingBufferMemory;

    // ── Pipeline/cache stats ──

    private int pipelineCacheHits;
    private int pipelineCacheMisses;
    private int spirvCacheHits;
    private int spirvCacheMisses;
    private long transferBytesPerSecond;

    // ── State ──

    private boolean enabled = false;
    private final AtomicReference<String[]> renderedLines = new AtomicReference<>(new String[0]);

    // ── Lifecycle ──

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Called at the start of each frame.
     */
    public void beginFrame() {
        if (!enabled) return;
        lastFrameStartNs = System.nanoTime();
        drawCalls = 0;
        triangles = 0;
        sectionsRendered = 0;
        sectionsCulled = 0;
        entitiesRendered = 0;
        blockEntitiesRendered = 0;
    }

    /**
     * Called at the end of each frame. Computes metrics and builds display text.
     */
    public void endFrame() {
        if (!enabled) return;

        long now = System.nanoTime();
        long frameTime = now - lastFrameStartNs;
        frameTimesNs[frameTimeIndex % HISTORY_SIZE] = frameTime;
        frameTimeIndex++;
        frameCount.incrementAndGet();

        renderedLines.set(buildOverlayText());
    }

    /**
     * Records GPU time for a render phase (from timestamp query results).
     */
    public void recordPhaseTime(Phase phase, long nanoseconds) {
        phaseTimesNs[phase.ordinal()] = nanoseconds;
    }

    // ── Stat recording ──

    public void addDrawCall(int tris) {
        drawCalls++;
        triangles += tris;
    }

    public void recordSectionCounts(int rendered, int culled) {
        sectionsRendered = rendered;
        sectionsCulled = culled;
    }

    public void recordEntityCount(int entities, int blockEntities) {
        entitiesRendered = entities;
        blockEntitiesRendered = blockEntities;
    }

    public void recordGPUMemory(long allocated, long used, long budget) {
        gpuMemoryAllocated = allocated;
        gpuMemoryUsed = used;
        gpuMemoryBudget = budget;
    }

    public void recordBufferMemory(long vertex, long index, long uniform, long staging) {
        vertexBufferMemory = vertex;
        indexBufferMemory = index;
        uniformBufferMemory = uniform;
        stagingBufferMemory = staging;
    }

    public void recordPipelineCacheStats(int hits, int misses) {
        pipelineCacheHits = hits;
        pipelineCacheMisses = misses;
    }

    public void recordSpirvCacheStats(int hits, int misses) {
        spirvCacheHits = hits;
        spirvCacheMisses = misses;
    }

    public void recordTransferThroughput(long bytesPerSecond) {
        transferBytesPerSecond = bytesPerSecond;
    }

    // ── Overlay text generation ──

    /**
     * Returns the current overlay lines for rendering.
     */
    public String[] getOverlayLines() {
        return renderedLines.get();
    }

    private String[] buildOverlayText() {
        FrameStats stats = computeFrameStats();

        return new String[]{
                // Header
                String.format("§eVulkanium§r  %d FPS  %.1f ms", stats.fps, stats.avgMs),
                String.format("  min %.1f / avg %.1f / max %.1f ms", stats.minMs, stats.avgMs, stats.maxMs),
                "",
                // Phase timing
                "§6GPU Phase Timing:§r",
                formatPhaseBar(),
                formatPhaseDetails(),
                "",
                // Draw stats
                "§6Draw Stats:§r",
                String.format("  Draw calls: %,d  Tris: %,d", drawCalls, triangles),
                String.format("  Sections: %,d rendered / %,d culled (%.0f%% culled)",
                        sectionsRendered, sectionsCulled,
                        sectionsRendered + sectionsCulled > 0
                                ? (sectionsCulled * 100.0 / (sectionsRendered + sectionsCulled)) : 0),
                String.format("  Entities: %,d  Block Entities: %,d", entitiesRendered, blockEntitiesRendered),
                "",
                // Memory
                "§6GPU Memory:§r",
                String.format("  Allocated: %s / Budget: %s (%.0f%%)",
                        formatBytes(gpuMemoryAllocated), formatBytes(gpuMemoryBudget),
                        gpuMemoryBudget > 0 ? (gpuMemoryAllocated * 100.0 / gpuMemoryBudget) : 0),
                String.format("  Vertex: %s  Index: %s  Uniform: %s  Staging: %s",
                        formatBytes(vertexBufferMemory), formatBytes(indexBufferMemory),
                        formatBytes(uniformBufferMemory), formatBytes(stagingBufferMemory)),
                "",
                // Cache
                "§6Caches:§r",
                String.format("  Pipeline: %d/%d (%.0f%% hit)",
                        pipelineCacheHits, pipelineCacheHits + pipelineCacheMisses,
                        hitRate(pipelineCacheHits, pipelineCacheMisses)),
                String.format("  SPIR-V:   %d/%d (%.0f%% hit)",
                        spirvCacheHits, spirvCacheHits + spirvCacheMisses,
                        hitRate(spirvCacheHits, spirvCacheMisses)),
                String.format("  Transfer:  %s/s", formatBytes(transferBytesPerSecond))
        };
    }

    private String formatPhaseBar() {
        long total = 0;
        for (long t : phaseTimesNs) total += t;
        if (total == 0) return "  [no GPU timing data]";

        StringBuilder bar = new StringBuilder("  [");
        String[] colors = {"§c", "§a", "§b", "§d", "§6", "§e", "§7"};
        for (int i = 0; i < Phase.values().length; i++) {
            int width = (int) Math.round((phaseTimesNs[i] * 40.0) / total);
            bar.append(colors[i % colors.length]);
            bar.append("█".repeat(Math.max(0, width)));
        }
        bar.append("§r]");
        return bar.toString();
    }

    private String formatPhaseDetails() {
        StringBuilder sb = new StringBuilder();
        for (Phase phase : Phase.values()) {
            long ns = phaseTimesNs[phase.ordinal()];
            if (ns > 0) {
                sb.append(String.format("  %-15s %6.2f ms\n", phase.getLabel(), ns / 1_000_000.0));
            }
        }
        return sb.toString().stripTrailing();
    }

    // ── Frame stats computation ──

    private record FrameStats(int fps, double minMs, double avgMs, double maxMs) {}

    private FrameStats computeFrameStats() {
        int count = Math.min(frameTimeIndex, HISTORY_SIZE);
        if (count == 0) return new FrameStats(0, 0, 0, 0);

        long min = Long.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < count; i++) {
            long t = frameTimesNs[i];
            if (t < min) min = t;
            if (t > max) max = t;
            sum += t;
        }

        double avgNs = (double) sum / count;
        int fps = avgNs > 0 ? (int) (1_000_000_000.0 / avgNs) : 0;

        return new FrameStats(fps, min / 1_000_000.0, avgNs / 1_000_000.0, max / 1_000_000.0);
    }

    // ── Utilities ──

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static double hitRate(int hits, int misses) {
        int total = hits + misses;
        return total > 0 ? (hits * 100.0 / total) : 0;
    }
}
