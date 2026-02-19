package net.vulkanium.render.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-persistent SPIR-V cache with content-addressed storage.
 *
 * <p>Caches compiled SPIR-V modules on disk indexed by a SHA-256 hash of the
 * preprocessed GLSL source. Eliminates redundant shaderc invocations across
 * game sessions and when switching between shader packs that share common code.</p>
 *
 * <h3>Cache Key</h3>
 * <pre>
 *   Hash = SHA-256(stage_byte + preprocessed_glsl_source + compiler_options_string)
 * </pre>
 *
 * <h3>Storage Layout</h3>
 * <pre>
 *   .minecraft/vulkanium/spirv_cache/
 *     ab/cdef0123456789...spv   (first 2 chars of hex hash = subdirectory)
 * </pre>
 *
 * <h3>Invalidation</h3>
 * <p>Cache entries are never explicitly invalidated — the content-addressed key
 * changes whenever the source changes. Old entries are garbage-collected by
 * tracking access timestamps and evicting entries not accessed in 30 days.</p>
 */
public class SPIRVCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/SPIRVCache");

    /** Maximum cache size (512 MB) */
    public static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;

    /** Maximum age of unused cache entries (30 days in millis) */
    public static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    private final Path cacheDir;

    /** In-memory lookup for current session */
    private final Map<String, byte[]> memoryCache = new ConcurrentHashMap<>();

    /** Stats */
    private int hits = 0;
    private int misses = 0;
    private long diskBytesRead = 0;
    private long diskBytesWritten = 0;

    public SPIRVCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create SPIR-V cache directory: {}", e.getMessage());
        }
    }

    /**
     * Computes the cache key for a shader source.
     *
     * @param stage           Shader stage (0=vertex, 1=fragment, 2=geometry, 3=compute)
     * @param glslSource      Preprocessed GLSL source
     * @param compilerOptions Compiler option string (optimization level, etc.)
     * @return Hex SHA-256 hash string
     */
    public static String computeKey(int stage, String glslSource, String compilerOptions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) stage);
            digest.update(glslSource.getBytes(StandardCharsets.UTF_8));
            digest.update(compilerOptions.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Looks up a cached SPIR-V module.
     *
     * @param key Cache key from {@link #computeKey}
     * @return SPIR-V binary, or null if not cached
     */
    public byte[] get(String key) {
        // Check memory cache first
        byte[] cached = memoryCache.get(key);
        if (cached != null) {
            hits++;
            return cached;
        }

        // Check disk cache
        Path file = getFilePath(key);
        try {
            if (Files.exists(file)) {
                cached = Files.readAllBytes(file);
                memoryCache.put(key, cached);
                diskBytesRead += cached.length;
                hits++;

                // Touch access time
                file.toFile().setLastModified(System.currentTimeMillis());

                return cached;
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to read cached SPIR-V {}: {}", key.substring(0, 8), e.getMessage());
        }

        misses++;
        return null;
    }

    /**
     * Stores a compiled SPIR-V module in the cache.
     *
     * @param key  Cache key from {@link #computeKey}
     * @param spirv SPIR-V binary data
     */
    public void put(String key, byte[] spirv) {
        memoryCache.put(key, spirv);

        // Write to disk asynchronously
        Path file = getFilePath(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, spirv);
            diskBytesWritten += spirv.length;
        } catch (IOException e) {
            LOGGER.debug("Failed to cache SPIR-V {}: {}", key.substring(0, 8), e.getMessage());
        }
    }

    /**
     * Evicts cache entries older than MAX_AGE_MS.
     * Should be called during startup or periodically.
     */
    public void evictStale() {
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        int[] evicted = {0};
        long[] bytesFreed = {0};

        try {
            Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".spv"))
                    .forEach(file -> {
                        try {
                            if (file.toFile().lastModified() < cutoff) {
                                bytesFreed[0] += Files.size(file);
                                Files.delete(file);
                                evicted[0]++;
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            LOGGER.debug("Error during cache eviction: {}", e.getMessage());
        }

        if (evicted[0] > 0) {
            LOGGER.info("Evicted {} stale SPIR-V cache entries ({} bytes freed)",
                    evicted[0], bytesFreed[0]);
        }
    }

    /**
     * Clears the entire cache (memory + disk).
     */
    public void clear() {
        memoryCache.clear();
        try {
            Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> { try { Files.delete(file); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}

        LOGGER.info("SPIR-V cache cleared");
    }

    private Path getFilePath(String key) {
        // Split into 2-char subdirectory + rest as filename
        String subDir = key.substring(0, 2);
        String fileName = key.substring(2) + ".spv";
        return cacheDir.resolve(subDir).resolve(fileName);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── Stats ──

    public int getHits() { return hits; }
    public int getMisses() { return misses; }
    public float getHitRate() { return (hits + misses) > 0 ? (float) hits / (hits + misses) : 0; }
    public long getDiskBytesRead() { return diskBytesRead; }
    public long getDiskBytesWritten() { return diskBytesWritten; }
    public int getMemoryCacheSize() { return memoryCache.size(); }
}
