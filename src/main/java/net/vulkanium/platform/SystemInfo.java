package net.vulkanium.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * System information collector for diagnostics and auto-tuning.
 *
 * <p>Collects CPU, RAM, JVM, and display information to help
 * auto-configure rendering settings and provide diagnostic data.</p>
 */
public class SystemInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/SystemInfo");

    private static SystemInfo instance;

    // CPU
    private final int cpuCores;
    private final int cpuThreads;
    private final String cpuName;

    // Memory
    private final long totalPhysicalMemory;
    private final long maxJvmHeap;

    // Java
    private final String javaVersion;
    private final String javaVendor;

    // Display
    private int displayWidth;
    private int displayHeight;
    private int refreshRate;

    private SystemInfo() {
        this.cpuCores = Runtime.getRuntime().availableProcessors();
        this.cpuThreads = cpuCores; // Physical vs logical might differ

        // Try to get CPU name (OS-specific)
        this.cpuName = detectCPUName();

        // Memory
        this.totalPhysicalMemory = detectPhysicalMemory();
        this.maxJvmHeap = Runtime.getRuntime().maxMemory();

        // Java
        this.javaVersion = System.getProperty("java.version", "unknown");
        this.javaVendor = System.getProperty("java.vendor", "unknown");
    }

    public static SystemInfo getInstance() {
        if (instance == null) {
            instance = new SystemInfo();
        }
        return instance;
    }

    /**
     * Auto-configure rendering settings based on system capabilities.
     */
    public AutoConfig autoConfigureDefaults() {
        AutoConfig config = new AutoConfig();

        // Chunk builder threads: leave 2 cores for main + render thread
        config.chunkBuilderThreads = Math.max(1, cpuCores - 2);

        // Frames in flight: 2 is usually best, 3 for high-refresh
        config.framesInFlight = refreshRate > 90 ? 3 : 2;

        // Staging buffer size based on available VRAM/RAM
        if (totalPhysicalMemory > 16L * 1024 * 1024 * 1024) {
            config.stagingBufferMB = 256;
        } else if (totalPhysicalMemory > 8L * 1024 * 1024 * 1024) {
            config.stagingBufferMB = 128;
        } else {
            config.stagingBufferMB = 64;
        }

        // Render distance recommendation
        if (cpuCores >= 8 && totalPhysicalMemory >= 16L * 1024 * 1024 * 1024) {
            config.recommendedRenderDistance = 16;
        } else if (cpuCores >= 4) {
            config.recommendedRenderDistance = 12;
        } else {
            config.recommendedRenderDistance = 8;
        }

        return config;
    }

    private String detectCPUName() {
        // Try runtime command (Linux)
        try {
            String brand = System.getenv("PROCESSOR_IDENTIFIER");
            if (brand != null) return brand;
        } catch (Exception ignored) {}

        return "Unknown CPU (" + cpuCores + " cores)";
    }

    private long detectPhysicalMemory() {
        try {
            // Use OperatingSystemMXBean if available
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getTotalMemorySize();
            }
        } catch (Exception ignored) {}

        // Fallback: estimate from max JVM heap
        return Runtime.getRuntime().maxMemory() * 2;
    }

    public void updateDisplayInfo(int width, int height, int refreshRate) {
        this.displayWidth = width;
        this.displayHeight = height;
        this.refreshRate = refreshRate;
    }

    /**
     * Get current JVM memory usage.
     */
    public JvmMemoryInfo getJvmMemory() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        return new JvmMemoryInfo(heap.getUsed(), heap.getMax(), heap.getCommitted());
    }

    // ─── Getters ───────────────────────────────────────────────────────

    public int getCpuCores() { return cpuCores; }
    public String getCpuName() { return cpuName; }
    public long getTotalPhysicalMemory() { return totalPhysicalMemory; }
    public long getMaxJvmHeap() { return maxJvmHeap; }
    public String getJavaVersion() { return javaVersion; }
    public String getJavaVendor() { return javaVendor; }
    public int getDisplayWidth() { return displayWidth; }
    public int getDisplayHeight() { return displayHeight; }
    public int getRefreshRate() { return refreshRate; }

    /**
     * Get a summary string for the debug overlay.
     */
    public String getSummary() {
        return String.format("CPU: %s (%d cores) | RAM: %.1f GB | Java %s | Display: %dx%d@%d",
            cpuName, cpuCores,
            totalPhysicalMemory / (1024.0 * 1024 * 1024),
            javaVersion,
            displayWidth, displayHeight, refreshRate);
    }

    // ─── Inner types ───────────────────────────────────────────────────

    public record JvmMemoryInfo(long used, long max, long committed) {
        public float utilization() { return max > 0 ? (float) used / max : 0; }
    }

    public static class AutoConfig {
        public int chunkBuilderThreads;
        public int framesInFlight;
        public int stagingBufferMB;
        public int recommendedRenderDistance;
    }
}
