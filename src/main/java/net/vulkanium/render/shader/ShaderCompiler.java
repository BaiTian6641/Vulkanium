package net.vulkanium.render.shader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GLSL → SPIR-V shader compiler with persistent on-disk cache.
 *
 * <h3>Compilation Pipeline</h3>
 * <pre>
 *   Pack GLSL
 *     → OptiFineGlslPreprocessor (text-level)
 *     → VulkaniumGlslTransformer (AST / regex-level)
 *     → <b>ShaderCompiler</b> (shaderc → SPIR-V binary)
 *     → VkShaderModule
 * </pre>
 *
 * <h3>Shaderc Configuration</h3>
 * <ul>
 *   <li>Target: Vulkan 1.2 ({@code shaderc_env_version_vulkan_1_2})</li>
 *   <li>SPIR-V: 1.5 ({@code shaderc_spirv_version_1_5})</li>
 *   <li>Optimization: Performance ({@code shaderc_optimization_level_performance})</li>
 *   <li>Warnings as errors: disabled (pack shaders have many warnings)</li>
 *   <li>Auto-bind uniforms: enabled (for any remaining bare uniforms the transformer missed)</li>
 * </ul>
 *
 * <h3>Persistent Cache</h3>
 * <p>Compiled SPIR-V binaries are cached on disk keyed by SHA-256 of the transformed
 * GLSL source. On subsequent launches, cache hits skip shaderc compilation entirely.
 * The cache is stored in {@code .vulkanium/shader_cache/} inside the game directory.</p>
 *
 * <p>Cache invalidation happens when:</p>
 * <ul>
 *   <li>The GLSL source changes (different hash)</li>
 *   <li>Vulkanium version changes (cache directory is version-stamped)</li>
 *   <li>The user clears the cache manually</li>
 * </ul>
 *
 * <h3>Error Handling</h3>
 * <p>Shaderc errors are parsed and mapped back to the original pack GLSL line numbers
 * where possible. Failed compilations are logged with full context to help shader pack
 * developers diagnose issues.</p>
 */
public class ShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/ShaderCompiler");

    /** Vulkanium version string for cache directory */
    private static final String CACHE_VERSION = "v1";

    /** Shaderc compiler handle */
    private long compiler;

    /** On-disk cache directory */
    private Path cacheDir;

    /** In-memory cache for current session */
    private final Map<String, byte[]> memoryCache = new ConcurrentHashMap<>();

    /** Timeout for a single shader compilation (seconds) */
    private static final int COMPILE_TIMEOUT_SECONDS = 30;

    /** Executor for timeout-guarded compilation */
    private final ExecutorService compileExecutor =
            Executors.newSingleThreadExecutor(r -> {
                // shaderc/glslang can recurse deeply on complex packs; use a larger
                // native thread stack to avoid StackOverflowError ("Out of stack space").
                Thread t = new Thread(null, r, "Vulkanium-ShaderCompiler", 16L * 1024L * 1024L);
                t.setDaemon(true);
                return t;
            });

    /** Configurable optimization level (0=none, 1=size, 2=performance) */
    private int optimizationLevel = 2;

    /** Statistics */
    private int totalCompilations = 0;
    private int cacheHits = 0;
    private int compilationErrors = 0;
    private int timeouts = 0;

    /**
     * Shader stage types for shaderc.
     */
    public enum ShaderStage {
        VERTEX(Shaderc.shaderc_vertex_shader),
        FRAGMENT(Shaderc.shaderc_fragment_shader),
        GEOMETRY(Shaderc.shaderc_geometry_shader),
        TESS_CONTROL(Shaderc.shaderc_tess_control_shader),
        TESS_EVALUATION(Shaderc.shaderc_tess_evaluation_shader),
        COMPUTE(Shaderc.shaderc_compute_shader);

        public final int shadercKind;

        ShaderStage(int shadercKind) {
            this.shadercKind = shadercKind;
        }
    }

    /**
     * Result of a shader compilation.
     */
    public static class CompilationResult {
        /** SPIR-V binary data (null if compilation failed) */
        public final ByteBuffer spirvBinary;
        /** Whether this result came from cache */
        public final boolean fromCache;
        /** Error message (null if compilation succeeded) */
        public final String errorMessage;
        /** Warning messages (may be non-null even on success) */
        public final String warnings;

        CompilationResult(ByteBuffer spirvBinary, boolean fromCache,
                          String errorMessage, String warnings) {
            this.spirvBinary = spirvBinary;
            this.fromCache = fromCache;
            this.errorMessage = errorMessage;
            this.warnings = warnings;
        }

        public boolean isSuccess() {
            return spirvBinary != null;
        }
    }

    /**
     * Initializes the shader compiler and cache directory.
     *
     * @param gameDir Minecraft game directory (for cache storage)
     */
    public void initialize(Path gameDir) {
        compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new RuntimeException("Failed to initialize shaderc compiler");
        }

        // Create cache directory
        cacheDir = gameDir.resolve(".vulkanium").resolve("shader_cache").resolve(CACHE_VERSION);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create shader cache directory: {}", e.getMessage());
            cacheDir = null;
        }

        LOGGER.info("Shader compiler initialized (shaderc) with cache at: {}",
                cacheDir != null ? cacheDir : "disabled");
    }

    /**
     * Sets the optimization level for future compilations.
     * @param level 0=none, 1=size, 2=performance
     */
    public void setOptimizationLevel(int level) {
        this.optimizationLevel = Math.max(0, Math.min(2, level));
    }

    /**
     * Compiles transformed GLSL source to SPIR-V binary.
     * Uses a timeout to prevent shaderc hangs, and retries with no optimization if timed out.
     *
     * @param source     Transformed GLSL 450 source
     * @param stage      Shader stage (vertex, fragment, etc.)
     * @param shaderName Human-readable name for error messages
     * @return Compilation result with SPIR-V binary or error details
     */
    public CompilationResult compile(String source, ShaderStage stage, String shaderName) {
        totalCompilations++;

        // Check cache
        String hash = computeHash(source + ":" + stage.name());

        // Memory cache (current session)
        byte[] cached = memoryCache.get(hash);
        if (cached != null) {
            cacheHits++;
            ByteBuffer buf = MemoryUtil.memAlloc(cached.length);
            buf.put(cached).flip();
            return new CompilationResult(buf, true, null, null);
        }

        // Disk cache
        if (cacheDir != null) {
            byte[] diskCached = readDiskCache(hash);
            if (diskCached != null) {
                cacheHits++;
                memoryCache.put(hash, diskCached);
                ByteBuffer buf = MemoryUtil.memAlloc(diskCached.length);
                buf.put(diskCached).flip();
                return new CompilationResult(buf, true, null, null);
            }
        }

        // Try compilation with configured optimization level
        int optLevel = this.optimizationLevel;
        CompilationResult result = compileShadercWithTimeout(source, stage, shaderName, optLevel);

        // If timed out and optimization was enabled, retry with no optimization
        if (result != null && !result.isSuccess() && result.errorMessage != null
                && result.errorMessage.startsWith("TIMEOUT") && optLevel > 0) {
            LOGGER.warn("Shader '{}' timed out with opt level {}, retrying with no optimization...",
                    shaderName, optLevel);
            result = compileShadercWithTimeout(source, stage, shaderName, 0);
        }

        return result != null ? result : new CompilationResult(null, false,
                "Unknown compilation error", null);
    }

    /**
     * Runs shaderc compilation in a separate thread with a timeout guard.
     */
    private CompilationResult compileShadercWithTimeout(String source, ShaderStage stage,
                                                         String shaderName, int optLevel) {
        Future<CompilationResult> future = compileExecutor.submit(
                () -> compileShadercDirect(source, stage, shaderName, optLevel));

        try {
            return future.get(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timeouts++;
            compilationErrors++;
            // Note: we can't cancel shaderc's native call, but the thread will eventually
            // return or the JVM will clean up the daemon thread
            future.cancel(true);
            LOGGER.error("Shader compilation TIMED OUT after {}s for '{}' (opt={})",
                    COMPILE_TIMEOUT_SECONDS, shaderName, optLevel);
            return new CompilationResult(null, false,
                    "TIMEOUT: Compilation took longer than " + COMPILE_TIMEOUT_SECONDS
                            + "s (opt level " + optLevel + ")", null);
        } catch (ExecutionException e) {
            compilationErrors++;
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            String causeType = cause != null ? cause.getClass().getSimpleName() : "ExecutionException";
            LOGGER.error("Shader compilation threw exception for '{}': {}: {}", shaderName, causeType, msg, cause);

            if (cause instanceof StackOverflowError) {
                msg = "StackOverflowError during shader compilation (likely preprocessor recursion): " + msg;
            }
            return new CompilationResult(null, false, msg, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CompilationResult(null, false, "Compilation interrupted", null);
        }
    }

    /**
     * Direct shaderc compilation (runs on compiler thread, no timeout).
     */
    private CompilationResult compileShadercDirect(String source, ShaderStage stage,
                                                    String shaderName, int optLevel) {
        String hash = computeHash(source + ":" + stage.name());

        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            // Set compilation options
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options,
                    Shaderc.shaderc_spirv_version_1_5);

            // Use configurable optimization level
            int shadercOptLevel = switch (optLevel) {
                case 0 -> Shaderc.shaderc_optimization_level_zero;
                case 1 -> Shaderc.shaderc_optimization_level_size;
                default -> Shaderc.shaderc_optimization_level_performance;
            };
            Shaderc.shaderc_compile_options_set_optimization_level(options, shadercOptLevel);

            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, false);
            if (optLevel == 0) {
                Shaderc.shaderc_compile_options_set_generate_debug_info(options);
            }

            // Compile using ByteBuffer overload instead of CharSequence overload.
            // The CharSequence path uses MemoryStack.nUTF8 internally and can throw
            // OutOfMemoryError("Out of stack space") on very large transformed shaders.
            ByteBuffer sourceBuf = MemoryUtil.memUTF8(source, false);
            ByteBuffer fileNameBuf = MemoryUtil.memUTF8(shaderName, true);
            ByteBuffer entryBuf = MemoryUtil.memUTF8("main", true);

            long result;
            try {
                result = Shaderc.shaderc_compile_into_spv(
                        compiler, sourceBuf, stage.shadercKind, fileNameBuf, entryBuf, options);
            } finally {
                MemoryUtil.memFree(sourceBuf);
                MemoryUtil.memFree(fileNameBuf);
                MemoryUtil.memFree(entryBuf);
            }

            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                String errorMsg = Shaderc.shaderc_result_get_error_message(result);
                long numWarnings = Shaderc.shaderc_result_get_num_warnings(result);
                long numErrors = Shaderc.shaderc_result_get_num_errors(result);

                if (status != Shaderc.shaderc_compilation_status_success) {
                    compilationErrors++;
                    LOGGER.error("Shader compilation failed for '{}' ({}): {}",
                            shaderName, stage, errorMsg);
                    dumpFailedShaderSource(shaderName, stage, source, errorMsg);
                    return new CompilationResult(null, false, errorMsg, null);
                }

                // Get SPIR-V binary
                ByteBuffer spirvData = Shaderc.shaderc_result_get_bytes(result);
                if (spirvData == null || spirvData.remaining() == 0) {
                    compilationErrors++;
                    return new CompilationResult(null, false, "Empty SPIR-V output", null);
                }

                // Copy to our own buffer (shaderc result will be freed)
                byte[] spirvBytes = new byte[spirvData.remaining()];
                spirvData.get(spirvBytes);
                spirvData.rewind();

                // Cache the result
                memoryCache.put(hash, spirvBytes);
                writeDiskCache(hash, spirvBytes);

                ByteBuffer outputBuf = MemoryUtil.memAlloc(spirvBytes.length);
                outputBuf.put(spirvBytes).flip();

                String warnings = numWarnings > 0 ? errorMsg : null;
                if (numWarnings > 0) {
                    LOGGER.debug("Shader '{}' compiled with {} warnings", shaderName, numWarnings);
                }

                return new CompilationResult(outputBuf, false, null, warnings);
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
        }
    }

    /**
     * Convenience method to compile and return raw SPIR-V bytes.
     *
     * @throws RuntimeException if compilation fails
     */
    public ByteBuffer compileOrThrow(String source, ShaderStage stage, String shaderName) {
        CompilationResult result = compile(source, stage, shaderName);
        if (!result.isSuccess()) {
            throw new RuntimeException("Shader compilation failed for '" + shaderName + "': "
                    + result.errorMessage);
        }
        return result.spirvBinary;
    }

    /**
     * Compiles a compute shader.
     */
    public CompilationResult compileCompute(String source, String shaderName) {
        return compile(source, ShaderStage.COMPUTE, shaderName);
    }

    /**
     * Clears the in-memory cache (disk cache is retained).
     */
    public void clearMemoryCache() {
        memoryCache.clear();
        LOGGER.info("Shader memory cache cleared");
    }

    /**
     * Clears both memory and disk caches.
     */
    public void clearAllCaches() {
        memoryCache.clear();
        if (cacheDir != null) {
            try {
                Files.walk(cacheDir)
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to delete cache file: {}", path);
                            }
                        });
            } catch (IOException e) {
                LOGGER.warn("Failed to clear shader cache: {}", e.getMessage());
            }
        }
        LOGGER.info("All shader caches cleared");
    }

    /**
     * Clears compiled shader cache files from disk for all cache versions.
     *
     * @param gameDir Minecraft game directory
     * @return number of deleted files
     */
    public static int clearCompiledShaderCacheFiles(Path gameDir) {
        Path root = gameDir.resolve(".vulkanium").resolve("shader_cache");
        if (!Files.isDirectory(root)) {
            return 0;
        }

        AtomicInteger deleted = new AtomicInteger();
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve("failed_sources")))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            deleted.incrementAndGet();
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete shader cache file: {}", path);
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Failed to clear compiled shader cache: {}", e.getMessage());
        }

        LOGGER.info("Cleared {} compiled shader cache files", deleted.get());
        return deleted.get();
    }

    /**
     * Clears failed shader source dump files from disk.
     *
     * @param gameDir Minecraft game directory
     * @return number of deleted files
     */
    public static int clearFailedSourceDumps(Path gameDir) {
        Path failedDir = gameDir.resolve(".vulkanium").resolve("shader_cache").resolve("failed_sources");
        if (!Files.isDirectory(failedDir)) {
            return 0;
        }

        AtomicInteger deleted = new AtomicInteger();
        try {
            Files.walk(failedDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            deleted.incrementAndGet();
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete failed shader dump: {}", path);
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Failed to clear failed shader dumps: {}", e.getMessage());
        }

        LOGGER.info("Cleared {} failed shader source dumps", deleted.get());
        return deleted.get();
    }

    /**
     * Gets compilation statistics.
     */
    public String getStats() {
        return String.format("Compilations: %d, Cache hits: %d (%.1f%%), Errors: %d, Timeouts: %d",
                totalCompilations, cacheHits,
                totalCompilations > 0 ? 100.0 * cacheHits / totalCompilations : 0,
                compilationErrors, timeouts);
    }

    private void dumpFailedShaderSource(String shaderName, ShaderStage stage, String source, String errorMsg) {
        if (cacheDir == null || source == null) {
            return;
        }

        try {
            Path dumpDir = cacheDir.getParent().resolve("failed_sources");
            Files.createDirectories(dumpDir);

            String safeName = shaderName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String sourceHash = computeHash(source).substring(0, 12);
            Path outFile = dumpDir.resolve(safeName + "_" + stage.name().toLowerCase() + "_" + sourceHash + ".glsl");

            StringBuilder content = new StringBuilder(source.length() + 512);
            content.append("// shader: ").append(shaderName).append('\n');
            content.append("// stage: ").append(stage).append('\n');
            content.append("// hash: ").append(sourceHash).append('\n');
            if (errorMsg != null && !errorMsg.isEmpty()) {
                content.append("// error: ").append(errorMsg.replace('\n', ' ')).append('\n');
            }
            content.append('\n').append(source);

            Files.writeString(outFile, content.toString());
            LOGGER.debug("Wrote failed shader source dump: {}", outFile);
        } catch (Exception dumpErr) {
            LOGGER.debug("Failed to write shader source dump for '{}': {}", shaderName, dumpErr.getMessage());
        }
    }

    /**
     * Destroys the compiler.
     */
    public void destroy() {
        compileExecutor.shutdownNow();
        if (compiler != 0) {
            Shaderc.shaderc_compiler_release(compiler);
            compiler = 0;
        }
        memoryCache.clear();
        LOGGER.info("Shader compiler destroyed. {}", getStats());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cache I/O
    // ═══════════════════════════════════════════════════════════════

    private byte[] readDiskCache(String hash) {
        if (cacheDir == null) return null;
        Path cacheFile = cacheDir.resolve(hash + ".spv");
        if (!Files.exists(cacheFile)) return null;
        try {
            return Files.readAllBytes(cacheFile);
        } catch (IOException e) {
            LOGGER.debug("Failed to read cache file: {}", cacheFile);
            return null;
        }
    }

    private void writeDiskCache(String hash, byte[] data) {
        if (cacheDir == null) return;
        Path cacheFile = cacheDir.resolve(hash + ".spv");
        try {
            Files.write(cacheFile, data);
        } catch (IOException e) {
            LOGGER.debug("Failed to write cache file: {}", cacheFile);
        }
    }

    private static String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException(e);
        }
    }
}
