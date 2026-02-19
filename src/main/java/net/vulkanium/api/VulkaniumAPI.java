package net.vulkanium.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Main entry point for the Vulkanium public API.
 *
 * <p>Mods can access Vulkanium capabilities through this API to:</p>
 * <ul>
 *   <li>Submit GPU compute tasks (C2ME-style world generation, lighting, physics)</li>
 *   <li>Register ray tracing modules (Radiance/MCVR-compatible path tracers)</li>
 *   <li>Register world modules that hook into the render pipeline</li>
 *   <li>Query Vulkan device capabilities (RT tier, compute limits, memory budget)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // In mod initializer
 *   VulkaniumAPI api = VulkaniumAPI.getInstance();
 *   if (api.isAvailable()) {
 *       // Register a compute-capable world module
 *       api.getModuleRegistry().register(new MyWorldModule());
 *
 *       // Submit GPU compute work
 *       api.getComputeService().submit(myComputeTask)
 *           .thenAccept(result -> { ... });
 *   }
 * }</pre>
 *
 * <h3>Availability</h3>
 * <p>The API is available after Vulkanium's pre-launch entrypoint has run.
 * Check {@link #isAvailable()} before using any services. If Vulkanium is not
 * loaded (e.g., user disabled it or on unsupported hardware), the API returns
 * no-op implementations that silently ignore all operations.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>All API methods are thread-safe. Compute submissions can be made from any
 * thread. Module registration must happen during mod initialization (before
 * first world load).</p>
 */
public final class VulkaniumAPI {

    private static VulkaniumAPI instance;

    private final boolean available;
    private final ComputeService computeService;
    private final ModuleRegistry moduleRegistry;
    private final DeviceCapabilities deviceCapabilities;

    private VulkaniumAPI(boolean available, ComputeService computeService,
                         ModuleRegistry moduleRegistry, DeviceCapabilities deviceCapabilities) {
        this.available = available;
        this.computeService = computeService;
        this.moduleRegistry = moduleRegistry;
        this.deviceCapabilities = deviceCapabilities;
    }

    /**
     * Gets the Vulkanium API instance.
     *
     * @return The API instance, never null. If Vulkanium is not loaded,
     *         returns a no-op instance where {@link #isAvailable()} returns false.
     */
    public static VulkaniumAPI getInstance() {
        if (instance == null) {
            // Return a no-op instance if not yet initialized
            instance = createNoOp();
        }
        return instance;
    }

    /**
     * Called internally by Vulkanium during initialization.
     * Not part of the public API.
     */
    public static void initialize(ComputeService compute, ModuleRegistry modules,
                                  DeviceCapabilities capabilities) {
        instance = new VulkaniumAPI(true, compute, modules, capabilities);
    }

    /** Whether Vulkanium is loaded and the Vulkan backend is active. */
    public boolean isAvailable() { return available; }

    /**
     * GPU compute service for submitting general-purpose compute work.
     *
     * <p>Mods like C2ME can use this for:</p>
     * <ul>
     *   <li>World generation noise computation on GPU</li>
     *   <li>Lighting engine updates</li>
     *   <li>Physics simulations</li>
     *   <li>Custom data processing pipelines</li>
     * </ul>
     */
    public ComputeService getComputeService() { return computeService; }

    /**
     * Module registry for registering render pipeline extensions.
     *
     * <p>World modules hook into the frame lifecycle at defined phases
     * (pre-terrain, post-terrain, shadow, composite, etc.).</p>
     */
    public ModuleRegistry getModuleRegistry() { return moduleRegistry; }

    /**
     * Query Vulkan device capabilities and limits.
     */
    public DeviceCapabilities getDeviceCapabilities() { return deviceCapabilities; }

    // ── Device Capabilities (read-only queries) ──

    /**
     * Describes the Vulkan device capabilities available to mods.
     */
    public interface DeviceCapabilities {
        /** Vulkan API version (e.g., VK_API_VERSION_1_2) */
        int vulkanApiVersion();

        /** GPU name string */
        String deviceName();

        /** Available VRAM in bytes */
        long availableVRAM();

        /** Total VRAM in bytes */
        long totalVRAM();

        /** Maximum compute workgroup size (x * y * z) */
        int maxComputeWorkGroupInvocations();

        /** Maximum compute shared memory size in bytes */
        int maxComputeSharedMemorySize();

        /** Maximum storage buffer range in bytes */
        long maxStorageBufferRange();

        /** Ray tracing tier: 0=none, 1=ray query, 2=full RT pipeline */
        int rayTracingTier();

        /** Maximum ray recursion depth (0 if RT not available) */
        int maxRayRecursionDepth();

        /** Whether timeline semaphores are available (Vulkan 1.2 core) */
        boolean hasTimelineSemaphores();

        /** Whether 16-bit storage (float16/int16 in buffers) is available */
        boolean has16BitStorage();

        /** Whether descriptor indexing (bindless) is available */
        boolean hasDescriptorIndexing();
    }

    // ── No-Op Implementation ──

    private static VulkaniumAPI createNoOp() {
        return new VulkaniumAPI(false,
                new NoOpComputeService(),
                new NoOpModuleRegistry(),
                new NoOpDeviceCapabilities());
    }

    private static class NoOpComputeService implements ComputeService {
        @Override
        public <T> ComputeFuture<T> submit(ComputeTask<T> task) {
            return ComputeFuture.completedEmpty();
        }

        @Override
        public <T> ComputeFuture<T> submitCritical(ComputeTask<T> task) {
            return ComputeFuture.completedEmpty();
        }

        @Override
        public boolean isAvailable() { return false; }
    }

    private static class NoOpModuleRegistry implements ModuleRegistry {
        @Override
        public void register(WorldModule module) { /* no-op */ }

        @Override
        public boolean unregister(String moduleId) { return false; }

        @Override
        public Optional<WorldModule> getModule(String moduleId) { return Optional.empty(); }

        @Override
        public java.util.List<WorldModule> getModules() { return java.util.List.of(); }

        @Override
        public java.util.List<WorldModule> getModulesForPhase(ModulePhase phase) { return java.util.List.of(); }
    }

    private static class NoOpDeviceCapabilities implements DeviceCapabilities {
        @Override public int vulkanApiVersion() { return 0; }
        @Override public String deviceName() { return "Not Available"; }
        @Override public long availableVRAM() { return 0; }
        @Override public long totalVRAM() { return 0; }
        @Override public int maxComputeWorkGroupInvocations() { return 0; }
        @Override public int maxComputeSharedMemorySize() { return 0; }
        @Override public long maxStorageBufferRange() { return 0; }
        @Override public int rayTracingTier() { return 0; }
        @Override public int maxRayRecursionDepth() { return 0; }
        @Override public boolean hasTimelineSemaphores() { return false; }
        @Override public boolean has16BitStorage() { return false; }
        @Override public boolean hasDescriptorIndexing() { return false; }
    }
}
